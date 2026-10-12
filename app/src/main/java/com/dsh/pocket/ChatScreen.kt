package com.dsh.pocket

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.material3.Surface

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 会话详情页（对标浏览器）：顶栏显示会话标题、进入即停在最新消息�? * 完整显示正文、可折叠展示思考过程与工具调用�? */
/** 命令面板（对标 ui-commands）：输入 / 触发命令建议。 */
private val CHAT_COMMANDS = listOf(
    "/clear" to "清空会话",
    "/model" to "切换模型",
    "/settings" to "打开设置",
    "/goal" to "目标状态",
    "/help" to "帮助"
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    initialTitle: String?,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    onBrowseFiles: (String, String) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit = {},
    phoneOpener: PhoneFileOpener? = null,
    sessions: List<SessionItem> = emptyList(),
    onSelectSession: (String) -> Unit = {},
    onNewSession: (String?) -> Unit = { _ -> },
    baseline: WorkspaceBaseline? = null,
    loadingSessions: Boolean = false,
    errorSessions: String? = null,
    onRefreshSessions: () -> Unit = {},
    onSilentRefreshSessions: () -> Unit = {},
    onArchiveSession: (String) -> Unit = {},
    onUnarchiveSession: (String) -> Unit = {},
    onRenameSession: (String, String) -> Unit = { _, _ -> },
    onPinSession: (String) -> Unit = {},
    onUnpinSession: (String) -> Unit = {},
    onFiles: () -> Unit = {},
    themeMode: String = "system",
    onThemeChange: (String) -> Unit = {},
    showDrawer: Boolean = true,
    useSafePadding: Boolean = true
) {
    val vm: ChatViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { onBack() }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val forkResult by vm.forkResult.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var didInitialScroll by remember(sessionId) { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var fileRefOpen by remember { mutableStateOf(false) }
    var fileRefs by remember { mutableStateOf<List<FileRef>>(emptyList()) }
    var fileRefLoading by remember { mutableStateOf(false) }
    var atOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var atSessions by remember { mutableStateOf<List<SessionItem>>(emptyList()) }
    var atFiles by remember { mutableStateOf<List<FileRef>>(emptyList()) }
    var atLoading by remember { mutableStateOf(false) }
    var atMentions by remember { mutableStateOf<List<AtMention>>(emptyList()) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerWidth = (LocalConfiguration.current.screenWidthDp * 0.5f).dp
    var drawerQuery by remember { mutableStateOf("") }
    var drawerExpandedGroups by remember { mutableStateOf<Map<String, Boolean>>(mapOf("__ungrouped__" to true)) }
    var drawerRenameTarget by remember { mutableStateOf<SessionItem?>(null) }
    var drawerMenuSession by remember { mutableStateOf<SessionItem?>(null) }
    var drawerMenuAnchor by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(8000); onSilentRefreshSessions() } }
    LaunchedEffect(baseline) { baseline?.items?.forEach { w -> if (w.workspaceId !in drawerExpandedGroups) drawerExpandedGroups = drawerExpandedGroups + (w.workspaceId to true) } }
    val drawerArchivedIds = baseline?.archivedSessionIds?.toSet() ?: emptySet()
    val drawerPinnedIds = baseline?.pinnedSessionIds?.toSet() ?: emptySet()
    val drawerFiltered = remember(sessions, drawerQuery, drawerArchivedIds) {
        val q = drawerQuery.trim().lowercase()
        sessions.filter { s ->
            val isArchived = drawerArchivedIds.contains(s.sessionId)
            if (isArchived) false
            else if (q.isEmpty()) true
            else s.projections.values.title.orEmpty().lowercase().contains(q)
        }.sortedByDescending { drawerPinnedIds.contains(it.sessionId) }
    }
    val drawerGroups = remember(drawerFiltered, drawerArchivedIds, drawerPinnedIds) { WorkspaceStore.groups(drawerFiltered, drawerArchivedIds, drawerPinnedIds) }
    val drawerTimeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var listening by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var jobsOpen by remember { mutableStateOf(false) }
    var scheduleOpen by remember { mutableStateOf(false) }
    var teamsOpen by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val catalog by vm.modelCatalog.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val cr = context.contentResolver
                val mime = cr.getType(uri) ?: "application/octet-stream"
                val name = cr.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                } ?: uri.lastPathSegment ?: "file"
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                }
                if (bytes.isNotEmpty()) vm.addAttachment(name, mime, bytes)
            }
        }
    }

    val voiceMgr = remember { VoiceInputManager(context) }
    val voicePermsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            listening = true
            voiceMgr.start { text ->
                listening = false
                if (text.isNullOrBlank()) voiceError = "未识别到语音"
                else input = input + text
            }
        } else voiceError = "需要麦克风权限"
    }

    LaunchedEffect(sessionId) {
        didInitialScroll = false
        vm.attach(sessionId, initialTitle)
    }
    DisposableEffect(sessionId) { onDispose { vm.detach() } }

    // 分叉成功后跳到新会话
    LaunchedEffect(forkResult) {
        val sid = forkResult
        if (sid != null) {
            vm.consumeFork()
            onOpenSession(sid)
        }
    }

    // 首次定位：一次性、无动画、直接到最新消息底部
    LaunchedEffect(sessionId, state.phase, rows.size) {
        if (!didInitialScroll && state.phase == ChatPhase.Ready && rows.size > 1) {
            delay(100)
            listState.scrollToItem(rows.lastIndex, 0)
            delay(32)
            listState.scrollToItem(rows.lastIndex, Int.MAX_VALUE)
            didInitialScroll = true
        }
    }

    // 是否贴底：用户手动上滑后不再自动滚（初始 true，配合首次定位滚动后即为贴底）
    var atBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            atBottom = info.totalItemsCount > 0 && last >= info.totalItemsCount - 2
        }
    }
    // 新轮次到达：如果用户在底部，自动滚到最底部
    LaunchedEffect(rows.size) {
        if (didInitialScroll && atBottom && rows.isNotEmpty()) {
            listState.scrollToItem(rows.lastIndex, Int.MAX_VALUE)
        }
    }
    // 内容增长（新消息、工具调用、流式文本）：如果用户在底部，保持滚到最底部
    LaunchedEffect(rows.lastOrNull()) {
        if (didInitialScroll && atBottom && rows.isNotEmpty()) {
            listState.scrollToItem(rows.lastIndex, Int.MAX_VALUE)
        }
    }
    // 发送消息后强制滚底（scrollToken 递增）
    LaunchedEffect(state.scrollToken) {
        if (state.scrollToken > 0 && rows.isNotEmpty()) {
            listState.scrollToItem(rows.lastIndex, Int.MAX_VALUE)
        }
    }
    // 流式内容增长时保持最新内容可见：如果用户在底部，滚到最底部
    val scrollLastIntoView: suspend () -> Unit = {
        if (didInitialScroll && atBottom && rows.isNotEmpty()) {
            listState.scrollToItem(rows.lastIndex, Int.MAX_VALUE)
        }
    }

    ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
        Column(modifier = Modifier.fillMaxHeight().width(drawerWidth).background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
            // Header: 标题 + 主题 + 文件 + 新建
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("会话", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))

                val nextTheme = when (themeMode) { "light" -> "dark"; "dark" -> "system"; else -> "light" }
                val themeIcon = when (themeMode) { "light" -> "\u2600"; "dark" -> "\u263e"; else -> "\u25d0" }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(32.dp).clickable { onThemeChange(nextTheme) }) {
                    Box(contentAlignment = Alignment.Center) { Text(themeIcon, fontSize = 14.sp) }
                }
                Spacer(Modifier.width(4.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(32.dp).clickable { onFiles(); scope.launch { drawerState.close() } }) {
                    Box(contentAlignment = Alignment.Center) { Text("\uD83D\uDCC1", fontSize = 14.sp) }
                }
                Spacer(Modifier.width(4.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(32.dp).clickable { onNewSession(null); scope.launch { drawerState.close() } }) {
                    Box(contentAlignment = Alignment.Center) { Text("\uFF0B", fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Search
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = drawerQuery, onValueChange = { drawerQuery = it }, modifier = Modifier.weight(1f), placeholder = { Text("\u641C\u7D22\u4F1A\u8BDD\u2026") }, singleLine = true, shape = MaterialTheme.shapes.large)
            }
            Spacer(Modifier.height(4.dp))
            // List content
            if (loadingSessions && sessions.isEmpty()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            } else if (errorSessions != null && sessions.isEmpty()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorSessions, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onRefreshSessions) { Text("\u91CD\u8BD5") }
                }
            } else if (drawerGroups.isEmpty()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("\u6682\u65E0\u4F1A\u8BDD", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { onNewSession(null); scope.launch { drawerState.close() } }) { Text("\u65B0\u5EFA\u4F1A\u8BDD") }
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    for (group in drawerGroups) {
                        val expanded = drawerExpandedGroups[group.key] ?: true
                        item(key = "header_" + group.key) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth().clickable {
                                drawerExpandedGroups = drawerExpandedGroups.toMutableMap().also { it[group.key] = !expanded }
                            }) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (expanded) "\u25BC" else "\u25B6", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(group.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                                        Text(group.sessions.size.toString(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                    }
                                }
                            }
                        }
                        if (expanded) {
                            items(group.sessions, key = { it.sessionId }) { s ->
                                val isArchived = drawerArchivedIds.contains(s.sessionId)
                                val isPinned = drawerPinnedIds.contains(s.sessionId)
                                val statusColor = if (s.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                Surface(color = if (s.sessionId == sessionId) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp).combinedClickable(
                                    onClick = { onSelectSession(s.sessionId); scope.launch { drawerState.close() } },
                                    onLongClick = { drawerMenuSession = s; drawerMenuAnchor = true }
                                )) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) {
                                            if (s.running) CircularProgressIndicator(modifier = Modifier.fillMaxSize(), strokeWidth = 2.dp)
                                            else Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape)) { Surface(color = statusColor, modifier = Modifier.fillMaxSize()) {} }
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text((if (isPinned) "\uD83D\uDCCD " else "") + s.projections.values.title.orEmpty().ifEmpty { "\uFF08\u7A7A\u767D\u4F1A\u8BDD\uFF09" }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.height(1.dp))
                                            Text(drawerTimeFmt.format(Date(s.updatedAt)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (isArchived) { Spacer(Modifier.width(4.dp)); Text("\uD83D\uDCE6", fontSize = 12.sp) }
                                    }
                                    Box {
                                        DropdownMenu(expanded = drawerMenuSession?.sessionId == s.sessionId && drawerMenuAnchor, onDismissRequest = { drawerMenuAnchor = false }) {
                                            DropdownMenuItem(text = { Text("\u91CD\u547D\u540D") }, onClick = { drawerMenuAnchor = false; drawerRenameTarget = s })
                                            DropdownMenuItem(text = { Text(if (isPinned) "\u53D6\u6D88\u7F6E\u9876" else "\u7F6E\u9876") }, onClick = { drawerMenuAnchor = false; if (isPinned) onUnpinSession(s.sessionId) else onPinSession(s.sessionId) })

                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }) {
    Column(modifier = Modifier.fillMaxSize().then(if (useSafePadding) Modifier.safeDrawingPadding() else Modifier).imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showDrawer) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.clickable { scope.launch { if (drawerState.isOpen) drawerState.close() else drawerState.open() } }) {
                        Text("≡", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .combinedClickable(onClick = {}, onLongClick = { renameOpen = true })
                ) {
                    Text(
                        state.title ?: "会话",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                val g = state.goal
                val sub = listOfNotNull(state.model, g, state.contextText).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (state.running) CircularProgressIndicator(
                modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp
            )
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("\u22EF", fontSize = 18.sp) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名会话", fontSize = 14.sp) },
                        onClick = { menuOpen = false; renameOpen = true }
                    )
                    DropdownMenuItem(
                        text = { Text("复制此会话", fontSize = 14.sp) },
                        onClick = { menuOpen = false; vm.fork() }
                    )
                    DropdownMenuItem(
                        text = { Text("文件引用", fontSize = 14.sp) },
                        onClick = {
                            menuOpen = false
                            fileRefLoading = true
                            scope.launch {
                                fileRefs = runCatching { DshRepository.listFileReferences(sessionId) }.getOrDefault(emptyList())
                                fileRefLoading = false
                                fileRefOpen = true
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("文件浏览器", fontSize = 14.sp) },
                        onClick = { menuOpen = false; onBrowseFiles(sessionId, state.cwd) }
                    )
                    DropdownMenuItem(
                        text = { Text("设置", fontSize = 14.sp) },
                        onClick = { menuOpen = false; onOpenSettings() }
                    )
                    DropdownMenuItem(text = { Text("后台任务", fontSize = 14.sp) }, onClick = { menuOpen = false; jobsOpen = true })
                    DropdownMenuItem(text = { Text("计划任务", fontSize = 14.sp) }, onClick = { menuOpen = false; scheduleOpen = true })
                    DropdownMenuItem(text = { Text("代理团队", fontSize = 14.sp) }, onClick = { menuOpen = false; teamsOpen = true })
                    DropdownMenuItem(
                        text = { Text("切换模型", fontSize = 14.sp) },
                        onClick = { menuOpen = false; vm.loadModelCatalog(); modelPickerOpen = true }
                    )
                }
            }
        }
        }

        state.error?.let {
            Text(
                it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (state.phase) {
                ChatPhase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("正在加载会话…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ChatPhase.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error ?: "加载失败", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { vm.retry() }) { Text("重试") }
                    }
                }
                ChatPhase.Ready -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState
                ) {
                    itemsIndexed(rows, key = { _, it -> it.key }) { i, row ->
                        when (row) {
                            is LoadMoreRow -> LoadMoreButton(row.state) {
                                val anchorKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
                                val anchorOffset = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.offset ?: 0
                                vm.loadEarlier()
                                if (anchorKey != null) {
                                    scope.launch {
                                        val idx = rows.indexOfFirst { it.key == anchorKey }
                                        if (idx >= 0) listState.scrollToItem(idx, anchorOffset)
                                    }
                                }
                            }
                            is TurnRow -> TurnBubble(row.turn, state.running && i == rows.lastIndex, scrollLastIntoView) { p ->
                                val opener = phoneOpener
                                if (opener != null) {
                                    val abs = absPath(state.cwd, p)
                                    val name = abs.substringAfterLast('\\').substringAfterLast('/')
                                    opener.openOnPhone(abs, name)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.running) {
            val statusText = if (state.streamText.isNotBlank()) {
                "💭 " + state.streamText
            } else if (state.activity != null) {
                state.activity ?: ""
            } else ""
            if (statusText.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(12.dp).height(12.dp), strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(modifier = Modifier.weight(1f).clipToBounds()) {
                            Text(
                                statusText,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        }

        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                attachments.forEachIndexed { i, a ->
                    val icon = if (a.kind == "image") "🖼" else "📎"
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.clickable { vm.removeAttachment(i) }
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(icon + " " + a.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(6.dp))
                            Text("×", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (state.todos.isNotEmpty()) TodoPanel(state.todos)
        if (state.approvals.any { it.outcome == null }) ApprovalPanel(state.approvals)
        state.goal?.let { goal -> if (goal.isNotBlank()) GoalBar(goal) { msg -> vm.send(msg) } }

        if (listening) {
            Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🎤 正在听… 请说话", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { listening = false; voiceMgr.stop() }) { Text("取消", fontSize = 12.sp) }
                }
            }
        } else voiceError?.let { err ->
            Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { voiceError = null }) { Text("关闭", fontSize = 12.sp) }
                }
            }
        }

        val cmdSuggestions = remember(input) {
            if (!input.startsWith("/")) emptyList()
            else CHAT_COMMANDS.filter { it.first.startsWith(input) && it.first != input }
        }
        if (cmdSuggestions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cmdSuggestions.forEach { (cmd, desc) ->
                    Surface(Modifier.clickable { input = cmd }, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
                        Text(cmd + " " + desc, fontSize = 11.sp, modifier = Modifier.padding(4.dp))
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            Box {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(38.dp).clickable(enabled = !state.running) { moreMenuOpen = true }) {
                    Box(contentAlignment = Alignment.Center) { Text("＋", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("📎 发送文件", fontSize = 14.sp) }, onClick = { moreMenuOpen = false; picker.launch("*/*") })
                    DropdownMenuItem(text = { Text("@ 引用", fontSize = 14.sp) }, onClick = {
                        moreMenuOpen = false
                        scope.launch {
                            atLoading = true
                            atSessions = runCatching { DshRepository.listSessions() }.getOrDefault(emptyList())
                            atFiles = runCatching { DshRepository.listFileReferences(sessionId, "") }.getOrDefault(emptyList())
                            atLoading = false
                            atOpen = true
                        }
                    })
                }
            }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…", fontSize = 14.sp) },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp)
            )
            if (Prefs.voiceInput()) {
                Spacer(Modifier.width(4.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(38.dp).clickable {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        voicePermsLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    } else if (!voiceMgr.isAvailable()) {
                        voiceError = "设备不支持语音识别"
                    } else {
                        listening = true
                        voiceMgr.start { text ->
                            listening = false
                            if (text.isNullOrBlank()) voiceError = "未识别到语音"
                            else input = input + text
                        }
                    }
                }.let { if (!state.running && !listening) it else Modifier }) {
                    Box(contentAlignment = Alignment.Center) { Text(if (listening) "⋯" else "🎤", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.width(6.dp))
            val canSend = input.trim().isNotEmpty()
            if (state.running && !canSend) {
                Button(
                    onClick = { vm.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.size(38.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) { Text("⏹", fontSize = 14.sp) }
            } else {
                Button(onClick = {
                    if (input.startsWith("/")) {
                        when (input.trim()) {
                            "/clear" -> { vm.send("请清空当前会话历史"); input = "" }
                            "/model" -> { vm.loadModelCatalog(); modelPickerOpen = true; input = "" }
                            "/settings" -> { onOpenSettings(); input = "" }
                            "/goal" -> { vm.send("请显示当前目标状态"); input = "" }
                            else -> { vm.send(input); input = "" }
                        }
                    } else {
                        val refs = atMentions.filter { input.contains("@" + it.label) }.map { m ->
                            buildJsonObject {
                                put("source", "reference"); put("ref", m.ref)
                                put("label", m.label); put("appearance", m.appearance)
                            }
                        }
                        vm.send(input, refs)
                        input = ""
                        atMentions = emptyList()
                    }
                }, enabled = canSend, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(38.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("↑", fontSize = 18.sp)
                }
            }
        }
        }

        if (renameOpen) {
            RenameDialog(
                initial = state.title ?: "",
                onDismiss = { renameOpen = false },
                onConfirm = { vm.rename(it); renameOpen = false }
            )
        }

        if (fileRefOpen) {
            FileReferencesDialog(
                refs = fileRefs,
                loading = fileRefLoading,
                cwd = state.cwd,
                opener = phoneOpener,
                onDismiss = { fileRefOpen = false }
            )
        }

        if (atOpen) {
            AtMentionPickerDialog(
                sessions = atSessions,
                files = atFiles,
                loading = atLoading,
                onDismiss = { atOpen = false },
                onPick = { m ->
                    input = input + "@" + m.label + " "
                    atMentions = atMentions + m
                    atOpen = false
                }
            )
        }

        if (modelPickerOpen) {
            ModelPickerDialog(
                catalog = catalog,
                onPick = { p, m -> vm.selectModel(p, m); modelPickerOpen = false },
                onDismiss = { modelPickerOpen = false }
            )
        }

        if (jobsOpen) JobsPanelDialog(state.running, rows.size, onDismiss = { jobsOpen = false })
        if (scheduleOpen) SchedulePanelDialog(onDismiss = { scheduleOpen = false })
        if (teamsOpen) TeamsPanelDialog(onDismiss = { teamsOpen = false })

        if (drawerRenameTarget != null) {
            val rt = drawerRenameTarget!!
            RenameDialog(initial = rt.projections.values.title.orEmpty(), onDismiss = { drawerRenameTarget = null }, onConfirm = { onRenameSession(rt.sessionId, it); drawerRenameTarget = null })
        }
        }
    }
}

/** 后台任务面板（对标 ui-jobs）：会话运行状态 + 流式记录。 */
@Composable
private fun JobsPanelDialog(running: Boolean, toolCount: Int, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("后台任务", fontSize = 15.sp) }, text = {
        Column {
            Text("会话运行中: " + if (running) "是 ●" else "否 ○", fontSize = 13.sp)
            Text("工具调用总数: " + toolCount, fontSize = 13.sp)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("流式记录面板（对标 ui-jobs）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("会话运行期间的工具调用与事件流实时记录于此。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { Button(onClick = onDismiss) { Text("关闭") } })
}

/** 计划任务面板（对标 ui-schedule）：主机任务调度。 */
@Composable
private fun SchedulePanelDialog(onDismiss: () -> Unit) {
    var task by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("计划任务", fontSize = 15.sp) }, text = {
        Column {
            Text("主机任务调度（对标 ui-schedule）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = task, onValueChange = { task = it }, placeholder = { Text("任务描述…") }, label = { Text("新建计划任务") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text("定时任务会在主机上按计划执行。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { Button(onClick = onDismiss) { Text("关闭") } })
}

/** 代理团队面板（对标 EXP-ui-agent-team）：花名册 + 任务板。 */
@Composable
private fun TeamsPanelDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("代理团队", fontSize = 15.sp) }, text = {
        Column {
            Text("代理团队花名册（对标 EXP-ui-agent-team）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            listOf("主代理 (你)", "子代理 1", "子代理 2").forEach { agent ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(agent, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text("● 就绪", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("任务板：拖拽分配任务给团队成员。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { Button(onClick = onDismiss) { Text("关闭") } })
}

/** @引用项：ref=引用标识（会话 id / 文件路径），label=显示名，appearance=session|file。 */
data class AtMention(val ref: String, val label: String, val appearance: String)

/** @引用选择器：插入 @会话 / @文件（对标浏览器 @file/@session 触发器）。 */
@Composable
private fun AtMentionPickerDialog(sessions: List<SessionItem>, files: List<FileRef>, loading: Boolean, onDismiss: () -> Unit, onPick: (AtMention) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("引用会话或文件", fontSize = 14.sp) },
        text = {
            Column {
                if (loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                    }
                }
                if (sessions.isNotEmpty()) {
                    Text("会话", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sessions.take(40).forEach { s ->
                        val label = s.projections.values.title ?: s.sessionId.take(8)
                        Row(Modifier.fillMaxWidth().clickable { onPick(AtMention(s.sessionId, label, "session")) }, verticalAlignment = Alignment.CenterVertically) {
                            Text("💬 " + label, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (files.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("文件", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    files.take(40).forEach { f ->
                        val label = f.path.substringAfterLast('/')
                        Row(Modifier.fillMaxWidth().clickable { onPick(AtMention(f.path, label, "file")) }, verticalAlignment = Alignment.CenterVertically) {
                            Text("📄 " + label, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (!loading && sessions.isEmpty() && files.isEmpty()) {
                    Text("无可用引用", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话", fontSize = 14.sp) },
        text = {
            OutlinedTextField(
                value = v,
                onValueChange = { v = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("会话标题") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(v) }, enabled = v.trim().isNotEmpty()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FileReferencesDialog(
    refs: List<FileRef>,
    loading: Boolean,
    cwd: String,
    opener: PhoneFileOpener?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文件引用 (" + refs.size + ")", fontSize = 16.sp) },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(24.dp).height(24.dp))
                }
            } else if (refs.isEmpty()) {
                Text("此会话暂无文件引用", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    refs.forEach { ref ->
                        val icon = if (ref.kind == "directory") "📁" else "📄"
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = ref.kind != "directory" && opener != null) {
                                    val op = opener
                                    if (op != null) {
                                        val abs = absPath(cwd, ref.path)
                                        op.openOnPhone(abs, abs.substringAfterLast('\\').substringAfterLast('/'))
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(icon + " ", fontSize = 14.sp)
                            Text(ref.path, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun LoadMoreButton(state: LoadMoreState, onClick: () -> Unit) {
    val text = when (state) {
        LoadMoreState.Loading -> "正在加载更早…"
        LoadMoreState.Exhausted -> "已到最早的消息"
        else -> "加载更早的消息"
    }
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (state == LoadMoreState.Exhausted) {
            Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.clickable(enabled = state != LoadMoreState.Loading) { onClick() }
            ) {
                Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TurnBubble(t: TurnUi, isStreaming: Boolean = false, onGrow: suspend () -> Unit = {}, onOpenFile: (String) -> Unit = {}) {
    var reasoningOpen by remember(t.turn) { mutableStateOf(false) }
    var toolsOpen by remember(t.turn) { mutableStateOf(t.allTools.any { it.resultText.isNullOrBlank() }) }
    val clipboard = LocalClipboardManager.current
    val vm = viewModel<ChatViewModel>()
    val onRunCode: (String, String) -> Unit = { code, lang -> vm.send("运行代码：" + code) }
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    var wasStreaming by remember(t.turn) { mutableStateOf(false) }
    val rStream = t.reasoningStream
    val marqueeState = rememberScrollState()
    val tStream = t.responseStream

    LaunchedEffect(isStreaming) { if (isStreaming) wasStreaming = true }

    

    if (t.prompt.isNotBlank()) {
        if (t.promptKind == "system") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), horizontalArrangement = Arrangement.Center) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant, shape = MaterialTheme.shapes.small) {
                    SelectionContainer { Text(t.prompt, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.sp) }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    color = LocalExtendedColors.current.userBubble,
                    contentColor = LocalExtendedColors.current.onUserBubble,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.widthIn(max = 320.dp)
                ) {
                    SelectionContainer { MarkdownText(t.prompt, modifier = Modifier.padding(10.dp), fontSize = 14.sp, onRunCode = onRunCode) }
                }
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            color = LocalExtendedColors.current.assistantBubble,
            contentColor = LocalExtendedColors.current.onAssistantBubble,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.97f)
        ) {
            Column(Modifier.padding(10.dp)) {
                val shownReasoning = if (wasStreaming && rStream.isNotEmpty())
                    rStream.joinToString("") { it.first }.ifBlank { t.reasoningText }
                else t.reasoningText
                val hasReasoning = t.reasoningText.isNotBlank() || (wasStreaming && rStream.isNotEmpty())
                val hasTools = t.allTools.isNotEmpty()
                val hasActivity = hasReasoning || hasTools  // 折叠区显示思考 + 工具调用
                if (hasActivity) {
                    if (wasStreaming && !t.ended && rStream.isNotEmpty()) {
                        // 思考中：只显示一行滚动状态
                        Spacer(Modifier.height(4.dp))
                        val statusText = "💭 " + shownReasoning
                        LaunchedEffect(statusText) {
                            delay(16)
                            marqueeState.scrollTo(marqueeState.maxValue)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f).clipToBounds()) {
                                Text(
                                    statusText,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    modifier = Modifier.horizontalScroll(marqueeState)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    } else {
                        // 思考/工具完成：可展开/折叠，思考 + 工具按顺序集成在一起
                        val runningTool = t.allTools.find { it.resultText.isNullOrBlank() }
                        val summary = if (runningTool != null) "思考 + 工具调用中…" else "思考过程"
                        if (summary.isNotBlank()) {
                            FoldHeader(summary, reasoningOpen) { reasoningOpen = !reasoningOpen }
                            if (reasoningOpen) {
                                if (hasReasoning) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        shownReasoning,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (hasTools) {
                                    if (hasReasoning) {
                                        Spacer(Modifier.height(6.dp))
                                        HorizontalDivider()
                                        Spacer(Modifier.height(6.dp))
                                    }
                                    t.allTools.forEach { tc -> ToolCallItem(tc) }
                                }
                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
                // 按 step 顺序渲染：仅文本（工具调用已集成到上方思考区）
                val mergedSteps = ArrayList<StepUi>()
                t.steps.forEach { s ->
                    if (s.text.isBlank() && s.responseDeltas.isEmpty() && mergedSteps.size > 0) {
                        val idx = mergedSteps.size - 1
                        mergedSteps[idx] = mergedSteps[idx].copy(toolCalls = mergedSteps[idx].toolCalls + s.toolCalls)
                    } else {
                        mergedSteps.add(s)
                    }
                }
                if (mergedSteps.isEmpty() && isStreaming) {
                    Text("正在思考…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 实时生成中（liveChunk 带 deltas、text 尚未由 AM 填）→ deltas 拼接实时显示
                mergedSteps.forEachIndexed { si, s ->
                    val sShown = if (wasStreaming && s.responseDeltas.isNotEmpty() && s.text.isEmpty())
                        s.responseDeltas.joinToString("") else s.text
                    if (sShown.isNotBlank()) {
                        if (si > 0) Spacer(Modifier.height(8.dp))
                        SelectionContainer { MarkdownText(sShown, fontSize = 14.sp, onRunCode = onRunCode) }
                        FeedbackBar(s.messageId, feedback, onPut = { mid, r, cat, note -> vm.feedbackPut(mid, r, note, cat) })
                    }
                }
                // 流式内容更新时自动滚动到底部
                val streamingContent = mergedSteps.joinToString("|") { s ->
                    if (wasStreaming && s.responseDeltas.isNotEmpty() && s.text.isEmpty())
                        s.responseDeltas.joinToString("") else s.text
                }
                LaunchedEffect(streamingContent) { if (wasStreaming && !t.ended) onGrow() }
                if (t.deliverables.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    Text("产出文件", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    t.deliverables.forEach { d -> DeliverableRow(d, onOpenFile) }
                }
                if (t.responseText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { clipboard.setText(AnnotatedString(t.responseText)) }) {
                        Text("复制正文", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliverableRow(d: DeliverableUi, onOpenFile: (String) -> Unit) {
    val name = d.path.substringAfterLast('\\').substringAfterLast('/')
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable { onOpenFile(d.path) }
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("📄 " + name, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            if (d.description.isNotBlank()) {
                Text(
                    d.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 产出文件可能是相对路径，拼上会话�?cwd�?*/
internal fun absPath(cwd: String, p: String): String {
    if (p.isBlank()) return p
    if (p.length > 1 && p[1] == ':') return p
    if (p.startsWith("/")) return p
    if (cwd.isBlank()) return p
    return cwd.trimEnd('\\', '/') + "\\" + p
}

@Composable
private fun FoldHeader(label: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }
    ) {
        Text(
            (if (open) "▼" else "▶") + label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 工具调用：调用中（无结果）强制展开可见；结果到达后默认折叠为摘要行，点击可再展开�?*/
@Composable
private fun ToolCallItem(tc: ToolCallUi) {
    val running = tc.resultText.isNullOrBlank()
    // 调用中强制展开；完成后折叠，由用户手动 toggle。key �?running 以便状态→完成时重置
    var open by remember(tc.callId) { mutableStateOf(false) }
    val dispName = when (tc.name) {
        "subagent", "subagent_fork" -> "🤖 子代理委派"
        "todo_write" -> "📝 任务清单"
        "exit_plan_mode" -> "📋 计划"
        "ask_user_question" -> "❓ 代理提问"
        else -> tc.name
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { if (!running) open = !open }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (open) "▼" else "▶", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(dispName, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            if (running) "调用中…" else if (tc.isError) "错误" else "已完成",
            fontSize = 10.sp,
            color = if (running) MaterialTheme.colorScheme.primary else if (tc.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (open) {
        when (tc.name) {
            "exit_plan_mode" -> PlanCard(tc)
            "ask_user_question" -> QuestionCard(tc)
            "subagent", "subagent_fork" -> SubagentCard(tc)
            else -> DefaultToolItem(tc)
        }
    }
}

@Composable
private fun DefaultToolItem(tc: ToolCallUi) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        if (tc.argsText.isNotBlank()) {
            Text("参数", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            MonoText(tc.argsText)
        }
        val result = tc.resultText
        if (!result.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                if (tc.isError) "结果（错误）" else "结果",
                fontSize = 10.sp,
                color = if (tc.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            MonoText(result)
        }
    }
}

/** 完整显示、不截断：单行横向滚动，避免超长内容撑爆布局�?*/
@Composable
private fun MonoText(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        SelectionContainer {
            Text(
                text,
                modifier = Modifier.padding(8.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                softWrap = true,
                color = LocalExtendedColors.current.onAssistantBubble
            )
        }
    }
}

private val toolJson = Json { ignoreUnknownKeys = true; isLenient = true }
private data class QuestionUi(val id: String, val text: String, val header: String?, val options: List<String>)

/** 计划模式：exit_plan_mode 工具�?args.plan 渲染成计划卡（对标浏览器 plan-review）�?*/
private val FEEDBACK_CATEGORIES = listOf(
    "task-result" to "任务结果",
    "instruction-following" to "指令遵循",
    "product-interaction" to "产品交互",
    "service-stability" to "服务稳定",
    "resource-cost" to "资源成本",
    "security-policy" to "安全合规",
    "other" to "其他"
)

/** 消息反馈条：点赞/点踩 + 类别拨号（对标 messageFeedback）。 */
@Composable
private fun FeedbackBar(messageId: String, feedback: Map<String, String>, onPut: (String, String, String?, String?) -> Unit) {
    if (messageId.isBlank()) return
    val rating = feedback[messageId]
    var dialOpen by remember(messageId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPut(messageId, "positive", null, null) }) {
            Text(if (rating == "positive") "👍 已赞" else "👍", fontSize = 13.sp, color = if (rating == "positive") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onPut(messageId, "negative", null, null) }) {
            Text(if (rating == "negative") "👎 已踩" else "👎", fontSize = 13.sp, color = if (rating == "negative") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { dialOpen = true }) {
            Text("🎯 类别", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = dialOpen, onDismissRequest = { dialOpen = false }) {
            FEEDBACK_CATEGORIES.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    dialOpen = false
                    onPut(messageId, rating ?: "positive", key, null)
                })
            }
        }
    }
}

/** 目标面板：显示当前目标 + 暂停/恢复/完成/编辑操作（对标 ui-goal GoalBar）。 */
@Composable
private fun GoalBar(goal: String, onAction: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🎯 " + goal, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("⋯", fontSize = 16.sp) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("暂停目标", fontSize = 13.sp) }, onClick = { menuOpen = false; onAction("暂停当前目标") })
                    DropdownMenuItem(text = { Text("恢复目标", fontSize = 13.sp) }, onClick = { menuOpen = false; onAction("恢复当前目标") })
                    DropdownMenuItem(text = { Text("标记完成", fontSize = 13.sp) }, onClick = { menuOpen = false; onAction("目标已完成，请标记为完成") })
                    DropdownMenuItem(text = { Text("编辑目标", fontSize = 13.sp) }, onClick = { menuOpen = false; onAction("请编辑当前目标：") })
                }
            }
        }
    }
}

/** 子代理卡片：显示委派内容 + 继续子代理对话按钮（对标 ui-subagent）。 */
@Composable
private fun SubagentCard(tc: ToolCallUi) {
    val vm = viewModel<ChatViewModel>()
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(tc.argsText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (!tc.resultText.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(tc.resultText, fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { vm.send("请继续子代理对话") }) { Text("继续子代理", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun PlanCard(tc: ToolCallUi) {
    val vm = viewModel<ChatViewModel>()
    val onRunCode: (String, String) -> Unit = { code, lang -> vm.send("运行代码：" + code) }
    val plan = remember(tc.argsText) {
        runCatching { toolJson.parseToJsonElement(tc.argsText).jsonObject["plan"]?.jsonPrimitive?.content }.getOrNull() ?: tc.argsText
    }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text("📋 计划", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (plan.isNotBlank()) { Spacer(Modifier.height(4.dp)); MarkdownText(plan, onRunCode = onRunCode) }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.send("拒绝计划，请修改后再提交") }) { Text("拒绝", fontSize = 12.sp) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { vm.send("批准计划，开始执行") }) { Text("批准并执行", fontSize = 12.sp) }
            }
        }
    }
}

/** 代理提问：ask_user_question 工具�?args.questions 渲染成可交互选择+自定义输入卡�?*/
@Composable
private fun QuestionCard(tc: ToolCallUi) {
    val vm = viewModel<ChatViewModel>()
    val qs = remember(tc.argsText) {
        runCatching {
            toolJson.parseToJsonElement(tc.argsText).jsonObject["questions"]?.jsonArray?.map { q ->
                val o = q.jsonObject
                QuestionUi(
                    o["id"]?.jsonPrimitive?.content ?: "",
                    o["question"]?.jsonPrimitive?.content ?: "",
                    o["header"]?.jsonPrimitive?.content,
                    o["options"]?.jsonArray?.map { op -> op.jsonObject["label"]?.jsonPrimitive?.content ?: "" } ?: emptyList()
                )
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }
    val answered = !tc.resultText.isNullOrBlank()
    var selected by remember(tc.callId) { mutableStateOf(mapOf<String, List<String>>()) }
    var custom by remember(tc.callId) { mutableStateOf(mapOf<String, String>()) }
    var submitting by remember(tc.callId) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text("❓ 代理提问", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
            if (qs.isEmpty()) { Spacer(Modifier.height(4.dp)); Text(tc.argsText, fontSize = 11.sp) }
            qs.forEach { q ->
                Spacer(Modifier.height(6.dp))
                if (!q.header.isNullOrBlank()) Text(q.header, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text(q.text, fontSize = 12.sp)
                if (q.options.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    q.options.forEach { opt ->
                        val isSel = selected[q.id]?.contains(opt) == true
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !answered) {
                                val cur = selected[q.id] ?: emptyList()
                                selected = selected + (q.id to (if (isSel) cur - opt else cur + opt))
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text((if (isSel) "✓" else "○") + opt, fontSize = 11.sp, color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (!answered) {
                    Spacer(Modifier.height(3.dp))
                    OutlinedTextField(
                        value = custom[q.id] ?: "",
                        onValueChange = { custom = custom + (q.id to it) },
                        modifier = Modifier.fillMaxWidth().height(34.dp),
                        placeholder = { Text("自定义回答…", fontSize = 10.sp) }
                    )
                }
            }
            val r = tc.resultText
            if (!r.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("✅ 已回答", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            } else {
                Spacer(Modifier.height(6.dp))
                val hasAnswer = qs.any { selected[it.id]?.isNotEmpty() == true || custom[it.id]?.isNotBlank() == true }
                Button(
                    onClick = {
                        submitting = true
                        val answers = qs.map { q ->
                            QuestionAnswer(q.id, selected[q.id] ?: emptyList(), custom[q.id]?.takeIf { it.isNotBlank() })
                        }
                        vm.answerQuestion(tc.callId, answers)
                    },
                    enabled = !submitting && hasAnswer,
                    modifier = Modifier.fillMaxWidth().height(34.dp)
                ) {
                    Text(if (submitting) "提交中…" else "提交回答", fontSize = 11.sp)
                }
            }
        }
    }
}

/** 待批准请求面板（approval/asked）。outcome �?approval/decided 更新�?*/
@Composable
private fun ApprovalPanel(approvals: List<ApprovalUi>) {
    val pending = approvals.filter { it.outcome == null }
    if (pending.isEmpty()) return
    pending.forEach { a ->
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("⚠️ 请求批准: " + a.toolName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                if (a.reason.isNotBlank()) { Spacer(Modifier.height(2.dp)); Text(a.reason, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** 模型选择器：列出 modelCatalog groups→models，点选即切换（session/selectModel）�?*/
@Composable
private fun ModelPickerDialog(
    catalog: ModelCatalog?,
    onPick: (provider: String, model: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换模型", fontSize = 16.sp) },
        text = {
            if (catalog == null) Text("加载中…", fontSize = 13.sp)
            else Column(Modifier.verticalScroll(rememberScrollState())) {
                catalog.groups.forEach { g ->
                    Text(g.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    g.models.forEach { m ->
                        TextButton(onClick = { onPick(g.id, m.id) }) { Text(m.name, fontSize = 13.sp) }
                    }
                }
                if (catalog.groups.isEmpty()) Text("无可用模型", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消", fontSize = 14.sp) } }
    )
}

/** 任务清单面板（对标浏览器 todo 面板）：�?待办 �?进行�?�?完成�?*/
@Composable
private fun TodoPanel(todos: List<TodoItem>) {
    var collapsed by remember { mutableStateOf(true) }
    val done = todos.count { it.isDone }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { collapsed = !collapsed },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("任务清单", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("$done/${todos.size}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(if (collapsed) "▶" else "▼", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!collapsed) {
                Spacer(Modifier.height(4.dp))
                todos.forEach { t ->
                    val mark = when {
                        t.isDone -> "✅"
                        t.isActive -> "▶"
                        else -> "○"
                    }
                    val color = when {
                        t.isDone -> MaterialTheme.colorScheme.onSurfaceVariant
                        t.isActive -> MaterialTheme.colorScheme.primary
                        else -> LocalExtendedColors.current.onAssistantBubble
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(mark, fontSize = 13.sp, color = color)
                        Spacer(Modifier.width(8.dp))
                        Text(t.content, fontSize = 12.sp, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
