package com.dsh.pocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight




import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private fun isEmulator(): Boolean {
    val fp = android.os.Build.FINGERPRINT
    val model = android.os.Build.MODEL
    return fp.startsWith("generic") || fp.startsWith("unknown") ||
        model.startsWith("sdk") || model.contains("Emulator") || model.contains("Android SDK built for x86")
}

private fun isPrivateHost(url: String): Boolean {
    val host = try { java.net.URI(url).host ?: return false } catch (e: Exception) { return false }
    return host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("127.") || host == "localhost"
}

private fun emuFallback(url: String): String =
    try { val h = java.net.URI(url).host; if (h == null) url else url.replace(h, "10.0.2.2") } catch (e: Exception) { url }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.ctx = applicationContext
        DshRepository.client.serverUrl = Prefs.url()
        DshRepository.pin = Prefs.pin()
        val phoneOpener = PhoneFileOpener(this)
        val themeMode = androidx.compose.runtime.mutableStateOf(Prefs.theme())
        setContent {
            PocketTheme(themeMode = themeMode.value) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NativeApp(phoneOpener, themeMode.value) { t -> themeMode.value = t; Prefs.saveTheme(t) }
                }
            }
        }
    }
}

@Composable
fun NativeApp(phoneOpener: PhoneFileOpener, themeMode: String = "system", onThemeChange: (String) -> Unit = {}) {
    var screen by remember { mutableStateOf(if (Prefs.hasConfig()) "loading" else "setup") }
    var sessions by remember { mutableStateOf<List<SessionItem>>(emptyList()) }
    var currentSession by remember { mutableStateOf<String?>(null) }
    var filesScope by remember { mutableStateOf<String?>(null) }
    var filesPath by remember { mutableStateOf("") }
    var filesCwd by remember { mutableStateOf("") }
    var previewScope by remember { mutableStateOf<String?>(null) }
    var previewPath by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val baseline by WorkspaceStore.baseline.collectAsState()
    

    LaunchedEffect(Unit) {
        WorkspaceStore.start()
        if (Prefs.hasConfig()) {
            // 已有配置：自动登录，直接进应用
            try {
                DshRepository.login()
                sessions = DshRepository.listSessions()
                val last = Prefs.lastSessionId()
                if (last != null) { currentSession = last; screen = "chat" }
                else screen = "list"
            } catch (_: Exception) { screen = "setup" }
        }
    }

    val doLogin: (String, String) -> Unit = { url, pin ->
        scope.launch {
            loading = true; error = null
            try {
                val eff = if (isEmulator() && isPrivateHost(url) && !url.contains("10.0.2.2")) emuFallback(url) else url
                Prefs.save(eff, pin)
                DshRepository.client.serverUrl = eff
                DshRepository.pin = pin
                try { DshRepository.login() }
                catch (io: java.io.IOException) {
                    val fb = emuFallback(url)
                    if (!isPrivateHost(url) || fb == DshRepository.client.serverUrl) throw io
                    DshRepository.client.serverUrl = fb
                    DshRepository.login()
                    Prefs.save(fb, pin)
                }
                sessions = DshRepository.listSessions()
                val last = Prefs.lastSessionId()
                if (last != null) { currentSession = last; screen = "chat" } else { screen = "list" }
            } catch (e: Exception) { error = e.message ?: "连接失败" }
            finally { loading = false }
        }
    }

    // 公共回调
    val doRefresh: () -> Unit = { scope.launch { loading = true; error = null; try { sessions = DshRepository.listSessions() } catch (e: Exception) { error = e.message } finally { loading = false } } }
    val doSilentRefresh: () -> Unit = { scope.launch { try { sessions = DshRepository.listSessions() } catch (_: Exception) {} } }
    val doOpenFiles: () -> Unit = { val s = sessions.firstOrNull { it.sessionId.isNotEmpty() }; filesScope = s?.sessionId; filesPath = ""; filesCwd = s?.cwd ?: ""; screen = "files" }
    val doArchive: (String) -> Unit = { sid: String -> scope.launch { try { DshRepository.archiveSession(sid); sessions = DshRepository.listSessions() } catch (_: Exception) {} } }
    val doUnarchive: (String) -> Unit = { sid: String -> scope.launch { try { DshRepository.unarchiveSession(sid); sessions = DshRepository.listSessions() } catch (_: Exception) {} } }
    val doRename: (String, String) -> Unit = { sid: String, t: String -> scope.launch { try { DshRepository.rename(sid, t); sessions = DshRepository.listSessions() } catch (_: Exception) {} } }
    val doPin: (String) -> Unit = { sid: String -> scope.launch { try { DshRepository.pinSession(sid); sessions = DshRepository.listSessions() } catch (_: Exception) {} } }
    val doUnpin: (String) -> Unit = { sid: String -> scope.launch { try { DshRepository.unpinSession(sid); sessions = DshRepository.listSessions() } catch (_: Exception) {} } }

    when {
        screen == "setup" -> SetupScreen(loading, error, DshRepository.client.serverUrl, DshRepository.pin, doLogin)
        screen == "loading" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("连接中…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        screen == "list" -> SessionListScreen(
            sessions, baseline, loading, error,
            doRefresh, doSilentRefresh,
            { sid -> Prefs.saveLastSession(sid); currentSession = sid; screen = "chat" },
            doOpenFiles,
            { ws -> scope.launch { try { currentSession = DshRepository.createSession(ws); Prefs.saveLastSession(currentSession); screen = "chat" } catch (e: Exception) { error = e.message } } },
            doArchive, doUnarchive, doRename, doPin, doUnpin,
            themeMode = themeMode, onThemeChange = onThemeChange
        )
        screen == "chat" -> ChatScreen(
            currentSession ?: "", null,
            onBack = { screen = "list"; scope.launch { sessions = DshRepository.listSessions() } },
            onBrowseFiles = { sid, cwd -> filesScope = sid; filesPath = ""; filesCwd = cwd; screen = "files" },
            onOpenSettings = { screen = "settings" },
            phoneOpener = phoneOpener, sessions = sessions,
            onSelectSession = { sid -> Prefs.saveLastSession(sid); currentSession = sid },
            onNewSession = { ws -> scope.launch { try { currentSession = DshRepository.createSession(ws); Prefs.saveLastSession(currentSession); sessions = DshRepository.listSessions() } catch (_: Exception) {} } },
            baseline = baseline, loadingSessions = loading, errorSessions = error,
            onRefreshSessions = doRefresh, onSilentRefreshSessions = doSilentRefresh,
            onArchiveSession = { sid -> scope.launch { loading = true; try { WorkspaceStore.archive(sid); sessions = DshRepository.listSessions() } catch (_: Exception) {} finally { loading = false } } },
            onUnarchiveSession = { sid -> scope.launch { loading = true; try { WorkspaceStore.unarchive(sid); sessions = DshRepository.listSessions() } catch (_: Exception) {} finally { loading = false } } },
            onRenameSession = { sid, t -> scope.launch { loading = true; try { DshRepository.rename(sid, t); sessions = DshRepository.listSessions() } catch (_: Exception) {} finally { loading = false } } },
            onPinSession = { sid -> scope.launch { loading = true; try { WorkspaceStore.pin(sid); sessions = DshRepository.listSessions() } catch (_: Exception) {} finally { loading = false } } },
            onUnpinSession = { sid -> scope.launch { loading = true; try { WorkspaceStore.unpin(sid); sessions = DshRepository.listSessions() } catch (_: Exception) {} finally { loading = false } } },
            onFiles = doOpenFiles,
            themeMode = themeMode, onThemeChange = onThemeChange
        )
        screen == "files" -> FilesScreen(filesScope, filesPath, filesCwd, phoneOpener, onPreview = { sid, p -> previewScope = sid; previewPath = p; screen = "preview" }, onBack = { screen = "list" })
        screen == "preview" -> PreviewScreen(previewScope ?: "", previewPath, onBack = { screen = "files" })
        screen == "settings" -> SettingsScreen(onBack = { screen = "list" }, onLogout = { screen = "login" })
    }
}

