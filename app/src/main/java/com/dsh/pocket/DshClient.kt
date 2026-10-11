package com.dsh.pocket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * DSH 原生客户端通信层（无 WebView）。
 *
 * 三件事：
 * 1) 登录：POST /pocket-login(token) + GET / → 拿到 dsh_pocket_token + dsh-auth 双 cookie。
 * 2) HTTP RPC（unary）：POST /api/<endpoint> + client-request 信封 → server-response.value。
 * 3) WebSocket 事件流：ws://host/api/remote.mux 开 $events 流，下行 agent 事件。
 *
 * 协议细节见 docs/PROTOCOL.md。
 */
class DshClient {

    var serverUrl: String = Config.SERVER_URL
    val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

    private val cookieJar = DshCookieJar()

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)   // 登录握手显式处理 302，避免自动跟随丢 cookie / 误判 503
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** 所有 cookie 拼成 "k=v; k2=v2"，供文件下载（/api/file）等复用。 */
    fun cookieHeader(): String = cookieJar.cookieHeaderFor(serverUrl)

    /** 是否已登录（持有双 cookie）。 */
    fun isLoggedIn(): Boolean =
        cookieJar.hasCookie("dsh_pocket_token") && cookieJar.hasCookiePrefix("dsh-auth-")

    /**
     * 登录握手（显式，不依赖 OkHttp 自动跟随重定向）：
     * 1) POST /pocket-login → 302 + Set-Cookie dsh_pocket_token
     * 2) GET /?dsh-pocket-auth=1（手动带 dsh_pocket_token）→ 200 + Set-Cookie dsh-auth
     * 两条 cookie 存入 cookieJar，供后续 api 端点与 WS 手动带 Cookie 头复用。
     */
    suspend fun login(pin: String): Boolean = withContext(Dispatchers.IO) {
        val form = ("token=" + java.net.URLEncoder.encode(pin, "UTF-8"))
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        // 1) 取 dsh_pocket_token（不跟随 302）
        val pocketToken = httpClient.newCall(
            Request.Builder().url(serverUrl + "/pocket-login").post(form).build()
        ).execute().use { r1 ->
            if (r1.code !in 200..399) throw DshException("pocket 网关不可达 (HTTP " + r1.code + ")")
            r1.headers("Set-Cookie").firstNotNullOfOrNull { c ->
                Regex("dsh_pocket_token=([^;]+)").find(c)?.groupValues?.get(1)
            } ?: throw DshException("登录失败：未收到 dsh_pocket_token（密码可能错误）")
        }
        cookieJar.saveRaw("dsh_pocket_token", pocketToken)
        // 2) GET /?dsh-pocket-auth=1 手动带 dsh_pocket_token → 取 dsh-auth
        httpClient.newCall(
            Request.Builder().url(serverUrl + "/?dsh-pocket-auth=1").get()
                .header("Cookie", "dsh_pocket_token=" + pocketToken).build()
        ).execute().use { r2 ->
            if (r2.code == 503) throw DshException("DSH 桌面未启动或离线 (503)，请确认桌面在线后重试")
            if (r2.code != 200) throw DshException("登录失败 (HTTP " + r2.code + ")")
            r2.headers("Set-Cookie").firstNotNullOfOrNull { c ->
                Regex("(dsh-auth-[^=]+)=([^;]+)").find(c)?.let { it.groupValues[1] to it.groupValues[2] }
            }?.let { (n, v) -> cookieJar.saveRaw(n, v) }
        }
        if (!isLoggedIn()) throw DshException("登录未完成：未获得 dsh-auth（桌面可能离线）")
        true
    }

    /**
     * HTTP RPC（unary）。
     * @param endpoint 形如 "session/list"、"session/prompt"
     * @param args 该端点参数对象，键为 wire 名（request 参数的 wire 是 "_request"）
     * @return server-response.result.value（成功值）
     */
    suspend fun rpcCall(endpoint: String, args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val rpcId = UUID.randomUUID().toString()
        val envelope = buildJsonObject {
            put("type", "client-request")
            put("rpcId", rpcId)
            put("method", endpoint)
            put("payload", buildJsonObject { put("args", args) })
        }
        val req = Request.Builder()
            .url(serverUrl + "/api/" + endpoint)
            .header("Cookie", cookieHeader())
            .post(envelope.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw DshException("未登录或会话过期，请返回重试登录")
            if (resp.code == 503) throw DshException("DSH 桌面服务不可用 (503)")
            if (!resp.isSuccessful) throw DshException("请求失败 (HTTP " + resp.code + ")")
            val bodyStr = resp.body?.string() ?: throw DshException("empty body")
            val full = json.parseToJsonElement(bodyStr).jsonObject
            val backId = full["rpcId"]?.jsonPrimitive?.content
            if (backId != rpcId) throw DshException("rpcId mismatch: sent " + rpcId + ", got " + backId)
            val result = full["result"]?.jsonObject ?: throw DshException("no result")
            val ok = result["ok"]?.jsonPrimitive?.content
            if (ok != "true") {
                val err = result["error"]?.jsonObject
                throw DshException(err?.get("code")?.jsonPrimitive?.content + ": " +
                        (err?.get("message")?.jsonPrimitive?.content ?: "rpc failed"))
            }
            result["value"] ?: JsonNull
        }
    }

    /**
     * 原始字节上传：POST /api/session/uploadFileBinary?sessionId=&name=
     * Content-Type: application/octet-stream，body 为原始字节，复用 cookie 鉴权。
     * 返回 {ok, value:{receiptId, file:{attachmentId,name,bytes}}}（裸 HTTP 响应，非 RPC 信封）。
     */
    suspend fun uploadFileBinary(sessionId: String, name: String, bytes: ByteArray): JsonObject =
        withContext(Dispatchers.IO) {
            val url = serverUrl + "/api/session/uploadFileBinary?sessionId=" +
                java.net.URLEncoder.encode(sessionId, "UTF-8") +
                "&name=" + java.net.URLEncoder.encode(name, "UTF-8")
            val req = Request.Builder()
                .url(url)
                .header("Cookie", cookieHeader())
                .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) throw DshException("未登录或会话过期")
                if (!resp.isSuccessful) throw DshException("上传失败 (HTTP " + resp.code + ")")
                val bodyStr = resp.body?.string() ?: throw DshException("empty upload body")
                json.parseToJsonElement(bodyStr).jsonObject
            }
        }

    /**
     * 开 $events 事件流。返回下行 item.value 的 Channel；关闭 Channel 即取消流与 WS。
     * 首个 value 形如 {"type":"ready","clientId":"...","host":{...}}。
     */
    suspend fun openEventsStream(): ReceiveChannel<JsonElement> = withContext(Dispatchers.IO) {
        val ch = Channel<JsonElement>(Channel.UNLIMITED)
        val streamId = UUID.randomUUID().toString()
        val wsUrl = serverUrl.replaceFirst("^http".toRegex(), "ws") + "/api/remote.mux"
        val req = Request.Builder().url(wsUrl).header("Cookie", cookieHeader()).build()
        val ws = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val open = buildJsonObject {
                    put("type", "open")
                    put("streamId", streamId)
                    put("endpoint", '$' + "events")
                    put("payload", buildJsonObject { put("args", buildJsonObject {}) })
                }
                webSocket.send(open.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val f = json.parseToJsonElement(text).jsonObject
                    if (f["streamId"]?.jsonPrimitive?.content != streamId) return
                    when (f["type"]?.jsonPrimitive?.content) {
                        "item" -> { f["value"]?.let { ch.trySend(it) } }
                        "end" -> ch.close()
                        "error" -> {
                            val msg = f["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                                ?: "stream error"
                            ch.close(DshException(msg))   // 让上层区分鉴权失败与断网
                        }
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ch.close(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ch.close() }
        })
        ch.invokeOnClose { ws.cancel() }
        ch
    }

    /**
     * 开通用 WS 流（mode:'stream' 端点，如 session/follow / workspace/follow）。
     * @param endpoint 形如 "session/follow"
     * @param args 该端点 args（session/follow = {request:{address:{kind:"session",sessionId}}}）
     * @return 下行 item.value 的 Channel（snapshot / event 帧）；关闭 Channel 即取消流与 WS。
     */
    suspend fun openStream(endpoint: String, args: JsonObject): ReceiveChannel<JsonElement> = withContext(Dispatchers.IO) {
        val ch = Channel<JsonElement>(Channel.UNLIMITED)
        val streamId = UUID.randomUUID().toString()
        val wsUrl = serverUrl.replaceFirst("^http".toRegex(), "ws") + "/api/remote.mux"
        val req = Request.Builder().url(wsUrl).header("Cookie", cookieHeader()).build()
        val ws = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val open = buildJsonObject {
                    put("type", "open")
                    put("streamId", streamId)
                    put("endpoint", endpoint)
                    put("payload", buildJsonObject { put("args", args) })
                }
                webSocket.send(open.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val f = json.parseToJsonElement(text).jsonObject
                    if (f["streamId"]?.jsonPrimitive?.content != streamId) return
                    when (f["type"]?.jsonPrimitive?.content) {
                        "item" -> { f["value"]?.let { ch.trySend(it) } }
                        "end", "error" -> ch.close()
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ch.close(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ch.close() }
        })
        ch.invokeOnClose { ws.cancel() }
        ch
    }
}

class DshException(message: String) : Exception(message)

/**
 * 内存 cookie 存储：纯 name→value。loadForRequest 返回空（不自动注入 Cookie 头），
 * 所有请求由 DshClient 手动带 Cookie 头（cookieHeader），绕开 OkHttp matches()/IP host 的坑。
 */
class DshCookieJar : CookieJar {
    private val store = linkedMapOf<String, String>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { c -> store[c.name] = c.value }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()

    @Synchronized
    fun cookieHeaderFor(url: String): String =
        store.entries.joinToString("; ") { (n, v) -> n + "=" + v }

    @Synchronized
    fun hasCookie(name: String): Boolean = store.containsKey(name)

    @Synchronized
    fun hasCookiePrefix(prefix: String): Boolean = store.keys.any { it.startsWith(prefix) }

    @Synchronized
    fun saveRaw(name: String, value: String) { store[name] = value }
}
