// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.json.JSONObject

/**
 * Typed launcher state.
 *
 * The Node service and the WebView page have independent lifecycles and are
 * tracked separately: a dead renderer does not mean the server is unusable, and
 * a page-load error must not make the service look "not ready". Only the
 * Messenger / on-disk / diagnostic boundaries convert these to JSON.
 */

/** Lifecycle of the bundled Node service, as reported by StService. */
enum class ServerPhase(val id: String) {
    IDLE("idle"),
    CONNECTING("connecting"),
    PREPARING("preparing"),
    COPYING("copying"),
    UNPACKING("unpacking"),
    VERIFYING("verifying"),
    DOWNLOADING("downloading"),
    DEPENDENCIES("dependencies"),
    STARTING("starting"),
    CHECKING("checking"),
    READY("ready"),
    STOPPING("stopping"),
    STOPPED("stopped"),
    FAILED("failed");

    /** True while startup is in progress and a progress indicator is meaningful. */
    val busy: Boolean
        get() = this == CONNECTING || this == PREPARING || this == COPYING ||
            this == UNPACKING || this == VERIFYING || this == STARTING || this == CHECKING ||
            this == DOWNLOADING || this == DEPENDENCIES

    companion object {
        fun from(id: String?): ServerPhase = entries.firstOrNull { it.id == id } ?: IDLE
    }
}

/** Lifecycle of the embedded WebView page. */
enum class BrowserPhase(val id: String) {
    /** No WebView exists (not started yet, or disposed). */
    ABSENT("absent"),
    /** A WebView exists and is loading the trusted local origin. */
    LOADING("loading"),
    /** The page finished loading on the trusted origin. */
    READY("ready"),
    /** The renderer died or the main frame failed to load. Recoverable. */
    ERROR("browser-error");

    companion object {
        fun from(id: String?): BrowserPhase = entries.firstOrNull { it.id == id } ?: ABSENT
    }
}

/** Unpacking/verifying progress, as counted items. */
data class Progress(val done: Int = 0, val total: Int = 0) {
    val known: Boolean get() = total > 0
}

/** Immutable snapshot of DOM availability flags extracted from the WebView. */
data class DomStatus(
    val chatInput: Boolean = false,
    val publicApi: Boolean = false,
    val login: Boolean = false,
) {
    val ready: Boolean get() = chatInput || login
}

/** Immutable snapshot of the launcher's own state; safe to hand to the UI. */
data class SessionState(
    val serverPhase: ServerPhase = ServerPhase.IDLE,
    val serverDetail: String = "",
    val browserPhase: BrowserPhase = BrowserPhase.ABSENT,
    val browserDetail: String = "",
    val progress: Progress = Progress(),
    val port: Int = 0,
    val serverPid: Int = 0,
    val pageLoaded: Boolean = false,
    val mainHttpError: Int? = null,
    val domCheck: String? = null,
    val dom: DomStatus? = null,
) {
    /**
     * Phase shown to the user. A browser error is only surfaced once the server
     * itself is healthy; otherwise the server problem is the actionable one.
     */
    val displayPhase: String
        get() = if (serverPhase == ServerPhase.READY && browserPhase == BrowserPhase.ERROR) {
            BrowserPhase.ERROR.id
        } else {
            serverPhase.id
        }

    val displayDetail: String
        get() = if (serverPhase == ServerPhase.READY && browserPhase == BrowserPhase.ERROR) browserDetail else serverDetail

    /** The Node service is usable, regardless of what the page is doing. */
    val serverUsable: Boolean get() = serverPhase == ServerPhase.READY

    /** The page can be rebuilt without restarting the whole service. */
    val browserRecoverable: Boolean
        get() = serverUsable && (browserPhase == BrowserPhase.ERROR || browserPhase == BrowserPhase.ABSENT)

    fun toJson(): JSONObject = JSONObject()
        .put("phase", displayPhase)
        .put("detail", displayDetail)
        .put("serverPhase", serverPhase.id)
        .put("serverDetail", serverDetail)
        .put("browserPhase", browserPhase.id)
        .put("browserDetail", browserDetail)
        .put("done", progress.done)
        .put("total", progress.total)
        .put("port", port)
        .put("pageLoaded", pageLoaded)
        .apply {
            mainHttpError?.let { put("mainHttpError", it) }
            domCheck?.let { put("domCheck", it) }
            dom?.let {
                put("dom", JSONObject().put("chatInput", it.chatInput).put("publicApi", it.publicApi).put("login", it.login))
            }
        }
}