@Composable
fun SetupScreen(loading: Boolean, error: String?, initialUrl: String, initialPin: String, onLogin: (String, String) -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    var pin by remember { mutableStateOf(initialPin) }
    val fieldShape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("初次设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text("请输入服务器地址和访问密码", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp)) {
                OutlinedTextField(value = url, onValueChange = { url = it }, placeholder = { Text("http://<电脑IP>:3081") }, label = { Text("服务器地址") }, singleLine = true, shape = fieldShape, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("PIN") }, singleLine = true, shape = fieldShape, modifier = Modifier.fillMaxWidth())
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.fillMaxWidth()) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { onLogin(url.trim(), pin.trim()) }, enabled = !loading && url.trim().isNotEmpty() && pin.trim().isNotEmpty(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("连接", fontSize = 16.sp)
        }
    }
}

@Composable
fun FilesScreen(scopeId: String?, path: String, cwd: String, opener: PhoneFileOpener, onPreview: (String, String) -> Unit, onBack: () -> Unit) {
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var curPath by remember { mutableStateOf(path) }
    BackHandler { onBack() }

    LaunchedEffect(scopeId, curPath) {
        if (scopeId == null) return@LaunchedEffect
        loading = true; error = null
        try { entries = DshRepository.listFiles(scopeId, curPath) }
        catch (e: Exception) { error = e.message }
        finally { loading = false }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.clickable { onBack() }) {
                    Text("←", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(if (curPath.isEmpty()) "工作区" else curPath, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (scopeId == null) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📁", fontSize = 48.sp)
                Spacer(Modifier.height(16.dp))
                Text("请从会话菜单打开文件浏览器", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("点击会话右上角 ≡ 菜单 → 文件浏览器", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onBack) { Text("返回会话列表") }
            }
        } else if (loading) { Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
        else if (error != null) { Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        else {
            if (entries.isEmpty()) { Text("（空）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            else LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
                items(entries) { e ->
                    val isDir = e.type == "directory"
                    val icon = if (isDir) "\uD83D\uDCC1" else "\uD83D\uDCC4"
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                            .clickable {
                                if (isDir) {
                                    curPath = if (curPath.isEmpty()) e.name else curPath + "/" + e.name
                                } else {
                                    val full = if (curPath.isEmpty()) e.name else curPath + "/" + e.name
                                    if (scopeId != null && isTextFile(e.name)) {
                                        onPreview(scopeId, full)
                                    } else {
                                        opener.openOnPhone(absPath(cwd, full), e.name)
                                    }
                                }
                            }
                    , verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (isDir) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(icon, fontSize = 16.sp)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(e.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (isDir) Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
