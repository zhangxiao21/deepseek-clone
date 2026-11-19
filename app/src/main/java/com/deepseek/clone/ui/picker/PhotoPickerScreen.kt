package com.deepseek.clone.ui.picker

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.deepseek.clone.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次最多选择的图片数（与官方客户端一致） */
const val MAX_IMAGE_SELECTION = 9

/** 相册文件夹：名称 + 该文件夹下的图片列表（按时间倒序） */
data class ImageFolder(val name: String, val images: List<Uri>)

private fun readImagesPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasImagesPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, readImagesPermission()) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * 从 MediaStore 读取全部图片，按相册文件夹分组。
 * 返回按「最近更新文件夹在前、文件夹内最新图片在前」排序的分组列表。
 */
private suspend fun loadImagesByFolder(context: Context): List<ImageFolder> =
    withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        // 用 LinkedHashMap 保证文件夹首次出现的顺序（即最新图片所在文件夹在前）
        val folders = LinkedHashMap<String, MutableList<Pair<Long, Uri>>>()
        try {
            context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val bucket = cursor.getString(bucketCol) ?: "其他"
                    val date = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val uri = ContentUris.withAppendedId(collection, id)
                    folders.getOrPut(bucket) { mutableListOf() }
                        .add(date to uri)
                }
            }
        } catch (_: Exception) { /* 无权限或厂商 ROM 异常时返回空列表 */ }

        folders.map { (name, items) ->
            // 每个文件夹内已按 DATE_ADDED DESC 排序（因为全局查询就是倒序），无需再排
            ImageFolder(name, items.map { it.second })
        }
    }

/** 把所有文件夹的图片摊平成一张列表（用于「最近图片」横条） */
private suspend fun loadRecentImages(context: Context, limit: Int = 10): List<Uri> =
    loadImagesByFolder(context).flatMap { it.images }.take(limit)

/**
 * 应用内图片选择器：不再拉起系统文件/图库界面，直接在 App 里以网格展示相册，
 * 支持一次勾选多张，确认后统一走 Files API 上传。
 */
@Composable
fun PhotoPickerScreen(
    onCancel: () -> Unit,
    onConfirm: (List<Uri>) -> Unit,
    maxSelection: Int = MAX_IMAGE_SELECTION
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasImagesPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    val folders by produceState(initialValue = emptyList<ImageFolder>(), key1 = granted) {
        value = if (granted) loadImagesByFolder(context) else emptyList()
    }
    val selected = remember { mutableStateListOf<Uri>() }

    Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = AppColors.TextPrimary)
                }
                Text(
                    if (selected.isEmpty()) "选择图片" else "已选择 ${selected.size}/$maxSelection",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = { onConfirm(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
                ) {
                    Text("完成${if (selected.isEmpty()) "" else " (${selected.size})"}")
                }
            }
            Divider(color = AppColors.Divider)

            when {
                !granted -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Image, null,
                                tint = AppColors.TextSecondary, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("需要相册权限才能选择图片",
                                color = AppColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { permissionLauncher.launch(readImagesPermission()) },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)) {
                                Text("授予权限")
                            }
                        }
                    }
                }
                folders.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("相册里还没有图片", color = AppColors.TextSecondary)
                    }
                }
                else -> {
                    // 按文件夹分组展示：每个文件夹一个标题 + 三列图片网格
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        folders.forEach { folder ->
                            item(key = "folder_${folder.name}") {
                                Text(
                                    "${folder.name}（${folder.images.size}）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AppColors.TextSecondary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(AppColors.Surface)
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                            items(folder.images.chunked(3), key = { "row_${folder.name}_${it.first()}" }) { row ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    row.forEach { uri ->
                                        val index = selected.indexOf(uri)
                                        PhotoCell(
                                            uri = uri,
                                            order = if (index >= 0) index + 1 else 0,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                if (index >= 0) selected.remove(uri)
                                                else if (selected.size < maxSelection) selected.add(uri)
                                            }
                                        )
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    Text(
                        "最多可选 $maxSelection 张 · 点选图片可取消",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 「+」面板顶部的最近图片横条：有权限时直接展示相册缩略图，点一下即作为附件上传。
 * 没有权限时静默隐藏（完整相册页会负责申请权限）。
 */
@Composable
fun RecentImagesStrip(onPick: (Uri) -> Unit) {
    val context = LocalContext.current
    val granted by remember { mutableStateOf(hasImagesPermission(context)) }
    val images by produceState(initialValue = emptyList<Uri>(), key1 = granted) {
        value = if (granted) loadRecentImages(context, limit = 10) else emptyList()
    }
    if (images.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(images) { uri ->
            AsyncImage(
                model = ImageRequest.Builder(context).data(uri).size(240).crossfade(true).build(),
                contentDescription = "最近图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 108.dp, height = 82.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.SurfaceVariant)
                    .clickable { onPick(uri) }
            )
        }
    }
}

@Composable
private fun PhotoCell(
    uri: Uri,
    order: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.SurfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(uri)
                .size(256)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (order > 0) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(AppColors.Primary.copy(alpha = 0.18f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(AppColors.Primary)
                    .border(BorderStroke(1.dp, Color.White), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (order == 1) {
                    Icon(Icons.Default.Check, null, tint = Color.White,
                        modifier = Modifier.size(14.dp))
                } else {
                    Text("$order", color = Color.White,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.9f)), CircleShape)
            )
        }
    }
}
