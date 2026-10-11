package com.dsh.pocket

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BT: Char = 0x60.toChar()
private val FENCE: String = BT.toString() + BT.toString() + BT.toString()
private val INLINE = Regex("""\*\*(.+?)\*\*|""" + BT + """(.+?)""" + BT + """|\[([^\]]+)\]\(([^)]+)\)""")
private val TABLE_ROW: Regex = Regex("""^\s*\|(.*)\|\s*$""")
private const val MAX_PARA_LINES = 40

private sealed class MdBlock {
    class Code(val lines: List<String>, val lang: String) : MdBlock()
    class Header(val level: Int, val text: String) : MdBlock()
    class Item(val text: String, val ordered: Boolean, val index: Int) : MdBlock()
    class Para(val text: String) : MdBlock()
    class Quote(val text: String) : MdBlock()
    class Table(val rows: List<List<String>>, val hasHeader: Boolean) : MdBlock()
    object Divider : MdBlock()
}

private fun parseMd(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    var para: MutableList<String>? = null
    var quote: MutableList<String>? = null
    var table: MutableList<List<String>>? = null
    var olIndex = 0

    fun flushPara() {
        para?.let { if (it.isNotEmpty()) out.add(MdBlock.Para(it.joinToString("\n"))) }
        para = null
    }
    fun flushQuote() {
        quote?.let { if (it.isNotEmpty()) out.add(MdBlock.Quote(it.joinToString("\n"))) }
        quote = null
    }
    fun flushTable() {
        val t = table ?: return
        table = null
        if (t.isEmpty()) return
        val hasHeader = t.size >= 2 && t[1].all { it.trim().matches(Regex(":?-{2,}:?")) }
        val rows = if (hasHeader) listOf(t[0]) + t.drop(2) else t
        if (rows.isNotEmpty()) out.add(MdBlock.Table(rows, hasHeader))
    }
    fun flushAll() { flushPara(); flushQuote(); flushTable() }

    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith(FENCE)) {
            flushAll()
            val lang = line.trimStart().removePrefix(FENCE).trim()
            val code = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(FENCE)) { code.add(lines[i]); i++ }
            if (i < lines.size) i++
            out.add(MdBlock.Code(code, lang))
            continue
        }
        val headerMatch = Regex("""^(#{1,6})\s+(.*)$""").find(line)
        if (headerMatch != null) {
            flushAll()
            out.add(MdBlock.Header(headerMatch.groupValues[1].length, headerMatch.groupValues[2]))
            i++; continue
        }
        if (TABLE_ROW.matches(line)) {
            flushPara(); flushQuote()
            val cells = line.trim().trimStart('|').trimEnd('|').split('|').map { it.trim() }
            if (table == null) table = mutableListOf()
            table!!.add(cells)
            i++; continue
        } else if (table != null) { flushTable() }
        if (line.startsWith(">")) {
            flushPara(); flushTable()
            if (quote == null) quote = mutableListOf()
            quote!!.add(line.removePrefix(">").trimStart())
            i++; continue
        } else if (quote != null) { flushQuote() }
        val ulMatch = Regex("""^\s*[-*+]\s+(.*)$""").find(line)
        if (ulMatch != null) {
            flushPara(); flushTable(); flushQuote()
            out.add(MdBlock.Item(ulMatch.groupValues[1], false, 0))
            i++; continue
        }
        val olMatch = Regex("""^\s*(\d+)\.[\s]+(.*)$""").find(line)
        if (olMatch != null) {
            flushPara(); flushTable(); flushQuote()
            olIndex++
            out.add(MdBlock.Item(olMatch.groupValues[2], true, olIndex))
            i++; continue
        }
        if (line.trim().matches(Regex("^[-*_]{3,}$"))) {
            flushAll()
            out.add(MdBlock.Divider)
            i++; continue
        }
        if (line.trim().isEmpty()) { flushAll(); i++; continue }
        if (para == null) para = mutableListOf()
        para!!.add(line)
        i++
    }
    flushAll()
    return out
}

private fun buildInline(text: String): AnnotatedString = buildAnnotatedString {
    val parts = INLINE.split(text)
    var j = 0
    while (j < parts.size) {
        when {
            parts[j].startsWith("**") -> {
                append(parts[j].trim('*'))
                if (j + 1 < parts.size) { append(parts[j+1]) }
                j += 2
            }
            parts[j].startsWith(BT) -> { append(parts[j].trim(BT)); j++ }
            else -> { append(parts[j]); j++ }
        }
    }
}

private val RUNNABLE_LANGS = setOf("bash","sh","shell","zsh","powershell","pwsh","cmd","python","py","js","javascript","ts","typescript","kotlin","kt","java","go","rust","rs","c","cpp","ruby","rb")
private fun isRunnable(lang: String): Boolean = lang.lowercase() in RUNNABLE_LANGS

