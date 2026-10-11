package com.dsh.pocket

/**
 * 会话归约缓冲：把 session/follow 快照、live 事件、session/page 翻页、session/projections 兜底
 * 全部归约成同一份 TurnUi 列表。
 *
 * 两条铁律：
 * 1) seq 幂等 —— reasoning/text 是 append 语义，同一事件应用两次就会文本翻倍。
 * 2) 工具按 callId 配对 —— tool-call part / tool/call / tool/result 三处 id 实测完全一致。
 */

class PendingPrompt(val text: String, val kind: String)

class ToolBuilder(var callId: String, var name: String = "tool") {
    var argsText: String = ""
    var resultText: String? = null
    var isError: Boolean = false

    fun toUi(): ToolCallUi = ToolCallUi(callId, name, argsText, resultText, isError)
}

class StepBuilder(val step: Int) {
    val reasoning = StringBuilder()
    val text = StringBuilder()
    val tools = LinkedHashMap<String, ToolBuilder>()
    /** 文本来自 projections 兜底；一旦有真实事件就整体替换，避免拼接翻倍。 */
    var textFromProjection: Boolean = false
    var done: Boolean = false
    /** 该 step 对应的 assistant 消息 id（用于 messageFeedback）。 */
    var messageId: String = ""
    /** 逐 token 增量 + 节奏（data.stream），用于逐字重放。 */
    val reasoningDeltas = mutableListOf<String>()
    val reasoningDt = mutableListOf<Long>()
    val responseDeltas = mutableListOf<String>()
    val responseDt = mutableListOf<Long>()

    fun toUi(): StepUi = StepUi(
        step = step,
        messageId = messageId,
        reasoning = reasoning.toString(),
        text = text.toString(),
        toolCalls = tools.values.map { it.toUi() },
        done = done,
        reasoningDeltas = reasoningDeltas.toList(),
        reasoningDt = reasoningDt.toList(),
        responseDeltas = responseDeltas.toList(),
        responseDt = responseDt.toList()
    )
}

class TurnBuilder(val turn: Int) {
    var prompt: String = ""
    var promptKind: String = "user"
    val steps = LinkedHashMap<Int, StepBuilder>()
    /** 产出文件，按 path 去重（deliverables/presented）。 */
    val deliverables = LinkedHashMap<String, DeliverableUi>()
    var ended: Boolean = false
    var minSeq: Long = Long.MAX_VALUE
    var maxSeq: Long = -1L

    fun step(s: Int): StepBuilder = steps.getOrPut(s) { StepBuilder(s) }

    fun observe(seq: Long) {
        if (seq < 0) return
        if (seq < minSeq) minSeq = seq
        if (seq > maxSeq) maxSeq = seq
    }

    fun toUi(): TurnUi = TurnUi(
        turn = turn,
        prompt = prompt,
        promptKind = promptKind,
        steps = steps.keys.sorted().map { steps[it]!!.toUi() },
        deliverables = deliverables.values.toList(),
        ended = ended,
        minSeq = if (minSeq == Long.MAX_VALUE) -1L else minSeq,
        maxSeq = maxSeq
    )
}

class SessionBuffer {

    private val turns = LinkedHashMap<Int, TurnBuilder>()
    private val applied = HashSet<Long>()
    private val toolOwner = HashMap<String, ToolBuilder>()
    private val pending = ArrayDeque<PendingPrompt>()
    private var placeholderCount = 0

    // 会话级状态（非轮次内）：任务清单 + 当前模型
    var todos: List<TodoItem> = emptyList()
        private set
    var model: String? = null
        private set
    var approvals: List<ApprovalUi> = emptyList()
        private set
    fun setTodos(t: List<TodoItem>) { todos = t }
    fun setModel(m: String?) { model = m }
    fun upsertApproval(a: ApprovalUi) {
        approvals = approvals.filterNot { it.id == a.id } + a
    }
    fun setApprovalOutcome(id: String, outcome: String) {
        approvals = approvals.map { if (it.id == id) it.copy(outcome = outcome) else it }
    }

    // 实时流式帧（assistant-stream）的 attemptId → (turn, step) 映射
    private val attempts = HashMap<String, Pair<Int, Int>>()
    fun bindAttempt(attemptId: String, turn: Int, step: Int) { attempts[attemptId] = turn to step }
    fun attemptTurnStep(attemptId: String): Pair<Int, Int>? = attempts[attemptId]

    fun size(): Int = turns.size

    fun turn(t: Int): TurnBuilder = turns.getOrPut(t) { TurnBuilder(t) }

    /** 本地刚发送的消息：占位 turn 号排在最后，等 turn/start 到达后由 renumber 归位。 */
    fun newPlaceholderTurn(): Int {
        val t = Int.MAX_VALUE - (++placeholderCount)
        turns[t] = TurnBuilder(t)
        return t
    }

    fun isPlaceholder(t: Int): Boolean = t > Int.MAX_VALUE - 10000

