package com.dsh.pocket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** 一个分组：一个工作区，或兜底的「未分组」。 */
data class WorkspaceGroup(
    val key: String,
    val title: String,
    val path: String? = null,
    val sessions: List<SessionItem> = emptyList()
)

/**
 * 工作区与归档状态的唯一权威来源：常驻一条 workspace/follow WS 流。
 *
 * 实测：session/list 不带归档标记，归档只存在于 workspace/follow 的 baseline
 * （{"type":"baseline","value":{items,archivedSessionIds,pinnedSessionIds}}）。
 * 另有 9 条会话不属于任何工作区 → 必须落到「未分组」桶，否则会凭空消失。
 */
object WorkspaceStore {

    private const val UNGROUPED = "__ungrouped__"

    private val _baseline = MutableStateFlow<WorkspaceBaseline?>(null)
    val baseline: StateFlow<WorkspaceBaseline?> = _baseline

    /** 工作区分组折叠状态：单例常驻，跨「列表↔会话」导航持久（修复返回后折叠丢失）。 */
    val expandedGroups = mutableStateMapOf<String, Boolean>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 1000L
            while (isActive) {
                try {
                    val ch = DshRepository.followWorkspaces()
                    ch.consumeEach { frame ->
                        parseWorkspaceBaseline(frame)?.let {
                            _baseline.value = it
                            backoff = 1000L
                        }
                    }
                } catch (_: Exception) {
                    // 静默重连
                }
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun archivedIds(): Set<String> =
        _baseline.value?.archivedSessionIds?.toSet() ?: emptySet()

    /** 归档：用返回值立即本地更新，不等流推增量。 */
    suspend fun archive(sessionId: String) {
        applyArchived(DshRepository.archiveSession(sessionId))
    }

    /** 取消归档（实测 workspace/unarchiveSession 端点存在）。 */
    suspend fun unarchive(sessionId: String) {
        applyArchived(DshRepository.unarchiveSession(sessionId))
    }

    private fun applyArchived(list: List<String>) {
        val cur = _baseline.value
        _baseline.value = (cur ?: WorkspaceBaseline()).copy(archivedSessionIds = list)
    }

    /** 置顶：从返回值提取 pinnedSessionIds 立即本地更新；提取不到则留待 follow 流推增量。 */
    suspend fun pin(sessionId: String) {
        applyPinned(DshRepository.pinSession(sessionId))
    }

    suspend fun unpin(sessionId: String) {
        applyPinned(DshRepository.unpinSession(sessionId))
    }

    private fun applyPinned(raw: JsonElement) {
        val arr = (raw as? JsonObject)?.get("pinnedSessionIds")?.jsonArray ?: return
        val list = arr.mapNotNull { it.jsonPrimitive.contentOrNull }
        val cur = _baseline.value
        _baseline.value = (cur ?: WorkspaceBaseline()).copy(pinnedSessionIds = list)
    }

    /**
     * 按工作区分组。归档会话不在主分组里返回，由 UI 决定是否单独展示。
     * 排序：工作区按 baseline 顺序；组内按 workspace.sessionIds 顺序；未分组按更新时间倒序。
     */
    fun groups(all: List<SessionItem>, archived: Set<String>, pinned: Set<String> = emptySet()): List<WorkspaceGroup> {
        val byId = all.associateBy { it.sessionId }
        val used = HashSet<String>()
        val out = ArrayList<WorkspaceGroup>()

        // 置顶组：跨工作区，固定在列表顶部（对标浏览器 pinned）
        if (pinned.isNotEmpty()) {
            val plist = all.filter { it.sessionId in pinned && it.sessionId !in archived }
                .sortedByDescending { it.updatedAt }
            if (plist.isNotEmpty()) {
                out.add(WorkspaceGroup("__pinned__", "置顶", null, plist))
                used.addAll(plist.map { it.sessionId })
            }
        }

        val items = _baseline.value?.items ?: emptyList()
        for (w in items) {
            val list = w.sessionIds.mapNotNull { byId[it] }
                .filter { it.sessionId !in archived && it.sessionId !in pinned }
                .sortedByDescending { it.updatedAt }
            used.addAll(w.sessionIds)
            // 工作区里可能全部被归档，仍保留分组头，避免分组忽隐忽现
            out.add(WorkspaceGroup(w.workspaceId, w.title.ifBlank { w.path }, w.path, list))
        }

        val rest = all.filter { it.sessionId !in used && it.sessionId !in archived && it.sessionId !in pinned }
            .sortedByDescending { it.updatedAt }
        if (rest.isNotEmpty()) {
            out.add(WorkspaceGroup(UNGROUPED, "未分组", null, rest))
        }
        return out.filter { it.sessions.isNotEmpty() }
    }

    fun archivedGroup(all: List<SessionItem>, archived: Set<String>): WorkspaceGroup? {
        if (archived.isEmpty()) return null
        val list = all.filter { it.sessionId in archived }.sortedByDescending { it.updatedAt }
        if (list.isEmpty()) return null
        return WorkspaceGroup("__archived__", "已归档", null, list)
    }
}
