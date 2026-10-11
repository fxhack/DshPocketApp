package com.dsh.pocket

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 可预览的文本类文件扩展名。 */
private val TEXT_EXTS = setOf(
    "md","markdown","txt","kt","kts","java","py","pyw","js","jsx","ts","tsx","mjs","cjs",
    "json","jsonc","yaml","yml","toml","xml","html","htm","css","scss","less","gradle","properties",
    "cfg","ini","log","csv","tsv","sh","bash","zsh","bat","cmd","ps1","psm1","go","rs","rb","php",
    "c","cpp","cc","cxx","h","hpp","cs","swift","scala","lua","pl","sql","graphql","proto","env","gitignore"
)

fun isTextFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in TEXT_EXTS

/**
 * 文档预览（对标 ui-sidebar-documentpreview）：MD 用 MarkdownText 渲染，其它文本用等宽字体显示。
 */
@Composable
fun PreviewScreen(scopeId: String, path: String, onBack: () -> Unit) {
    var content by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var binary by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }

    LaunchedEffect(scopeId, path) {
        loading = true; error = null
        try { content = DshRepository.readFile(scopeId, path) }
        catch (e: Exception) { error = e.message }
        finally { loading = false }
    }

    val name = path.substringAfterLast('/')
    val isMd = name.endsWith(".md", true) || name.endsWith(".markdown", true)
    val ext = name.substringAfterLast('.', "")

    Column(modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Text(ext.uppercase(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("无法预览此文件", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                Text(error ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            isMd -> MarkdownText(content, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp))
            else -> Text(content, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp))
        }
    }
}
