package com.dsh.pocket

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 完整设置页面（对标 ui-settings*）：账号 / 模型 / 插件 / 日志 / Shell / 子代理 / Web 搜索 / 语音 / 命令。
 * 所有设置持久化到本地 Prefs，部分（主题）即时生效。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    var theme by remember { mutableStateOf(Prefs.theme()) }
    var webSearch by remember { mutableStateOf(Prefs.webSearch()) }
    var logLevel by remember { mutableStateOf(Prefs.logLevel()) }
    var shell by remember { mutableStateOf(Prefs.shell()) }
    var voice by remember { mutableStateOf(Prefs.voiceInput()) }
    var autoApprove by remember { mutableStateOf(Prefs.autoApprove()) }
    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.clickable { onBack() }) {
                    Text("←", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section("账号") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("服务器", fontSize = 13.sp, modifier = Modifier.width(100.dp))
                    Text(Prefs.url(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("PIN", fontSize = 13.sp, modifier = Modifier.width(100.dp))
                    Text("•".repeat(Prefs.pin().length.coerceAtLeast(4)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text("切换账号") }
            }

            Section("主题") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (v, label) ->
                        TextButton(onClick = { theme = v; Prefs.saveTheme(v) }) {
                            Text(if (theme == v) "✓ " else "" + label, fontSize = 13.sp,
                                color = if (theme == v) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            }

            Section("模型") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("默认模型", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("glm-5.2", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("在会话菜单中切换当前会话模型。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("Web 搜索") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("启用 Web 搜索", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = webSearch, onCheckedChange = { webSearch = it; Prefs.saveWebSearch(it) })
                }
            }

            Section("日志") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("日志级别", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(logLevel, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("debug", "info", "warn", "error").forEach { lvl ->
                        TextButton(onClick = { logLevel = lvl; Prefs.saveLogLevel(lvl) }) {
                            Text(lvl, fontSize = 12.sp,
                                color = if (logLevel == lvl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            }

            Section("Shell") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("默认 Shell", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(shell, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("auto", "pwsh", "bash", "cmd").forEach { s ->
                        TextButton(onClick = { shell = s; Prefs.saveShell(s) }) {
                            Text(s, fontSize = 12.sp,
                                color = if (shell == s) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            }

            Section("子代理") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("自动批准子代理委派", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = autoApprove, onCheckedChange = { autoApprove = it; Prefs.saveAutoApprove(it) })
                }
            }

            Section("语音输入") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("启用语音输入", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = voice, onCheckedChange = { voice = it; Prefs.saveVoiceInput(it) })
                }
            }

            Section("关于") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("版本", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("DshPocket v2.37", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("原生 Compose 实现，无 WebView。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(4.dp).clip(androidx.compose.foundation.shape.CircleShape)) { Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize()) {} }
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
