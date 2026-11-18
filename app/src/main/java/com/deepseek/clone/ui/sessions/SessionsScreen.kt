package com.deepseek.clone.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepseek.clone.R
import com.deepseek.clone.data.local.SecureStore
import com.deepseek.clone.data.local.SessionEntity
import com.deepseek.clone.data.repository.Repository
import com.deepseek.clone.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

private data class SessionGroup(val label: String, val items: List<SessionEntity>)

/** 官方抽屉风格：按「30 天内 / 年月」分组 */
private fun groupSessions(list: List<SessionEntity>): List<SessionGroup> {
    val now = System.currentTimeMillis()
    val cutoff = 30L * 24 * 60 * 60 * 1000
    val recent = list.filter { now - it.updatedAt <= cutoff }
    val older = list.filter { now - it.updatedAt > cutoff }

    val result = mutableListOf<SessionGroup>()
    if (recent.isNotEmpty()) result += SessionGroup("30 天内", recent)

    val byMonth = older.groupBy { s ->
        val c = Calendar.getInstance().apply { timeInMillis = s.updatedAt }
        c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
    }
    byMonth.keys.sortedDescending().forEach { key ->
        val year = key / 12
        val month = key % 12 + 1
        result += SessionGroup("${year}年${month}月", byMonth[key].orEmpty())
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    store: SecureStore,
    repository: Repository,
    onOpenChat: (String, String) -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
    onSessionsDeleted: (List<String>) -> Unit
) {
    val sessions by repository.observeSessions().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var menuSession by remember { mutableStateOf<SessionEntity?>(null) }
    var renameTarget by remember { mutableStateOf<SessionEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SessionEntity?>(null) }
    var sortAsc by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var showBatchDelete by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    val filtered = sessions.filter { it.title.contains(query, ignoreCase = true) }
        .let { if (sortAsc) it.sortedBy { s -> s.updatedAt } else it }
    val groups = groupSessions(filtered)

    Scaffold(
        bottomBar = {
            if (selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { selectionMode = false; selectedIds.clear() }) {
                        Text("取消")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { if (selectedIds.isNotEmpty()) showBatchDelete = true },
                        enabled = selectedIds.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Error),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("删除 (${selectedIds.size})")
                    }
                }
            } else {
                DrawerUserRow(
                    nickname = store.activeApiKey()?.label ?: "我",
                    onSettings = onSettings
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 顶部搜索框（官方抽屉样式：灰色胶囊 + 放大镜）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).height(48.dp)
                        .background(AppColors.Surface, RoundedCornerShape(24.dp)),
                    placeholder = {
                        Text("搜索对话内容...", color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, null, tint = AppColors.TextSecondary)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = AppColors.Surface,
                        unfocusedBorderColor = AppColors.Surface
                    )
                )
                Spacer(Modifier.width(10.dp))
                IconButton(onClick = { sortAsc = !sortAsc }) {
                    Icon(Icons.Default.Tune, contentDescription = "排序",
                        tint = AppColors.TextSecondary)
                }
            }

            // 新对话 / 批量选择工具条
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionMode) {
                    TextButton(onClick = {
                        if (selectedIds.size == filtered.size) selectedIds.clear()
                        else {
                            selectedIds.clear()
                            selectedIds.addAll(filtered.map { it.id })
                        }
                    }) { Text("全选") }
                    Text("已选 ${selectedIds.size} 项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = { selectionMode = false; selectedIds.clear() }) {
                        Text("完成")
                    }
                } else {
                    Row(modifier = Modifier.weight(1f).clickable(onClick = onNewChat),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = AppColors.Primary,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("新对话", style = MaterialTheme.typography.bodyLarge,
                            color = AppColors.Primary, fontWeight = FontWeight.Medium)
                    }
                    TextButton(onClick = { selectionMode = true; selectedIds.clear() }) {
                        Text("选择")
                    }
                }
            }

            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (query.isEmpty()) "还没有对话" else "没有找到相关对话",
                        color = AppColors.TextSecondary)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    groups.forEachIndexed { groupIndex, group ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(group.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AppColors.TextSecondary)
                                Spacer(Modifier.weight(1f))
                                if (groupIndex == 0) {
                                    Icon(Icons.Default.Sort, contentDescription = null,
                                        tint = AppColors.TextSecondary,
                                        modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        items(group.items, key = { it.id }) { session ->
                            val checked = selectedIds.contains(session.id)
                            SessionRow(
                                session = session,
                                selectionMode = selectionMode,
                                selected = checked,
                                onClick = {
                                    if (selectionMode) {
                                        if (checked) selectedIds.remove(session.id)
                                        else selectedIds.add(session.id)
                                    } else {
                                        onOpenChat(session.id, session.title)
                                    }
                                },
                                onToggle = {
                                    if (checked) selectedIds.remove(session.id)
                                    else selectedIds.add(session.id)
                                },
                                onMore = { menuSession = session }
                            )
                        }
                    }
                }
            }
        }
    }

    // 会话操作菜单
    menuSession?.let { s ->
        AlertDialog(
            onDismissRequest = { menuSession = null },
            title = { Text(s.title, maxLines = 1) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            scope.launch { repository.setPinned(s.id, !s.pinned) }
                            menuSession = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PushPin, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (s.pinned) "取消置顶" else "置顶")
                    }
                    TextButton(
                        onClick = {
                            renameTarget = s; renameText = s.title; menuSession = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("重命名")
                    }
                    TextButton(
                        onClick = { deleteTarget = s; menuSession = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, null, tint = AppColors.Error,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("删除", color = AppColors.Error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuSession = null }) { Text("取消") }
            }
        )
    }

    renameTarget?.let { s ->
        var text by remember { mutableStateOf(renameText) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) scope.launch { repository.renameSession(s.id, text) }
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    deleteTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除对话") },
            text = { Text("确定删除「${s.title}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.deleteSession(s.id) }
                    onSessionsDeleted(listOf(s.id))
                    deleteTarget = null
                }) { Text("删除", color = AppColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    if (showBatchDelete) {
        AlertDialog(
            onDismissRequest = { showBatchDelete = false },
            title = { Text("删除 ${selectedIds.size} 个对话") },
            text = { Text("将同时删除这些对话中的全部消息，此操作不可恢复。确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedIds.toList()
                    scope.launch { repository.deleteSessions(ids) }
                    onSessionsDeleted(ids)
                    selectedIds.clear()
                    selectionMode = false
                    showBatchDelete = false
                }) { Text("删除", color = AppColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DrawerUserRow(nickname: String, onSettings: () -> Unit) {
    Divider(color = AppColors.Divider)
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(AppColors.Background)
            .clickable(onClick = onSettings)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).background(AppColors.PrimaryLight),
            contentAlignment = Alignment.Center
        ) {
            Text("🐋", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(12.dp))
        Text(nickname, style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1)
        Icon(Icons.Default.MoreHoriz, contentDescription = "设置",
            tint = AppColors.TextSecondary)
    }
}

@Composable
private fun SessionRow(
    session: SessionEntity,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(6.dp))
        }
        Text(session.title, style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextPrimary, maxLines = 1,
            modifier = Modifier.weight(1f))
        if (!selectionMode) {
            if (session.pinned) {
                Icon(Icons.Default.PushPin, contentDescription = "置顶",
                    tint = AppColors.TextSecondary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
            }
            if (session.source == "imported") {
                Text("导入", style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextSecondary)
            }
            IconButton(onClick = onMore, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多",
                    tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Suppress("unused")
private fun formatDate(ts: Long): String {
    val now = System.currentTimeMillis()
    val days = (now - ts) / 86400000
    return when {
        days < 1 -> "今天"
        days < 2 -> "昨天"
        days < 7 -> "$days 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(Date(ts))
    }
}
