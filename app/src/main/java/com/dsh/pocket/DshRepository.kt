package com.dsh.pocket

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/**
 * DSH 数据仓库：在 DshClient 之上封装会话/消息语义。
 *
 * - listSessions(): session/list（wire=_request）→ 会话列表含 turnOutline。
 * - getProjections(sid): session/projections（wire=request）→ 该会话对话轮次。
 * - prompt(sid, text): session/prompt 排队用户消息。
 * - awaitNewTurn(sid, prev): 轮询 projections 直到出现新轮。
 */
object DshRepository {

    val client = DshClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

    var pin: String = Config.PIN
    var loggedIn: Boolean = false
        private set

    suspend fun login(): Boolean {
        loggedIn = client.login(pin)
        return loggedIn
    }

    /** 建会话。指定 workspaceId 可让新会话直接归属某个工作区（实测该参数会被校验）。 */
    suspend fun createSession(workspaceId: String? = null, cwd: String? = null): String {
        val args = buildJsonObject {
            put("request", buildJsonObject {
                if (!workspaceId.isNullOrBlank()) put("workspaceId", workspaceId)
                if (!cwd.isNullOrBlank()) put("cwd", cwd)
            })
        }
        val v = client.rpcCall("session/create", args)
        return v.jsonObject["sessionId"]?.jsonPrimitive?.content
            ?: throw DshException("session/create 未返回 sessionId")
    }

    /** 重命名会话（对标浏览器：标题可直接改）。 */
    suspend fun rename(sessionId: String, title: String) {
        val args = buildJsonObject {
            put("request", buildJsonObject {
                put("sessionId", sessionId)
                put("title", title)
            })
        }
        client.rpcCall("session/rename", args)
    }

    /** 消息反馈（对标浏览器 messageFeedback）：点赞/点踩 + 备注 + 类别拨号。 */
    suspend fun messageFeedbackPut(sessionId: String, messageId: String, rating: String, note: String? = null, category: String? = null) {
        val args = buildJsonObject {
            put("request", buildJsonObject {
                put("sessionId", sessionId)
                put("messageId", messageId)
                put("rating", rating)
                if (!note.isNullOrBlank()) put("note", note)
                if (!category.isNullOrBlank()) put("category", category)
            })
        }
        client.rpcCall("messageFeedback/put", args)
    }

    /** 拉取某会话所有消息的已有反馈：messageId → rating。 */
    suspend fun messageFeedbackList(sessionId: String): Map<String, String> {
        val args = buildJsonObject {
            put("request", buildJsonObject { put("sessionId", sessionId) })
        }
        val v = client.rpcCall("messageFeedback/list", args)
        val items = v.jsonObject["value"]?.jsonObject?.get("items")?.jsonArray ?: return emptyMap()
        return buildMap {
            for (it in items) {
                val o = it.jsonObject
                val mid = o["messageId"]?.jsonPrimitive?.contentOrNull ?: continue
                val r = o["rating"]?.jsonPrimitive?.contentOrNull ?: continue
                if (mid.isNotBlank()) put(mid, r)
            }
        }
    }

    /** 从当前会话分叉出一个新会话。返回新 sessionId。 */
    suspend fun fork(sessionId: String, atSeq: Long? = null): String {
        val args = buildJsonObject {
            put("request", buildJsonObject {
                put("sessionId", sessionId)
                if (atSeq != null) put("atSeq", atSeq)
            })
        }
        val v = client.rpcCall("session/fork", args)
        return v.jsonObject["sessionId"]?.jsonPrimitive?.content
            ?: throw DshException("session/fork 未返回 sessionId")
    }

    suspend fun listSessions(): List<SessionItem> {
        val args = buildJsonObject { put("_request", buildJsonObject {}) }
        val v = client.rpcCall("session/list", args)
        return json.decodeFromJsonElement(SessionListValue.serializer(), v).items
    }

    /** session/projections 返回 {asOfSeq, values:{title, goal, turnOutline}}（权威标题 + 文本兜底）。 */
    suspend fun getProjectionsValue(sessionId: String): ProjectionsValue {
        val args = buildJsonObject {
            put("request", buildJsonObject { put("sessionId", sessionId) })
        }
        val v = client.rpcCall("session/projections", args)
        return json.decodeFromJsonElement(ProjectionsValue.serializer(), v)
    }

    suspend fun getProjections(sessionId: String): List<TurnOutline> =
        getProjectionsValue(sessionId).values.turnOutline

