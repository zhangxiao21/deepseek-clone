package com.deepseek.clone.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepseek.clone.R
import com.deepseek.clone.data.local.SessionEntity
import com.deepseek.clone.data.repository.Repository
import com.deepseek.clone.ui.theme.AppColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    repository: Repository,
    onOpenChat: (String, String) -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit
) {
    val sessions by repository.observeSessions().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var menuSession by remember { mutableStateOf<SessionEntity?>(null) }
    var renameTarget by remember { mutableStateOf<SessionEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SessionEntity?>(null) }
    val scope = rememberCoroutineScope()

    val filtered = sessions.filter { it.title.contains(query, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = AppColors.Primary,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    actionIconContentColor = androidx.compose.ui.graphics.Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewChat, containerColor = AppColors.Primary) {
                Icon(Icons.Default.Add, contentDescription = "新对话",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_sessions)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
            )

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💬", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.no_sessions), color = AppColors.TextSecondary)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(filtered, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            onClick = { onOpenChat(session.id, session.title) },
                            onMore = { menuSession = session }
                        )
                        Divider(color = AppColors.Divider)
                    }
                }
            }
        }
    }

    // 会话操作菜单（Material3 1.0.0 无 ModalBottomSheet / 新版 ListItem，改用 AlertDialog）
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
                        Text(stringResource(R.string.rename))
                    }
                    TextButton(
                        onClick = { deleteTarget = s; menuSession = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, null, tint = AppColors.Error,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.delete), color = AppColors.Error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuSession = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 重命名对话框
    renameTarget?.let { s ->
        var text by remember { mutableStateOf(renameText) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) scope.launch { repository.renameSession(s.id, text) }
                    renameTarget = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 删除确认
    deleteTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_session_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.deleteSession(s.id) }
                    deleteTarget = null
                }) { Text(stringResource(R.string.delete), color = AppColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun SessionRow(session: SessionEntity, onClick: () -> Unit, onMore: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.ChatBubbleOutline, contentDescription = null,
            tint = AppColors.Primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(session.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(formatDate(session.updatedAt), style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextSecondary)
        }
        if (session.pinned) {
            Icon(Icons.Default.PushPin, contentDescription = "置顶",
                tint = AppColors.Primary, modifier = Modifier.size(16.dp))
        }
        if (session.source == "imported") {
            Text("导入", style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
        }
        IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多",
                tint = AppColors.TextSecondary)
        }
    }
}

private fun formatDate(ts: Long): String {
    val now = System.currentTimeMillis()
    val days = (now - ts) / 86400000
    return when {
        days < 1 -> "今天"
        days < 2 -> "昨天"
        days < 7 -> "$days 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
}
