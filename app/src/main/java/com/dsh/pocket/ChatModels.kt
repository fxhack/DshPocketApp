package com.dsh.pocket

/**
 * 聊天页 UI 模型：由 SessionBuffer 归约 session/follow + session/page + session/projections 得到。
 *
 * 层级：一轮 TurnUi 含多个 StepUi（实测 turn48 有 step1/2，turn67 有 step1/2/3），
 * 每个 StepUi 含 reasoning（思考）、text（正文）、toolCalls（工具调用）。
 */

/** 多 step 正文/推理的拼接符。实测同 (turn,step) 只有一条 assistant/message，多 step 是续写块。 */
const val STEP_JOIN: String = "\n\n"

/** 一次工具调用：tool/call 事件 + assistant/message 的 tool-call part + tool/result 合并后的统一形态。 */
data class ToolCallUi(
    val callId: String = "",
    val name: String = "tool",
    val argsText: String = "",
    val resultText: String? = null,
    val isError: Boolean = false
)

/** 一个 step = 一次模型输出。 */
data class StepUi(
    val step: Int = 1,
    val messageId: String = "",
    val reasoning: String = "",
    val text: String = "",
    val toolCalls: List<ToolCallUi> = emptyList(),
    val done: Boolean = false,
    /** 逐 token 增量（data.stream reasoning-chunks/text-chunks 的 texts），用于逐字重放。 */
    val reasoningDeltas: List<String> = emptyList(),
    val reasoningDt: List<Long> = emptyList(),
    val responseDeltas: List<String> = emptyList(),
    val responseDt: List<Long> = emptyList()
)

/** 一个轮次的产出文件（deliverables/presented 事件）。 */
data class DeliverableUi(
    val path: String = "",
    val description: String = ""
)

/** 任务清单项（todo/write 事件）。status: pending|in_progress|completed。 */
data class TodoItem(
    val content: String = "",
    val status: String = "pending"
) {
    val isDone: Boolean get() = status == "completed"
    val isActive: Boolean get() = status == "in_progress"
}

/** 批准请求（approval/asked 事件）。outcome: null=待决定 | allowed-once/rejected/cancelled。 */
data class ApprovalUi(
    val id: String = "",
    val toolName: String = "",
    val reason: String = "",
    val outcome: String? = null
)

/** 模型目录（session/modelCatalog）：groups → models[{id,name}]。 */
data class ModelCatalogModel(val id: String = "", val name: String = "")
data class ModelProviderGroup(val id: String = "", val name: String = "", val models: List<ModelCatalogModel> = emptyList())
data class ModelCatalog(val groups: List<ModelProviderGroup> = emptyList())

/** 一轮对话。turn 号来自服务端，单调递增且稳定，直接作为 LazyColumn 的 key。 */
data class TurnUi(
    val turn: Int = 0,
    val prompt: String = "",
    /** user=真人输入 / goal=目标驱动提示 / system=系统通知（切模型等） */
    val promptKind: String = "user",
    val steps: List<StepUi> = emptyList(),
    val deliverables: List<DeliverableUi> = emptyList(),
    val ended: Boolean = false,
    val minSeq: Long = Long.MAX_VALUE,
    val maxSeq: Long = -1L
) {
    val responseText: String
        get() = steps.map { it.text }.filter { it.isNotBlank() }.joinToString(STEP_JOIN) { it.trim() }
    val reasoningText: String
        get() = steps.map { it.reasoning }.filter { it.isNotBlank() }.joinToString(STEP_JOIN) { it.trim() }
    /** 逐 token 重放流：(文本片段, 该片段前的延迟ms)。按 data.stream 的 dt 还原生成期逐字实时感。 */
    val reasoningStream: List<Pair<String, Long>>
        get() = buildList {
            var first = true
            for (s in steps) {
                val ds = s.reasoningDeltas
                val has = ds.isNotEmpty() || s.reasoning.isNotBlank()
                if (!has) continue
                if (!first) add(STEP_JOIN to 0L)
                if (ds.isNotEmpty()) ds.forEachIndexed { i, d -> add(d to (s.reasoningDt.getOrNull(i) ?: 16L)) }
                else add(s.reasoning to 0L)
                first = false
            }
        }
    val responseStream: List<Pair<String, Long>>
        get() = buildList {
            var first = true
            for (s in steps) {
                val ds = s.responseDeltas
                val has = ds.isNotEmpty() || s.text.isNotBlank()
                if (!has) continue
                if (!first) add(STEP_JOIN to 0L)
                if (ds.isNotEmpty()) ds.forEachIndexed { i, d -> add(d to (s.responseDt.getOrNull(i) ?: 16L)) }
                else add(s.text to 0L)
                first = false
            }
        }
    val allTools: List<ToolCallUi>
        get() = steps.flatMap { it.toolCalls }
    val running: Boolean
        get() = !ended && responseText.isEmpty() && reasoningText.isEmpty()
}

/** 顶部「加载更早」的状态。 */
enum class LoadMoreState { Hidden, Available, Loading, Exhausted }

/** LazyColumn 行模型：扁平化以便 key 稳定。 */
sealed interface ChatRow {
    val key: String
}

data class LoadMoreRow(val state: LoadMoreState = LoadMoreState.Hidden) : ChatRow {
    override val key: String get() = "__load_more__"
}

data class TurnRow(val turn: TurnUi) : ChatRow {
    override val key: String get() = "t:" + turn.turn
}

enum class ChatPhase { Loading, Ready, Error }

data class ChatState(
    val sessionId: String = "",
    val title: String? = null,
    val goal: String? = null,
    /** 当前模型（model/selection 事件，形如 provider/model）。 */
    val model: String? = null,
    /** 实时任务清单（todo/write 事件）。 */
    val todos: List<TodoItem> = emptyList(),
    /** 待批准请求（approval/asked 事件）。 */
    val approvals: List<ApprovalUi> = emptyList(),
    /** 会话工作目录，用于拼产出文件的绝对路径。 */
    val cwd: String = "",
    /** 上下文占用描述，如 "上下文 47% · 118K/256K"。 */
    val contextText: String? = null,
    val phase: ChatPhase = ChatPhase.Loading,
    val error: String? = null,
    /** 渲染窗口起始下标（0 = 最早的已加载轮次）。 */
    val windowStart: Int = 0,
    /** 还有更早的历史可加载（来自 session/page 的 hasMore）。 */
    val hasMoreBefore: Boolean = false,
    val loadingEarlier: Boolean = false,
    /** 服务端是否已把该会话全部历史都给过（决定「加载更早」是否还能发请求）。 */
    val pageExhausted: Boolean = false,
    val running: Boolean = false,
    val activity: String? = null,
    /** 历史细节（思考/工具）是否已由 follow 快照补齐。 */
    val detailLoaded: Boolean = false,
    /** 递增计数：发送后让 UI 跳到底部。 */
    val scrollToken: Int = 0
)