    suspend fun prompt(sessionId: String, text: String): Boolean {
        val args = buildJsonObject {
            put("request", buildJsonObject {
                put("requestId", UUID.randomUUID().toString())
                put("sessionId", sessionId)
                put("mode", "queue")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", text) })
                })
            })
        }
        val v = client.rpcCall("session/prompt", args)
        // v 形如 {"accepted": true}
        val ok = (v as? JsonObject)?.get("accepted")?.toString()?.contains("true") ?: false
        return ok
    }

    /** 中断当前运行中的轮次（对标浏览器停止键）。session/cancel {request:{sessionId}} → {accepted:true}。 */
    suspend fun stop(sessionId: String) {
        val args = buildJsonObject {
            put("request", buildJsonObject { put("sessionId", sessionId) })
        }
        client.rpcCall("session/cancel", args)
    }

    /** 上传非图片附件，返回 receiptId（用于 prompt 的 {type:file,receiptId} 部件）。 */
    suspend fun uploadAttachment(sessionId: String, name: String, bytes: ByteArray): String {
        val v = client.uploadFileBinary(sessionId, name, bytes)
        val ok = v["ok"]?.jsonPrimitive?.contentOrNull
        if (ok != "true") throw DshException(
            v["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "上传失败"
        )
        val value = v["value"]?.jsonObject ?: throw DshException("上传无 value")
        return value["receiptId"]?.jsonPrimitive?.contentOrNull
            ?: throw DshException("上传无 receiptId")
    }

    /** 发送带任意内容部件的消息（text/image/file），可附 @引用（references 数组，对标 @file/@session）。 */
    suspend fun promptContent(sessionId: String, parts: List<JsonObject>, references: List<JsonObject> = emptyList()): Boolean {
        val arr = buildJsonArray { parts.forEach { add(it) } }
        val refArr = buildJsonArray { references.forEach { add(it) } }
        val args = buildJsonObject {
            put("request", buildJsonObject {
                put("requestId", UUID.randomUUID().toString())
                put("sessionId", sessionId)
                put("mode", "queue")
                put("content", arr)
                if (references.isNotEmpty()) put("references", refArr)
            })
        }
        val v = client.rpcCall("session/prompt", args)
        return (v as? JsonObject)?.get("accepted")?.toString()?.contains("true") ?: false
    }

    suspend fun listFiles(scopeId: String, path: String): List<FileEntry> {
        val args = buildJsonObject {
            put("workspaceFileScopeId", scopeId)
            put("path", if (path.isEmpty()) "." else path)
        }
        val v = client.rpcCall("workspaceFiles/list", args)
        return json.decodeFromJsonElement(FileListValue.serializer(), v).entries
    }

    /** 读取工作区文件内容（对标 ui-sidebar-documentpreview）。 */
    suspend fun readFile(scopeId: String, path: String): String {
        val args = buildJsonObject {
            put("workspaceFileScopeId", scopeId)
            put("path", path)
        }
        val v = client.rpcCall("workspaceFiles/read", args)
        val obj = v as? JsonObject
        return obj?.get("content")?.jsonPrimitive?.content
            ?: obj?.get("text")?.jsonPrimitive?.content
            ?: obj?.get("data")?.jsonPrimitive?.content
            ?: obj?.get("base64")?.jsonPrimitive?.content
            ?: v.toString()
    }

    /** 会话引用的文件列表（对标浏览器"会话文件"面板）。{agentId, query} → [{path,kind}]。 */
    suspend fun listFileReferences(agentId: String, query: String = ""): List<FileRef> {
        val args = buildJsonObject {
            put("agentId", agentId)
            put("query", query)
        }
        val v = client.rpcCall("fileReferences/list", args)
        return json.decodeFromJsonElement<List<FileRef>>(v)
    }

    /** 开 session/follow 流，返回 item.value 帧（snapshot + event）。ChatScreen 消费。 */
    suspend fun followSession(sessionId: String): ReceiveChannel<JsonElement> {
        val args = buildJsonObject {
            put("request", buildJsonObject {
                put("address", buildJsonObject {
                    put("kind", "session")
                    put("sessionId", sessionId)
                })
                put("assistantStream", true)
            })
        }
        return client.openStream("session/follow", args)
    }

    /**
     * 历史翻页（「加载更早」的正解，实测可用）。
     * {address:{kind:"session",sessionId}, throughSeq, beforeSeq?, maxMessages?} → {records, hasMore}
     * throughSeq 是上界（开区间）：传当前最早已加载的 seq 即可拿到更早的一批。
     */
    suspend fun pageHistory(
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long? = null,
        maxMessages: Int = 150
    ): PageValue {
        val args = buildJsonObject {
            put("request", buildJsonObject {
                put("address", buildJsonObject {
                    put("kind", "session")
                    put("sessionId", sessionId)
                })
                put("throughSeq", throughSeq)
                if (beforeSeq != null) put("beforeSeq", beforeSeq)
                put("maxMessages", maxMessages)
            })
        }
        val v = client.rpcCall("session/page", args)
        return json.decodeFromJsonElement(PageValue.serializer(), v)
    }

    /** workspace/follow：WS 流，args = 空对象。首帧 {type:"baseline", value:{items,archivedSessionIds,pinnedSessionIds}}。 */
    suspend fun followWorkspaces(): ReceiveChannel<JsonElement> =
        client.openStream("workspace/follow", buildJsonObject { })

    /** 归档会话（从工作区分组里隐藏）。返回最新的归档集合。 */
    suspend fun archiveSession(sessionId: String): List<String> {
        val args = buildJsonObject {
            put("request", buildJsonObject { put("sessionId", sessionId) })
        }
        val v = client.rpcCall("workspace/archiveSession", args)
        return json.decodeFromJsonElement(ArchiveValue.serializer(), v).archivedSessionIds
    }

    /** 取消归档（实测端点存在）。返回最新的归档集合。 */
    suspend fun unarchiveSession(sessionId: String): List<String> {
        val args = buildJsonObject {
            put("request", buildJsonObject { put("sessionId", sessionId) })
        }
        val v = client.rpcCall("workspace/unarchiveSession", args)
        return json.decodeFromJsonElement(ArchiveValue.serializer(), v).archivedSessionIds
    }

    /** 置顶会话（workspace/pinSession，与 archive 同控制器）。返回值含最新 pinnedSessionIds。 */
    suspend fun pinSession(sessionId: String): JsonElement {
        val args = buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) }
        return client.rpcCall("workspace/pinSession", args)
    }

    /** 取消置顶（workspace/unpinSession）。 */
    suspend fun unpinSession(sessionId: String): JsonElement {
        val args = buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) }
        return client.rpcCall("workspace/unpinSession", args)
    }

    /** 模型目录（session/modelCatalog，空参）。手动解析 groups→models[{id,name}]。 */
    suspend fun getModelCatalog(): ModelCatalog {
        val v = client.rpcCall("session/modelCatalog", buildJsonObject { })
        val groups = v.jsonObject["groups"]?.jsonArray?.map { g ->
            val go = g.jsonObject
            val models = go["models"]?.jsonArray?.map { m ->
                val mo = m.jsonObject
                ModelCatalogModel(mo["id"]?.jsonPrimitive?.content ?: "", mo["name"]?.jsonPrimitive?.content ?: "")
            } ?: emptyList()
            ModelProviderGroup(go["id"]?.jsonPrimitive?.content ?: "", go["name"]?.jsonPrimitive?.content ?: "", models)
        } ?: emptyList()
        return ModelCatalog(groups)
    }

    /** 切换会话模型（session/selectModel {request:{sessionId,provider,model}}）。 */
    suspend fun selectModel(sessionId: String, provider: String, model: String) {
        val args = buildJsonObject {
            put("request", buildJsonObject {
                put("sessionId", sessionId)
                put("provider", provider)
                put("model", model)
            })
        }
        client.rpcCall("session/selectModel", args)
    }

    /**
     * 回答 AI 提问（userQuestions/answer）。
     * 参数：agent=sessionId, callId, answer={answers:[{id, selected, custom?}]}。
     * selected 为选中的选项 label 数组；custom 为自定义输入。
     */
    suspend fun answerQuestion(sessionId: String, callId: String, answers: List<QuestionAnswer>): Boolean {
        val args = buildJsonObject {
            put("agent", sessionId)
            put("callId", callId)
            put("answer", buildJsonObject {
                putJsonArray("answers") {
                    answers.forEach { a ->
                        add(buildJsonObject {
                            put("id", a.id)
                            putJsonArray("selected") { a.selected.forEach { add(it) } }
                            if (!a.custom.isNullOrBlank()) put("custom", a.custom)
                        })
                    }
                }
            })
        }
        val v = client.rpcCall("userQuestions/answer", args)
        return runCatching { v.jsonPrimitive.content }.getOrNull()?.toBoolean() ?: false
    }
}

/** 单个问题的回答：id=问题 id，selected=选中的选项 label，custom=自定义输入。 */
data class QuestionAnswer(val id: String, val selected: List<String>, val custom: String? = null)
