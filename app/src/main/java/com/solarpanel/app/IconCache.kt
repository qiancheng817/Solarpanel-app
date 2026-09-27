package com.solarpanel.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 卡片图标原生磁盘缓存（冷启动仍有效）。
 *
 * 背景：JS 图标池只存在于 V8 内存，App 冷启动后清空；Chromium HTTP 缓存受源站
 * 响应头控制（favicon.im 为 no-store，Google 源不可达），面板 Service Worker 对
 * 跨域 favicon 只能拿到 opaque 响应不入库、对 /uploads/ 又是 network-first。
 * 本缓存在原生网络层拦截图标请求，绕开以上全部限制：
 *
 * - 命中：直接返回磁盘字节并附 immutable 缓存头，无网络 RTT，SW 也能瞬间拿到 200；
 * - 未命中：原生代取一次（仅接受 image 主类型且 ≤ [MAX_ICON_BYTES]），落盘后返回；
 * - 仅拦截面板 faviconSources 的 4 个第三方源 + 面板同域 /uploads/ 图标，其余放行；
 * - 磁盘文件按 URL 的 SHA-256 命名，LRU 上限 [MAX_FILES] / [MAX_BYTES]；
 * - 两个拦截入口共用：WebViewClient（页面子资源）与 ServiceWorkerClient（SW 的 fetch）。
 *
 * shouldInterceptRequest 回调运行在非 UI 线程，允许做短时间磁盘/网络 I/O。
 */
object IconCache {

    private const val MAX_FILES = 800
    private const val MAX_BYTES = 24L * 1024 * 1024
    private const val MAX_ICON_BYTES = 256 * 1024
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 12000

    /** 面板 faviconSources() 四个源的精确 host。 */
    private val FAVICON_HOSTS = setOf(
        "favicon.cccyun.cc",
        "icon.horse",
        "favicon.im",
        "www.google.com",
    )

    @Volatile
    private var dir: File? = null
    private var writesSinceTrim = 0

    fun init(context: Context) {
        if (dir == null) {
            synchronized(this) {
                if (dir == null) {
                    dir = File(context.applicationContext.cacheDir, "sp_icon_cache")
                }
            }
        }
    }

    /**
     * 拦截入口。非图标请求或处理失败时返回 null，交回 WebView 默认网络流程，
     * 任何异常都不影响页面正常加载。
     */
    fun intercept(
        context: Context,
        request: WebResourceRequest,
        panelHost: String?,
        userAgent: String?
    ): WebResourceResponse? {
        return try {
            if (!request.method.equals("GET", ignoreCase = true)) return null
            val uri = request.url ?: return null
            if (!shouldHandle(uri, panelHost)) return null
            init(context)
            serve(uri.toString(), userAgent)
        } catch (e: Exception) {
            null
        }
    }

    private fun shouldHandle(uri: Uri, panelHost: String?): Boolean {
        val scheme = uri.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host ?: return false
        return when {
            // Google 源只拦 favicon 接口，避免误伤同域其它资源
            host == "www.google.com" -> uri.path?.startsWith("/s2/favicons") == true
            host in FAVICON_HOSTS -> true
            panelHost != null && host.equals(panelHost, ignoreCase = true) -> {
                val path = uri.path ?: return false
                path.startsWith("/uploads/") || path.startsWith("/frontend/uploads/")
            }
            else -> false
        }
    }

    private fun serve(urlString: String, userAgent: String?): WebResourceResponse? {
        val base = dir ?: return null
        if (!base.exists()) {
            base.mkdirs()
        }
        val key = sha256(urlString)
        val dataFile = File(base, key)
        val mimeFile = File(base, "$key.m")

        // 命中：回读磁盘，刷新 LRU 时间
        if (dataFile.isFile && dataFile.length() > 0) {
            val bytes = dataFile.readBytes()
            val mime = mimeFile.takeIf { it.isFile }?.readText()
                ?.takeIf { it.isNotBlank() }
                ?: guessMime(urlString)
            dataFile.setLastModified(System.currentTimeMillis())
            return buildResponse(mime, bytes)
        }

        // 未命中：原生代取
        val conn = (URL(urlString).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            if (!userAgent.isNullOrEmpty()) {
                conn.setRequestProperty("User-Agent", userAgent)
            }
            conn.setRequestProperty(
                "Accept",
                "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5"
            )
            // 同域 /uploads 可能依赖登录态，带上面板 Cookie
            CookieManager.getInstance().getCookie(urlString)
                ?.takeIf { it.isNotEmpty() }
                ?.let { conn.setRequestProperty("Cookie", it) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }
            val mime = conn.contentType?.substringBefore(';')?.trim().orEmpty()
            // 只缓存图片：favicon 源失败时常返回 HTML 错误页，必须挡掉
            if (!mime.startsWith("image/")) {
                return null
            }
            val bytes = readCapped(conn.inputStream, MAX_ICON_BYTES) ?: return null

            FileOutputStream(dataFile).use { it.write(bytes) }
            mimeFile.writeText(mime)
            writesSinceTrim++
            if (writesSinceTrim >= 10) {
                writesSinceTrim = 0
                trim(base)
            }
            return buildResponse(mime, bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildResponse(mime: String, bytes: ByteArray): WebResourceResponse {
        val headers = LinkedHashMap<String, String>(2)
        headers["Cache-Control"] = "public, max-age=31536000, immutable"
        headers["Access-Control-Allow-Origin"] = "*"
        return WebResourceResponse(
            mime,
            null,
            200,
            "OK",
            headers,
            ByteArrayInputStream(bytes)
        )
    }

    /** 读取全部字节，超过 [cap] 直接放弃（防止把异常大响应刷进缓存）。 */
    private fun readCapped(input: InputStream, cap: Int): ByteArray? {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        while (true) {
            val n = input.read(buffer)
            if (n < 0) {
                break
            }
            out.write(buffer, 0, n)
            if (out.size() > cap) {
                return null
            }
        }
        return out.toByteArray()
    }

    /** LRU 淘汰：按最后访问时间从旧到新删除，直到数量与总容量都达标。 */
    private fun trim(base: File) {
        val entries = base.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".m") }
            ?: return
        var count = entries.size
        var total = entries.sumOf { it.length() }
        if (count <= MAX_FILES && total <= MAX_BYTES) {
            return
        }
        for (file in entries.sortedBy { it.lastModified() }) {
            if (count <= MAX_FILES && total <= MAX_BYTES) {
                break
            }
            val length = file.length()
            if (file.delete()) {
                count--
                total -= length
                File(base, file.name + ".m").delete()
            }
        }
    }

    private fun guessMime(urlString: String): String {
        val path = urlString.substringBefore('?').lowercase()
        return when {
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".gif") -> "image/gif"
            else -> "image/png"
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) {
                sb.append('0')
            }
            sb.append(v.toString(16))
        }
        return sb.toString()
    }

    /** 清除数据菜单调用：清空整个图标缓存目录。 */
    fun clear() {
        val base = dir ?: return
        synchronized(this) {
            base.listFiles()?.forEach { it.delete() }
        }
    }
}
