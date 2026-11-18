package com.deepseek.clone.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object FileHelper {

    /** 将 content:// Uri 复制到应用缓存目录，返回本地 File */
    fun copyUriToCache(context: Context, uri: Uri): File? {
        return try {
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: "upload_${System.currentTimeMillis()}"

            val safeName = fileName.replace(Regex("[^\\w.\\-]"), "_").take(120)
            val target = File(context.cacheDir, "upload_${System.currentTimeMillis()}_$safeName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
            target
        } catch (e: Exception) {
            null
        }
    }

    /** 人类可读文件大小 */
    fun readableSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
