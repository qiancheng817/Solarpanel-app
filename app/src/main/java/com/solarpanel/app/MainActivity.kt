package com.solarpanel.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.solarpanel.app.databinding.ActivityMainBinding
import com.solarpanel.app.databinding.ItemServerHistoryBinding
import com.solarpanel.app.databinding.SheetActionsBinding
import com.solarpanel.app.databinding.SheetServerSwitchBinding
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * solarpanel 原生安卓客户端。
 *
 * 为自托管 solarpanel 导航面板打造的原生 WebView 容器：
 * 1. 页面内的 http/https 链接一律在当前 WebView 内打开，不跳转外部浏览器；
 * 2. 首次启动要求填写服务器地址，可在顶栏菜单中随时修改；
 * 3. 允许明文 HTTP 与自签名证书，以适配局域网 / NAS 自托管环境；
 * 4. 支持后台管理面板所需的文件上传与文件下载（自动带登录 Cookie）；
 * 5. 系统返回键 / 边缘手势优先回退网页历史，根页面二次确认退出；
 * 6. 全面屏边到边：顶栏不与状态栏重叠，网页底部避开导航栏 / 手势条；
 * 7. 适配面板 PWA：清除数据时同步注销 Service Worker 并清空离线缓存。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var userAgent: String = ""

    private var mainFrameFailed = false
    private var sslWarningShown = false
    private var lastBackPressedAt = 0L

    /** 等待 Service Worker 清理完成后继续执行的回调；仅在 clearWebData 过程中非 null。 */
    private var swCleanupContinuation: Runnable? = null
    private val swCleanupHandler = Handler(Looper.getMainLooper())
    private val swCleanupTimeoutRunnable = Runnable {
        swCleanupContinuation?.let {
            swCleanupContinuation = null
            it.run()
        }
    }

    /** Android 13+ 请求通知权限的 launcher；下载完成通知依赖该权限。 */
    private var pendingDownload: Runnable? = null

    /** 顶栏实测高度（含状态栏 inset，首次布局后取得）。 */
    private var topBarHeight = 0
    private var topBarHidden = false
    private var topBarSettled = false

    /** 渲染进程崩溃自动恢复计数：同一页面短时间连续崩溃超过上限就停止自动重载。 */
    private var lastRendererCrashedUrl: String? = null
    private var rendererCrashCount = 0
    private var firstRendererCrashAt = 0L

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val task = pendingDownload
            pendingDownload = null
            if (task != null) {
                if (!granted) {
                    toast(R.string.toast_notification_denied)
                }
                task.run()
            }
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            filePathCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            filePathCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 系统闪屏只短停留：纯白底 + 无图标，交棒给文字品牌页
        val splashScreen = installSplashScreen()
        val splashStart = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition {
            SystemClock.elapsedRealtime() - splashStart < SPLASH_SYSTEM_MS
        }

        // 边到边布局：系统栏区域由各容器自行通过 inset 处理，
        // 保证顶栏不与手机状态栏重叠、网页不被导航栏永久遮挡。
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        webView = binding.webView
        setContentView(binding.root)

        // 品牌开屏：背景层全程不透明（与系统闪屏/根布局同为 app_background，
        // 交棒无色差），仅对文字组做淡入，避免整层白底动画导致的闪屏。
        // 开屏期间隐藏顶栏（GONE），防止 MaterialToolbar 首帧穿透品牌层闪现；
        // 顶栏高度在开屏结束后重新测量（见 scheduleSplashDismiss）。
        binding.topBar.isVisible = false
        binding.splashBrandContent.alpha = 0f
        binding.splashBrandContent.animate().alpha(1f).setDuration(500).start()
        scheduleSplashDismiss()

        configureInsets()
        configureWebView()
        configureIconCache()
        configureWebChromeClient()
        configureWebViewClient()
        configureDownloadListener()
        configureToolbar()
        configureTopBar()
        configureScrollHiding()
        configureBackHandling()
        configureSetupPanel()
        configureEdgeSwipe()

        val savedServer = Prefs.getServer(this)
        if (savedServer.isEmpty()) {
            showSetupPanel("")
        } else {
            loadServer(savedServer)
            // 静默检查更新：避免启动瞬间弹窗打断用户
            scheduleSilentUpdateCheck()
        }
    }

    // ------------------------------------------------------------------
    // 系统栏 inset 适配
    // ------------------------------------------------------------------

    private fun configureInsets() {
        // 白底顶栏：状态栏图标用深色（深色模式下仍用浅色图标），与顶栏融为一体
        val nightMode =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        WindowCompat.getInsetsController(window, binding.root)
            .isAppearanceLightStatusBars = nightMode != Configuration.UI_MODE_NIGHT_YES

        // 顶栏顶部补出状态栏高度：工具栏内容永远在状态栏下方
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = top)
            insets
        }

        // 配置页：四周补出系统栏（含横屏刘海 / 手势条）
        ViewCompat.setOnApplyWindowInsetsListener(binding.setupPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            insets
        }

        // 错误页：只补上下，保留左右 32dp 设计边距
        ViewCompat.setOnApplyWindowInsetsListener(binding.errorPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        // 网页底部避开三键导航栏 / 手势条
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            (webView.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom
            insets
        }
    }

    // ------------------------------------------------------------------
    // WebView 配置
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        // 页面加载前保持透明，露出根布局 app_background，
        // 与开屏品牌层同色，品牌页移除瞬间不会出现 米→白 跳变
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true

        // 关键：关闭多窗口，使 target="_blank" / window.open() 的链接
        // 留在当前 WebView 中打开，而不是弹到系统浏览器。
        settings.setSupportMultipleWindows(false)

        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.textZoom = 100 // 锁定系统字体放缩，防止面板卡片布局错乱
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 站点全部来自远程，不需要本地文件访问能力
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // desktop 模式用桌面 UA（不含 Mobile）；mobile 模式用移动 UA
        applyUserAgent()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.app_background))
        WebView.setWebContentsDebuggingEnabled(false)

        // 暴露给网页的最小接口：上报滚动方向、异步清理完成事件
        webView.addJavascriptInterface(ScrollBridge(), "SolarpanelHost")
    }

    /**
     * 根据 Prefs 中的 display_mode 设置 User-Agent。
     * desktop：桌面 UA（不含 Mobile），配合 shouldInterceptRequest 删 viewport → 桌面宽渲染
     * mobile：移动 UA（含 Mobile），不拦截 → 按 device-width 渲染
     */
    private fun applyUserAgent() {
        val mobile = Prefs.getDisplayMode(this) == "mobile"
        userAgent = if (mobile) {
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.6478.126 Mobile Safari/537.36 " +
                "SolarpanelAndroid/" + BuildConfig.VERSION_NAME
        } else {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.6478.126 Safari/537.36 " +
                "SolarpanelAndroid/" + BuildConfig.VERSION_NAME
        }
        webView.settings.userAgentString = userAgent
    }

    /**
     * 启用卡片图标原生磁盘缓存（详见 IconCache）。
     * 页面子资源由 WebViewClient.shouldInterceptRequest 拦截；面板 Service Worker
     * 发起的 fetch（favicon 多源回退、/uploads/ 的 network-first）只经过
     * ServiceWorkerClient，必须在这里单独接入同一个缓存。
     */
    private fun configureIconCache() {
        IconCache.init(this)
        try {
            // SW 回调在非 UI 线程触发，这里提前在 UI 线程取出 UA，
            // 避免回调内访问 webView（跨线程调 WebView 方法会直接崩溃）
            val swUserAgent = webView.settings.userAgentString
            ServiceWorkerController.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        return try {
                            IconCache.intercept(
                                this@MainActivity,
                                request,
                                currentPanelHost(),
                                swUserAgent,
                                fromServiceWorker = true
                            )
                        } catch (t: Throwable) {
                            null
                        }
                    }
                }
            )
            IconCache.markSwRegistration(true, null)
        } catch (t: Throwable) {
            // 极少数禁用 SW 的环境下退回仅页面侧拦截，不影响正常使用
            IconCache.markSwRegistration(false, t.javaClass.simpleName + ": " + t.message)
        }
    }

    private fun currentPanelHost(): String? =
        Prefs.getServer(this).takeIf { it.isNotEmpty() }?.let { Uri.parse(it).host }

    private fun configureWebChromeClient() {
        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress >= 100) {
                    binding.progressBar.visibility = View.GONE
                } else {
                    binding.progressBar.progress = newProgress
                    if (binding.progressBar.visibility != View.VISIBLE) {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                }
                // 加载过程中提前注入：面板 HTML 一旦解析出 head/下拉框，
                // 样式立即生效，避免首开先看到老样式、刷新才正常。
                // 三个脚本均幂等，重复调用无副作用。
                if (newProgress in 20..99) {
                    injectPanelCssFix()
                    injectGroupNav()
                    injectIconPool()
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                binding.toolbar.subtitle = if (title.isNullOrEmpty()) null else title
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                // 后台管理面板的图标 / 壁纸上传依赖这里
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooserLauncher.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    false
                }
            }
        }
    }

    private fun configureWebViewClient() {
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = handleUri(request.url)

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleUri(Uri.parse(url))

            /**
             * 网络层拦截：对外部页面（非 solarpanel 自己）的 GET 主文档 HTML，
             * 抓取后删除 <meta name="viewport">，实现 Chrome「桌面版网站」模式。
             * 渲染引擎从第一帧开始就看不到 viewport meta，用默认 ~980px 桌面宽渲染，
             * 再配合 wideViewPort + overviewMode 自动等比缩小到手机屏幕。
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // 卡片图标原生磁盘缓存（favicon 第三方源 + 面板 /uploads/），
                // 覆盖 SW 未生效/不接管的页面子资源请求
                IconCache.intercept(
                    this@MainActivity,
                    request,
                    currentPanelHost(),
                    userAgent,
                    fromServiceWorker = false
                )?.let { return it }
                if (!request.isForMainFrame) {
                    return null // 只拦主文档，子资源放行
                }
                if (Prefs.getDisplayMode(this@MainActivity) == "mobile") {
                    return null // 手机模式保留 viewport meta
                }
                if (!request.method.equals("GET", ignoreCase = true)) {
                    return null // POST 等带 body 的请求必须交给 WebView 原生处理
                }
                val uri = request.url
                val scheme = uri.scheme
                if (scheme != "http" && scheme != "https") {
                    return null
                }
                // solarpanel 自己的面板保留 viewport meta
                val server = Prefs.getServer(this@MainActivity)
                if (server.isNotEmpty()) {
                    val serverHost = Uri.parse(server).host
                    if (serverHost != null && serverHost.equals(uri.host, ignoreCase = true)) {
                        return null
                    }
                }
                return try {
                    fetchAndStripViewport(request)
                } catch (e: Exception) {
                    null // 拦截失败就让 WebView 自己加载
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                mainFrameFailed = false
                // 每次新页面加载时重置缩放状态：setInitialScale(0) 让 WebView 配合
                // overviewMode 自动计算"整页塞进屏幕"的缩放比例，桌面网页布局完整保留。
                view.setInitialScale(0)
                // 新页面开始加载时先把顶栏放出来
                applyTopBarState(hide = false, animate = true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!mainFrameFailed) {
                    hideErrorPanel()
                }
                injectPanelCssFix()
                injectGroupNav()
                injectIconPool()
                updateCloseButton()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // SPA 路由切换（pushState）不触发 onPageFinished，这里补一次
                injectPanelCssFix()
                injectGroupNav()
                injectIconPool()
                updateCloseButton()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    showErrorPanel()
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                if (ALLOW_SELF_SIGNED_CERTIFICATE) {
                    handler.proceed()
                    if (!sslWarningShown) {
                        sslWarningShown = true
                        toast(R.string.toast_ssl_warning)
                    }
                } else {
                    handler.cancel()
                }
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView,
                handler: HttpAuthHandler,
                host: String,
                realm: String
            ) {
                // 兼容 Nginx 反代上常见的 HTTP Basic 认证
                showHttpAuthDialog(handler, host, realm)
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                // 渲染进程被杀 / 崩溃时必须销毁重建 WebView 并恢复页面；
                // 返回 true 阻止应用进程跟着崩溃。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    handleRenderProcessGone(view)
                }
                return true
            }
        }
    }

    private fun configureDownloadListener() {
        webView.setDownloadListener { url, userAgentHeader, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgentHeader, contentDisposition, mimeType)
        }
    }

    // ------------------------------------------------------------------
    // 顶栏 / 底部操作面板
    // ------------------------------------------------------------------

    private fun configureToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_close -> {
                    goHome()
                    true
                }
                R.id.action_menu -> {
                    showActionsSheet()
                    true
                }
                else -> false
            }
        }
    }

    /** 打开手机习惯的底部操作面板。 */
    private fun showActionsSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetBinding = SheetActionsBinding.inflate(layoutInflater)

        // 显示模式切换项：文案 / 图标表示"将要切到的模式"
        val mobile = Prefs.getDisplayMode(this) == "mobile"
        sheetBinding.toggleIcon.setImageResource(
            if (mobile) R.drawable.ic_desktop_windows else R.drawable.ic_smartphone
        )
        sheetBinding.toggleText.setText(
            if (mobile) R.string.menu_toggle_display_to_desktop
            else R.string.menu_toggle_display_to_mobile
        )

        // 内外网切换项：仅面板页面存在 #lanBtn 时显示，副标题反映当前模式
        sheetBinding.rowLan.isVisible = false
        webView.evaluateJavascript(
            "(function(){var b=document.getElementById('lanBtn');" +
                "if(!b){return 'none';}" +
                "return localStorage.getItem('sp_lan_mode')==='lan'?'lan':'wan';})();"
        ) { result ->
            val mode = result?.removeSurrounding("\"")
            if (mode == "lan" || mode == "wan") {
                sheetBinding.lanHint.setText(
                    if (mode == "lan") R.string.menu_lan_lan_hint
                    else R.string.menu_lan_wan_hint
                )
                sheetBinding.rowLan.isVisible = true
            }
        }

        // 更新代理：检查更新/下载 APK 走该代理，副标题显示当前值
        val currentProxy = Prefs.getProxy(this)
        sheetBinding.proxyHint.text = if (currentProxy.isBlank()) {
            getString(R.string.menu_proxy_hint_direct)
        } else {
            getString(R.string.menu_proxy_hint_set, currentProxy)
        }
        sheetBinding.rowProxy.setOnClickListener {
            dialog.dismiss()
            showProxyDialog()
        }

        sheetBinding.rowRefresh.setOnClickListener {
            dialog.dismiss()
            if (webView.url != null) webView.reload()
        }
        sheetBinding.rowToggleDisplay.setOnClickListener {
            dialog.dismiss()
            toggleDisplayMode()
        }
        sheetBinding.rowLan.setOnClickListener {
            // 直接触发面板原切换逻辑（更新卡片地址模式、localStorage 与提示）
            webView.evaluateJavascript(
                "var b=document.getElementById('lanBtn');if(b){b.click();}", null
            )
            dialog.dismiss()
        }
        sheetBinding.rowChangeServer.setOnClickListener {
            dialog.dismiss()
            showServerSwitchSheet()
        }
        sheetBinding.rowClearData.setOnClickListener {
            dialog.dismiss()
            confirmClearWebData()
        }
        sheetBinding.rowOpenBrowser.setOnClickListener {
            dialog.dismiss()
            openInSystemBrowser()
        }
        sheetBinding.rowAbout.setOnClickListener {
            dialog.dismiss()
            showAboutDialog()
        }

        dialog.setContentView(sheetBinding.root)
        dialog.show()
    }

    /**
     * 顶栏（含进度条）覆盖在网页之上，网页整体被顶栏高度向下推一段，
     * 网页顶部不会被顶栏遮挡；顶栏收起时位移归零，不触发 WebView 重新布局。
     */
    private fun configureTopBar() {
        binding.topBar.doOnPreDraw {
            topBarHeight = binding.topBar.height
            applyTopBarState(hide = false, animate = false)
        }
    }

    private fun configureScrollHiding() {
        // 顶栏固定显示，不随滚动收起：不再安装触摸方向兜底监听。
        // （网页滚动仍由注入的 JS 上报，但收起请求在 applyTopBarState 被拒绝。）
    }

    /**
     * 收起或展开顶栏。当前产品决策为**顶栏固定**，因此一切 hide=true
     * 请求直接拒绝，保证 JS 上报 / 历史调用路径都无法收起顶栏。
     *
     * @param hide true 收起（被忽略），false 展开
     * @param animate 是否播放动画
     */
    private fun applyTopBarState(hide: Boolean, animate: Boolean) {
        if (hide) {
            return
        }
        if (topBarHeight <= 0) {
            return
        }
        val barTarget = if (hide) -topBarHeight.toFloat() else 0f
        val contentTarget = if (hide) 0f else topBarHeight.toFloat()

        if (!topBarSettled) {
            topBarSettled = true
            topBarHidden = hide
            binding.topBar.translationY = barTarget
            webView.translationY = contentTarget
            return
        }
        if (topBarHidden == hide) {
            return
        }
        topBarHidden = hide

        if (!animate) {
            binding.topBar.translationY = barTarget
            webView.translationY = contentTarget
            return
        }
        binding.topBar.animate().cancel()
        webView.animate().cancel()
        binding.topBar.animate()
            .translationY(barTarget)
            .setDuration(TOP_BAR_ANIM_MS)
            .start()
        webView.animate()
            .translationY(contentTarget)
            .setDuration(TOP_BAR_ANIM_MS)
            .start()
    }

    // ------------------------------------------------------------------
    // 边缘滑动手势（后退 / 退出）
    // ------------------------------------------------------------------

    private fun configureEdgeSwipe() {
        EdgeSwipeController(
            activity = this,
            zone = binding.edgeSwipeZone,
            scrim = binding.swipeScrim,
            arrow = binding.swipeArrow,
            movableViews = {
                listOf(webView, binding.topBar, binding.setupPanel, binding.errorPanel)
            },
            canGoBack = { webView.canGoBack() },
            onBack = { webView.goBack() },
            onPrepareExit = { requestExit() },
            onExit = { finish() }
        )
    }

    // ------------------------------------------------------------------
    // 渲染进程崩溃恢复
    // ------------------------------------------------------------------

    /**
     * WebView 渲染进程意外终止时的兜底恢复。
     * 已崩溃的实例不可再用，必须移除并 destroy，再新建实例恢复崩溃前 URL；
     * 同一页面短时间窗口连续崩溃超过上限则停止自动重载，显示错误页由用户手动决定。
     */
    private fun handleRenderProcessGone(dead: WebView) {
        val crashedUrl = dead.url
        val now = SystemClock.elapsedRealtime()
        if (crashedUrl == lastRendererCrashedUrl
            && now - firstRendererCrashAt < RENDERER_CRASH_WINDOW_MS
        ) {
            rendererCrashCount++
        } else {
            lastRendererCrashedUrl = crashedUrl
            rendererCrashCount = 1
            firstRendererCrashAt = now
        }

        filePathCallback = null
        recreateWebView(dead)

        if (rendererCrashCount > MAX_RENDERER_AUTO_RECOVERIES) {
            mainFrameFailed = true
            showErrorPanel()
            return
        }

        mainFrameFailed = false
        hideErrorPanel()
        if (!crashedUrl.isNullOrEmpty()) {
            webView.loadUrl(crashedUrl)
        } else {
            val server = Prefs.getServer(this)
            if (server.isNotEmpty()) {
                webView.loadUrl(server)
            } else {
                showSetupPanel("")
            }
        }
    }

    /**
     * 销毁已崩溃的 WebView 并在原位置创建全新实例，重新套用所有
     * 与 WebView 实例绑定的配置；顶栏 / 配置页 / 错误页等共享视图、
     * 菜单监听与返回键回调不受影响、不重复注册。
     */
    private fun recreateWebView(dead: WebView) {
        val parent = dead.parent as? ViewGroup
        var index = 0
        if (parent != null) {
            val oldIndex = parent.indexOfChild(dead)
            if (oldIndex >= 0) {
                index = oldIndex
            }
            parent.removeView(dead)
        }
        try {
            dead.destroy()
        } catch (ignored: Exception) {
        }

        val realParent = parent ?: findViewById<ViewGroup>(R.id.root)
        val fresh = WebView(this)
        fresh.id = R.id.webView
        val layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // 保留崩溃前底部导航栏间距
        layoutParams.bottomMargin =
            (webView.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin ?: 0
        realParent.addView(fresh, index, layoutParams)
        webView = fresh

        // 按崩溃前的顶栏状态就位
        fresh.translationY = if (topBarHeight > 0 && !topBarHidden) topBarHeight.toFloat() else 0f

        configureWebView()
        configureWebChromeClient()
        configureWebViewClient()
        configureDownloadListener()
        configureScrollHiding()
    }

    // ------------------------------------------------------------------
    // 桌面版网站：viewport 剥离
    // ------------------------------------------------------------------

    /**
     * 自己发起 HTTP 请求拿到 HTML，正则删除所有 viewport meta 后返回。
     *
     * 压缩处理（关键）：显式只协商 identity 原文，避免 Cloudflare 等 CDN 返回
     * HttpURLConnection 无法解压的 Brotli 导致文档损坏；另按魔数兜底 gzip/deflate；
     * 万一仍收到 br/zstd，放弃拦截交回 WebView 原生网络栈加载。
     */
    @Throws(IOException::class)
    private fun fetchAndStripViewport(request: WebResourceRequest): WebResourceResponse {
        val url = URL(request.url.toString())
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            conn.setRequestProperty("Accept-Encoding", "identity")

            CookieManager.getInstance().getCookie(url.toString())?.takeIf { it.isNotEmpty() }
                ?.let { conn.setRequestProperty("Cookie", it) }

            for ((key, value) in request.requestHeaders) {
                if (!key.equals("User-Agent", ignoreCase = true)
                    && !key.equals("Cookie", ignoreCase = true)
                    && !key.equals("Host", ignoreCase = true)
                    && !key.equals("Connection", ignoreCase = true)
                    && !key.equals("Accept-Encoding", ignoreCase = true)
                ) {
                    conn.setRequestProperty(key, value)
                }
            }

            val code = conn.responseCode
            val input: InputStream =
                (if (code in 200..399) conn.inputStream else conn.errorStream)
                    ?: conn.inputStream

            val raw = readDecodedBody(input, conn.contentEncoding)
            var html = String(raw, StandardCharsets.UTF_8)

            html = VIEWPORT_META_REGEX_1.replace(html, "")
            html = VIEWPORT_META_REGEX_2.replace(html, "")

            var mimeType = "text/html"
            var encoding = "utf-8"
            conn.contentType?.let { contentType ->
                val semi = contentType.indexOf(';')
                if (semi > 0) {
                    mimeType = contentType.substring(0, semi).trim()
                    val charsetPos = contentType.indexOf("charset=", semi + 1)
                    if (charsetPos >= 0) {
                        encoding = contentType.substring(charsetPos + 8).trim()
                    }
                } else {
                    mimeType = contentType.trim()
                }
            }

            // body 已重新编码，原始传输 / 编码 / 长度头必须剔除，
            // 否则 WebView 会二次解压 / 按旧长度处理导致黑屏。
            val responseHeaders = LinkedHashMap<String, String>()
            for ((headerKey, values) in conn.headerFields) {
                if (headerKey == null || values.isNullOrEmpty()) {
                    continue
                }
                if (headerKey.equals("Content-Encoding", ignoreCase = true)
                    || headerKey.equals("Content-Length", ignoreCase = true)
                    || headerKey.equals("Transfer-Encoding", ignoreCase = true)
                ) {
                    continue
                }
                responseHeaders[headerKey] = values[0]
            }

            val body = ByteArrayInputStream(html.toByteArray(StandardCharsets.UTF_8))
            return WebResourceResponse(
                mimeType, encoding, code, conn.responseMessage, responseHeaders, body
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 读取主文档响应体并按实际压缩情况解压：
     * - 声明 br/zstd 等无法解码的编码：抛异常，外层放弃拦截；
     * - gzip/zlib 按魔数判断，兼容服务器不按声明出牌；
     * - 声明 deflate 但无 zlib 头时按 raw deflate 兜底。
     */
    @Throws(IOException::class)
    private fun readDecodedBody(input: InputStream, contentEncoding: String?): ByteArray {
        val enc = contentEncoding?.trim()?.lowercase(Locale.ROOT) ?: ""
        enc.split(",").map { it.trim() }.forEach { token ->
            if (token == "br" || token == "brotli"
                || token == "zstd" || token == "zst"
                || token == "compress" || token == "x-compress"
            ) {
                throw IOException("Unsupported Content-Encoding, fallback to native load: $enc")
            }
        }

        val raw = readAllBytes(input)

        // gzip 魔数 1F 8B
        if (raw.size >= 2 && (raw[0].toInt() and 0xFF) == 0x1F
            && (raw[1].toInt() and 0xFF) == 0x8B
        ) {
            GZIPInputStream(ByteArrayInputStream(raw)).use { gis ->
                return readAllBytes(gis)
            }
        }
        // zlib 头：CM=8 且 (CMF*256+FLG) 能被 31 整除
        if (raw.size >= 2 && (raw[0].toInt() and 0x0F) == 0x08) {
            val header = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
            if (header % 31 == 0) {
                return inflateBody(raw, rawDeflate = false)
            }
        }
        if (enc == "deflate") {
            return inflateBody(raw, rawDeflate = true)
        }
        return raw
    }

    /** zlib（rawDeflate=false）或 raw deflate（rawDeflate=true）解压。 */
    @Throws(IOException::class)
    private fun inflateBody(data: ByteArray, rawDeflate: Boolean): ByteArray {
        val inflater = Inflater(rawDeflate)
        InflaterInputStream(ByteArrayInputStream(data), inflater).use { iis ->
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = iis.read(buffer)
                if (count == -1) break
                baos.write(buffer, 0, count)
            }
            inflater.end()
            return baos.toByteArray()
        }
    }

    /** 读尽输入流（readAllBytes 需 API 33，minSdk 24 不支持）。 */
    @Throws(IOException::class)
    private fun readAllBytes(input: InputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            baos.write(buffer, 0, count)
        }
        return baos.toByteArray()
    }

    // ------------------------------------------------------------------
    // JS 注入
    // ------------------------------------------------------------------

    /** 注入面板手机端适配样式，详见 PANEL_CSS_FIX_JS。 */
    private fun injectPanelCssFix() {
        webView.evaluateJavascript(PANEL_CSS_FIX_JS, null)
    }

    /**
     * 注入图标胶囊分组条，详见 GROUP_NAV_JS：
     * 一行 5 个图标胶囊、可点可滑；屏幕左右滑动切换分组；记忆当前分组，
     * 从卡片返回时恢复到该卡片所在分组。脚本自启动并等待面板数据，可重复注入。
     */
    private fun injectGroupNav() {
        webView.evaluateJavascript(GROUP_NAV_JS, null)
    }

    /**
     * 注入卡片图标池，详见 ICON_POOL_JS：
     * nav 模式每切一次分组面板都会清空重建 #groupsWrap，全新 <img> 要重新请求
     * favicon / 重新等多源回退，图标总要"加载一下"才出现。池化已解码的 img
     * 节点后，切组重建时同步换回，图标即时显示；并在后台低并发预热其余分组。
     */
    private fun injectIconPool() {
        webView.evaluateJavascript(ICON_POOL_JS, null)
    }

    /** 只有在"当前不在面板首页"时，顶栏才显示关闭按钮。 */
    private fun updateCloseButton() {
        val home = Prefs.getServer(this)
        val atHome = home.isNotEmpty() && Urls.isSamePage(webView.url, home)
        binding.toolbar.menu.findItem(R.id.action_close)?.isVisible = !atHome
    }

    /** 切换桌面 / 手机屏幕比例模式，切换后重新加载当前页使 UA 与 viewport 策略生效。 */
    private fun toggleDisplayMode() {
        val toMobile = Prefs.getDisplayMode(this) == "desktop"
        Prefs.setDisplayMode(this, if (toMobile) "mobile" else "desktop")
        applyUserAgent()
        Toast.makeText(
            this,
            if (toMobile) R.string.toast_display_mobile else R.string.toast_display_desktop,
            Toast.LENGTH_SHORT
        ).show()
        if (webView.url != null) {
            webView.reload()
        }
    }

    /** 关闭当前服务页面，回到面板首页。 */
    private fun goHome() {
        val home = Prefs.getServer(this)
        if (home.isEmpty()) {
            showSetupPanel("")
            return
        }
        if (Urls.isSamePage(webView.url, home)) {
            return
        }
        applyTopBarState(hide = false, animate = true)
        webView.loadUrl(home)
    }

    /** 暴露给网页的最小接口：PWA 缓存异步清理完成事件。 */
    private inner class ScrollBridge {

        @JavascriptInterface
        fun onSwCleanupDone() {
            runOnUiThread {
                swCleanupHandler.removeCallbacksAndMessages(null)
                val continuation = swCleanupContinuation
                swCleanupContinuation = null
                continuation?.run()
            }
        }
    }

    // ------------------------------------------------------------------
    // 链接调度
    // ------------------------------------------------------------------

    /**
     * @return true 表示已由本应用处理（不再交给 WebView），
     * false 表示交给 WebView 在当前页面内加载。
     */
    private fun handleUri(uri: Uri?): Boolean {
        if (uri == null) {
            return true
        }
        // 卡片链接可能写成 localhost / 127.0.0.1，重写成用户配置的服务器地址
        val rewritten = Urls.rewriteLocalHost(uri, Prefs.getServer(this))
        if (rewritten != null) {
            webView.loadUrl(rewritten.toString())
            return true
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false

        return when (scheme) {
            "http", "https", "about", "data", "blob", "javascript" -> false
            "intent" -> startAndroidIntent(uri)
            "tel", "mailto", "sms", "smsto", "geo", "market",
            "weixin", "alipays", "taobao" -> startExternal(uri)
            else -> startExternal(uri)
        }
    }

    private fun startExternal(uri: Uri): Boolean {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.toast_no_browser)
        }
        return true
    }

    private fun startAndroidIntent(uri: Uri): Boolean {
        try {
            val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            toast(R.string.toast_no_browser)
        }
        return true
    }

    // ------------------------------------------------------------------
    // 服务器地址
    // ------------------------------------------------------------------

    private fun configureSetupPanel() {
        binding.connectButton.setOnClickListener { applyServerInput() }
        binding.serverInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO
                || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_SEND
            ) {
                applyServerInput()
                true
            } else {
                false
            }
        }

        binding.errorRetryButton.setOnClickListener {
            hideErrorPanel()
            val server = Prefs.getServer(this)
            when {
                server.isEmpty() -> showSetupPanel("")
                webView.url != null -> webView.reload()
                else -> webView.loadUrl(server)
            }
        }
        binding.errorChangeButton.setOnClickListener {
            // 连接失败时换服务器：优先弹出历史列表供一键切换
            if (Prefs.getServerHistory(this).size > 1) {
                showServerSwitchSheet()
            } else {
                showSetupPanel(Prefs.getServer(this))
            }
        }
    }

    private fun applyServerInput() {
        val normalized = Urls.normalize(binding.serverInput.text?.toString() ?: "")
        if (normalized == null) {
            binding.serverInputLayout.error = getString(R.string.toast_url_invalid)
            return
        }
        binding.serverInputLayout.error = null
        toast(R.string.toast_url_saved)
        switchToServer(normalized)
    }

    /** 切换/保存服务器并加载：同时写入历史记录。 */
    private fun switchToServer(url: String) {
        Prefs.setServer(this, url)
        webView.stopLoading()
        webView.clearHistory()
        loadServer(url)
    }

    /** 切换服务器底部面板：列出连接过的地址供一键切换。 */
    private fun showServerSwitchSheet() {
        val dialog = BottomSheetDialog(this)
        val sheet = SheetServerSwitchBinding.inflate(layoutInflater)
        val current = Prefs.getServer(this)

        fun rebuildList() {
            val history = Prefs.getServerHistory(this)
            sheet.historyList.removeAllViews()
            sheet.emptyHint.isVisible = history.isEmpty()
            history.forEach { url ->
                val row = ItemServerHistoryBinding.inflate(
                    layoutInflater, sheet.historyList, false
                )
                row.serverUrl.text = url
                row.currentHint.isVisible = url == current
                row.root.setOnClickListener {
                    dialog.dismiss()
                    if (url != current) switchToServer(url)
                }
                row.deleteButton.setOnClickListener {
                    Prefs.removeServerFromHistory(this, url)
                    rebuildList()
                }
                sheet.historyList.addView(row.root)
            }
        }
        rebuildList()

        sheet.rowAddServer.setOnClickListener {
            dialog.dismiss()
            showSetupPanel(Prefs.getServer(this))
        }

        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun loadServer(url: String) {
        hideSetupPanel()
        hideErrorPanel()
        mainFrameFailed = false
        sslWarningShown = false
        webView.loadUrl(url)
    }

    private fun showSetupPanel(prefill: String) {
        binding.setupPanel.visibility = View.VISIBLE
        binding.errorPanel.visibility = View.GONE
        binding.serverInput.setText(prefill)
        binding.serverInput.setSelection(binding.serverInput.text?.length ?: 0)
        binding.serverInputLayout.error = null
        binding.serverInput.requestFocus()
    }

    private fun hideSetupPanel() {
        binding.setupPanel.visibility = View.GONE
        currentFocus?.let { focused ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(focused.windowToken, 0)
        }
    }

    private fun showErrorPanel() {
        binding.errorPanel.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
    }

    private fun hideErrorPanel() {
        binding.errorPanel.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    private fun enqueueDownload(
        url: String,
        userAgentHeader: String,
        contentDisposition: String?,
        mimeType: String?
    ) {
        // Android 13+ 需要 POST_NOTIFICATIONS 权限才能显示下载完成通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val state = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            )
            if (state != PackageManager.PERMISSION_GRANTED) {
                if (pendingDownload != null) {
                    return
                }
                pendingDownload = Runnable {
                    doEnqueueDownload(url, userAgentHeader, contentDisposition, mimeType)
                }
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        doEnqueueDownload(url, userAgentHeader, contentDisposition, mimeType)
    }

    private fun doEnqueueDownload(
        url: String,
        userAgentHeader: String,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            var downloadUri = Uri.parse(url)
            val serverUrl = Prefs.getServer(this)
            Urls.rewriteLocalHost(downloadUri, serverUrl)?.let {
                downloadUri = it
            }
            val finalUrl = downloadUri.toString()

            val fileName = URLUtil.guessFileName(finalUrl, contentDisposition, mimeType)

            val request = DownloadManager.Request(downloadUri)
            request.setMimeType(mimeType)
            request.setTitle(fileName)
            request.setDescription(finalUrl)
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)

            // 备份导出等接口需要登录态：DownloadManager 不共享 WebView CookieJar，手动注入；
            // 同时把面板域名 Cookie 一并带上，兼容下载重定向到另一个域名的情况。
            val cookieManager = CookieManager.getInstance()
            val cookieHeader = StringBuilder()
            val downloadCookie = cookieManager.getCookie(finalUrl)
            if (!downloadCookie.isNullOrEmpty()) {
                cookieHeader.append(downloadCookie)
            }
            if (serverUrl.isNotEmpty()) {
                val serverCookie = cookieManager.getCookie(serverUrl)
                if (!serverCookie.isNullOrEmpty() && serverCookie != downloadCookie) {
                    if (cookieHeader.isNotEmpty()) {
                        cookieHeader.append("; ")
                    }
                    cookieHeader.append(serverCookie)
                }
            }
            if (cookieHeader.isNotEmpty()) {
                request.addRequestHeader("Cookie", cookieHeader.toString())
            }
            if (!userAgentHeader.isNullOrEmpty()) {
                request.addRequestHeader("User-Agent", userAgentHeader)
            }

            try {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            } catch (e: Exception) {
                request.setDestinationInExternalFilesDir(
                    this, Environment.DIRECTORY_DOWNLOADS, fileName
                )
            }

            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: throw IllegalStateException("DownloadManager unavailable")
            manager.enqueue(request)
            toast(R.string.toast_download_start)
        } catch (e: Exception) {
            toast(R.string.toast_download_fail)
        }
    }

    // ------------------------------------------------------------------
    // 菜单动作
    // ------------------------------------------------------------------

    private fun confirmClearWebData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_data_title)
            .setMessage(R.string.clear_data_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> clearWebData() }
            .show()
    }

    private fun clearWebData() {
        // Service Worker + Cache Storage 不属于 Cookie / HTTP 缓存 / DOM 存储，
        // 先注入脚本异步清理，Promise.all 完成回调后再执行其余清理。
        swCleanupContinuation = Runnable {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()

            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()

            WebStorage.getInstance().deleteAllData()
            IconCache.clear()

            toast(R.string.toast_cleared)
            val server = Prefs.getServer(this)
            if (server.isNotEmpty()) {
                webView.loadUrl(server)
            }
        }
        webView.evaluateJavascript(PANEL_SW_CLEANUP_JS, null)

        // 3 秒超时兜底
        swCleanupHandler.postDelayed(swCleanupTimeoutRunnable, 3000L)
    }

    private fun openInSystemBrowser() {
        var url = webView.url
        if (url.isNullOrEmpty()) {
            url = Prefs.getServer(this)
        }
        if (url.isNullOrEmpty()) {
            return
        }
        startExternal(Uri.parse(url))
    }

    private fun showAboutDialog() {
        var server = Prefs.getServer(this)
        if (server.isEmpty()) {
            server = getString(R.string.about_no_server)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(
                getString(R.string.about_message, BuildConfig.VERSION_NAME, server) +
                    "\n\n" + IconCache.statsText()
            )
            .setPositiveButton(R.string.dialog_ok, null)
            .setNeutralButton(R.string.menu_check_update) { _, _ ->
                checkUpdateFromUser()
            }
            .show()
    }

    // ------------------------------------------------------------------
    // 应用内更新检查（纯匿名调用 GitHub 公开 API）
    // ------------------------------------------------------------------

    private var silentUpdateScheduled = false

    /** 冷启动后延迟执行，避免与首页加载抢资源。 */
    private fun scheduleSilentUpdateCheck() {
        if (silentUpdateScheduled) return
        silentUpdateScheduled = true
        Handler(Looper.getMainLooper()).postDelayed({
            runUpdateCheck(silent = true)
        }, 3_000)
    }

    /** 手动触发：关于页点击检查更新。 */
    private fun checkUpdateFromUser() {
        toast(R.string.toast_checking_update)
        runUpdateCheck(silent = false)
    }

    /** 更新代理设置弹窗：留空=直连，否则形如 127.0.0.1:7890。 */
    private fun showProxyDialog() {
        val input = EditText(this).apply {
            setText(Prefs.getProxy(this@MainActivity))
            hint = getString(R.string.proxy_dialog_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            val pad = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics
            ).toInt()
            setPadding(pad, 0, pad, 0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.proxy_dialog_title)
            .setMessage(R.string.proxy_dialog_message)
            .setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    Prefs.setProxy(this, "")
                    toast(R.string.toast_proxy_saved)
                } else if (UpdateChecker.parseProxy(value) != null) {
                    Prefs.setProxy(this, value)
                    toast(R.string.toast_proxy_saved)
                } else {
                    toast(R.string.toast_proxy_invalid)
                }
            }
            .show()
    }

    private fun runUpdateCheck(silent: Boolean) {
        UpdateChecker.check(BuildConfig.VERSION_NAME, Prefs.getProxy(this)) { result ->
            when (result) {
                is UpdateChecker.Result.HasUpdate ->
                    showUpdateDialog(result.info)
                UpdateChecker.Result.UpToDate -> {
                    if (!silent) toast(R.string.toast_update_latest)
                }
                is UpdateChecker.Result.Error -> {
                    if (!silent) {
                        // 显示具体原因，并提供“浏览器打开发布页”兜底
                        AlertDialog.Builder(this)
                            .setTitle(R.string.toast_update_failed)
                            .setMessage(
                                getString(R.string.toast_update_failed_detail, result.message)
                            )
                            .setNegativeButton(R.string.dialog_cancel, null)
                            .setPositiveButton(R.string.update_open_browser) { _, _ ->
                                startExternal(
                                    Uri.parse(
                                        "https://github.com/qiancheng817/Solarpanel-app/releases/latest"
                                    )
                                )
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.ReleaseInfo) {
        val notes = info.notes.ifBlank { getString(R.string.update_notes_empty) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_found_title, info.versionName))
            .setMessage(notes)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.update_download_button) { _, _ ->
                downloadAndInstall(info)
            }
            .show()
    }

    private var updateProgressDialog: AlertDialog? = null

    /** 应用内下载 APK（支持代理），带进度弹窗，完成后拉起安装器。 */
    private fun downloadAndInstall(info: UpdateChecker.ReleaseInfo) {
        val progress = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            val pad = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics
            ).toInt()
            setPadding(pad, 0, pad, 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_downloading_title, info.versionName))
            .setView(progress)
            .setCancelable(false)
            .create()
        dialog.show()
        updateProgressDialog = dialog

        ApkDownloader.download(this, info.apkUrl, Prefs.getProxy(this)) { state ->
            when (state) {
                is ApkDownloader.State.Progress -> {
                    if (state.percent >= 0) progress.progress = state.percent
                }
                is ApkDownloader.State.Done -> {
                    updateProgressDialog?.dismiss()
                    updateProgressDialog = null
                    try {
                        ApkDownloader.install(this, state.file)
                    } catch (e: Exception) {
                        toast(R.string.toast_download_fail)
                    }
                }
                is ApkDownloader.State.Error -> {
                    updateProgressDialog?.dismiss()
                    updateProgressDialog = null
                    AlertDialog.Builder(this)
                        .setTitle(R.string.toast_download_fail)
                        .setMessage(state.message)
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show()
                }
            }
        }
    }

    private fun showHttpAuthDialog(handler: HttpAuthHandler, host: String, realm: String?) {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics
        ).toInt()
        container.setPadding(padding, padding / 2, padding, 0)

        val username = EditText(this)
        username.setHint(R.string.auth_username)
        username.inputType = InputType.TYPE_CLASS_TEXT
        container.addView(
            username,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val password = EditText(this)
        password.setHint(R.string.auth_password)
        password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        container.addView(
            password,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        AlertDialog.Builder(this)
            .setTitle(if (realm.isNullOrEmpty()) host else realm)
            .setView(container)
            .setCancelable(false)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> handler.cancel() }
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                handler.proceed(username.text.toString(), password.text.toString())
            }
            .show()
    }

    // ------------------------------------------------------------------
    // 返回 / 退出
    // ------------------------------------------------------------------

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.errorPanel.visibility == View.VISIBLE) {
                    hideErrorPanel()
                    return
                }
                if (binding.setupPanel.visibility == View.VISIBLE) {
                    if (Prefs.getServer(this@MainActivity).isNotEmpty()) {
                        hideSetupPanel()
                    } else {
                        attemptExit()
                    }
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                attemptExit()
            }
        })
    }

    /**
     * 退出二次确认：首次只记录时间并提示，2 秒内再次调用返回 true。
     * 系统返回键与边缘滑动手势共用。
     */
    private fun requestExit(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastBackPressedAt < DOUBLE_BACK_INTERVAL_MS) {
            true
        } else {
            lastBackPressedAt = now
            toast(R.string.toast_exit_hint)
            false
        }
    }

    private fun attemptExit() {
        if (requestExit()) {
            finish()
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onPause() {
        super.onPause()
        if (this::webView.isInitialized) {
            webView.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) {
            webView.onResume()
        }
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        swCleanupHandler.removeCallbacksAndMessages(null)
        swCleanupContinuation = null
        pendingDownload = null
        if (this::webView.isInitialized) {
            webView.stopLoading()
            webView.removeAllViews()
            webView.removeJavascriptInterface("SolarpanelHost")
            webView.destroy()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    /** 品牌开屏：最短展示后仅淡出文字组，再隐藏品牌层并恢复顶栏（背景同色，隐藏无跳变） */
    private fun scheduleSplashDismiss() {
        Handler(Looper.getMainLooper()).postDelayed({
            binding.splashBrandContent.animate()
                .alpha(0f)
                .setDuration(SPLASH_FADE_MS)
                .withEndAction {
                    binding.splashBrand.isVisible = false
                    binding.topBar.isVisible = true
                    // 开屏期间顶栏为 GONE（不参与布局，height=0），configureTopBar
                    // 首帧测到的高度是 0，位移从未生效；恢复可见后重新测量并应用
                    binding.topBar.doOnPreDraw {
                        topBarHeight = binding.topBar.height
                        topBarSettled = false
                        applyTopBarState(hide = false, animate = false)
                    }
                }
                .start()
        }, SPLASH_BRAND_MS)
    }

    // ------------------------------------------------------------------
    // 常量
    // ------------------------------------------------------------------

    companion object {

        private const val DOUBLE_BACK_INTERVAL_MS = 2000L
        private const val TOP_BAR_ANIM_MS = 180L
        private const val SPLASH_SYSTEM_MS = 300L
        private const val SPLASH_BRAND_MS = 1600L
        private const val SPLASH_FADE_MS = 450L

        private const val MAX_RENDERER_AUTO_RECOVERIES = 2
        private const val RENDERER_CRASH_WINDOW_MS = 30_000L

        /**
         * 自托管服务常使用自签名证书，严格校验会导致整站无法访问。
         * 若使用受信任的正式证书，改为 false 可恢复严格校验。
         */
        private const val ALLOW_SELF_SIGNED_CERTIFICATE = true

        /** 电脑模式下识别并删除 viewport meta 的两种写法。 */
        private val VIEWPORT_META_REGEX_1 = Regex(
            "<meta\\s+[^>]*name\\s*=\\s*[\"']viewport[\"'][^>]*/?>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val VIEWPORT_META_REGEX_2 = Regex(
            "<meta\\s+[^>]*[\"']viewport[\"'][^>]*name\\s*=\\s*[\"'][^\"']+[\"'][^>]*/?>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        /**
         * 面板手机端适配（幂等）：
         * 1) 永久隐藏面板网页自带顶栏 .topbar（其滚动自动隐藏功能随之失效），
         *    内外网切换已挪到原生底部操作面板；
         * 2) 去掉页面顶部留白（content-pt 与时钟区上边距），收紧页面与分组边距、
         *    缩小卡片图标与字号，让服务卡片在窄屏稳定排成两列（detail 风格）
         *    或三列（app 小图标风格）；
         * 3) 补 .group-head 的 flex-wrap，避免分组标题被挤成竖排。
         */
        private val PANEL_CSS_FIX_JS =
            "(function(){" +
                "if(document.getElementById('solarpanel-css-fix')){return;}" +
                "var s=document.createElement('style');" +
                "s.id='solarpanel-css-fix';" +
                "s.textContent='" +
                ".topbar{display:none !important;}" +
                "@media (max-width:640px){" +
                ".page{padding-top:0 !important;padding-left:10px;padding-right:10px;}" +
                ".clock-area{margin:12px auto 12px !important;}" +
                ".group{padding:13px 11px 14px;margin-bottom:12px;}" +
                ".group-head{flex-wrap:wrap;gap:8px;margin-bottom:10px;}" +
                ".group-head h2{flex:0 0 auto;font-size:16px;}" +
                ".group-head .desc{min-width:0;font-size:12px;}" +
                ".cards{grid-template-columns:repeat(auto-fill,minmax(128px,1fr));gap:9px;}" +
                ".cards.style-app{grid-template-columns:repeat(auto-fill,minmax(84px,1fr));gap:8px;}" +
                ".card{padding:10px;gap:9px;border-radius:15px;}" +
                ".card .icon{width:42px;height:42px;border-radius:12px;font-size:17px;}" +
                ".card .info .t{font-size:13px;}" +
                ".card .info .d{font-size:11px;}" +
                ".card.app-card{padding:12px 6px 10px;gap:7px;}" +
                ".card.app-card .icon{width:50px;height:50px;border-radius:14px;}" +
                ".card.app-card .info .t{font-size:12px;max-width:88px;}" +
                "}';" +
                "(document.head||document.documentElement).appendChild(s);" +
                "})();"

        /**
         * 图标胶囊分组条（nav 模式手机端）：
         * - 一行 5 个；图标优先取面板 v2.1.16+ 分组图标库为每个分组配置的图标
         *   （state.groups[*].icon，option.value 即 groups 下标，不发起额外网络请求）；
         *   旧版面板 / 未配置库图标的分组依次回退：分组标题前的 emoji →
         *   标题首字色块，该分组卡片渲染后再自动升级为第一张卡片的真实图标。
         *   超过 5 个可横向滑动胶囊条查看更多。
         * - 点击胶囊切换分组；在网页区域左右滑动屏幕也可切换上/下一个分组。
         * - 当前分组写入 localStorage，页面因打开卡片而跳走、再返回时恢复到
         *   该卡片所在分组，而不是默认第一个。
         * 脚本通过 MutationObserver + 轮询等待面板异步渲染，幂等，可重复注入。
         */
        private val GROUP_NAV_JS =
            "(function(){" +
                "function spBoot(){" +
                "if(window.__spGroupNav){try{window.__spGroupNav.fix();}catch(e){}return;}" +
                "var LS_KEY='sp_nav_group_v1',EDGE=34,TH=42;" +
                "var st=document.createElement('style');" +
                "st.textContent=[" +
                "'#spGroupBar{margin:0;padding:2px 0 10px;}'," +
                "'#spGroupBar .sp-track{display:flex;gap:4px;overflow-x:auto;scroll-snap-type:x mandatory;scrollbar-width:none;-webkit-overflow-scrolling:touch;}'," +
                "'#spGroupBar .sp-track::-webkit-scrollbar{display:none;}'," +
                "'#spGroupBar .sp-cap{flex:0 0 calc((100% - 16px)/5);scroll-snap-align:center;display:flex;align-items:center;justify-content:center;}'," +
                "'#spGroupBar .sp-in{width:46px;height:46px;border-radius:999px;background:var(--surface,#fff);border:1.5px solid var(--edge,#ddd);display:flex;align-items:center;justify-content:center;overflow:hidden;box-sizing:border-box;}'," +
                "'#spGroupBar .sp-in img{width:100%;height:100%;object-fit:cover;border-radius:999px;}'," +
                "'#spGroupBar .sp-in .sp-tx{font-size:15px;font-weight:600;color:#fff;line-height:1;}'," +
                "'#spGroupBar .sp-in .sp-em{font-size:24px;line-height:1;}'," +
                "'#spGroupBar .sp-cap.active .sp-in{border-color:var(--accent,#3b82f6);border-width:2px;}'," +
                "'#navbarSelect{display:none !important;}'" +
                "].join('');" +
                "(document.head||document.documentElement).appendChild(st);" +
                "var bar=document.createElement('div');bar.id='spGroupBar';bar.style.display='none';" +
                "var track=document.createElement('div');track.className='sp-track';bar.appendChild(track);" +
                "var sel=null,restored=false,observer=null,pending=false,upgraded={};" +
                "function safe(fn){try{fn();}catch(e){}}" +
                "function leadingEmoji(title){" +
                "var m=/^\\s*(\\p{Extended_Pictographic})/u.exec(title);" +
                "if(!m){return null;}" +
                "var end=m.index+m[0].length;" +
                "while(end<title.length){" +
                "var cp=title.codePointAt(end);" +
                "if(cp===0xFE0F||cp===0x200D||(cp>=0x1F3FB&&cp<=0x1F3FF)||(cp>=0xE0020&&cp<=0xE007F)){" +
                "end+=cp>0xFFFF?2:1;" +
                "if(cp===0x200D){var m2=/^\\p{Extended_Pictographic}/u.exec(title.slice(end));" +
                "if(m2){end+=m2[0].length;}else{break;}}" +
                "}else{break;}}" +
                "return title.slice(m.index,end);}" +
                "function titleInitials(text){" +
                "var em=leadingEmoji(text);" +
                "var body=em?text.slice(em.length):text;" +
                "body=body.replace(/[\\s\\p{P}\\p{S}]/gu,'');" +
                "if(!body){body=String(text||'?');}" +
                "return Array.from(body).slice(0,2).join('');}" +
                // 面板 v2.1.16+ 分组图标库：option.value 即 state.groups 下标，
                // 直接读取该分组在图标库中配置的 icon；旧版面板无此数据时回退 null。
                "function groupIcon(opt){" +
                "try{" +
                "if(typeof state!=='undefined'&&state&&state.groups&&state.groups.length){" +
                "var g=state.groups[+opt.value];" +
                "if(g&&g.icon){return String(g.icon);}" +
                "}}" +
                "catch(e){}" +
                "return null;}" +
                "function fillCap(inEl,text,libIcon){" +
                "var em=libIcon||leadingEmoji(text);" +
                "if(em){" +
                "var es=document.createElement('span');es.className='sp-em';es.textContent=em;inEl.appendChild(es);" +
                "}else{" +
                "inEl.style.background=window.stringColor?stringColor(text||'?'):'#6b7280';" +
                "var tx=document.createElement('span');tx.className='sp-tx';tx.textContent=titleInitials(text);inEl.appendChild(tx);}}" +
                "function syncActive(val,scroll){" +
                "var caps=track.children;" +
                "for(var i=0;i<caps.length;i++){caps[i].classList.toggle('active',i===val);}" +
                "if(scroll&&caps[val]){caps[val].scrollIntoView({inline:'center',block:'nearest',behavior:'smooth'});}}" +
                "function rebuild(){" +
                "if(!sel||!sel.options||!sel.options.length){bar.style.display='none';return;}" +
                "var prevLeft=track.scrollLeft;track.innerHTML='';var sig='';" +
                "for(var i=0;i<sel.options.length;i++){(function(idx){" +
                "sig+=sel.options[idx].textContent+'|';" +
                "var cap=document.createElement('div');cap.className='sp-cap';" +
                "var inEl=document.createElement('span');inEl.className='sp-in';" +
                "fillCap(inEl,sel.options[idx].textContent,groupIcon(sel.options[idx]));cap.appendChild(inEl);" +
                "cap.addEventListener('click',function(){applySelect(idx);});" +
                "track.appendChild(cap);})(i);}" +
                "bar.setAttribute('data-sig',sig);" +
                "syncActive(+sel.value,false);track.scrollLeft=prevLeft;" +
                "if(!bar.parentNode&&sel.parentNode){sel.parentNode.insertBefore(bar,sel);}" +
                "bar.style.display='block';" +
                "scheduleUpgrade();}" +
                "function upgradeActive(){" +
                "var idx=+sel.value;if(upgraded[idx]){return;}" +
                "var cap=track.children[idx];if(!cap){return;}" +
                "var inEl=cap.querySelector('.sp-in');if(!inEl){return;}" +
                "if(inEl.querySelector('.sp-em')){upgraded[idx]=true;return;}" +
                "var icon=document.querySelector('#groupsWrap .cards .card .icon');if(!icon){return;}" +
                "var img=icon.querySelector('img');" +
                "if(img&&img.complete&&img.naturalWidth>0){" +
                "var n=document.createElement('img');n.alt='';n.src=img.src;" +
                "inEl.style.background='';inEl.innerHTML='';inEl.appendChild(n);" +
                "upgraded[idx]=true;}}" +
                "function scheduleUpgrade(){" +
                "[200,600,1200,2200].forEach(function(d){setTimeout(function(){safe(upgradeActive);},d);});}" +
                "function persist(){var idx=+sel.value;if(!sel.options[idx]){return;}" +
                "try{localStorage.setItem(LS_KEY,JSON.stringify({idx:idx,title:sel.options[idx].textContent,t:Date.now()}));}catch(e){}}" +
                "function applySelect(idx){if(!sel.options[idx]){return;}" +
                "sel.value=String(idx);sel.dispatchEvent(new Event('change',{bubbles:true}));}" +
                "function restore(){if(restored){return;}restored=true;" +
                "var s=null;try{s=JSON.parse(localStorage.getItem(LS_KEY));}catch(e){}" +
                "if(s&&typeof s.idx==='number'&&s.idx<sel.options.length&&(!s.title||sel.options[s.idx].textContent===s.title)){" +
                "if(s.idx!==(+sel.value)){applySelect(s.idx);}" +
                "else{var c=track.children[s.idx];if(c){c.scrollIntoView({inline:'center',block:'nearest'});}}}}" +
                "function ensure(){safe(function(){" +
                // 面板可能整体重建 <head> 里的内容，样式表被移除时补回去
                "if(!st.isConnected){(document.head||document.documentElement).appendChild(st);}" +
                "var cur=document.getElementById('navbarSelect');" +
                // 面板数据到达后常把 select 节点整个替换；检测到换人就重新挂监听
                "if(cur!==sel){" +
                "sel=cur;" +
                "if(observer){observer.disconnect();observer=null;}" +
                "if(sel){" +
                "observer=new MutationObserver(function(){schedule();});" +
                "observer.observe(sel,{childList:true});" +
                "sel.addEventListener('change',function(){safe(persist);syncActive(+sel.value,true);scheduleUpgrade();});" +
                "}}" +
                "if(!sel||!sel.options||!sel.options.length){if(bar.isConnected){bar.style.display='none';}return;}" +
                // 胶囊条被一起清掉、位置错乱、数量不一致，或选项文案变化
                // （图标库图标/标题在后台被修改、面板刷新数据）→ 整体重建
                "var sig='';" +
                "for(var si=0;si<sel.options.length;si++){sig+=sel.options[si].textContent+'|';}" +
                "if(!bar.isConnected||bar.parentNode!==sel.parentNode||track.children.length!==sel.options.length||sig!==bar.getAttribute('data-sig')){rebuild();}" +
                "else{bar.style.display='block';syncActive(+sel.value,false);}" +
                "if(!restored){restore();}" +
                "});}" +
                "function schedule(){if(pending){return;}pending=true;" +
                "setTimeout(function(){pending=false;ensure();},80);}" +
                "window.__spGroupNav={fix:ensure};" +
                "ensure();" +
                // 前 20 秒高频巡检（面板首屏数据/Service Worker 双渲染都在此区间），
                // 之后低频常驻：自愈开销极小，保证任何时候被面板重建都能自动恢复。
                "var fastIv=setInterval(ensure,300);" +
                "setTimeout(function(){clearInterval(fastIv);setInterval(ensure,1500);},20000);" +
                "var x0=0,y0=0,dec=null,ign=false;" +
                "document.addEventListener('touchstart',function(e){" +
                "dec=null;ign=false;" +
                "if(!sel||!sel.options.length||!bar.parentNode){ign=true;return;}" +
                "var t=e.touches[0];" +
                "if(t.clientX<EDGE||bar.contains(e.target)){ign=true;return;}" +
                "var mm=document.getElementById('iframeModal');" +
                "if(mm&&mm.classList.contains('show')){ign=true;return;}" +
                "var at=document.activeElement;" +
                "if(at&&(at.tagName==='INPUT'||at.tagName==='TEXTAREA'||at.tagName==='SELECT')){ign=true;return;}" +
                "x0=t.clientX;y0=t.clientY;},{passive:true});" +
                "document.addEventListener('touchmove',function(e){" +
                "if(ign){return;}" +
                "var t=e.touches[0],dx=t.clientX-x0,dy=t.clientY-y0;" +
                "if(dec){e.preventDefault();return;}" +
                "if(Math.abs(dx)>TH&&Math.abs(dx)>Math.abs(dy)*1.4){" +
                "dec=dx<0?1:-1;" +
                "var cur=+sel.value,n=cur+dec;" +
                "if(n>=0&&n<sel.options.length){applySelect(n);}" +
                "e.preventDefault();}},{passive:false});" +
                "document.addEventListener('touchend',function(){dec=null;},{passive:true});" +
                "document.addEventListener('touchcancel',function(){dec=null;},{passive:true});" +
                "}" +
                "spBoot();" +
                "})();"

        /**
         * 卡片图标池（幂等，可重复注入）：
         * 面板 nav 模式切换分组时 renderNavbarCardsOnly 会 wrap.innerHTML='' 重建
         * 全部卡片，新 <img> 必须重新请求 favicon（甚至重新串行回退多个第三方源），
         * 图标"加载一下"才出现。本脚本：
         * 1) MutationObserver 监听 #groupsWrap：面板销毁旧分组时，从被移除的子树中
         *    抢救已加载完成（已解码）的 <img> 节点，按卡片 data-id 池化保留；
         * 2) 新分组卡片出现时，若池里有对应节点，立即摘掉尚未加载完的新 img
         *    （同时停掉面板的多源 onerror 回退链）并换回池中节点 + has-img，
         *    已解码图片重复挂载为同步显示，切组图标零等待；拖拽排序导致的
         *    节点移除再插回也能自动补回；
         * 3) 页面数据就绪后低并发（4）后台预热未访问分组的 image / favicon 图标，
         *    首次切过去也无需等待；预热顺序从当前分组的下一组开始循环，
         *    数据到达 600ms 即启动；上限 240 个，避免对第三方 favicon 源造成突发。
         *    配合原生 IconCache（磁盘持久），冷启动预热同样近乎瞬时完成。
         */
        private val ICON_POOL_JS =
            "(function(){" +
                "function spBoot(){" +
                "if(window.__spIconPool){try{window.__spIconPool.scan();}catch(e){}return;}" +
                "var CAP=300,PRE_MAX=240,PRE_CONC=4,PRE_DELAY=600;" +
                "var pool=new Map();" +
                "var resolved=Object.create(null);" +
                "var bound=Object.create(null);" +
                "var scanT=0,wrapObs=null;" +
                "function safe(fn){try{fn();}catch(e){}}" +
                "function putPool(id,node){" +
                "if(!node){return;}" +
                "if(pool.has(id)){pool.delete(id);}" +
                "pool.set(id,node);" +
                "while(pool.size>CAP){pool.delete(pool.keys().next().value);}}" +
                // 从被移除的子树中抢救已解码 img（含拖拽排序后同节点再插回的情况）
                "function harvest(nodes){" +
                "if(!nodes||!nodes.length){return;}" +
                "for(var n=0;n<nodes.length;n++){" +
                "(function(node){" +
                "if(node.nodeType!==1){return;}" +
                "var cards=(node.matches&&node.matches('a.card[data-id]'))?[node]:" +
                "(node.querySelectorAll?node.querySelectorAll('a.card[data-id]'):[]);" +
                "for(var i=0;i<cards.length;i++){(function(card){" +
                "var id=String(card.getAttribute('data-id'));" +
                "var img=card.querySelector('.icon img');" +
                "if(img&&img.src&&img.complete&&img.naturalWidth>0){" +
                "resolved[id]=img.src;" +
                "if(img.parentNode){img.parentNode.removeChild(img);}" +
                "putPool(id,img);}" +
                "})(cards[i]);}" +
                "})(nodes[n]);}}" +
                "function cloneToPool(id,src){" +
                "if(!src||pool.has(id)){return;}" +
                "var im=new Image();" +
                "im.alt='';" +
                "try{im.referrerPolicy='no-referrer';}catch(e){}" +
                "im.onload=function(){if(im.naturalWidth>0){putPool(id,im);}};" +
                "im.src=src;}" +
                "function bindFresh(id,img){" +
                "if(bound[id]===img){return;}" +
                "bound[id]=img;" +
                "img.addEventListener('load',function(){" +
                "bound[id]=null;" +
                "if(img.naturalWidth>0&&img.src){resolved[id]=img.src;cloneToPool(id,img.src);}});" +
                "img.addEventListener('error',function(){bound[id]=null;});}" +
                "function doScan(){" +
                "var wrap=document.getElementById('groupsWrap');" +
                "if(!wrap||!wrap.querySelectorAll){return;}" +
                "var cards=wrap.querySelectorAll('a.card[data-id]');" +
                "for(var i=0;i<cards.length;i++){(function(card){" +
                "var id=String(card.getAttribute('data-id'));" +
                "var icon=card.querySelector('.icon');" +
                "if(!icon){return;}" +
                "var img=icon.querySelector('img');" +
                "if(pool.has(id)){" +
                "if(!img){icon.appendChild(pool.get(id));icon.classList.add('has-img');}" +
                "else if(!img.complete||img.naturalWidth===0){" +
                "var node=pool.get(id);" +
                "try{img.onload=null;img.onerror=null;}catch(e){}" +
                "if(img.parentNode===icon){icon.removeChild(img);}" +
                "icon.appendChild(node);icon.classList.add('has-img');" +
                "}" +
                "return;}" +
                "if(!img){return;}" +
                "if(img.complete&&img.naturalWidth>0){" +
                "resolved[id]=img.src;cloneToPool(id,img.src);" +
                "}else{bindFresh(id,img);}" +
                "})(cards[i]);}}" +
                "function scheduleScan(){" +
                "clearTimeout(scanT);" +
                "scanT=setTimeout(function(){safe(doScan);},30);}" +
                "function ensureWrap(){" +
                "var wrap=document.getElementById('groupsWrap');" +
                "if(!wrap){setTimeout(ensureWrap,400);return;}" +
                "if(wrapObs){return;}" +
                "wrapObs=new MutationObserver(function(muts){" +
                "for(var i=0;i<muts.length;i++){(function(m){safe(function(){harvest(m.removedNodes);});})(muts[i]);}" +
                "scheduleScan();});" +
                "wrapObs.observe(wrap,{childList:true,subtree:true});" +
                "doScan();}" +
                // ---- 后台预热：复用面板自身的 assetUrl / faviconSources，回退顺序与卡片一致 ----
                "function itemSrcs(item){" +
                "try{" +
                "if(item.icon_type==='image'&&item.icon_value&&typeof assetUrl==='function'){" +
                "return [assetUrl(item.icon_value)];}" +
                "if(item.icon_type==='favicon'&&(item.url||item.lan_url)&&typeof faviconSources==='function'){" +
                "return faviconSources(item.url||item.lan_url)||[];}" +
                "}catch(e){}" +
                "return [];}" +
                "var preStarted=false,preQueue=[],preIdx=0,preRunning=0;" +
                "function loadOne(job,done){" +
                "var si=0;" +
                "(function next(){" +
                "if(pool.has(job.id)||resolved[job.id]){done();return;}" +
                "if(si>=job.srcs.length){done();return;}" +
                "var url=job.srcs[si];si++;" +
                "var im=new Image();" +
                "im.alt='';" +
                "try{im.referrerPolicy='no-referrer';}catch(e){}" +
                "im.onload=function(){" +
                "if(im.naturalWidth>0){resolved[job.id]=im.src;putPool(job.id,im);done();}" +
                "else{next();}};" +
                "im.onerror=function(){next();};" +
                "im.src=url;})();}" +
                "function prePump(){" +
                "while(preRunning<PRE_CONC&&preIdx<preQueue.length&&preIdx<PRE_MAX){" +
                "preRunning++;" +
                "loadOne(preQueue[preIdx++],function(){preRunning--;prePump();});}}" +
                "function preTick(tries){" +
                "if(preStarted){return;}" +
                "var ready=false;" +
                "try{" +
                "if(typeof state!=='undefined'&&state&&state.groups&&state.groups.length){ready=true;}" +
                "}catch(e){}" +
                "if(!ready){" +
                "if(tries>0){setTimeout(function(){preTick(tries-1);},2000);}" +
                "return;}" +
                "preStarted=true;" +
                "setTimeout(function(){" +
                "safe(function(){" +
                // 冷启动后优先预热「下一个分组起」的图标（当前分组已在渲染，排最后），
                // 用户冷启动后立刻切组时目标图标最先完成解码入池
                "var preGroups;" +
                "try{" +
                "var all=(state.groups||[]);var li=JSON.parse(localStorage.getItem('sp_nav_group_v1')||'null');" +
                "var cur=(li&&typeof li.idx==='number'&&li.idx>=0&&li.idx<all.length)?li.idx:0;" +
                "preGroups=all.slice(cur+1).concat(all.slice(0,cur+1));" +
                "}catch(e){preGroups=(state.groups||[]).slice();}" +
                "preGroups.forEach(function(g){" +
                "(g.items||[]).forEach(function(it){" +
                "var id=String(it.id);" +
                "if(resolved[id]||pool.has(id)){return;}" +
                "var srcs=itemSrcs(it);" +
                "if(srcs.length){preQueue.push({id:id,srcs:srcs});}});});" +
                "prePump();});" +
                "},PRE_DELAY);}" +
                "window.__spIconPool={scan:function(){safe(doScan);}};" +
                "ensureWrap();" +
                "preTick(30);" +
                "}" +
                "spBoot();" +
                "})();"

        /**
         * 清除数据时执行：注销全部 Service Worker 注册并清空 Cache Storage，
         * 完成后回调原生层。非安全上下文下各分支自动跳过。
         */
        private val PANEL_SW_CLEANUP_JS =
            "(function(){" +
                "var pending=[];" +
                "try{" +
                "if(navigator.serviceWorker&&navigator.serviceWorker.getRegistrations){" +
                "pending.push(navigator.serviceWorker.getRegistrations().then(function(rs){" +
                "rs.forEach(function(r){try{r.unregister();}catch(e){}});" +
                "}).catch(function(){}));}" +
                "if(window.caches&&caches.keys){" +
                "pending.push(caches.keys().then(function(ks){" +
                "ks.forEach(function(k){try{caches.delete(k);}catch(e){}});" +
                "}).catch(function(){}));}" +
                "}catch(e){}" +
                "Promise.all(pending).then(function(){" +
                "try{SolarpanelHost.onSwCleanupDone();}catch(e){}" +
                "}).catch(function(){" +
                "try{SolarpanelHost.onSwCleanupDone();}catch(e){}" +
                "});" +
                "})();"
    }
}
