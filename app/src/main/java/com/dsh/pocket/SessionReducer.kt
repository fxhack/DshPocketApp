package com.dsh.pocket

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 事件归约器（纯 Kotlin，无 Android / Compose 依赖，可 JVM 单测）。
 *
 * 输入：session/follow 的 snapshot.records / live event / session/page 的 records，全部同构。
 * 输出：SessionBuffer，再由 buffer.snapshot() 得到 List<TurnUi>。
 */
object SessionReducer {

    /** 应用单条事件。返回 true 表示内容有变化。 */
    fun applyEvent(buf: SessionBuffer, ev: RawEvent): Boolean {
        if (!buf.claim(ev.seq)) return false
        val data = ev.data
        return when (ev.type) {
            "turn/start" -> {
                val t = dataInt(data, "turn") ?: return false
                buf.bindPendingPrompt(t)
                buf.turn(t).observe(ev.seq)
                true
            }
            "step/start" -> {
                val tb = resolveTurn(buf, data) ?: return false
                val s = dataInt(data, "step") ?: 1
                tb.step(s)
                tb.observe(ev.seq)
                true
            }
            "step/end" -> {
                val tb = resolveTurn(buf, data) ?: return false
                val s = dataInt(data, "step") ?: tb.steps.keys.maxOrNull() ?: 1
                tb.step(s).done = true
                tb.observe(ev.seq)
                true
            }
            "turn/end" -> {
                val tb = resolveTurn(buf, data) ?: return false
                tb.ended = true
                tb.observe(ev.seq)
                true
            }
            "assistant/message" -> applyAssistantMessage(buf, ev)
            "tool/call" -> applyToolCall(buf, ev)
            "tool/result" -> applyToolResult(buf, ev)
            "user/message" -> applyUserMessage(buf, ev)
            "deliverables/presented" -> applyDeliverables(buf, ev)
            "todo/write" -> applyTodoWrite(buf, ev)
            "approval/asked" -> applyApprovalAsked(buf, ev)
            "approval/decided" -> applyApprovalDecided(buf, ev)
            "model/selection" -> applyModelSelection(buf, ev)
            "tool/ptc-dispatch-start" -> applyPtcStart(buf, ev)
            "tool/ptc-dispatch" -> applyPtcDispatch(buf, ev)
            "system/message", "agent/inbox/spliced", "request/header", "request/context",
            "session/title", "goal/change", "command/run", "command/done",
            "workspace/changes", "assistant/attempt" -> false
            else -> false
        }
    }

    fun applyEvents(buf: SessionBuffer, evs: List<RawEvent>): Boolean {
        var changed = false
        for (e in evs) if (applyEvent(buf, e)) changed = true
        return changed
    }

    fun applySnapshot(buf: SessionBuffer, snap: SnapshotFrame): Boolean {
        val changed = applyEvents(buf, snap.records)
        // 没有对应 turn/start 的残留用户消息不再落盘，避免产生幽灵空轮
        dropPending(buf)
        return changed
    }

    fun applyPage(buf: SessionBuffer, records: List<RawEvent>): Boolean {
        val changed = applyEvents(buf, records)
        dropPending(buf)
        return changed
    }

    /** session/projections 的 turnOutline 兜底：只填空白槽位，不覆盖已有内容。 */
    fun applyProjections(buf: SessionBuffer, outline: List<TurnOutline>): Boolean {
        var changed = false
        for (o in outline) {
            if (o.turn <= 0) continue
            val tb = buf.turn(o.turn)
            if (tb.prompt.isEmpty() && o.prompt.isNotEmpty()) {
                tb.prompt = o.prompt
                changed = true
            }
            if (o.response.isNotEmpty()) {
                val sb = tb.step(1)
                if (sb.text.isEmpty()) {
                    sb.text.append(o.response)
                    sb.textFromProjection = true
                    sb.done = true
                    changed = true
                }
            }
            if (!tb.ended) { tb.ended = true; changed = true }
        }
        return changed
    }

    /** 目标文本：projections.values.goal 常为 null，实测要从 goal/change 事件取。 */
    fun goalObjective(ev: RawEvent): String? {
        if (ev.type != "goal/change") return null
        val d = ev.data ?: return null
        val g = d.jsonObject["goal"]
        if (g != null) {
            return g.jsonObject["objective"]?.jsonPrimitive?.content
                ?: g.jsonObject["title"]?.jsonPrimitive?.content
        }
        return d.jsonObject["objective"]?.jsonPrimitive?.content
    }

