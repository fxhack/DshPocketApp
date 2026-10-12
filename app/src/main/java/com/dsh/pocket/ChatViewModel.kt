package com.dsh.pocket

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 从 projections.values.goal 提取目标文本（服务端可能返回字符串或 {objective,...} 对象）。 */
private fun goalText(g: JsonElement?): String? = when (g) {
    is JsonPrimitive -> g.contentOrNull
    is JsonObject -> g["objective"]?.jsonPrimitive?.contentOrNull
    else -> null
}

/**
 * 聊天页状态容器。
 *
 * 两条数据源：
 * - HTTP session/projections：快、小，先把全部轮次的文本渲染出来（首屏秒开），标题也来自这里。
 * - WS session/follow：权威，快照里带 reasoning 与工具调用，用来补齐「思考/工具」。
 * - HTTP session/page：点「加载更早」时按 throughSeq 回溯更早历史。
 *
 * 归约统一在 SessionBuffer 上做，靠 seq 幂等保证三条路径叠加不会重复。
 */
class ChatViewModel : ViewModel() {

    /** 首次渲染的轮数窗口（用户选定：先加载最近约 20 轮）。 */
    private val windowSize = 20
    /** session/page 单次回溯的事件条数上限。 */
    private val pageMessages = 150

    private var buf = SessionBuffer()
    private val mutex = Mutex()
    private var generation = 0

    companion object {
        private val bufferCache = LinkedHashMap<String, SessionBuffer>()
        private val stateCache = mutableMapOf<String, ChatState>()
        private val windowCache = mutableMapOf<String, Pair<Int, Boolean>>()
        private const val MAX_CACHE = 8
    }

    /** 切换会话前缓存当前会话的缓冲（以便下次进入秒开）。 */
    private fun cacheSession(sid: String) {
        bufferCache[sid] = buf
        stateCache[sid] = _state.value
        windowCache[sid] = windowStart to windowInitialized
        buf = SessionBuffer()
        while (bufferCache.size > MAX_CACHE) {
            val oldest = bufferCache.keys.iterator().next()
            bufferCache.remove(oldest)
            stateCache.remove(oldest)
            windowCache.remove(oldest)
        }
    }

    /** 从缓存恢复会话缓冲。命中返回 true。 */
    private fun restoreSession(sid: String): Boolean {
        val cachedBuf = bufferCache.remove(sid) ?: return false
        val cachedState = stateCache.remove(sid)
        val cachedWindow = windowCache.remove(sid)
        buf = cachedBuf
        if (cachedWindow != null) {
            windowStart = cachedWindow.first
            windowInitialized = cachedWindow.second
        }
        if (cachedState != null) {
            _state.value = cachedState
            _rows.value = buildRows()
        }
        return true
    }

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    private val _rows = MutableStateFlow<List<ChatRow>>(emptyList())
    val rows: StateFlow<List<ChatRow>> = _rows

    /** 分叉出的新会话 id（ChatScreen 收到后跳转并置空）。 */
    private val _forkResult = MutableStateFlow<String?>(null)
    val forkResult: StateFlow<String?> = _forkResult

    /** 待发送的附件（图片内联 / 文件已上传拿 receiptId）。 */
    private val _attachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val attachments: StateFlow<List<PendingAttachment>> = _attachments

    private val _modelCatalog = MutableStateFlow<ModelCatalog?>(null)
    val modelCatalog: StateFlow<ModelCatalog?> = _modelCatalog

    /** 已有消息反馈：messageId → rating（positive/negative）。 */
    private val _feedback = MutableStateFlow<Map<String, String>>(emptyMap())
    val feedback: StateFlow<Map<String, String>> = _feedback

    private var followJob: Job? = null
    private var throttleJob: Job? = null
    private var windowStart = 0
    private var windowInitialized = false
    private var pendingPlaceholder: Int? = null

    // ---- 生命周期 ----

