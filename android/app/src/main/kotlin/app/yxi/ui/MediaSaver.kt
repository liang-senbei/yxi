package app.yxi.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把文件**原样**存进系统相册 / 下载目录 —— 实验室的「保存到本地」用。
 *
 * 存的是**原文件字节**（流式 copy，不重新编码），所以是原画/原片，不是预览那张缩图。
 * 按 MIME 分流：图片→相册 Pictures/Yxi、视频→相册 Movies/Yxi、其它→下载 Download/Yxi，
 * 存完就在系统相册/文件管理器里能看到。
 *
 * ⚠️ 用 MediaStore 的分区存储：**Android 10+（API 29）不用任何权限**。
 * ponytail: 26–28 要动态申请 WRITE_EXTERNAL_STORAGE，用户机是 15，先只做 29+，老机明确报错不静默失败。
 */
object MediaSaver {
    /** @return 落地的 Uri。失败抛异常（消息给人看）。 */
    fun save(ctx: Context, filename: String, mime: String, src: File): Uri {
        if (Build.VERSION.SDK_INT < 29) throw RuntimeException(t("保存到本地需要 Android 10 及以上"))
        val cr = ctx.contentResolver
        val (collection, relDir) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_PICTURES}/Yxi"
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MOVIES}/Yxi"
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/Yxi"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(collection, values) ?: throw RuntimeException(t("建不了文件（存储可能满了）"))
        runCatching {
            cr.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw RuntimeException(t("写不进去"))
        }.onFailure { runCatching { cr.delete(uri, null, null) }; throw it }   // 半个文件别留在相册里
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        return uri
    }

    /** 从文件名后缀猜 MIME；猜不出就按实验室的 type 兜底，再不行 octet-stream。 */
    fun mimeOf(file: String, type: String): String {
        val ext = file.substringAfterLast('.', "").lowercase()
        // ⚠️ 常见数据/办公格式**优先用这张表** —— 各机型的 MimeTypeMap 对 xlsx/csv 之类给的不一致，
        // mime 不对下载目录里的文件系统就认不出、点了打不开对应 app。
        COMMON[ext]?.let { return it }
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (type) {
            "image" -> "image/*"; "gif" -> "image/gif"; "video" -> "video/*"
            "svg" -> "image/svg+xml"; "html" -> "text/html"; else -> "application/octet-stream"
        }
    }

    private val COMMON = mapOf(
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xls" to "application/vnd.ms-excel",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "doc" to "application/msword",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ppt" to "application/vnd.ms-powerpoint",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        // ⚠️ Android 的 MimeTypeMap **不认 apk**，不写在这儿就落成 octet-stream ——
        // 存进「下载」目录后点它，系统不知道那是个安装包，不会给「安装」的入口。
        "apk" to "application/vnd.android.package-archive",
        "json" to "application/json",
        "md" to "text/markdown",
        "txt" to "text/plain",
        "log" to "text/plain",
    )
}
