// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.junit.Assert.*
import org.junit.Test

class SessionStateTest {
    @Test fun downloadAndDependenciesAreBusyNotReady() {
        for (phase in listOf(ServerPhase.DOWNLOADING, ServerPhase.DEPENDENCIES)) {
            assertTrue(phase.busy)
            assertFalse(SessionState(serverPhase = phase).serverUsable)
            assertEquals(phase, ServerPhase.from(phase.id))
        }
    }
    @Test fun browserFailureNeverDemotesHealthyServer() {
        val value = SessionState(serverPhase = ServerPhase.READY, browserPhase = BrowserPhase.ERROR,
            browserDetail = "renderer exited")
        assertTrue(value.serverUsable)
        assertTrue(value.browserRecoverable)
        assertEquals("ready", value.toJson().getString("serverPhase"))
        assertEquals("browser-error", value.displayPhase)
    }
    @Test fun browserRecoveryClearsOnlyBrowserError() {
        val failed = SessionState(serverPhase = ServerPhase.READY, browserPhase = BrowserPhase.ERROR)
        val recovered = failed.copy(browserPhase = BrowserPhase.READY, pageLoaded = true)
        assertEquals(ServerPhase.READY, recovered.serverPhase)
        assertFalse(recovered.browserRecoverable)
        assertEquals("ready", recovered.displayPhase)
    }
    @Test fun absentBrowserCanRecoverWithoutRestartingNode() {
        assertTrue(SessionState(serverPhase = ServerPhase.READY, browserPhase = BrowserPhase.ABSENT).browserRecoverable)
        assertFalse(SessionState(serverPhase = ServerPhase.FAILED, browserPhase = BrowserPhase.ERROR).browserRecoverable)
    }
}
