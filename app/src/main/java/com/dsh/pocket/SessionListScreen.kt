package com.dsh.pocket

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表：按工作区分组，归档默认隐藏，支持本地搜索。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    sessions: List<SessionItem>,
    baseline: WorkspaceBaseline?,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onSilentRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onFiles: () -> Unit,
    onNew: (String?) -> Unit,
    onArchive: (String) -> Unit,
    onUnarchive: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onPin: (String) -> Unit,
    onUnpin: (String) -> Unit,
    themeMode: String = "system",
    onThemeChange: (String) -> Unit = {},
    useSafePadding: Boolean = true
) {
    var query by remember { mutableStateOf("") }
    var expandedGroups by remember { mutableStateOf<Map<String, Boolean>>(mapOf("__ungrouped__" to true)) }
    LaunchedEffect(baseline) {
        baseline?.items?.forEach { w -> if (w.workspaceId !in expandedGroups) expandedGroups = expandedGroups + (w.workspaceId to true) }
    }
    var renameTarget by remember { mutableStateOf<SessionItem?>(null) }
    var menuSession by remember { mutableStateOf<SessionItem?>(null) }
    var menuAnchor by remember { mutableStateOf(false) }

    // 自动轮询
    LaunchedEffect(Unit) {
        while (true) {
            delay(8000)
            onSilentRefresh()
        }
    }

    val archivedIds = baseline?.archivedSessionIds?.toSet() ?: emptySet()
    val pinnedIds = baseline?.pinnedSessionIds?.toSet() ?: emptySet()
    val workspaceMap = remember(baseline) {
        (baseline?.items ?: emptyList()).associate { it.workspaceId to it }
    }

    val filtered = remember(sessions, query, archivedIds) {
        val q = query.trim().lowercase()
        sessions.filter { s ->
            val isArchived = archivedIds.contains(s.sessionId)
            if (isArchived) false
            else if (q.isEmpty()) true
            else s.projections.values.title.orEmpty().lowercase().contains(q)
        }.sortedByDescending { pinnedIds.contains(it.sessionId) }
    }

    val groups = remember(filtered, archivedIds, pinnedIds) {
        WorkspaceStore.groups(filtered, archivedIds, pinnedIds)
    }

    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().then(if (useSafePadding) Modifier.safeDrawingPadding() else Modifier)) {
        // 顶部栏
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("会话", fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
                val nextTheme = when (themeMode) { "light" -> "dark"; "dark" -> "system"; else -> "light" }
                val themeIcon = when (themeMode) { "light" -> "☀"; "dark" -> "☾"; else -> "◐" }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(36.dp).clickable { onThemeChange(nextTheme) }) {
                    Box(contentAlignment = Alignment.Center) { Text(themeIcon, fontSize = 16.sp) }
                }
                Spacer(Modifier.width(6.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(36.dp).clickable { onFiles() }) {
                    Box(contentAlignment = Alignment.Center) { Text("📁", fontSize = 16.sp) }
                }
                Spacer(Modifier.width(6.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(36.dp).clickable { onNew(null) }) {
                    Box(contentAlignment = Alignment.Center) { Text("＋", fontSize = 20.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                }
            }
        }
        // 搜索框
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索会话…") },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )
        }

        if (loading && sessions.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("加载中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (error != null && sessions.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRefresh) { Text("重试") }
            }
        } else if (groups.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂无会话", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onNew(null) }) { Text("新建会话") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                for (group in groups) {
                    val expanded = expandedGroups[group.key] ?: true
                    item(key = "header_" + group.key) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().clickable {
                                expandedGroups = expandedGroups.toMutableMap().also { it[group.key] = !expanded }
                            }
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (expanded) "▼" else "▶", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(group.title, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) {
                                    Text(group.sessions.size.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }
                    if (expanded) {
                            items(group.sessions, key = { it.sessionId }) { s ->
                                    val isArchived = archivedIds.contains(s.sessionId)
                                    val isPinned = pinnedIds.contains(s.sessionId)
                                    val statusColor = if (s.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = MaterialTheme.shapes.medium,
                                        tonalElevation = 1.dp,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).combinedClickable(
                                            onClick = { onOpen(s.sessionId) },
                                            onLongClick = { menuSession = s; menuAnchor = true }
                                        )
                                    ) {
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
                                                if (s.running) CircularProgressIndicator(modifier = Modifier.fillMaxSize(), strokeWidth = 2.dp)
                                                else Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape)) { Surface(color = statusColor, modifier = Modifier.fillMaxSize()) {} }
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    (if (isPinned) "📌 " else "") + s.projections.values.title.orEmpty().ifEmpty { "（空白会话）" },
                                                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(timeFmt.format(Date(s.updatedAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (isArchived) { Spacer(Modifier.width(6.dp)); Text("📦", fontSize = 14.sp) }
                                        }
                                        Box {
                                            DropdownMenu(expanded = menuSession?.sessionId == s.sessionId && menuAnchor, onDismissRequest = { menuAnchor = false }) {
                                                DropdownMenuItem(text = { Text("重命名") }, onClick = { menuAnchor = false; renameTarget = s })
                                                DropdownMenuItem(text = { Text(if (isPinned) "取消置顶" else "置顶") }, onClick = { menuAnchor = false; if (isPinned) onUnpin(s.sessionId) else onPin(s.sessionId) })
                                                DropdownMenuItem(text = { Text("复制会话") }, onClick = { menuAnchor = false })
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }


    // 重命名对话框
    val rt = renameTarget
    if (rt != null) {
        var title by remember { mutableStateOf(rt.projections.values.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { if (title.trim().isNotEmpty()) onRename(rt.sessionId, title.trim()); renameTarget = null }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }
}
