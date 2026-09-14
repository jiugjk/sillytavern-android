// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.annotation.SuppressLint
import android.content.*
import android.net.Uri
import android.net.http.SslError
import android.os.*
import android.view.ViewGroup
import android.webkit.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference

/** Keeps one WebView + bound server connection through Activity recreation. No FGS claim. */
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
    private var state = JSONObject().put("phase", "idle")
    var fileCallback: ValueCallback<Array<Uri>>? = null
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == StService.STATUS) {
            val data = message.data
            state = JSONObject().put("phase", data.getString("phase")).put("detail", data.getString("detail"))
                .put("done", data.getInt("done")).put("total", data.getInt("total")).put("port", data.getInt("port"))
            if (state.optString("phase") == "ready") {
                try { openBrowser(data.getInt("port")) }
                catch (error: Exception) { state.put("phase", "browser-error").put("detail", error.message) }
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
        web?.let { (it.parent as? ViewGroup)?.removeView(it); view.attachBrowser(it) }
        render()
    }
    fun detach(view: MainActivity) {
        if (activity.get() === view) {
            web?.let { (it.parent as? ViewGroup)?.removeView(it) }
            contextWrapper?.baseContext = app
            activity.clear()
        }
    }
    fun start() {
        if (bound) return
        intentionalStop = false
        state = JSONObject().put("phase", "connecting").put("detail", "连接应用内服务")
        val owner = newConnection(); connection = owner
        bound = app.bindService(Intent(app, StService::class.java), owner, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        if (!bound) { connection = null; state.put("phase", "failed").put("detail", "无法绑定服务") }
        render()
    }
    fun stop(restart: Boolean = false) {
        restartGeneration++
        retry = restart; intentionalStop = true
        disposeBrowser()
        if (service == null) {
            releaseBinding()
            state = JSONObject().put("phase", "stopped")
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
        state = JSONObject().put("phase", if (intentionalStop) "stopped" else "failed")
            .put("detail", if (intentionalStop) "服务已停止" else "服务进程退出；已保存数据保留，未完成生成不会自动重试")
        render()
        if (retry) {
            retry = false
            val generation = restartGeneration
            main.postDelayed({ if (restartGeneration == generation) start() }, 300)
        }
    }
    private fun disposeBrowser() {
        fileCallback?.onReceiveValue(null); fileCallback = null
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        web = null; contextWrapper = null; browserOrigin = null
    }
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
        ByteArrayInputStream("External WebView network access is disabled in this experiment".toByteArray()))

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
        view.webViewClient = object : WebViewClient() {
            private fun checkDom(view: WebView, attempt: Int = 0) {
                if (web !== view) return
                view.evaluateJavascript("(function(){return {chatInput:!!document.getElementById('send_textarea'),publicApi:!!(window.SillyTavern&&window.SillyTavern.getContext),login:location.pathname==='/login'};})()") { result ->
                    if (web === view) {
                        try {
                            val dom = JSONObject(result); state.put("dom", dom)
                            if (dom.optBoolean("chatInput") || dom.optBoolean("login")) state.remove("mainHttpError")
                            if (!dom.optBoolean("publicApi") && !dom.optBoolean("login") && attempt < 60) main.postDelayed({ checkDom(view, attempt + 1) }, 500)
                        } catch (_: Exception) { state.put("domCheck", "unavailable") }
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
                    state.put("detail", "拒绝了额外的HTTP认证挑战；若本机认证失效，请重开实验App")
                    render()
                }
            }
            override fun onPageCommitVisible(view: WebView, url: String) { initialAuth = false }
            override fun onPageFinished(view: WebView, url: String) {
                initialAuth = false
                if (web === view && origin.allows(url)) {
                    state.put("pageLoaded", true); CookieManager.getInstance().flush()
                    checkDom(view)
                    render()
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (web === view && request.isForMainFrame) { state.put("mainHttpError", response.statusCode); render() }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel() }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                initialAuth = false
                if (web !== view) return true
                main.post {
                    if (web !== view) return@post
                    disposeBrowser()
                    state.put("phase", "browser-error").put("detail", "WebView渲染进程退出；生成可能已中断，请手动重启，不会自动重复收费请求")
                    render()
                }
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (web === view && request.isForMainFrame) {
                    state.put("phase", "browser-error").put("detail", "页面加载失败：${error.description}"); render()
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
                // Microphone/camera integration is a separate milestone.
                request.deny()
            }
        }
        view.setDownloadListener { _, _, _, _, _ ->
            activity.get()?.notice("实验版尚未实现Blob/认证下载导出；不要把此App作为数据唯一副本")
        }
        activity.get()?.attachBrowser(view)
        view.loadUrl(origin.value + "/")
    }
    private fun render() { activity.get()?.showState(JSONObject(state.toString())) }
    fun diagnostic(): JSONObject {
        val result = JSONObject().put("schemaVersion", 1).put("scope", "android-foreground-experiment")
            .put("ui", JSONObject(state.toString()))
        for (name in listOf("app-diagnostic.json", "node-info.json", "node-exit.json")) {
            try { val file = File(AppFiles.state(app), name); if (file.exists()) result.put(name, AppFiles.readJson(file)) } catch (_: Exception) { }
        }
        return result // No transport.json, cookies, config, chat content or automatic log export.
    }
}
