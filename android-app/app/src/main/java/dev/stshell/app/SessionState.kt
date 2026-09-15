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
    val dom: JSONObject? = null,
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
            dom?.let { put("dom", it) }
        }
}
