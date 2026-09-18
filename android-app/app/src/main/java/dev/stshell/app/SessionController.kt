// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.annotation.SuppressLint
import android.content.*
import android.net.Uri
import android.net.http.SslError
import android.os.*
import android.view.ViewGroup
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Retains the WebView and bound Node connection across Activity instances. */
@SuppressLint("SetJavaScriptEnabled")
object SessionController {
    private val main = Handler(Looper.getMainLooper())
    private var activity = WeakReference<MainActivity>(null)
    private lateinit var app: Context
    private var service: Messenger? = null
    private var bound = false
    private var intentionalStop = false
    private var retry = false
    private var restartGeneration = 0
    private var contextWrapper: MutableContextWrapper? = null
    private var web: WebView? = null
    private var browserOrigin: String? = null
    private val generation = GenerationState()
    private var observerToken = ""
    private var observerInjected = false
    private var bridgeAvailable = false
    private var activityVisible = false
    private var lastSnapshot: LauncherUiState? = null
    // Phases recur (the payload is verified before Node boots, the installed ST
    // tree afterwards), so the step shown is the furthest one reached, never the
    // raw phase index, which would visibly jump backwards mid-install.
    private var furthestStep = 0

    /** Server state and page state are tracked separately; see SessionState. */
    private var state = SessionState()