    /** 活动状态行文案（给 UI 显示"正在做什么"）。 */
    fun activityLabel(ev: RawEvent): String? = when (ev.type) {
        "turn/start" -> "开始回复"
        "step/start" -> "执行步骤"
        "assistant/attempt" -> "正在生成回复…"
        "request/context", "request/header" -> "准备上下文"
        "session/title" -> "生成标题"
        "tool/call", "tool/ptc-dispatch" -> (dataString(ev.data, "name") ?: "工具") + "…"
        "todo/write" -> "更新任务清单"
        "turn/end" -> null
        else -> null
    }

    // ---- 内部 ----

    private fun dropPending(buf: SessionBuffer) {
        // 没有 turn/start 承接的用户消息不单独成轮
        buf.dropPending()
    }

    /** 缺少 turn 号的事件一律忽略：不允许凭空造轮，否则会产生幽灵空轮。 */
    private fun resolveTurn(buf: SessionBuffer, data: JsonElement?): TurnBuilder? {
        val t = dataInt(data, "turn") ?: return null
        return buf.turn(t)
    }

    private fun applyAssistantMessage(buf: SessionBuffer, ev: RawEvent): Boolean {
        val data = ev.data ?: return false
        val tb = resolveTurn(buf, data) ?: return false
        val step = dataInt(data, "step") ?: 1
        val sb = tb.step(step)
        dataObject(data, "message")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }?.let { sb.messageId = it }
        tb.observe(ev.seq)

