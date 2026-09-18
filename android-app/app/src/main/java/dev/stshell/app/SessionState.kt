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

    /**
     * 1-based position in the observable startup pipeline, or 0 for phases that
     * are not part of one. A run skips the steps it does not need (an offline
     * start never downloads) and a phase can recur, so the launcher reports the
     * furthest step reached rather than the raw value; see LauncherUiState.step.
     */
    val step: Int
        get() = when (this) {
            CONNECTING -> 1
            PREPARING -> 2
            COPYING -> 3
            UNPACKING -> 4
            VERIFYING -> 5
            DOWNLOADING -> 6
            DEPENDENCIES -> 7
            STARTING -> 8
            CHECKING -> 9
            // READY and the terminal phases are not steps of a startup.
            else -> 0
        }

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

/** Download/unpack/verify progress. [unit] says what done/total count. */
data class Progress(val done: Int = 0, val total: Int = 0, val unit: ProgressUnit = ProgressUnit.ITEMS) {
    val known: Boolean get() = total > 0

    /** 0..100 while the total is known; null keeps the bar indeterminate. */
    val percent: Int? get() = InstallProgress.percentOf(done, total)
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
        .put("progressUnit", progress.unit.id)
        .put("step", serverPhase.step)
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
    /** Furthest startup step reached in this run; 0 once there is nothing to report. */
    val step: Int = 0,
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
    var currentSession: Long = 0L,
    var unit: ProgressUnit = ProgressUnit.ITEMS
) {
    fun transition(
        session: Long,
        newPhase: String,
        message: String = "",
        n: Int = 0,
        all: Int = 0,
        isProgress: Boolean = false,
        cancelled: Boolean = false,
        progressUnit: ProgressUnit = ProgressUnit.ITEMS
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
        unit = progressUnit
        return true
    }
}

/**
 * Cadence for polling the page for chat-ready DOM markers. Early attempts are
 * cheap and frequent so a fast page is detected immediately; once the page is
 * plainly slow the interval widens, which keeps a long install or a stalled
 * page from running an `evaluateJavascript` round trip twice a second for
 * half a minute. The overall budget is unchanged.
 */
object DomPollSchedule {
    /** Total time the page gets to expose a chat input or the login form. */
    const val BUDGET_MS = 30_000L

    /** Delay before the attempt following [attempt] (0-based). */
    fun delay(attempt: Int): Long = when {
        attempt < 6 -> 250L
        attempt < 16 -> 500L
        else -> 1_000L
    }

    /** Number of attempts that fit in [BUDGET_MS]. */
    val attempts: Int = run {
        var elapsed = 0L
        var count = 0
        while (elapsed + delay(count) <= BUDGET_MS) { elapsed += delay(count); count++ }
        count
    }
}
