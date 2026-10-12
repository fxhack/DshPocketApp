package com.dsh.pocket

import android.app.Activity
import android.content.Intent
import android.widget.Toast

import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 从电脑上的 DSH 取文件字节并在手机上用 App 打开。
 *
 * DSH 的"打开文件"默认是宿主命令（让电脑用桌面应用打开），手机无法承接。
 * 本类改走 DSH 的字节路由 GET /api/file?path=<绝对路径>（同源 cookie 鉴权），
 * 把字节取到手机本地，再用 ACTION_VIEW 交给手机上的 App（WPS/Office/PDF 阅读器等）。
 */
class PhoneFileOpener(private val activity: Activity) {

    fun openOnPhone(absolutePath: String, displayName: String) {
        toast("正在从电脑获取「" + displayName + "」…")
        Thread {
            try {
                val url = URL(DshRepository.client.serverUrl + "/api/file?path=" + URLEncoder.encode(absolutePath, "UTF-8"))
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                val cookie = DshRepository.client.cookieHeader()
                if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty("Accept", "*/*")
                conn.connect()
                val code = conn.responseCode
                if (code != 200) {
                    conn.disconnect()
                    toast("获取失败 (HTTP " + code + ")")
                    return@Thread
                }
                val contentType = conn.contentType ?: "application/octet-stream"
                val dir = File(activity.cacheDir, "dsh_files").apply { mkdirs() }
                val safeName = sanitize(displayName)
                val out = File(dir, safeName)
                val ins = conn.inputStream
                BufferedOutputStream(FileOutputStream(out)).use { bos -> ins.copyTo(bos) }
                ins.close()
                conn.disconnect()
                toast("已获取：" + safeName + " (" + out.length() / 1024 + "KB)")
                openLocalFile(out, contentType)
            } catch (e: Exception) {
                toast("打开失败：" + (e.message ?: "未知错误"))
            }
        }.start()
    }

    private fun openLocalFile(file: File, contentType: String) {
        val rawMime = contentType.substringBefore(';').trim()
        val ext = file.name.substringAfterLast('.', "").lowercase()
        // .apk 无论服务端给什么 contentType，都强制走安装器（服务端常误报 application/zip）
        val mime = when {
            ext == "apk" -> "application/vnd.android.package-archive"
            rawMime.isEmpty() || rawMime == "application/octet-stream" -> guessMime(file.name)
            else -> rawMime
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        activity.runOnUiThread {
            try {
                val target = intent.resolveActivity(activity.packageManager)?.let { intent }
                    ?: Intent.createChooser(intent, "打开 " + file.name)
                activity.startActivity(target)
            } catch (e: Exception) {
                toast("无可用应用，请到「文件管理器」打开")
            }
        }
    }

    private fun sanitize(name: String): String {
        val sb = StringBuilder()
        for (ch in name) {
            val code = ch.code
            val keep = ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || (code in 0x4e00..0x9fa5)
            sb.append(if (keep) ch else '_')
        }
        val s = sb.toString()
        return s.ifEmpty { "dsh_file" }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "md", "csv", "log", "json", "xml", "yaml", "yml" -> "text/plain"
            "html", "htm" -> "text/html"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "gz" -> "application/gzip"
            "apk" -> "application/vnd.android.package-archive"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    private fun toast(msg: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
