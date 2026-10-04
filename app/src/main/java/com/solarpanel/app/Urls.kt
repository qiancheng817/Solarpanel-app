package com.solarpanel.app

import android.net.Uri
import java.util.Locale

/**
 * 服务器地址的规范化与校验。
 *
 * 允许用户只输入 "192.168.1.10:8080" 或 "panel.example.com"，
 * 此时自动补齐 http:// 前缀。
 */
object Urls {

    /**
     * @return 规范化后的地址；若无法解析为合法的 http/https 地址则返回 null。
     */
    fun normalize(rawInput: String?): String? {
        if (rawInput == null) {
            return null
        }
        var value = rawInput.trim().replace('\u3000', ' ').trim()
        if (value.isEmpty()) {
            return null
        }

        // 去掉用户可能粘进来的首尾引号
        if (value.length > 1
            && (value.startsWith('"') || value.startsWith('\''))
            && value.endsWith(value[0])
        ) {
            value = value.substring(1, value.length - 1).trim()
        }

        // 缺少协议头时补 http://
        if (!value.matches(Regex("(?i)^[a-z][a-z0-9+.\\-]*://.*"))) {
            value = "http://$value"
        }

        val uri = try {
            Uri.parse(value)
        } catch (e: Exception) {
            return null
        }

        val scheme = uri.scheme
        val host = uri.host
        if (scheme == null || host.isNullOrEmpty()) {
            return null
        }
        val normalizedScheme = scheme.lowercase(Locale.ROOT)
        if (normalizedScheme != "http" && normalizedScheme != "https") {
            return null
        }
        if (host.contains(' ')) {
            return null
        }

        // 去掉结尾多余的斜杠，避免拼接出 "//admin"
        while (value.endsWith("/")) {
            value = value.substring(0, value.length - 1)
        }
        return value
    }

    /**
     * 判断两个地址是否指向同一个页面（用于识别"当前是否还在面板首页"）。
     *
     * 比较时忽略查询串与锚点，并把末尾斜杠、默认端口统一掉，
     * 因此 http://ip:8080、http://ip:8080/、http://ip:8080/#section 会被视作同一页。
     */
    fun isSamePage(left: String?, right: String?): Boolean {
        val key = pageKey(left)
        return key != null && key == pageKey(right)
    }

    private fun pageKey(raw: String?): String? {
        if (raw.isNullOrEmpty()) {
            return null
        }
        val uri = try {
            Uri.parse(raw)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme
        val host = uri.host
        if (scheme == null || host.isNullOrEmpty()) {
            return null
        }
        val normalizedScheme = scheme.lowercase(Locale.ROOT)
        val normalizedHost = host.lowercase(Locale.ROOT)

        var port = uri.port
        if (port < 0) {
            port = if (normalizedScheme == "https") 443 else 80
        }

        var path: String = uri.path?.takeIf { it.isNotEmpty() } ?: "/"
        while (path.length > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length - 1)
        }
        return "$normalizedScheme://$normalizedHost:$port$path"
    }

    /**
     * 判断 host 是否指向本机回环地址（localhost / 127.0.0.1 / ::1）。
     * 自托管面板在反代、容器端口映射等场景下，返回的卡片链接可能写成 localhost，
     * 手机上的 WebView 去连手机自身的 localhost 当然是连不上的。
     */
    private fun isLocalHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) {
            return false
        }
        return when (host.lowercase(Locale.ROOT)) {
            "localhost", "127.0.0.1", "::1", "0.0.0.0" -> true
            else -> false
        }
    }

    /**
     * 如果 uri 的 host 是 localhost / 127.0.0.1 / ::1，把它重写成 baseUrl 的 host + port，
     * 这样自托管面板返回的回环地址卡片链接就能在手机上正确打开。
     *
     * @return 重写后的 Uri；如果不需要重写或无法重写则返回 null。
     */
    fun rewriteLocalHost(uri: Uri?, baseUrl: String?): Uri? {
        if (uri == null || baseUrl.isNullOrEmpty()) {
            return null
        }
        val scheme = uri.scheme
        val host = uri.host
        if (scheme == null || host == null) {
            return null
        }
        val normalizedScheme = scheme.lowercase(Locale.ROOT)
        if (normalizedScheme != "http" && normalizedScheme != "https") {
            return null
        }
        if (!isLocalHost(host)) {
            return null
        }

        val base = try {
            Uri.parse(baseUrl)
        } catch (e: Exception) {
            return null
        }
        val baseHost = base.host
        if (baseHost.isNullOrEmpty()) {
            return null
        }

        // port 处理：uri 自带的 port 保留，否则用 base 的 port
        var port = uri.port
        if (port < 0) {
            port = base.port
        }
        if (port < 0) {
            port = if (normalizedScheme == "https") 443 else 80
        }

        // 手动拼 URI 字符串再 parse 回去，比 Uri.Builder 更可靠
        val sb = StringBuilder()
        sb.append(normalizedScheme).append("://").append(baseHost)
        val defaultPort = (normalizedScheme == "https" && port == 443)
            || (normalizedScheme == "http" && port == 80)
        if (!defaultPort) {
            sb.append(':').append(port)
        }
        uri.path?.let { sb.append(it) }
        uri.query?.let { sb.append('?').append(it) }
        uri.fragment?.let { sb.append('#').append(it) }
        return try {
            Uri.parse(sb.toString())
        } catch (e: Exception) {
            null
        }
    }
}
