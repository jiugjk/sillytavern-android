// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.content.Context
import java.util.Locale

/**
 * What a done/total pair counts. The installer reports bytes while downloading
 * and items while unpacking or verifying; without this the panel would render a
 * byte count as "12345678/27182818" and the bar would look stuck.
 */
enum class ProgressUnit(val id: String) {
    ITEMS("items"),
    BYTES("bytes");

    companion object {
        fun from(id: String?): ProgressUnit = entries.firstOrNull { it.id == id } ?: ITEMS
    }
}

/** Turns raw phase/progress state into the text the launcher shows. */
object InstallProgress {
    /** Startup steps a user can observe; see [ServerPhase.step]. */
    const val STEP_COUNT = 9

    /** "12.3 MB", "820 KB", "512 B" — a stable, locale-independent rendering. */
    fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0)
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            value < kb -> "$value B"
            value < mb -> String.format(Locale.US, "%.0f KB", value / kb)
            value < gb -> String.format(Locale.US, "%.1f MB", value / mb)
            else -> String.format(Locale.US, "%.2f GB", value / gb)
        }
    }

    /** 0..100, or null when the total is unknown (indeterminate work). */
    fun percentOf(done: Int, total: Int): Int? {
        if (total <= 0 || done < 0) return null
        return ((done.toLong().coerceAtMost(total.toLong()) * 100) / total).toInt()
    }

    /** Display name for a phase identifier, including the page-error pseudo phase. */
    fun labelRes(phase: String): Int = when (phase) {
        BrowserPhase.ERROR.id -> R.string.phase_browser_error
        else -> when (ServerPhase.from(phase)) {
            ServerPhase.IDLE -> R.string.phase_idle
            ServerPhase.CONNECTING -> R.string.phase_connecting
            ServerPhase.PREPARING -> R.string.phase_preparing
            ServerPhase.COPYING -> R.string.phase_copying
            ServerPhase.UNPACKING -> R.string.phase_unpacking
            ServerPhase.VERIFYING -> R.string.phase_verifying
            ServerPhase.DOWNLOADING -> R.string.phase_downloading
            ServerPhase.DEPENDENCIES -> R.string.phase_dependencies
            ServerPhase.STARTING -> R.string.phase_starting
            ServerPhase.CHECKING -> R.string.phase_checking
            ServerPhase.READY -> R.string.phase_ready
            ServerPhase.STOPPING -> R.string.phase_stopping
            ServerPhase.STOPPED -> R.string.phase_stopped
            ServerPhase.FAILED -> R.string.phase_failed
        }
    }

    /** Colour of the status dot: green running, red broken, amber working. */
    fun toneRes(phase: String): Int = when {
        phase == BrowserPhase.ERROR.id -> R.color.launcher_danger
        else -> when (val value = ServerPhase.from(phase)) {
            ServerPhase.READY -> R.color.launcher_success
            ServerPhase.FAILED -> R.color.launcher_danger
            ServerPhase.IDLE, ServerPhase.STOPPED, ServerPhase.STOPPING -> R.color.launcher_text_secondary
            else -> if (value.busy) R.color.launcher_warning else R.color.launcher_text_secondary
        }
    }

    /** "12.3 MB / 27.4 MB", "48/120 项", "已处理 48 项", or "" when nothing is counted. */
    fun counts(context: Context, progress: Progress): String {
        val done = progress.done
        val total = progress.total
        if (done <= 0 && total <= 0) return ""
        return when (progress.unit) {
            ProgressUnit.BYTES ->
                if (total > 0) "${formatBytes(done.toLong())} / ${formatBytes(total.toLong())}"
                else formatBytes(done.toLong())
            ProgressUnit.ITEMS ->
                if (total > 0) context.getString(R.string.progress_items, done, total)
                else context.getString(R.string.progress_items_open, done)
        }
    }

    /** "45% · 12.3 MB / 27.4 MB" — the right-hand side of the progress row. */
    fun measure(context: Context, progress: Progress): String {
        val counts = counts(context, progress)
        val percent = percentOf(progress.done, progress.total)
            ?.let { context.getString(R.string.progress_percent, it) }
        return listOfNotNull(percent, counts.takeIf { it.isNotEmpty() }).joinToString(" · ")
    }

    /** Compact one-line state used before the WebView exists. */
    fun summary(context: Context, phase: String, detail: String, progress: Progress): String {
        val parts = mutableListOf(context.getString(labelRes(phase)))
        if (detail.isNotEmpty()) parts.add(detail)
        val measure = measure(context, progress)
        if (measure.isNotEmpty()) parts.add(measure)
        return parts.joinToString(" · ")
    }
}
