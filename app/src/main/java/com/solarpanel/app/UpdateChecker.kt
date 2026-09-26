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
        val conn = openConnection(API_URL, proxy).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "solarpanel-android")
        }
        return try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // 把状态码带出去，便于诊断代理是否生效
                throw RuntimeException("HTTP $code")
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
            ReleaseInfo(tag, version, apkUrl, notes)
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
