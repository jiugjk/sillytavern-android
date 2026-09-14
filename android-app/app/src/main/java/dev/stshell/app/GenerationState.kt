// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.json.JSONObject

/** Tracks frontend and backend activity for CPU wake-lock ownership. */
class GenerationState {
    private var token = ""
    private var sequence = 0L
    private var receivedAt = -1L
    private var lastBusyAt = -1L
    private var documentPresent = false
    private var bodyBusy = false
    private var streamBusy = false
    private var groupBusy = false
    private var streamEvents = 0L
    private var hiddenEvents = 0L
    private var visibility = "unknown"
    var ready = false
        private set
    var rejectedMessages = 0
        private set

    fun reset(newToken: String) {
        token = newToken; sequence = 0; receivedAt = -1; lastBusyAt = -1
        documentPresent = true; ready = false; bodyBusy = false; streamBusy = false; groupBusy = false
        streamEvents = 0; hiddenEvents = 0; visibility = "unknown"
    }
    fun detach() { documentPresent = false; ready = false; bodyBusy = false; streamBusy = false; groupBusy = false; lastBusyAt = -1 }
    fun accept(text: String, now: Long): Boolean {
        try {
            require(text.length <= 2048)
            val value = JSONObject(text)
            val keys = value.keys().asSequence().toSet()
            require(keys == setOf("schemaVersion", "token", "sequence", "ready", "bodyBusy", "streamBusy", "groupBusy", "streamEvents", "reason", "visibility"))
            require(documentPresent && value.opt("schemaVersion") == 1 && value.optString("token") == token)
            fun integer(name: String): Long {
                val number = value.get(name); require(number is Number)
                val result = number.toLong(); require(result in 0..9007199254740991L && number.toDouble() == result.toDouble())
                return result
            }
            val seq = integer("sequence"); require(seq > sequence)
            val count = integer("streamEvents"); require(count >= streamEvents)
            for (name in listOf("ready", "bodyBusy", "streamBusy", "groupBusy")) require(value.get(name) is Boolean)
            val reason = value.getString("reason"); require(reason in setOf("install", "sample", "event", "dom", "visibility", "pagehide"))
            val visible = value.getString("visibility"); require(visible in setOf("visible", "hidden", "prerender", "unknown"))
            // First hidden snapshot may include events from just before hiding.
            // Count only a consecutive, entirely hidden observation interval.
            if (visible == "hidden" && visibility == "hidden" && seq == sequence + 1) hiddenEvents += count - streamEvents
            sequence = seq; streamEvents = count; receivedAt = now; visibility = visible
            ready = value.getBoolean("ready")
            bodyBusy = value.getBoolean("bodyBusy"); streamBusy = value.getBoolean("streamBusy"); groupBusy = value.getBoolean("groupBusy")
            if (reason == "pagehide") detach()
            return true
        } catch (_: Exception) { rejectedMessages++; return false }
    }
    fun wantsCpu(armed: Boolean, serverReady: Boolean, activeGeneration: Int, activeSave: Int, now: Long): Boolean {
        if (!armed || !serverReady || !documentPresent) { lastBusyAt = -1; return false }
        val busy = bodyBusy || streamBusy || groupBusy || activeGeneration > 0 || activeSave > 0
        if (busy) lastBusyAt = now
        // Only a resource-release grace, never cancellation or a generation limit.
        return busy || (lastBusyAt >= 0 && now - lastBusyAt < 2000)
    }
    fun diagnostic(now: Long): JSONObject = JSONObject().put("observerReady", ready)
        .put("documentPresent", documentPresent).put("sequence", sequence)
        .put("bodyBusy", bodyBusy).put("streamBusy", streamBusy).put("groupBusy", groupBusy)
        .put("visibility", visibility).put("streamEvents", streamEvents).put("streamEventsObservedWhileHidden", hiddenEvents)
        .put("heartbeatAgeMs", if (receivedAt >= 0) now - receivedAt else JSONObject.NULL)
        .put("rejectedMessages", rejectedMessages)
}