/** 语法高亮正则（语言无关的通用 token）。 */
private val HL_CMT = Regex("""//.*|#[^!].*|/\*.*?\*/""")
private val HL_STR = Regex("""(?:"[^"\n]*"|'[^'\n]*')""")
private val HL_NUM = Regex("""\b\d+(?:\.\d+)?\b""")
private val HL_KEY = Regex("""\b(?:var|val|fun|import|package|class|object|interface|enum|data|when|if|else|for|while|do|return|true|false|null|def|print|function|const|let|public|private|protected|internal|open|override|sealed|suspend|companion|try|catch|finally|throw|in|is|as|by|get|set|typealias|this|super|static|void)\b""")

/** 语法高亮：按注释/字符串/数字/关键字上色（VSCode Dark+/Light+ 配色）。 */
private fun highlightCode(code: String, isLight: Boolean): AnnotatedString {
    val p = if (isLight)
        arrayOf(Color(0xFF008000), Color(0xFFA31515), Color(0xFF098658), Color(0xFF0000FF))
    else
        arrayOf(Color(0xFF6A9955), Color(0xFFCE9178), Color(0xFFB5CEA8), Color(0xFFC678DD))
    val candidates = arrayOf(HL_CMT to p[0], HL_STR to p[1], HL_NUM to p[2], HL_KEY to p[3])
    return buildAnnotatedString {
        var pos = 0
        while (pos < code.length) {
            val rest = code.substring(pos)
            var bestStart = -1; var bestLen = 0; var bestColor: Color? = null; var bestValue: String? = null
            for ((re, col) in candidates) {
                val m = re.find(rest) ?: continue
                if (bestStart < 0 || m.range.first < bestStart) {
                    bestStart = m.range.first; bestLen = m.value.length; bestColor = col; bestValue = m.value
                }
            }
            if (bestValue == null) { append(rest); break }
            if (bestStart > 0) append(rest.substring(0, bestStart))
            withStyle(SpanStyle(color = bestColor!!)) { append(bestValue) }
            pos += bestStart + bestLen
        }
    }
}

@Composable
private fun CodeBlock(lines: List<String>, lang: String, baseSize: TextUnit, onRunCode: (String, String) -> Unit) {
    val text = lines.joinToString("\n")
    val cs = MaterialTheme.colorScheme
    val isLight = (0.299f * cs.surfaceVariant.red + 0.587f * cs.surfaceVariant.green + 0.114f * cs.surfaceVariant.blue) > 0.5f
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    Surface(color = LocalExtendedColors.current.codeBackground, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (lang.isNotBlank()) Text(lang, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                else Box(Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text(if (copied) "已复制" else "复制", fontSize = 11.sp)
                }
                if (isRunnable(lang)) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { onRunCode(text, lang) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Text("运行", fontSize = 11.sp)
                    }
                }
            }
            Text(text = highlightCode(text, isLight), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun HeaderBlock(level: Int, text: String, baseSize: TextUnit) {
    val size = when (level) { 1 -> 20f; 2 -> 18f; 3 -> 16f; else -> 14f }
    Text(text, fontSize = size.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun ItemBlock(text: String, ordered: Boolean, index: Int, baseSize: TextUnit) {
    val prefix = if (ordered) index.toString() + ". " else "• "
    Row(Modifier.padding(start = 12.dp)) {
        Text(prefix, fontSize = baseSize, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(2.dp))
        Box(Modifier.weight(1f)) { Text(buildInline(text), fontSize = baseSize, color = MaterialTheme.colorScheme.onBackground) }
    }
}

@Composable
private fun ParaBlock(text: String, baseSize: TextUnit) {
    val paras = if (text.lines().size > MAX_PARA_LINES) text.split("\n") else listOf(text)
    paras.forEach { p -> Text(buildInline(p), fontSize = baseSize, color = MaterialTheme.colorScheme.onBackground) }
}

@Composable
private fun QuoteBlock(text: String, baseSize: TextUnit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 8.dp, top = 3.dp, bottom = 3.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(buildInline(text), fontSize = baseSize, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>, hasHeader: Boolean, baseSize: TextUnit) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
        rows.forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    Text(text = buildInline(cell), fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.widthIn(min = 40.dp).padding(horizontal = 6.dp, vertical = 2.dp), fontWeight = if (hasHeader && ri == 0) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 14.sp, onRunCode: (String, String) -> Unit = { _, _ -> }) {
    val blocks = remember(text) { parseMd(text) }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.lines, block.lang, fontSize, onRunCode)
                is MdBlock.Header -> HeaderBlock(block.level, block.text, fontSize)
                is MdBlock.Item -> ItemBlock(block.text, block.ordered, block.index, fontSize)
                is MdBlock.Para -> ParaBlock(block.text, fontSize)
                is MdBlock.Quote -> QuoteBlock(block.text, fontSize)
                is MdBlock.Table -> TableBlock(block.rows, block.hasHeader, fontSize)
                MdBlock.Divider -> { Spacer(Modifier.height(2.dp)); HorizontalDivider(); Spacer(Modifier.height(2.dp)) }
            }
        }
    }
}