    /** 占位轮归位到真实 turn 号（prompt 文本匹配）。 */
    fun renumberPlaceholder(placeholder: Int, realTurn: Int) {
        val src = turns.remove(placeholder) ?: return
        val dst = turns[realTurn]
        if (dst == null) {
            src.let { old ->
                val nt = TurnBuilder(realTurn)
                nt.prompt = old.prompt
                nt.promptKind = old.promptKind
                nt.ended = old.ended
                nt.minSeq = old.minSeq
                nt.maxSeq = old.maxSeq
                old.steps.forEach { (k, v) -> nt.steps[k] = v }
                turns[realTurn] = nt
            }
        } else {
            if (dst.prompt.isEmpty()) { dst.prompt = src.prompt; dst.promptKind = src.promptKind }
            src.steps.forEach { (k, v) -> if (!dst.steps.containsKey(k)) dst.steps[k] = v }
            dst.minSeq = minOf(dst.minSeq, src.minSeq)
            dst.maxSeq = maxOf(dst.maxSeq, src.maxSeq)
        }
        reindexTools()
    }

    private fun reindexTools() {
        toolOwner.clear()
        turns.values.forEach { tb ->
            tb.steps.values.forEach { sb -> sb.tools.forEach { (id, tool) -> toolOwner[id] = tool } }
        }
    }

    /**
     * seq 幂等登记。返回 false 表示这个事件已经应用过，调用方应直接丢弃。
     * seq < 0（缺字段）不做幂等，交给调用方保证。
     */
    fun claim(seq: Long): Boolean {
        if (seq < 0) return true
        return applied.add(seq)
    }

    fun hasSeq(seq: Long): Boolean = seq >= 0 && applied.contains(seq)

    fun pushPendingPrompt(text: String, kind: String) {
        if (text.isBlank()) return
        pending.addLast(PendingPrompt(text, kind))
    }

    /** turn/start 时把排队的用户消息绑到该轮。 */
    fun bindPendingPrompt(t: Int): Boolean {
        val p = pending.removeFirstOrNull() ?: return false
        val tb = turn(t)
        if (tb.prompt.isEmpty()) { tb.prompt = p.text; tb.promptKind = p.kind }
        return true
    }

    fun pendingCount(): Int = pending.size

    /** 丢弃未绑定 turn 的用户消息（没有 turn/start 承接的不单独成轮）。 */
    fun dropPending() {
        pending.clear()
    }

    /** 无 turn 号事件时的落点：最后一个未结束的轮次；没有就建占位。 */
    fun currentTurn(): TurnBuilder {
        turns.values.lastOrNull { !it.ended }?.let { return it }
        return turn(newPlaceholderTurn())
    }

    fun toolByCallId(callId: String): ToolBuilder? = toolOwner[callId]

    fun upsertTool(tb: TurnBuilder, step: Int, callId: String, name: String?, args: String?) {
        val sb = tb.step(step)
        val key = if (callId.isBlank()) "anon:" + tb.turn + ":" + step + ":" + (name ?: "tool") else callId
        val tool = sb.tools[key] ?: ToolBuilder(key, name ?: "tool").also {
            sb.tools[key] = it
            toolOwner[key] = it
        }
        // 只补空字段，绝不覆盖非空：防止 tool/call 事件把已拿到的 result 清掉
        if (name != null && name.isNotBlank() && (tool.name == "tool" || tool.name.isEmpty())) tool.name = name
        if (args != null && args.isNotBlank() && tool.argsText.isEmpty()) tool.argsText = args
    }

    /** tool/result 可能落在与 tool/call 不同的 step，所以全局按 callId 查。 */
    fun setToolResult(callId: String, result: String, isError: Boolean): Boolean {
        val tool = toolOwner[callId]
        if (tool != null) {
            if (tool.resultText.isNullOrEmpty()) {
                tool.resultText = result
                tool.isError = isError
            }
            return true
        }
        return false
    }

    /** 孤儿 result（没配到 call）：挂到最后一个轮次的最后一个 step。 */
    fun appendOrphanTool(callId: String, result: String, isError: Boolean) {
        val tb = turns.values.lastOrNull() ?: turn(newPlaceholderTurn())
        val sb = tb.step(tb.steps.keys.maxOrNull() ?: 1)
        val tool = ToolBuilder(callId, "tool")
        tool.resultText = result
        tool.isError = isError
        sb.tools[callId] = tool
        toolOwner[callId] = tool
    }

    fun snapshot(): List<TurnUi> = turns.keys.sorted().map { turns[it]!!.toUi() }

    /** 已归约的最早 seq，供 session/page 的 beforeSeq 使用。 */
    fun minSeq(): Long {
        var m = Long.MAX_VALUE
        turns.values.forEach { if (it.minSeq < m) m = it.minSeq }
        return if (m == Long.MAX_VALUE) -1L else m
    }

    fun maxSeq(): Long {
        var m = -1L
        turns.values.forEach { if (it.maxSeq > m) m = it.maxSeq }
        return m
    }

    fun turnNumbers(): List<Int> = turns.keys.sorted()

    fun clear() {
        turns.clear()
        applied.clear()
        toolOwner.clear()
        pending.clear()
        placeholderCount = 0
        todos = emptyList()
        model = null
        approvals = emptyList()
    }
}
