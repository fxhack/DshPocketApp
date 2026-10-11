package com.dsh.pocket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionReducer 单测。
 * 重点覆盖：seq 幂等（文本不能翻倍）、多 step 拼接、工具配对、turn 排序、projections 兜底不覆盖。
 */
class SessionReducerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun ev(jsonStr: String): RawEvent {
        val o = json.parseToJsonElement(jsonStr).jsonObject
        return parseEventObject(o)!!
    }

    private fun assistant(turn: Int, step: Int, seq: Long, reasoning: String? = null, text: String? = null): RawEvent {
        val parts = mutableListOf<String>()
        reasoning?.let { parts.add("""{"type":"reasoning","text":${escape(it)}}""") }
        text?.let { parts.add("""{"type":"text","text":${escape(it)}}""") }
        return ev(
            """{"type":"assistant/message","seq":$seq,"data":{"turn":$turn,"step":$step,
               "message":{"role":"assistant","content":[${parts.joinToString(",")}]}}}""".trimIndent()
        )
    }

    private fun escape(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun 多step正文拼接() {
        val buf = SessionBuffer()
        SessionReducer.applyEvent(buf, assistant(48, 1, 100, text = "第一段"))
        SessionReducer.applyEvent(buf, assistant(48, 2, 101, text = "第二段"))
        SessionReducer.applyEvent(buf, assistant(48, 3, 102, text = "第三段"))
        val t = buf.snapshot().first()
        assertEquals(48, t.turn)
        assertEquals(3, t.steps.size)
        assertEquals("第一段\n\n第二段\n\n第三段", t.responseText)
    }

    @Test
    fun 同一事件重复应用不翻倍() {
        val buf = SessionBuffer()
        val e = assistant(48, 1, 100, reasoning = "想一想", text = "正文")
        SessionReducer.applyEvent(buf, e)
        SessionReducer.applyEvent(buf, e)
        SessionReducer.applyEvent(buf, e)
        val t = buf.snapshot().first()
        assertEquals("正文", t.responseText)
        assertEquals("想一想", t.reasoningText)
    }

    @Test
    fun 快照与live事件重叠不翻倍() {
        val buf = SessionBuffer()
        val records = listOf(
            ev("""{"type":"turn/start","seq":10,"data":{"turn":48}}"""),
            assistant(48, 1, 100, text = "正文")
        )
        SessionReducer.applySnapshot(
            buf,
            SnapshotFrame(cursor = 100, hasMore = false, records = records, title = null)
        )
        // 重连后服务端重发同一批
        SessionReducer.applySnapshot(
            buf,
            SnapshotFrame(cursor = 100, hasMore = false, records = records, title = null)
        )
        assertEquals("正文", buf.snapshot().first().responseText)
    }

    @Test
    fun 工具调用与结果按callId配对() {
        val buf = SessionBuffer()
        SessionReducer.applyEvent(buf, ev("""{"type":"turn/start","seq":10,"data":{"turn":48}}"""))
        SessionReducer.applyEvent(
            buf,
            ev(
                """{"type":"assistant/message","seq":11,"data":{"turn":48,"step":1,"message":{"content":
                   [{"type":"tool-call","id":"call_abc|fc_call_abc","name":"run_code","arguments":"{\"code\":\"x\"}"}]}}}"""
            )
        )
        SessionReducer.applyEvent(
            buf,
            ev(
                """{"type":"tool/call","seq":12,"data":{"turn":48,"step":1,"callId":"call_abc|fc_call_abc",
                   "name":"run_code","arguments":"{\"code\":\"x\"}"}}"""
            )
        )
        SessionReducer.applyEvent(
            buf,
            ev(
                """{"type":"tool/result","seq":13,"data":{"turn":48,"step":1,"message":{"role":"tool",
                   "toolCallId":"call_abc|fc_call_abc","content":[{"type":"text","text":"执行完成"}]}}}"""
            )
        )
        val tools = buf.snapshot().first().allTools
        assertEquals(1, tools.size)
        assertEquals("run_code", tools[0].name)
        assertEquals("""{"code":"x"}""", tools[0].argsText)
        assertEquals("执行完成", tools[0].resultText)
    }

    @Test
    fun 工具结果不与参数互相覆盖() {
        val buf = SessionBuffer()
        SessionReducer.applyEvent(buf, ev("""{"type":"turn/start","seq":1,"data":{"turn":7}}"""))
        SessionReducer.applyEvent(
            buf,
            ev(
                """{"type":"tool/call","seq":2,"data":{"turn":7,"step":1,"callId":"id1","name":"read","arguments":"a"}}"""
            )
        )
        SessionReducer.applyEvent(
            buf,
            ev(
                """{"type":"tool/result","seq":3,"data":{"turn":7,"step":1,"message":{"toolCallId":"id1",
                   "content":[{"type":"text","text":"r1"}]}}}"""
            )
        )
        // 重复的 tool/call 事件不能把已拿到的 result 清掉
        SessionReducer.applyEvent(
            buf,
            ev(
                """{"type":"tool/call","seq":4,"data":{"turn":7,"step":1,"callId":"id1","name":"read","arguments":"a"}}"""
            )
        )
        val tool = buf.snapshot().first().allTools[0]
        assertEquals("r1", tool.resultText)
        assertEquals("a", tool.argsText)
    }

    @Test
    fun 用户消息通过turnstart绑定() {
        val buf = SessionBuffer()
        SessionReducer.applyEvent(
            buf,
            ev(
                """{"type":"user/message","seq":1,"data":{"role":"user","source":{"kind":"user"},
                   "content":[{"type":"text","text":"帮我编译"}]}}"""
            )
        )
        SessionReducer.applyEvent(buf, ev("""{"type":"turn/start","seq":2,"data":{"turn":5}}"""))
        SessionReducer.applyEvent(buf, assistant(5, 1, 3, text = "好的"))
        val t = buf.snapshot().first()
        assertEquals("帮我编译", t.prompt)
        assertEquals("user", t.promptKind)
        assertEquals("好的", t.responseText)
    }

    @Test
    fun 目标驱动消息标记为goal() {
        val buf = SessionBuffer()
        SessionReducer.applyEvent(
            buf,
            ev(
                """{"type":"user/message","seq":1,"data":{"role":"user","source":{"kind":"goal","round":3},
                   "content":[{"type":"text","text":"继续推进目标"}]}}"""
            )
        )
        SessionReducer.applyEvent(buf, ev("""{"type":"turn/start","seq":2,"data":{"turn":9}}"""))
        assertEquals("goal", buf.snapshot().first().promptKind)
    }

    @Test
    fun projections兜底不覆盖已有推理与正文() {
        val buf = SessionBuffer()
        SessionReducer.applyEvent(buf, ev("""{"type":"turn/start","seq":1,"data":{"turn":3}}"""))
        SessionReducer.applyEvent(buf, assistant(3, 1, 2, reasoning = "真实推理", text = "真实正文"))
        SessionReducer.applyProjections(
            buf,
            listOf(TurnOutline(turn = 3, seq = 2, prompt = "问题", response = "投影正文"))
        )
        val t = buf.snapshot().first()
        assertEquals("真实正文", t.responseText)
        assertEquals("真实推理", t.reasoningText)
        assertEquals("问题", t.prompt)
    }

    @Test
    fun projections文本被事件文本整体替换() {
        val buf = SessionBuffer()
        SessionReducer.applyProjections(
            buf,
            listOf(TurnOutline(turn = 1, seq = 5, prompt = "问题", response = "投影正文"))
        )
        assertEquals("投影正文", buf.snapshot().first().responseText)
        SessionReducer.applyEvent(buf, assistant(1, 1, 5, text = "权威正文"))
        assertEquals("权威正文", buf.snapshot().first().responseText)
    }

    @Test
    fun 轮次按turn号升序() {
        val buf = SessionBuffer()
        SessionReducer.applyEvent(buf, assistant(9, 1, 3, text = "c"))
        SessionReducer.applyEvent(buf, assistant(2, 1, 1, text = "a"))
        SessionReducer.applyEvent(buf, assistant(5, 1, 2, text = "b"))
        assertEquals(listOf(2, 5, 9), buf.snapshot().map { it.turn })
        assertEquals("a", buf.snapshot()[0].responseText)
    }

    @Test
    fun 占位轮排在最后且可归位() {
        val buf = SessionBuffer()
        SessionReducer.applyEvent(buf, assistant(1, 1, 1, text = "历史"))
        val ph = buf.newPlaceholderTurn()
        buf.turn(ph).prompt = "刚发送"
        assertEquals(listOf(1, ph), buf.snapshot().map { it.turn })
        SessionReducer.applyEvent(buf, ev("""{"type":"turn/start","seq":50,"data":{"turn":2}}"""))
        buf.pushPendingPrompt("刚发送", "user")
        buf.bindPendingPrompt(2)
        buf.renumberPlaceholder(ph, 2)
        assertEquals(listOf(1, 2), buf.snapshot().map { it.turn })
        assertEquals("刚发送", buf.snapshot()[1].prompt)
    }

    @Test
    fun 空事件与未知类型不抛异常() {
        val buf = SessionBuffer()
        SessionReducer.applyEvent(buf, ev("""{"type":"weird/new","seq":1,"data":{}}"""))
        SessionReducer.applyEvent(buf, ev("""{"type":"assistant/message","seq":2,"data":{}}"""))
        assertTrue(buf.snapshot().isEmpty())
    }
}