    // Background protection polls once per second and used to read + parse
    // request-activity.json on the main thread. The file is bounded, but disk
    // access stays off the UI critical path: a worker refreshes this snapshot
    // and the main thread only reads the last published immutable value.
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "SessionControllerIo").apply { isDaemon = true } }
    private val backendActivity = AtomicReference(JSONObject())
    @Volatile private var backendRefreshInFlight = false

    var fileCallback: ValueCallback<Array<Uri>>? = null

    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == StService.STATUS) {
            val data = message.data
            val phase = ServerPhase.from(data.getString("phase"))
            state = state.copy(
                serverPhase = phase,
                serverDetail = data.getString("detail") ?: "",
                progress = Progress(data.getInt("done"), data.getInt("total"), ProgressUnit.from(data.getString("unit"))),
                port = data.getInt("port"),
                serverPid = data.getInt("servicePid"),
            )
            furthestStep = if (phase.step == 0) 0 else maxOf(furthestStep, phase.step)
            if (phase == ServerPhase.READY) {
                try { openBrowser(data.getInt("port")) }
                catch (error: Exception) {
                    // A page failure never demotes the server: serverPhase stays READY.
                    state = state.copy(browserPhase = BrowserPhase.ERROR, browserDetail = error.message ?: "无法打开页面")
                }
            }
            render()
        }
        true
    })
    private var connection: ServiceConnection? = null
    private fun newConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!bound || connection !== this) return
            service = Messenger(binder)
            val owner = this
            try {
                binder.linkToDeath({ main.post { if (connection === owner && service?.binder === binder) disconnected() } }, 0)
                service?.send(Message.obtain(null, StService.CONNECT).apply { replyTo = receiver })
            } catch (_: RemoteException) { if (connection === owner) disconnected() }
        }
        override fun onServiceDisconnected(name: ComponentName) { if (connection === this) disconnected() }
        override fun onBindingDied(name: ComponentName) { if (connection === this) disconnected() }
        override fun onNullBinding(name: ComponentName) { if (connection === this) disconnected() }
    }
    fun attach(view: MainActivity) {
        app = view.applicationContext
        activity = WeakReference(view)
        contextWrapper?.baseContext = view
        val existing = web
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            view.attachBrowser(existing)
        } else if (state.browserRecoverable) {
            // Returning to a live service whose WebView was destroyed (renderer
            // loss, or the Activity was recreated after disposal): rebuild the
            // page instead of leaving a blank screen that only "restart" fixes.
            reopenBrowser()
        }
        render(force = true)
    }
    fun detach(view: MainActivity) {
        if (activity.get() === view) {
            web?.let { (it.parent as? ViewGroup)?.removeView(it) }
            contextWrapper?.baseContext = app
            activity.clear()
        }
    }
    fun start() {
        if (bound) {
            // The service is already bound. If only the page is broken, recover
            // the page rather than silently doing nothing.
            if (state.browserRecoverable) reopenBrowser()
            return
        }
        intentionalStop = false
        furthestStep = ServerPhase.CONNECTING.step
        state = SessionState(serverPhase = ServerPhase.CONNECTING, serverDetail = "连接应用内服务")
        val owner = newConnection(); connection = owner
        bound = app.bindService(Intent(app, StService::class.java), owner, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        if (!bound) {
            connection = null
            state = state.copy(serverPhase = ServerPhase.FAILED, serverDetail = "无法绑定服务")
        }
        render()
    }

    /** True when the page can be rebuilt without restarting the Node service. */
    fun canReopenBrowser() = state.browserRecoverable && service != null

    /** Rebuilds the WebView against the running service; keeps Node untouched. */
    fun reopenBrowser() {
        if (service == null || !state.serverUsable) return
        val port = state.port
        if (port !in 1024..65535) return
        try {
            disposeBrowser()
            openBrowser(port)
            // Clear ONLY the page error; the server was never in doubt.
            state = state.copy(browserPhase = BrowserPhase.LOADING, browserDetail = "")
        } catch (error: Exception) {
            state = state.copy(browserPhase = BrowserPhase.ERROR, browserDetail = error.message ?: "无法重新打开页面")
        }
        render()
    }

    /** Confirmation is owned by the Activity. Only requests a fresh installation;
     * the installer commits it under the native lock AFTER the old server exits. */
    fun redownloadSources() {
        io.execute {
            try {
                AppFiles.writeJson(File(AppFiles.state(app), "reinstall-request.json"), JSONObject().put("schemaVersion", 1))
                main.post { stop(restart = true) }
            } catch (error: Exception) {
                main.post { activity.get()?.notice(error.message ?: app.getString(R.string.action_failed)) }
            }
        }
    }

    fun stop(restart: Boolean = false) {
        restartGeneration++
        furthestStep = 0
        retry = restart; intentionalStop = true
        BackgroundProtectionService.disable(app)
        disposeBrowser()
        if (service == null) {
            releaseBinding()
            state = SessionState(serverPhase = ServerPhase.STOPPED)
            render()
            if (retry) { retry = false; start() }
            return
        }
        try { service?.send(Message.obtain(null, StService.STOP)) }
        catch (_: RemoteException) { disconnected() }
    }
    private fun releaseBinding() {
        service = null
        val previous = connection; connection = null
        if (bound) { bound = false; try { if (previous != null) app.unbindService(previous) } catch (_: Exception) { } }
    }
    private fun disconnected() {
        if (!bound && service == null) return
        releaseBinding(); disposeBrowser()
        furthestStep = 0
        BackgroundProtectionService.disable(app)
        state = SessionState(
            serverPhase = if (intentionalStop) ServerPhase.STOPPED else ServerPhase.FAILED,
            serverDetail = if (intentionalStop) "服务已停止" else "服务已退出，请重新启动",
        )
        render()
        if (retry) {
            retry = false
            val generation = restartGeneration
            main.postDelayed({ if (restartGeneration == generation) start() }, 300)
        }
    }
    private fun disposeBrowser() {
        generation.detach(); observerInjected = false; bridgeAvailable = false
        BackgroundProtectionService.refresh()
        fileCallback?.onReceiveValue(null); fileCallback = null
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        web = null; contextWrapper = null; browserOrigin = null
        state = state.copy(browserPhase = BrowserPhase.ABSENT, pageLoaded = false, dom = null, mainHttpError = null)
    }
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
        ByteArrayInputStream("External WebView network access is disabled".toByteArray()))

    private fun openBrowser(port: Int) {
        val origin = LocalOrigin(port)
        if (browserOrigin == origin.value && web != null) return
        disposeBrowser()
        val transport = AppFiles.readJson(File(AppFiles.state(app), "transport.json"), 4096)
        check(transport.getInt("port") == port)
        val user = transport.getString("username"); val password = transport.getString("password")
        val wrapper = MutableContextWrapper(activity.get() ?: app)
        contextWrapper = wrapper
        var initialAuth = true
        val view = WebView(wrapper)
        web = view; browserOrigin = origin.value
        state = state.copy(browserPhase = BrowserPhase.LOADING, browserDetail = "")
        observerToken = UUID.randomUUID().toString().replace("-", "")
        generation.reset(observerToken)
        // Query the capability once and reuse that single answer everywhere.
        bridgeAvailable = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (bridgeAvailable) {
            WebViewCompat.addWebMessageListener(view, "stShellActivity", setOf(origin.value)) { sourceView, message, sourceOrigin, isMainFrame, _ ->
                if (sourceView === web && isMainFrame && message.type == WebMessageCompat.TYPE_STRING && origin.allows(sourceOrigin.toString() + "/")) {
                    val text = message.data
                    if (text != null && generation.accept(text, SystemClock.elapsedRealtime())) {
                        BackgroundProtectionService.refresh()
                        render()
                    }
                }
            }
        }
        view.setBackgroundColor(app.getColor(R.color.launcher_window_background))
        view.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        view.isScrollbarFadingEnabled = true
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        view.settings.setSupportMultipleWindows(false)
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                if (origin.allows(request.url.toString())) null else blocked()
        })
        val navTracker = BrowserNavigationTracker()
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (web === view && origin.allows(url)) {
                    val navId = navTracker.onPageStarted()
                    observerToken = UUID.randomUUID().toString().replace("-", "")
                    observerInjected = false
                    generation.reset(observerToken)
                    state = state.copy(browserPhase = BrowserPhase.LOADING, browserDetail = "", pageLoaded = false, mainHttpError = null, dom = null, domCheck = null)
                    BackgroundProtectionService.refresh()
                }
            }
            private fun checkDom(view: WebView, navId: Long, attempt: Int = 0) {
                if (web !== view || navId != navTracker.navigationId || navTracker.mainFrameFailed) return
                view.evaluateJavascript("(function(){return {chatInput:!!document.getElementById('send_textarea'),publicApi:!!(window.SillyTavern&&window.SillyTavern.getContext),login:location.pathname==='/login'};})()") { result ->
                    if (web === view && navId == navTracker.navigationId && !navTracker.mainFrameFailed) {
                        try {
                            val json = JSONObject(result)
                            val domStatus = DomStatus(
                                chatInput = json.optBoolean("chatInput"),
                                publicApi = json.optBoolean("publicApi"),
                                login = json.optBoolean("login")
                            )
                            if (domStatus.ready) {
                                state = state.copy(browserPhase = BrowserPhase.READY, browserDetail = "", dom = domStatus, mainHttpError = null, domCheck = "ready")
                            } else {
                                state = state.copy(dom = domStatus)
                            }
                            if (domStatus.publicApi && bridgeAvailable && !observerInjected) {
                                val scriptBytes = app.assets.open("app/generation-observer.js").use { it.readBytes() }
                                val expected = app.assets.open("app-contract.json").bufferedReader().use { JSONObject(it.readText()) }
                                    .getJSONObject("inputs").getString("runtime/android/generation-observer.js")
                                check(PayloadArchive.hash(scriptBytes) == expected)
                                val config = JSONObject().put("token", observerToken).toString()
                                observerInjected = true
                                view.evaluateJavascript(String(scriptBytes, Charsets.UTF_8).replace("__ST_ANDROID_CONFIG__", config), null)
                            }
                            if (!domStatus.ready) {
                                if (attempt < DomPollSchedule.attempts) {
                                    main.postDelayed({ checkDom(view, navId, attempt + 1) }, DomPollSchedule.delay(attempt))
                                } else {
                                    state = state.copy(browserPhase = BrowserPhase.ERROR, browserDetail = "页面加载超时，聊天界面未就绪", domCheck = "timeout")
                                }
                            }
                        } catch (_: Exception) { state = state.copy(browserPhase = BrowserPhase.ERROR, browserDetail = "页面DOM检查异常", domCheck = "unavailable") }
                        render()
                    }
                }
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!origin.localDocument(url)) return blocked()
                if (request.isForMainFrame && origin.isRoot(url) && !app.getSharedPreferences(DefaultUiLanguage.PREF_FILE, Context.MODE_PRIVATE).getBoolean(DefaultUiLanguage.PREF_SEEDED, false)) {
                    app.getSharedPreferences(DefaultUiLanguage.PREF_FILE, Context.MODE_PRIVATE).edit().putBoolean(DefaultUiLanguage.PREF_SEEDED, true).apply()
                    return WebResourceResponse("text/html", "UTF-8", 200, "OK",
                        mapOf("Cache-Control" to "no-store", "Content-Type" to "text/html; charset=UTF-8"),
                        java.io.ByteArrayInputStream(DefaultUiLanguage.document(origin)))
                }
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (origin.allows(url.toString()) || url.toString() == "about:blank") return false
                if (request.isForMainFrame && request.hasGesture() && url.scheme in setOf("https", "http")) {
                    try { activity.get()?.startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) { }
                }
                return true
            }
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                // Chromium supplies host WITHOUT port here. Grant only once for
                // the native-initiated initial trusted root load. Thereafter rely
                // on Chromium's origin-scoped auth cache; cancel new challenges.
                if (initialAuth && web === view && origin.authHost(host) && realm == "SillyTavern") {
                    initialAuth = false
                    handler.proceed(user, password)
                } else {
                    handler.cancel()
                    state = state.copy(browserDetail = "认证未完成，请重新打开应用")
                    render()
                }
            }
            override fun onPageCommitVisible(view: WebView, url: String) { initialAuth = false }
            override fun onPageFinished(view: WebView, url: String) {
                initialAuth = false
                if (web === view && origin.allows(url)) {
                    if (!navTracker.canFinish(navTracker.navigationId)) return
                    val navId = navTracker.navigationId
                    state = state.copy(pageLoaded = true)
                    CookieManager.getInstance().flush()
                    checkDom(view, navId)
                    render()
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (web === view && request.isForMainFrame) {
                    val status = response.statusCode
                    state = state.copy(mainHttpError = status)
                    if (navTracker.onReceivedHttpError(true, status)) {
                        state = state.copy(browserPhase = BrowserPhase.ERROR, browserDetail = "页面返回 HTTP $status", pageLoaded = false)
                    }
                    render()
                }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                navTracker.onReceivedSslError()
                if (web === view) {
                    state = state.copy(browserPhase = BrowserPhase.ERROR, browserDetail = "SSL安全验证失败", pageLoaded = false)
                    render()
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                initialAuth = false
                if (web !== view) return true
                main.post {
                    if (web !== view) return@post
                    disposeBrowser()
                    // Page-only failure. The Node service keeps running, so the
                    // page can be rebuilt without a full service restart.
                    state = state.copy(browserPhase = BrowserPhase.ERROR,
                        browserDetail = "WebView渲染进程已退出，可重新打开页面")
                    render()
                    if (activityVisible && state.serverUsable) reopenBrowser()
                }
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (web === view && request.isForMainFrame) {
                    navTracker.onReceivedError(true)
                    state = state.copy(browserPhase = BrowserPhase.ERROR, browserDetail = "页面加载失败：${error.description}", pageLoaded = false)
                    render()
                }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val owner = activity.get()
                if (web !== view || owner == null || !origin.allows(view.url ?: "")) { callback.onReceiveValue(null); return true }
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                owner.chooseFiles(params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }
        view.setDownloadListener { _, _, _, _, _ ->
            activity.get()?.notice(app.getString(R.string.download_unsupported))
        }
        activity.get()?.attachBrowser(view)
        view.loadUrl(origin.value + "/")
    }

    /**
     * Background protection needs a live page AND a healthy server. It does NOT
     * require the page to be error-free forever: once the page recovers,
     * browserPhase returns to READY and protection becomes available again.
     */
    fun canProtect() = web != null && state.serverUsable && state.browserPhase == BrowserPhase.READY &&
        bridgeAvailable && generation.ready
    fun enableProtection() {
        check(canProtect()) { "请等待ST界面及活动观察器准备完成；不支持桥接的WebView只能前台使用" }
        check(activityVisible) { "请在前台主动开启后台保护" }
        BackgroundProtectionService.enable(app)
    }
    fun disableProtection() = BackgroundProtectionService.disable(app)
    fun activityVisibility(visible: Boolean) { activityVisible = visible; BackgroundProtectionService.refresh() }
    fun cancelGenerationFromNotification() {
        val view = web ?: return
        // A non-null trusted origin plus the live URL check is the real guard;
        // re-reading browserOrigin here added nothing on this synchronous path.
        browserOrigin ?: return
        if (LocalOrigin(state.port).allows(view.url ?: "")) {
            view.evaluateJavascript("(function(){const c=window.SillyTavern&&window.SillyTavern.getContext&&window.SillyTavern.getContext();return c&&c.stopGeneration?c.stopGeneration():false;})()", null)
        }
    }

    /** Re-reads request-activity.json off the main thread and publishes it. */
    private fun refreshBackendActivity() {
        if (backendRefreshInFlight) return
        backendRefreshInFlight = true
        val pid = state.serverPid
        io.execute {
            val value = try {
                val file = File(AppFiles.state(app), "request-activity.json")
                if (file.isFile) {
                    val parsed = AppFiles.readJson(file, 16384)
                    if (parsed.optInt("schemaVersion") == 1 && parsed.optInt("pid") == pid) parsed else JSONObject()
                } else JSONObject()
            } catch (_: Exception) { JSONObject().put("readError", true) }
            backendActivity.set(value)
            backendRefreshInFlight = false
        }
    }

    fun backgroundSnapshot(armed: Boolean): JSONObject {
        val now = SystemClock.elapsedRealtime()
        // Main thread only reads the last published snapshot; the disk read and
        // JSON parse happen on the io executor.
        val backend = backendActivity.get()
        refreshBackendActivity()
        val serverReady = service != null && state.serverUsable
        val needed = generation.wantsCpu(armed, serverReady, backend.optInt("activeGeneration"), backend.optInt("activeSave"), now)
        return JSONObject().put("serverReady", serverReady).put("cpuNeeded", needed)
            .put("activityVisible", activityVisible).put("frontend", generation.diagnostic(now)).put("backend", backend)
    }
    fun protectionChanged() { render() }
    /**
     * [force] re-delivers an unchanged snapshot, which a freshly attached
     * Activity needs; every other caller is a poll or an event that usually
     * carries no visible change, and re-rendering those costs layout passes.
     */
    private fun render(force: Boolean = false) {
        val snapshot = LauncherUiState(
            session = state,
            observer = ObserverSnapshot(
                observerReady = generation.diagnostic(SystemClock.elapsedRealtime()).optBoolean("observerReady")
            ),
            protection = ProtectionSnapshot(
                enabled = BackgroundProtectionService.running,
                wakeHeld = BackgroundProtectionService.diagnostic().optBoolean("wakeHeld")
            ),
            bridgeAvailable = bridgeAvailable,
            canReopenBrowser = canReopenBrowser(),
            step = furthestStep
        )
        if (!force && snapshot == lastSnapshot) return
        lastSnapshot = snapshot
        activity.get()?.showState(snapshot)
    }
    /** Live state only; the on-disk parts are added by [attachStateFiles]. */
    private fun diagnosticBase(): JSONObject =
        JSONObject().put("schemaVersion", 1).put("scope", "android-launcher")
            .put("ui", state.toJson())
            .put("background", BackgroundProtectionService.diagnostic())
            .put("activity", backgroundSnapshot(BackgroundProtectionService.running))

    private fun attachStateFiles(result: JSONObject): JSONObject {
        for (name in listOf("app-diagnostic.json", "node-info.json", "node-exit.json")) {
            try { val file = File(AppFiles.state(app), name); if (file.exists()) result.put(name, AppFiles.readJson(file)) } catch (_: Exception) { }
        }
        return result // No transport.json, cookies, config, chat content or automatic log export.
    }

    fun diagnostic(): JSONObject = attachStateFiles(diagnosticBase())

    /**
     * Same content as [diagnostic], with the private-file reads and the JSON
     * formatting moved off the UI thread; the callback lands on the main thread.
     */
    fun diagnosticAsync(callback: (String) -> Unit) {
        val base = diagnosticBase()
        io.execute {
            val text = try { attachStateFiles(base).toString(2) } catch (error: Exception) {
                JSONObject().put("error", error.javaClass.simpleName).toString(2)
            }
            main.post { callback(text) }
        }
    }
}
