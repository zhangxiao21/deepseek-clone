package com.deepseek.clone.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object FileHelper {

    /**
     * 将 content:// Uri 复制到应用缓存目录，返回本地 File。
     * @param tag 批量上传时的序号，避免同一毫秒内多张图片文件名撞车
     * @param defaultExt 某些 MediaStore Uri 取不到 DISPLAY_NAME 时补的扩展名（图片传 ".jpg"）
     */
    fun copyUriToCache(
        context: Context,
        uri: Uri,
        tag: String = "",
        defaultExt: String = ""
    ): File? {
        return try {
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: "upload_${System.currentTimeMillis()}$defaultExt"

            val withExt = if (!fileName.contains('.') && defaultExt.isNotEmpty()) {
                fileName + defaultExt
            } else fileName
            val safeName = withExt.replace(Regex("[^\\w.\\-]"), "_").take(120)
            val target = File(
                context.cacheDir,
                "upload_${System.currentTimeMillis()}_${tag}_$safeName"
            )
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
