package com.dsh.pocket

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * session/follow 与 session/page 帧解析层。
 *
 * 实测结构：
 * - follow 首帧：{"type":"snapshot","header":{...},"cursor":3159,"records":[{"type":"event","event":{...}}],
 *                "hasMore":true,"projections":{"asOfSeq":..,"values":{"title":..}}}
 * - follow 后续：{"type":"event","event":{"type":"assistant/message","seq":..,"time":..,"data":{..}}}
 * - session/page：{"records":[{"type":"event","event":{...}}],"hasMore":true}
 */

data class RawEvent(
    val type: String = "",
    val seq: Long = -1L,
    val time: Long = 0L,
    val data: JsonElement? = null
)

sealed interface FollowFrame

data class SnapshotFrame(
    val cursor: Long = -1L,
    val hasMore: Boolean = false,
    val records: List<RawEvent> = emptyList(),
    val title: String? = null,
    val cwd: String? = null
) : FollowFrame

data class EventFrame(val event: RawEvent) : FollowFrame

data class ErrorFrame(val message: String) : FollowFrame

object UnknownFrame : FollowFrame

/** records[] 元素：{"type":"event","event":{...}}，也容错裸 event。 */
fun parseRecord(el: JsonElement): RawEvent? {
    val o = el.jsonObject
    val inner = o["event"] ?: return parseEventObject(el)
    return parseEventObject(inner)
}

fun parseEventObject(el: JsonElement): RawEvent? {
    val o = el.jsonObject
    val type = o["type"]?.jsonPrimitive?.contentOrNull ?: return null
    val seq = o["seq"]?.jsonPrimitive?.longOrNull ?: -1L
    val time = o["time"]?.jsonPrimitive?.longOrNull ?: 0L
    return RawEvent(type, seq, time, o["data"])
}

fun parseFollowFrame(v: JsonElement): FollowFrame {
    val o = v.jsonObject
    return when (o["type"]?.jsonPrimitive?.contentOrNull) {
        "snapshot" -> {
            val cursor = o["cursor"]?.jsonPrimitive?.longOrNull ?: -1L
            val hasMore = o["hasMore"]?.jsonPrimitive?.contentOrNull == "true"
            val records = o["records"]?.jsonArray?.mapNotNull { parseRecord(it) } ?: emptyList()
            val title = o["projections"]?.jsonObject?.get("values")
                ?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
            val cwd = o["header"]?.jsonObject?.get("cwd")?.jsonPrimitive?.contentOrNull
            SnapshotFrame(cursor, hasMore, records, title, cwd)
        }
        "event" -> {
            val ev = o["event"]?.let { parseEventObject(it) } ?: return UnknownFrame
            EventFrame(ev)
        }
        "error" -> ErrorFrame(
            o["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: o["message"]?.jsonPrimitive?.contentOrNull
                ?: "stream error"
        )
        else -> UnknownFrame
    }
}

/** workspace/follow 首帧：{"type":"baseline","value":{items,archivedSessionIds,pinnedSessionIds}}。 */
fun parseWorkspaceBaseline(v: JsonElement): WorkspaceBaseline? {
    val o = v.jsonObject
    if (o["type"]?.jsonPrimitive?.contentOrNull != "baseline") return null
    val value = o["value"]?.jsonObject ?: return null
    return WorkspaceBaseline(
        items = value["items"]?.jsonArray?.mapNotNull { it.jsonObject.let { w ->
            WorkspaceItem(
                workspaceId = w["workspaceId"]?.jsonPrimitive?.contentOrNull ?: "",
                path = w["path"]?.jsonPrimitive?.contentOrNull ?: "",
                title = w["title"]?.jsonPrimitive?.contentOrNull ?: "",
                sessionIds = w["sessionIds"]?.jsonArray?.mapNotNull { s ->
                    (s as? JsonPrimitive)?.contentOrNull
                } ?: emptyList()
            )
        } } ?: emptyList(),
        archivedSessionIds = value["archivedSessionIds"]?.jsonArray?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        } ?: emptyList(),
        pinnedSessionIds = value["pinnedSessionIds"]?.jsonArray?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        } ?: emptyList()
    )
}

/** 取 data.xxx 的字符串（data 可能是 JsonObject）。 */
fun dataString(data: JsonElement?, key: String): String? =
    data?.jsonObject?.get(key)?.jsonPrimitive?.contentOrNull

fun dataLong(data: JsonElement?, key: String): Long? =
    data?.jsonObject?.get(key)?.jsonPrimitive?.longOrNull

fun dataInt(data: JsonElement?, key: String): Int? =
    data?.jsonObject?.get(key)?.jsonPrimitive?.contentOrNull?.toIntOrNull()

fun dataObject(data: JsonElement?, key: String): JsonElement? =
    data?.jsonObject?.get(key)
