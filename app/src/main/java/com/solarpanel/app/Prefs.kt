package com.solarpanel.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量配置存储：服务器地址与屏幕比例模式。
 *
 * 注意：文件名 / 键名与旧版客户端保持一致，
 * 覆盖安装后用户原来填写的地址自动保留。
 */
object Prefs {

    private const val FILE_NAME = "solarpanel_prefs"
    private const val KEY_SERVER = "server_url"
    private const val KEY_DISPLAY_MODE = "display_mode" // desktop / mobile

    private fun store(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getServer(context: Context): String =
        store(context).getString(KEY_SERVER, "") ?: ""

    fun setServer(context: Context, url: String) {
        store(context).edit().putString(KEY_SERVER, url).apply()
    }

    /** 桌面 / 手机屏幕比例模式，默认 desktop。 */
    fun getDisplayMode(context: Context): String {
        val value = store(context).getString(KEY_DISPLAY_MODE, "desktop")
        return if (value == "mobile") "mobile" else "desktop"
    }

    fun setDisplayMode(context: Context, mode: String) {
        store(context).edit().putString(KEY_DISPLAY_MODE, mode).apply()
    }
}
