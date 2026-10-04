package com.solarpanel.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内 APK 下载器：系统 DownloadManager 不支持代理，这里自己实现。
 * 支持 HTTP 代理（跟随 302 跳转到 objects.githubusercontent.com），
 * 带进度回调，完成后通过 FileProvider 拉起系统安装器。
 */
object ApkDownloader {

    /** 下载时手动跟随重定向的最大跳数。 */
    private const val MAX_REDIRECTS = 5

    sealed class State {
        /** [percent] 0-100，总大小未知时为 -1 */
        data class Progress(val percent: Int) : State()
        data class Done(val file: File) : State()
        data class Error(val message: String) : State()
    }

    private var running = false

    fun download(context: Context, url: String, proxy: String, callback: (State) -> Unit) {
        if (running) {
            callback(State.Error("已有下载任务进行中"))
            return
        }
        running = true
        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val file = doDownload(appContext, url, proxy) { percent ->
                    mainHandler.post { callback(State.Progress(percent)) }
                }
                mainHandler.post { callback(State.Done(file)) }
            } catch (e: Exception) {
                mainHandler.post { callback(State.Error(e.message ?: "下载失败")) }
            } finally {
                running = false
            }
        }.apply {
            isDaemon = true
            name = "ApkDownloader"
            start()
        }
    }

    private fun doDownload(
        context: Context,
        url: String,
        proxy: String,
        onProgress: (Int) -> Unit
    ): File {
        // 手动跟随重定向：github.com → objects.githubusercontent.com。
        // 每跳都 disconnect 上一个连接；收到 200 立即跳出，避免对最终地址重复发起请求。
        var current = url
        var conn: HttpURLConnection? = null
        var redirectCount = 0
        while (true) {
            val parsed = UpdateChecker.parseProxy(proxy)
            val c = if (parsed != null) {
                URL(current).openConnection(parsed) as HttpURLConnection
            } else {
                URL(current).openConnection() as HttpURLConnection
            }
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "solarpanel-android")
            when (val code = c.responseCode) {
                in 300..399 -> {
                    if (redirectCount >= MAX_REDIRECTS) {
                        c.disconnect()
                        throw RuntimeException("重定向次数过多")
                    }
                    val loc = c.getHeaderField("Location")
                    if (loc == null) {
                        c.disconnect()
                        throw RuntimeException("重定向缺少 Location")
                    }
                    c.disconnect()
                    current = loc
                    redirectCount++
                }
                HttpURLConnection.HTTP_OK -> {
                    conn = c
                    break
                }
                else -> {
                    c.disconnect()
                    throw RuntimeException("HTTP $code")
                }
            }
        }
        val finalConn = conn ?: throw RuntimeException("无法建立下载连接")

        val outFile = File(context.cacheDir, "update-${System.currentTimeMillis()}.apk")
        return try {
            val total = finalConn.contentLengthLong
            finalConn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        val percent = if (total > 0) {
                            (downloaded * 100 / total).toInt()
                        } else {
                            -1
                        }
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                    output.flush()
                }
            }
            outFile
        } catch (e: Exception) {
            outFile.delete()
            throw e
        } finally {
            finalConn.disconnect()
        }
    }

    /** 拉起系统安装器安装已下载的 APK。 */
    fun install(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