        val content = dataObject(data, "message")?.jsonObject?.get("content")?.jsonArray
            ?: return false
        var changed = false
        var reasoning: String? = null
        var text: String? = null
        for (part in content) {
            val p = part.jsonObject
            val type = p["type"]?.jsonPrimitive?.content ?: continue
            when (type) {
                "reasoning" -> {
                    val v = p["text"]?.jsonPrimitive?.content ?: continue
                    reasoning = if (reasoning == null) v else reasoning + "\n" + v
                }
                "text" -> {
                    val v = p["text"]?.jsonPrimitive?.content ?: continue
                    text = if (text == null) v else text + "\n" + v
                }
                "tool-call" -> {
                    val id = p["id"]?.jsonPrimitive?.content ?: ""
                    val name = p["name"]?.jsonPrimitive?.content
                    val args = p["arguments"]?.let {
                        if (it is JsonPrimitive) it.content else it.toString()
                    }
                    buf.upsertTool(tb, step, id, name, args)
                    changed = true
                }
            }
        }
        // 从 data.stream 提取逐 token 增量（reasoning-chunks/text-chunks 的 texts+dt），
        // 按 dt 重放还原生成期逐字实时感（content 是整块→秒出）。
        val rDeltas = mutableListOf<String>()
        val rDt = mutableListOf<Long>()
        val xDeltas = mutableListOf<String>()
        val xDt = mutableListOf<Long>()
        dataObject(data, "stream")?.jsonArray?.forEach { c ->
            val o = c.jsonObject
            when (o["type"]?.jsonPrimitive?.content) {
                "reasoning-chunks" -> {
                    val ts = o["texts"]?.jsonArray; val d = o["dt"]?.jsonArray
                    ts?.forEachIndexed { i, tx ->
                        rDeltas += tx.jsonPrimitive.content
                        rDt += (d?.getOrNull(i)?.jsonPrimitive?.content?.toLongOrNull() ?: 16L)
                    }
                }
                "text-chunks" -> {
                    val ts = o["texts"]?.jsonArray; val d = o["dt"]?.jsonArray
                    ts?.forEachIndexed { i, tx ->
                        xDeltas += tx.jsonPrimitive.content
                        xDt += (d?.getOrNull(i)?.jsonPrimitive?.content?.toLongOrNull() ?: 16L)
                    }
                }
            }
        }
        // 优先用 delta 拼装文本（与逐字重放对齐）；无 delta 用 content；无 delta 的非空文本作单条 delta(dt=0) 兜底
        val rText = if (rDeltas.isNotEmpty()) rDeltas.joinToString("") else reasoning?.trim()
        val xText = if (xDeltas.isNotEmpty()) xDeltas.joinToString("") else text?.trim()
        val rDeltasF = rDeltas.ifEmpty { rText?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList() }
        val rDtF = rDt.ifEmpty { if (rDeltasF.isNotEmpty()) listOf(0L) else emptyList() }
        val xDeltasF = xDeltas.ifEmpty { xText?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList() }
        val xDtF = xDt.ifEmpty { if (xDeltasF.isNotEmpty()) listOf(0L) else emptyList() }
        if (!rText.isNullOrEmpty()) {
            if (sb.reasoning.isNotEmpty()) sb.reasoning.append(STEP_JOIN)
            sb.reasoning.append(rText)
            sb.reasoningDeltas.clear(); sb.reasoningDeltas.addAll(rDeltasF)
            sb.reasoningDt.clear(); sb.reasoningDt.addAll(rDtF)
            changed = true
        }
        if (!xText.isNullOrEmpty()) {
            // 兜底文本整体让位给权威事件文本，避免拼接翻倍
            if (sb.textFromProjection) {
                sb.text.setLength(0)
                sb.textFromProjection = false
            }
            if (sb.text.isNotEmpty()) sb.text.append(STEP_JOIN)
            sb.text.append(xText)
            sb.responseDeltas.clear(); sb.responseDeltas.addAll(xDeltasF)
            sb.responseDt.clear(); sb.responseDt.addAll(xDtF)
            changed = true
        }
        sb.done = true
        return changed
    }

    /** 实时流式帧（assistant-stream）：生成期逐 token chunk，立即填充 deltas（dt=0 实时展示）。
     *  只填 deltas，不动 reasoning/text（StringBuilder）——AM 到达时由 AM 设最终文本，避免翻倍；
     *  deltas 与 AM stream 同源同 size，AM 不触发额外重放。 */
    fun applyLiveChunk(buf: SessionBuffer, fr: JsonObject): Boolean {
        val type = (fr["type"] as? JsonPrimitive)?.content ?: return false
        val attemptId = (fr["attemptId"] as? JsonPrimitive)?.content ?: return false
        return when (type) {
            "start" -> {
                val turn = (fr["turn"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return false
                val step = (fr["step"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
                buf.bindAttempt(attemptId, turn, step)
                buf.turn(turn)
                true
            }
            "chunk" -> {
                val chunk = fr["chunk"] as? JsonObject ?: return false
                val ct = (chunk["type"] as? JsonPrimitive)?.content ?: return false
                val text = (chunk["text"] as? JsonPrimitive)?.content ?: return false
                val ts = buf.attemptTurnStep(attemptId) ?: return false
                val sb = buf.turn(ts.first).step(ts.second)
                when (ct) {
                    "reasoning-delta" -> { sb.reasoningDeltas.add(text); sb.reasoningDt.add(0L) }
                    "text-delta" -> { sb.responseDeltas.add(text); sb.responseDt.add(0L) }
                    else -> return false
                }
                true
            }
            "end" -> false
            else -> false
        }
    }

    private fun applyToolCall(buf: SessionBuffer, ev: RawEvent): Boolean {
        val data = ev.data ?: return false
        val tb = resolveTurn(buf, data) ?: return false
        val step = dataInt(data, "step") ?: 1
        val callId = dataString(data, "callId") ?: ""
        val name = dataString(data, "name")
        val args = data.jsonObject["arguments"]?.let {
            if (it is JsonPrimitive) it.content else it.toString()
        }
        tb.observe(ev.seq)
        buf.upsertTool(tb, step, callId, name, args)
        return true
    }

    private fun applyToolResult(buf: SessionBuffer, ev: RawEvent): Boolean {
        val data = ev.data ?: return false
        val msg = dataObject(data, "message")?.jsonObject ?: return false
        val callId = msg["toolCallId"]?.jsonPrimitive?.content
            ?: msg["source"]?.jsonObject?.get("callId")?.jsonPrimitive?.content
            ?: return false
        val text = msg["content"]?.jsonArray?.mapNotNull { p ->
            val o = p.jsonObject
            if (o["type"]?.jsonPrimitive?.content == "text") o["text"]?.jsonPrimitive?.content else null
        }?.joinToString("\n")?.trim() ?: ""
        val isError = msg["isError"]?.jsonPrimitive?.content == "true"
        val tb = resolveTurn(buf, data) ?: return false
        tb.observe(ev.seq)
        return if (buf.setToolResult(callId, text, isError)) true else {
            buf.appendOrphanTool(callId, text, isError)
            true
        }
    }

    /** 产出文件：data = {turn, callId, files:[{path, description}]}。 */
    private fun applyDeliverables(buf: SessionBuffer, ev: RawEvent): Boolean {
        val data = ev.data ?: return false
        val t = dataInt(data, "turn") ?: return false
        val files = data.jsonObject["files"]?.jsonArray ?: return false
        val tb = buf.turn(t)
        tb.observe(ev.seq)
        var changed = false
        for (f in files) {
            val o = f.jsonObject
            val path = o["path"]?.jsonPrimitive?.content ?: continue
            if (path.isBlank()) continue
            val desc = o["description"]?.jsonPrimitive?.content ?: ""
            if (!tb.deliverables.containsKey(path)) {
                tb.deliverables[path] = DeliverableUi(path, desc)
                changed = true
            }
        }
        return changed
    }

    private fun applyUserMessage(buf: SessionBuffer, ev: RawEvent): Boolean {
        val data = ev.data ?: return false
        val content = data.jsonObject["content"]?.jsonArray ?: return false
        val text = content.mapNotNull { p ->
            val o = p.jsonObject
            if (o["type"]?.jsonPrimitive?.content == "text") o["text"]?.jsonPrimitive?.content else null
        }.joinToString("\n").trim()
        if (text.isEmpty()) return false
        val sourceKind = data.jsonObject["source"]?.jsonObject?.get("kind")?.jsonPrimitive?.content
        val kind = when (sourceKind) {
            "goal" -> "goal"
            "model-selection" -> "system"
            else -> "user"
        }
        buf.pushPendingPrompt(text, kind)
        return true
    }

    /** 任务清单：data.todos = [{content, status}]。整体替换。 */
    private fun applyTodoWrite(buf: SessionBuffer, ev: RawEvent): Boolean {
        val arr = ev.data?.jsonObject?.get("todos")?.jsonArray ?: return false
        val list = arr.mapNotNull { el ->
            val o = el.jsonObject
            val c = o["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val s = o["status"]?.jsonPrimitive?.content ?: "pending"
            TodoItem(c, s)
        }
        buf.setTodos(list)
        return true
    }

    /** 当前模型：data = {provider, model, reasoningEffort?}。 */
    private fun applyModelSelection(buf: SessionBuffer, ev: RawEvent): Boolean {
        val d = ev.data?.jsonObject ?: return false
        val model = d["model"]?.jsonPrimitive?.content ?: return false
        val provider = d["provider"]?.jsonPrimitive?.content
        buf.setModel(if (provider != null && !model.startsWith(provider)) "$provider/$model" else model)
        return true
    }

    /** 批准请求：data = {id, toolName, callId?, reason?}。会话级（无 turn 字段）。 */
    private fun applyApprovalAsked(buf: SessionBuffer, ev: RawEvent): Boolean {
        val d = ev.data?.jsonObject ?: return false
        val id = d["id"]?.jsonPrimitive?.content ?: return false
        val toolName = d["toolName"]?.jsonPrimitive?.content ?: "工具"
        val reason = d["reason"]?.jsonPrimitive?.content ?: ""
        buf.upsertApproval(ApprovalUi(id, toolName, reason, null))
        return true
    }

    /** 批准结果：data = {id, outcome}。outcome: allowed-once/rejected/cancelled。 */
    private fun applyApprovalDecided(buf: SessionBuffer, ev: RawEvent): Boolean {
        val d = ev.data?.jsonObject ?: return false
        val id = d["id"]?.jsonPrimitive?.content ?: return false
        val outcome = d["outcome"]?.jsonPrimitive?.content ?: return false
        buf.setApprovalOutcome(id, outcome)
        return true
    }

    /** ptc 工具调用开始：data = {subCallId, name, arguments}。无 turn 字段，落到当前活跃轮。 */
    private fun applyPtcStart(buf: SessionBuffer, ev: RawEvent): Boolean {
        val d = ev.data?.jsonObject ?: return false
        val subCallId = d["subCallId"]?.jsonPrimitive?.content ?: ""
        val name = d["name"]?.jsonPrimitive?.content
        val args = d["arguments"]?.let { if (it is JsonPrimitive) it.content else it.toString() }
        val tb = buf.currentTurn()
        val step = tb.steps.keys.maxOrNull() ?: 1
        buf.upsertTool(tb, step, subCallId, name, args)
        return true
    }

    /** ptc 工具调用结果：data = {subCallId, name?, arguments?, isError, content:[{type:text}]}。 */
    private fun applyPtcDispatch(buf: SessionBuffer, ev: RawEvent): Boolean {
        val d = ev.data?.jsonObject ?: return false
        val subCallId = d["subCallId"]?.jsonPrimitive?.content ?: ""
        val text = d["content"]?.jsonArray?.mapNotNull { p ->
            val o = p.jsonObject
            if (o["type"]?.jsonPrimitive?.content == "text") o["text"]?.jsonPrimitive?.content else null
        }?.joinToString("\n")?.trim() ?: ""
        val isError = d["isError"]?.jsonPrimitive?.content == "true"
        // 快照里可能只有 -dispatch（无 -start）：先确保 tool 存在
        if (buf.toolByCallId(subCallId) == null) {
            val name = d["name"]?.jsonPrimitive?.content
            val args = d["arguments"]?.let { if (it is JsonPrimitive) it.content else it.toString() }
            val tb = buf.currentTurn()
            val step = tb.steps.keys.maxOrNull() ?: 1
            buf.upsertTool(tb, step, subCallId, name, args)
        }
        return if (buf.setToolResult(subCallId, text, isError)) true else {
            buf.appendOrphanTool(subCallId, text, isError)
            true
        }
    }
}
