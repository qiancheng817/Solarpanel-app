package com.solarpanel.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * 应用内更新检查：匿名调用 GitHub Releases 公开 API。
 * 不携带任何 Token，与浏览器直接访问效果一致，只读取公开信息。
 * 仓库地址为公开事实，不构成敏感信息泄露。
 */
object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/qiancheng817/Solarpanel-app/releases/latest"
    private const val RELEASES_LATEST_URL =
        "https://github.com/qiancheng817/Solarpanel-app/releases/latest"
    private const val RELEASE_DOWNLOAD_BASE =
        "https://github.com/qiancheng817/Solarpanel-app/releases/download"

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    sealed class Result {
        data class HasUpdate(val info: ReleaseInfo) : Result()
        object UpToDate : Result()
        data class Error(val message: String) : Result()
    }

    /** 解析 "host:port" 形式的代理配置，格式非法返回 null。 */
    fun parseProxy(proxy: String): Proxy? {
        if (proxy.isBlank()) return null
        val parts = proxy.trim().split(':')
        if (parts.size != 2) return null
        val port = parts[1].toIntOrNull() ?: return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(parts[0].trim(), port))
    }

    /**
     * 后台线程发起请求，主线程回调 [callback]。
     * [proxy] 为 Prefs.getProxy 的值，空 = 直连。
     */
    fun check(currentVersionName: String, proxy: String, callback: (Result) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val result = runCatching { fetchRelease(proxy) }
                .fold(
                    onSuccess = { release ->
                        if (release == null) {
                            Result.Error("未查询到版本信息")
                        } else if (isNewer(release.versionName, currentVersionName)) {
                            Result.HasUpdate(release)
                        } else {
                            Result.UpToDate
                        }
                    },
                    onFailure = { Result.Error(it.message ?: "网络异常") }
                )
            mainHandler.post { callback(result) }
        }.apply {
            isDaemon = true
            name = "UpdateChecker"
            start()
        }
    }

    private fun openConnection(url: String, proxy: String): HttpURLConnection {
        val parsed = parseProxy(proxy)
            ?: return URL(url).openConnection() as HttpURLConnection
        return URL(url).openConnection(parsed) as HttpURLConnection
    }

    private fun fetchRelease(proxy: String): ReleaseInfo? {
        // 优先走 API（能拿到更新说明）；API 匿名限额 60次/小时按出口 IP 计，
        // 共享代理出口常被用光返回 403，此时回退到 releases/latest 网页跳转方式。
        return try {
            fetchReleaseViaApi(proxy)
        } catch (e: ApiException) {
            fetchReleaseViaRedirect(proxy)
        }
    }

    private class ApiException(message: String) : RuntimeException(message)

    private fun fetchReleaseViaApi(proxy: String): ReleaseInfo? {
        val conn = openConnection(API_URL, proxy).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "solarpanel-android")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // 把状态码带出去，便于诊断代理是否生效
                throw ApiException("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim()
            val version = tag.removePrefix("v")
            val notes = json.optString("body").trim()
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            if (apkUrl.isEmpty()) return null
            return ReleaseInfo(tag, version, apkUrl, notes)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 兜底：releases/latest 会 302 到 releases/tag/vX.Y.Z，
     * 从 Location 解析版本号，按命名约定拼出 APK 直链（无更新说明）。
     */
    private fun fetchReleaseViaRedirect(proxy: String): ReleaseInfo {
        val conn = openConnection(RELEASES_LATEST_URL, proxy).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "solarpanel-android")
        }
        try {
            val code = conn.responseCode
            if (code !in 300..399) throw RuntimeException("HTTP $code")
            val location = conn.getHeaderField("Location")
                ?: throw RuntimeException("跳转缺少地址")
            val tag = location.substringAfterLast("/tag/", "")
            if (tag.isBlank()) throw RuntimeException("无法解析版本号")
            val version = tag.removePrefix("v")
            val apkUrl = "$RELEASE_DOWNLOAD_BASE/$tag/solarpanel-$version.apk"
            return ReleaseInfo(tag, version, apkUrl, "")
        } finally {
            conn.disconnect()
        }
    }

    /** 语义化版本比较：2.1.0 > 2.0.9。忽略非数字段，位数不同补 0。 */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(r.size, l.size)
        for (i in 0 until size) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
