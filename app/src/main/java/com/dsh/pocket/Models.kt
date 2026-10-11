package com.dsh.pocket

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 一轮对话摘要（session/list 与 session/projections 的 turnOutline 元素）。 */
@Serializable
data class TurnOutline(
    val turn: Int = 0,
    val seq: Int = 0,
    val prompt: String = "",
    val response: String = "",
    val reasoning: String? = null,
    val toolCalls: List<String> = emptyList()
)

/** 会话投影 values：标题 + 目标 + 对话轮次 + 上下文压力。 */
@Serializable
data class SessionValues(
    val title: String? = null,
    val goal: JsonElement? = null,
    val contextPressure: ContextPressure? = null,
    val turnOutline: List<TurnOutline> = emptyList()
)

/** 上下文占用（浏览器会在会话头部显示）。 */
@Serializable
data class ContextPressure(
    val pressureTokens: Long = 0,
    val projectedTokens: Long = 0,
    val contextWindow: Long = 0
)

@Serializable
data class SessionProjections(
    val kind: String? = null,
    val asOfSeq: Int = 0,
    val values: SessionValues = SessionValues()
)

@Serializable
data class SessionItem(
    val sessionId: String = "",
    val updatedAt: Long = 0,
    val running: Boolean = false,
    val blank: Boolean = false,
    val cwd: String? = null,
    val projections: SessionProjections = SessionProjections()
)

@Serializable
data class SessionListValue(
    val items: List<SessionItem> = emptyList()
)

/** session/projections 返回 {asOfSeq, values}。 */
@Serializable
data class ProjectionsValue(
    val asOfSeq: Int = 0,
    val values: SessionValues = SessionValues()
)

/** workspaceFiles/list 返回。 */
@Serializable
data class FileEntry(
    val name: String = "",
    val type: String = "",
    val size: Long? = null
)

/** fileReferences/list 返回的会话文件引用项。 */
@Serializable
data class FileRef(
    val path: String = "",
    val kind: String = ""
)

/** 待发送的附件：图片走内联 base64，文件走上传 receiptId。 */
data class PendingAttachment(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
    val kind: String,           // "image" 或 "file"
    val receiptId: String? = null   // 文件上传后的 receiptId；image 不用
)

// ---- 工作区 / 归档 / 历史翻页（本轮实测新增） ----

/** workspace/follow baseline 里的一个工作区。 */
@Serializable
data class WorkspaceItem(
    val workspaceId: String = "",
    val path: String = "",
    val title: String = "",
    val sessionIds: List<String> = emptyList()
)

/** workspace/follow 首帧 value：工作区列表 + 归档/置顶会话。 */
@Serializable
data class WorkspaceBaseline(
    val items: List<WorkspaceItem> = emptyList(),
    val archivedSessionIds: List<String> = emptyList(),
    val pinnedSessionIds: List<String> = emptyList()
)

/** workspace/archiveSession 与 workspace/unarchiveSession 的返回。 */
@Serializable
data class ArchiveValue(
    val archivedSessionIds: List<String> = emptyList()
)

/** session/page 返回：{records:[{type:"event",event:{...}}], hasMore}。records 由 FollowFrames 解析。 */
@Serializable
data class PageValue(
    val records: List<JsonElement> = emptyList(),
    val hasMore: Boolean = false
)

@Serializable
data class FileListValue(
    val path: String = "",
    val entries: List<FileEntry> = emptyList(),
    val truncated: Boolean = false
)
