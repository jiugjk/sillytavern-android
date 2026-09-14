// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GenerationStateTest {
    private val token = "a".repeat(32)
    private fun message(seq: Int, body: Boolean = false, stream: Boolean = false, group: Boolean = false, count: Int = 0, visibility: String = "visible") =
        JSONObject().put("schemaVersion", 1).put("token", token).put("sequence", seq).put("ready", true)
            .put("bodyBusy", body).put("streamBusy", stream).put("groupBusy", group).put("streamEvents", count)
            .put("reason", "sample").put("visibility", visibility)
    private fun state() = GenerationState().apply { reset(token) }

    @Test fun idleAndDryRunDoNotAcquireCpu() {
        val s = state(); assertTrue(s.accept(message(1).toString(), 100))
        assertFalse(s.wantsCpu(true, true, 0, 0, 100))
    }
    @Test fun endedUiStillHoldsForStreamingFinalizationAndSave() {
        val s = state(); s.accept(message(1, body = true).toString(), 100)
        assertTrue(s.wantsCpu(true, true, 0, 0, 100))
        s.accept(message(2, stream = true).toString(), 200)
        assertTrue(s.wantsCpu(true, true, 0, 0, 200))
        s.accept(message(3).toString(), 300)
        assertTrue(s.wantsCpu(true, true, 0, 1, 300))
        assertTrue(s.wantsCpu(true, true, 0, 0, 1000))
        assertFalse(s.wantsCpu(true, true, 0, 0, 2300))
    }
    @Test fun activeGenerationHasNoArbitraryDurationLimit() {
        val s = state(); s.accept(message(1, body = true).toString(), 100)
        assertTrue(s.wantsCpu(true, true, 1, 0, 100))
        assertTrue(s.wantsCpu(true, true, 1, 0, 24 * 60 * 60 * 1000L))
    }
    @Test fun disableServerLossAndRendererLossReleaseImmediately() {
        val s = state(); s.accept(message(1, body = true).toString(), 100)
        assertFalse(s.wantsCpu(false, true, 1, 1, 100))
        assertFalse(s.wantsCpu(true, false, 1, 1, 100))
        s.detach(); assertFalse(s.wantsCpu(true, true, 1, 1, 100))
    }
    @Test fun groupAndBackendRequestsCoverGapsWithoutFrontendEvents() {
        val s = state(); s.accept(message(1, group = true).toString(), 100)
        assertTrue(s.wantsCpu(true, true, 0, 0, 100))
        s.accept(message(2).toString(), 1000)
        assertTrue(s.wantsCpu(true, true, 1, 0, 1000))
    }
    @Test fun staleOrMalformedMessagesCannotChangeStateOrLeakData() {
        val s = state(); assertTrue(s.accept(message(1, body = true).toString(), 100))
        assertFalse(s.accept(message(1).toString(), 200))
        assertFalse(s.accept(message(2).put("token", "old").toString(), 200))
        assertFalse(s.accept(message(2).put("bodyBusy", "false").toString(), 200))
        assertFalse(s.accept(message(2).put("prompt", "PRIVATE").toString(), 200))
        assertTrue(s.diagnostic(200).getBoolean("bodyBusy"))
        assertFalse(s.diagnostic(200).toString().contains("PRIVATE"))
        s.reset("b".repeat(32)); assertFalse(s.accept(message(3).toString(), 300))
    }
    @Test fun hiddenProgressExcludesTheTransitionAndMissingSamples() {
        val s = state(); s.accept(message(1, count = 1).toString(), 100)
        s.accept(message(2, count = 3, visibility = "hidden").toString(), 200)
        assertEquals(0, s.diagnostic(200).getInt("streamEventsObservedWhileHidden"))
        s.accept(message(3, count = 5, visibility = "hidden").toString(), 300)
        assertEquals(2, s.diagnostic(300).getInt("streamEventsObservedWhileHidden"))
        s.accept(message(5, count = 10, visibility = "hidden").toString(), 400)
        assertEquals(2, s.diagnostic(400).getInt("streamEventsObservedWhileHidden"))
    }
}