    fun attach(sessionId: String, initialTitle: String?) {
        generation++
        val gen = generation

        val curSid = _state.value.sessionId
        val sameSession = curSid == sessionId && buf.size() > 0

        // 切换到不同会话时缓存当前会话
        if (!sameSession && curSid.isNotEmpty() && buf.size() > 0) {
            cacheSession(curSid)
        }

        detach()

        // 不同会话：尝试从缓存恢复
        if (!sameSession) {
            restoreSession(sessionId)
        }

        if (buf.size() > 0) {
            // 秒开：已有消息（同会话重入或缓存命中），直接显示，后台刷新最新 projections
            _state.value = _state.value.copy(
                sessionId = sessionId, phase = ChatPhase.Ready,
                error = null, activity = null
            )
            _rows.value = buildRows()
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val pv = DshRepository.getProjectionsValue(sessionId)
                    if (gen != generation) return@launch
                    mutex.withLock { if (gen == generation) SessionReducer.applyProjections(buf, pv.values.turnOutline) }
                    if (gen != generation) return@launch
                    publishNow()
                    _state.value = _state.value.copy(
                        title = pv.values.title ?: initialTitle,
                        goal = goalText(pv.values.goal),
                        contextText = contextText(pv.values.contextPressure)
                    )
                } catch (_: Exception) { }
            }
        } else {
            // 首次加载：从服务端拉取
            buf.clear()
            windowStart = 0
            windowInitialized = false
            pendingPlaceholder = null
            _rows.value = emptyList()
            _state.value = ChatState(sessionId = sessionId, title = initialTitle, phase = ChatPhase.Loading)

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val pv = DshRepository.getProjectionsValue(sessionId)
                    if (gen != generation) return@launch
                    mutex.withLock { if (gen == generation) SessionReducer.applyProjections(buf, pv.values.turnOutline) }
                    if (gen != generation) return@launch
                    publishNow()
                    _state.value = _state.value.copy(
                        title = pv.values.title ?: initialTitle,
                        goal = goalText(pv.values.goal),
                        contextText = contextText(pv.values.contextPressure),
                        phase = ChatPhase.Ready,
                        error = null
                    )
                } catch (e: Exception) {
                    if (gen == generation && _state.value.phase == ChatPhase.Loading) {
                        _state.value = _state.value.copy(
                            phase = ChatPhase.Error,
                            error = e.message ?: "加载会话失败"
                        )
                    }
                }
            }
        }
        startFollow(sessionId)
        loadFeedback()
    }

    fun detach() {
        followJob?.cancel()
        followJob = null
        throttleJob?.cancel()
        throttleJob = null
        runningWatchdog?.cancel()
        runningWatchdog = null
    }

    override fun onCleared() {
        detach()
        buf.clear()
        bufferCache.clear()
        stateCache.clear()
        windowCache.clear()
        super.onCleared()
    }

    fun retry() {
        val s = _state.value
        if (s.sessionId.isNotEmpty()) attach(s.sessionId, s.title)
    }

    // ---- 交互 ----

    fun send(text: String, references: List<JsonObject> = emptyList()) {
        val t = text.trim()
        val sid = _state.value.sessionId
        if (sid.isEmpty()) return
        val atts = _attachments.value
        if (t.isEmpty() && atts.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                val ph = buf.newPlaceholderTurn()
                buf.turn(ph).prompt = if (t.isNotEmpty()) t else "(附件)"
                pendingPlaceholder = ph
                _rows.value = buildRows()
            }
            _state.value = _state.value.copy(
                running = true,
                activity = if (atts.isNotEmpty()) "正在发送附件…" else "已发送，等待回复…",
                error = null,
                scrollToken = _state.value.scrollToken + 1
            )
            startRunningWatchdog()
            try {
                val parts = mutableListOf<JsonObject>()
                if (t.isNotEmpty()) parts += buildJsonObject {
                    put("type", "text"); put("text", t)
                }
                for (a in atts) {
                    if (a.kind == "image") {
                        val mt = if (a.mime in listOf("image/png", "image/jpeg", "image/webp", "image/gif")) a.mime else "image/png"
                        parts += buildJsonObject {
                            put("type", "image")
                            put("mediaType", mt)
                            put("data", android.util.Base64.encodeToString(a.bytes, android.util.Base64.NO_WRAP))
                            if (a.name.isNotBlank()) put("name", a.name)
                        }
                    } else {
                        val rid = a.receiptId ?: throw DshException("附件未上传: " + a.name)
                        parts += buildJsonObject {
                            put("type", "file")
                            put("receiptId", rid)
                        }
                    }
                }
                DshRepository.promptContent(sid, parts, references)
                _attachments.value = emptyList()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    running = false,
                    activity = null,
                    error = e.message ?: "发送失败"
                )
            }
        }
    }

    /** 回答 AI 提问（ask_user_question）：提交选中选项 + 自定义输入。 */
    fun answerQuestion(callId: String, answers: List<QuestionAnswer>) {
        val sid = _state.value.sessionId
        if (sid.isEmpty() || callId.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DshRepository.answerQuestion(sid, callId, answers)
                _state.value = _state.value.copy(activity = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(activity = null, error = e.message ?: "回答失败")
            }
        }
    }

    /** 加入附件：图片存原始字节（发送时内联 base64），文件先上传拿 receiptId。 */
    fun addAttachment(name: String, mime: String, bytes: ByteArray) {
        val sid = _state.value.sessionId
        if (sid.isEmpty()) return
        val isImage = mime.startsWith("image/")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isImage) {
                    _attachments.value = _attachments.value + PendingAttachment(name, mime, bytes, "image")
                } else {
                    _state.value = _state.value.copy(activity = "正在上传 " + name + "…")
                    val rid = DshRepository.uploadAttachment(sid, name, bytes)
                    _attachments.value = _attachments.value + PendingAttachment(name, mime, bytes, "file", rid)
                    _state.value = _state.value.copy(activity = null)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(activity = null, error = e.message ?: "附件处理失败")
            }
        }
    }

    fun removeAttachment(index: Int) {
        _attachments.value = _attachments.value.toMutableList().also { it.removeAt(index) }
    }

    fun stop() {
        val sid = _state.value.sessionId
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(activity = "正在停止…")
            try {
                DshRepository.stop(sid)
            } catch (_: Exception) {
                _state.value = _state.value.copy(activity = null)
            }
        }
    }

    /** 重命名会话（对标浏览器：标题可直接改）。 */
    fun rename(title: String) {
        val t = title.trim()
        val sid = _state.value.sessionId
        if (t.isEmpty() || sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DshRepository.rename(sid, t)
                _state.value = _state.value.copy(title = t, error = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "重命名失败")
            }
        }
    }

    /** 从当前会话分叉出一个新会话（保留到此为止的历史）。 */
    fun fork() {
        val sid = _state.value.sessionId
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _forkResult.value = DshRepository.fork(sid)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "复制会话失败")
            }
        }
    }

    fun consumeFork() {
        _forkResult.value = null
    }

    /** 拉取本会话已有消息反馈（点赞/点踩）列表。 */
    fun loadFeedback() {
        val sid = _state.value.sessionId
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try { _feedback.value = DshRepository.messageFeedbackList(sid) }
            catch (_: Exception) { }
        }
    }

    /** 提交一条消息反馈：rating=positive/negative，可附备注 note 与类别 category（拨号）。 */
    fun feedbackPut(messageId: String, rating: String, note: String? = null, category: String? = null) {
        val sid = _state.value.sessionId
        if (sid.isEmpty() || messageId.isEmpty()) return
        _feedback.value = _feedback.value + (messageId to rating)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DshRepository.messageFeedbackPut(sid, messageId, rating, note, category)
                _feedback.value = _feedback.value + (messageId to rating)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "反馈失败")
            }
        }
    }

    /** 加载模型目录（菜单「切换模型」打开时调用）。 */
    fun loadModelCatalog() {
        if (_modelCatalog.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            try { _modelCatalog.value = DshRepository.getModelCatalog() }
            catch (e: Exception) { _state.value = _state.value.copy(error = e.message ?: "加载模型失败") }
        }
    }

    /** 切换会话模型。selectModel 成功后服务端会发 model/selection 事件更新 header。 */
    fun selectModel(provider: String, model: String) {
        val sid = _state.value.sessionId
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try { DshRepository.selectModel(sid, provider, model) }
            catch (e: Exception) { _state.value = _state.value.copy(error = e.message ?: "切换模型失败") }
        }
    }

    /** 顶部「加载更早」：先本地前移窗口（瞬时），必要时再向服务端回溯。 */
    fun loadEarlier() {
        if (_state.value.sessionId.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                windowStart = (windowStart - windowSize).coerceAtLeast(0)
                _rows.value = buildRows()
            }
            maybeLoadPage()
        }
    }

    // ---- 内部 ----

    private fun startFollow(sessionId: String) {
        followJob?.cancel()
        followJob = viewModelScope.launch(Dispatchers.IO) {
            var backoff = 1000L
            while (isActive) {
                try {
                    val ch = DshRepository.followSession(sessionId)
                    // consumeEach 保证退出时 cancel channel → 关闭 WebSocket，不泄漏连接
                    ch.consumeEach { frame ->
                        val ftype = (frame as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.content }
                        if (ftype == "assistant-stream") {
                            onLiveChunk(frame)
                            return@consumeEach
                        }
                        when (val f = parseFollowFrame(frame)) {
                            is SnapshotFrame -> {
                                mutex.withLock { SessionReducer.applySnapshot(buf, f) }
                                publishNow()
                                // 目标常不在 projections 里（实测为 null），从 goal/change 事件取
                                val snapGoal = f.records
                                    .mapNotNull { SessionReducer.goalObjective(it) }
                                    .lastOrNull()
                                // 快照校正运行态：最后一条 turn 事件是 turn/start→运行中，turn/end→已结束。
                                // 防止 WS 中断丢失 turn/end 导致「任务结束仍显示正在思考」。
                                val lastTurn = f.records.lastOrNull { it.type == "turn/start" || it.type == "turn/end" }
                                val snapRunning = lastTurn?.type == "turn/start"
                                _state.value = _state.value.copy(
                                    title = f.title ?: _state.value.title,
                                    cwd = f.cwd ?: _state.value.cwd,
                                    goal = snapGoal ?: _state.value.goal,
                                    todos = buf.todos,
                                    model = buf.model,
                                    approvals = buf.approvals,
                                    running = snapRunning,
                                    activity = if (snapRunning) _state.value.activity else null,
                                    detailLoaded = true,
                                    phase = ChatPhase.Ready,
                                    error = null
                                )
                                backoff = 1000L
                            }
                            is EventFrame -> onLiveEvent(f.event)
                            is ErrorFrame -> throw DshException(f.message)
                            UnknownFrame -> Unit
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    val msg = e.message ?: "连接断开"
                    val fatal = msg.contains("401") || msg.contains("未登录") ||
                        msg.contains("not-found") || msg.contains("session/not-found")
                    if (fatal) {
                        _state.value = _state.value.copy(phase = ChatPhase.Error, error = msg)
                        break
                    }
                }
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun onLiveEvent(ev: RawEvent) {
        val label = SessionReducer.activityLabel(ev)
        // 目标变更事件（projections.values.goal 实测常为 null，只能从事件拿）
        SessionReducer.goalObjective(ev)?.let { obj ->
            if (_state.value.goal != obj) _state.value = _state.value.copy(goal = obj)
        }
        var changed = false
        mutex.withLock {
                if (ev.type == "turn/start") {
                    val t = dataInt(ev.data, "turn")
                    val ph = pendingPlaceholder
                    if (t != null && ph != null && !buf.turnNumbers().contains(t)) {
                        buf.renumberPlaceholder(ph, t)
                        pendingPlaceholder = null
                        changed = true
                    }
                }
                if (SessionReducer.applyEvent(buf, ev)) changed = true
                if (_state.value.todos != buf.todos || _state.value.model != buf.model || _state.value.approvals != buf.approvals)
                    _state.value = _state.value.copy(todos = buf.todos, model = buf.model, approvals = buf.approvals)
            }
            when (ev.type) {
                "turn/end" -> {
                    _state.value = _state.value.copy(running = false, activity = null, streamText = "")
                    publishNow()
                }
                "turn/start" -> {
                    _state.value = _state.value.copy(running = true, activity = label ?: "开始回复", streamText = "")
                    if (changed) publishNow()
                }
                else -> {
                    if (changed) {
                        if (label != null) _state.value = _state.value.copy(activity = label)
                        publishThrottled()
                    }
                }
            }
    }

    /** 实时流式帧（assistant-stream）：生成期逐 token chunk，立即展示（dt=0），不等待 AM。 */
    private suspend fun onLiveChunk(frame: JsonElement) {
        val fr = (frame as? JsonObject)?.get("frame") as? JsonObject ?: return
        var changed = false
        var chunkText = ""
        mutex.withLock {
            val type = (fr["type"] as? JsonPrimitive)?.content
            if (type == "chunk") {
                val chunk = fr["chunk"] as? JsonObject
                val text = (chunk?.get("text") as? JsonPrimitive)?.content
                if (text != null) chunkText = text
            }
            if (SessionReducer.applyLiveChunk(buf, fr)) changed = true
        }
        if (changed) {
            // 更新流式文本用于状态栏滚动显示
            if (chunkText.isNotEmpty()) {
                _state.value = _state.value.copy(
                    streamText = (_state.value.streamText + chunkText).takeLast(200)
                )
            }
            publishThrottled()
        }
    }

    private suspend fun maybeLoadPage() {
        val s = _state.value
        if (s.pageExhausted || s.loadingEarlier || s.sessionId.isEmpty()) return
        // 本地缓冲还够用就先不发请求
        if (windowStart > windowSize / 2) return
        val earliest = buf.minSeq()
        if (earliest <= 0) {
            _state.value = s.copy(pageExhausted = true)
            publishNow()
            return
        }
        _state.value = s.copy(loadingEarlier = true)
        publishNow()
        try {
            val page = DshRepository.pageHistory(s.sessionId, earliest, null, pageMessages)
            val recs = page.records.mapNotNull { parseRecord(it) }
            mutex.withLock { SessionReducer.applyPage(buf, recs) }
            val gotNew = recs.isNotEmpty()
            _state.value = _state.value.copy(
                loadingEarlier = false,
                pageExhausted = !page.hasMore || !gotNew,
                hasMoreBefore = page.hasMore
            )
            publishNow()
        } catch (e: Exception) {
            _state.value = _state.value.copy(loadingEarlier = false, pageExhausted = true)
            publishNow()
        }
    }

    private fun publishThrottled() {
        if (throttleJob?.isActive == true) return
        throttleJob = viewModelScope.launch {
            delay(16)
            publishNow()
        }
    }

    /** 运行态看门狗：running 期间每 7s 轮询会话真实运行态；服务端已结束则收敛
     * （防 turn/end 事件丢失导致「任务结束仍显示正在思考」）。 */
    private var runningWatchdog: Job? = null
    private fun startRunningWatchdog() {
        runningWatchdog?.cancel()
        runningWatchdog = viewModelScope.launch(Dispatchers.IO) {
            var falseReads = 0
            while (isActive) {
                delay(8000)
                if (!isActive || !_state.value.running) break
                try {
                    val sid = _state.value.sessionId
                    if (sid.isEmpty()) break
                    val s = DshRepository.listSessions().firstOrNull { it.sessionId == sid }
                    if (s != null && !s.running) {
                        falseReads++
                        // 连续两次确认服务端已结束才收敛（避免轮次启动前的瞬时 false 误清）
                        if (falseReads >= 2) {
                            _state.value = _state.value.copy(running = false, activity = null)
                            publishNow()
                            break
                        }
                    } else {
                        falseReads = 0
                    }
                } catch (_: Exception) { falseReads = 0 }
            }
        }
    }

    private suspend fun publishNow() {
        mutex.withLock { _rows.value = buildRows() }
    }

    /** 必须在 mutex 内调用。 */
    private fun buildRows(): List<ChatRow> {
        val all = buf.snapshot()
        if (!windowInitialized && all.size > 0) {
            windowStart = (all.size - windowSize).coerceAtLeast(0)
            windowInitialized = true
        }
        val start = windowStart.coerceIn(0, (all.size - 1).coerceAtLeast(0))
        val visible = if (all.isEmpty()) emptyList() else all.subList(start, all.size)
        val more = when {
            _state.value.loadingEarlier -> LoadMoreState.Loading
            start > 0 -> LoadMoreState.Available
            _state.value.pageExhausted -> LoadMoreState.Exhausted
            else -> LoadMoreState.Available
        }
        val out = ArrayList<ChatRow>(visible.size + 1)
        out.add(LoadMoreRow(more))
        for (t in visible) out.add(TurnRow(t))
        return out
    }

    /** 上下文占用：形如 "上下文 47% · 118K/256K"。 */
    private fun contextText(cp: ContextPressure?): String? {
        if (cp == null || cp.contextWindow <= 0) return null
        val pct = ((cp.pressureTokens * 100L) / cp.contextWindow).toInt().coerceIn(0, 100)
        return "上下文 " + pct + "% · " + fmtK(cp.pressureTokens) + "/" + fmtK(cp.contextWindow)
    }

    private fun fmtK(v: Long): String =
        if (v >= 1024) (v / 1024).toString() + "K" else v.toString()
}