data class ProtectionSnapshot(
    val enabled: Boolean = false,
    val wakeHeld: Boolean = false,
)

data class ObserverSnapshot(
    val observerReady: Boolean = false,
)

/** Fully-typed UI model delivered to MainActivity, avoiding untyped JSON round-trips. */
data class LauncherUiState(
    val session: SessionState = SessionState(),
    val observer: ObserverSnapshot = ObserverSnapshot(),
    val protection: ProtectionSnapshot = ProtectionSnapshot(),
    val bridgeAvailable: Boolean = false,
    val canReopenBrowser: Boolean = false,
) {
    val displayPhase: String get() = session.displayPhase
    val displayDetail: String get() = session.displayDetail
    val progress: Progress get() = session.progress
}

/**
 * Manages the state and sequence of WebView page navigations, protecting
 * against race conditions where onPageFinished or stale callbacks overwrite
 * main-frame errors.
 */
class BrowserNavigationTracker {
    var navigationId: Long = 0L
        private set
    var mainFrameFailed: Boolean = false
        private set

    fun onPageStarted(): Long {
        mainFrameFailed = false
        return ++navigationId
    }

    fun onReceivedError(isMainFrame: Boolean): Boolean {
        if (isMainFrame) {
            mainFrameFailed = true
            return true
        }
        return false
    }

    fun onReceivedHttpError(isMainFrame: Boolean, statusCode: Int): Boolean {
        if (isMainFrame && statusCode >= 400) {
            mainFrameFailed = true
            return true
        }
        return false
    }

    fun onReceivedSslError(): Boolean {
        mainFrameFailed = true
        return true
    }

    fun canFinish(navId: Long): Boolean {
        return !mainFrameFailed && navId == navigationId
    }
}

/**
 * State machine managing StService phase transitions on the main thread.
 * Guarantees that asynchronous events from background threads cannot regress
 * phases (e.g. stale progress cannot overwrite READY, STOPPING, or CHECKING).
 */
class ServiceStateMachine(
    var phase: String = "idle",
    var detail: String = "",
    var done: Int = 0,
    var total: Int = 0,
    var currentSession: Long = 0L
) {
    fun transition(
        session: Long,
        newPhase: String,
        message: String = "",
        n: Int = 0,
        all: Int = 0,
        isProgress: Boolean = false,
        cancelled: Boolean = false
    ): Boolean {
        if (session != currentSession) return false
        if (cancelled && newPhase != "stopping" && newPhase != "stopped" && newPhase != "failed") return false

        when (phase) {
            "stopped", "failed" -> return false
            "stopping" -> {
                if (newPhase != "stopped" && newPhase != "failed") return false
            }
            "ready" -> {
                if (newPhase != "stopping" && newPhase != "stopped" && newPhase != "failed" && newPhase != "ready") return false
            }
            "checking" -> {
                if (isProgress) return false
                if (newPhase != "ready" && newPhase != "failed" && newPhase != "stopping" && newPhase != "stopped" && newPhase != "checking") return false
            }
            else -> {
                if (isProgress && (phase == "checking" || phase == "ready" || phase == "stopping" || phase == "stopped" || phase == "failed")) {
                    return false
                }
            }
        }

        phase = newPhase
        detail = message
        done = n
        total = all
        return true
    }
}
