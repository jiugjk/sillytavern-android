// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    // B1: ServiceStateMachine regressions for async progress race conditions
    @Test fun staleProgressCannotDemoteCheckingOrReadyOrStopping() {
        val sm = ServiceStateMachine(currentSession = 1)
        assertTrue(sm.transition(1, "preparing"))
        assertTrue(sm.transition(1, "starting"))
        assertTrue(sm.transition(1, "downloading", "50%", 50, 100, isProgress = true))
        assertEquals("downloading", sm.phase)

        // Enter checking: progress events must now be rejected
        assertTrue(sm.transition(1, "checking", "checking health"))
        assertFalse(sm.transition(1, "downloading", "old progress", 60, 100, isProgress = true))
        assertEquals("checking", sm.phase)

        // Enter ready: stale progress or starting must be rejected
        assertTrue(sm.transition(1, "ready", "http://127.0.0.1:18766"))
        assertEquals("ready", sm.phase)
        assertFalse(sm.transition(1, "downloading", "stale", 70, 100, isProgress = true))
        assertFalse(sm.transition(1, "starting", "stale starting"))
        assertEquals("ready", sm.phase)

        // Enter stopping: progress and ready must be rejected
        assertTrue(sm.transition(1, "stopping", "stopping"))
        assertEquals("stopping", sm.phase)
        assertFalse(sm.transition(1, "downloading", "late progress", isProgress = true))
        assertFalse(sm.transition(1, "ready", "late ready"))
        assertEquals("stopping", sm.phase)

        // Enter stopped: terminal
        assertTrue(sm.transition(1, "stopped", "clean stop"))
        assertEquals("stopped", sm.phase)
        assertFalse(sm.transition(1, "starting", "new start on old session"))
        assertEquals("stopped", sm.phase)
    }

    @Test fun staleSessionEventIsRejected() {
        val sm = ServiceStateMachine(currentSession = 2, phase = "ready")
        assertFalse(sm.transition(1, "failed", "error from old session 1"))
        assertEquals("ready", sm.phase)
    }

    @Test fun cancelledRejectsNonTerminalEvents() {
        val sm = ServiceStateMachine(currentSession = 1, phase = "starting")
        assertFalse(sm.transition(1, "downloading", "progress", isProgress = true, cancelled = true))
        assertFalse(sm.transition(1, "ready", "ready", cancelled = true))
        assertEquals("starting", sm.phase)
        assertTrue(sm.transition(1, "stopping", "stop", cancelled = true))
        assertEquals("stopping", sm.phase)
    }

    @Test fun concurrentBarrierProgressDoesNotRegressReady() {
        val sm = ServiceStateMachine(currentSession = 1, phase = "starting")
        val progressReadLatch = CountDownLatch(1)
        val readyPublishedLatch = CountDownLatch(1)
        val progressApplied = AtomicBoolean(false)

        val progressThread = Thread {
            // Simulate background progress reader reading progress before ready
            progressReadLatch.countDown()
            assertTrue(readyPublishedLatch.await(5, TimeUnit.SECONDS))
            // Now attempt to deliver stale progress to main-thread state machine
            val applied = synchronized(sm) {
                sm.transition(1, "downloading", "stale progress", 80, 100, isProgress = true)
            }
            progressApplied.set(applied)
        }
        progressThread.start()

        assertTrue(progressReadLatch.await(5, TimeUnit.SECONDS))
        // Main thread publishes checking and ready first
        synchronized(sm) {
            sm.transition(1, "checking")
            sm.transition(1, "ready", "http://127.0.0.1:18766")
        }
        readyPublishedLatch.countDown()
        progressThread.join(5000)

        assertFalse("Stale progress must not be applied after ready", progressApplied.get())
        assertEquals("ready", sm.phase)
    }

    @Test fun concurrentBarrierProgressDoesNotRegressStopping() {
        val sm = ServiceStateMachine(currentSession = 1, phase = "starting")
        val progressReadLatch = CountDownLatch(1)
        val stopPublishedLatch = CountDownLatch(1)
        val progressApplied = AtomicBoolean(false)

        val progressThread = Thread {
            progressReadLatch.countDown()
            assertTrue(stopPublishedLatch.await(5, TimeUnit.SECONDS))
            val applied = synchronized(sm) {
                sm.transition(1, "downloading", "late download progress", 90, 100, isProgress = true)
            }
            progressApplied.set(applied)
        }
        progressThread.start()

        assertTrue(progressReadLatch.await(5, TimeUnit.SECONDS))
        synchronized(sm) {
            sm.transition(1, "stopping", "stopping server")
        }
        stopPublishedLatch.countDown()
        progressThread.join(5000)

        assertFalse("Stale progress must not be applied after stopping", progressApplied.get())
        assertEquals("stopping", sm.phase)
    }

    // B2: Browser navigation and DOM check state regressions
    @Test fun browserNavigationTrackerHandlesErrorThenFinished() {
        val tracker = BrowserNavigationTracker()
        val navId = tracker.onPageStarted()
        assertEquals(1L, navId)
        assertFalse(tracker.mainFrameFailed)

        // Non-main-frame error does not fail the navigation
        assertFalse(tracker.onReceivedError(isMainFrame = false))
        assertFalse(tracker.mainFrameFailed)
        assertTrue(tracker.canFinish(navId))

        // Main-frame error marks failure
        assertTrue(tracker.onReceivedError(isMainFrame = true))
        assertTrue(tracker.mainFrameFailed)
        // Subsequent onPageFinished must be blocked
        assertFalse(tracker.canFinish(navId))
    }

    @Test fun browserNavigationTrackerHandlesHttp500ThenFinished() {
        val tracker = BrowserNavigationTracker()
        val navId = tracker.onPageStarted()

        // 200/300 status code does not fail the main frame
        assertFalse(tracker.onReceivedHttpError(isMainFrame = true, statusCode = 200))
        assertFalse(tracker.mainFrameFailed)

        // 500 fails the main frame
        assertTrue(tracker.onReceivedHttpError(isMainFrame = true, statusCode = 500))
        assertTrue(tracker.mainFrameFailed)
        assertFalse(tracker.canFinish(navId))
    }

    @Test fun browserNavigationTrackerSuccessAndRecovery() {
        val tracker = BrowserNavigationTracker()
        val nav1 = tracker.onPageStarted()
        tracker.onReceivedError(isMainFrame = true)
        assertFalse(tracker.canFinish(nav1))

        // Reload initiates a fresh navigation
        val nav2 = tracker.onPageStarted()
        assertEquals(2L, nav2)
        assertFalse(tracker.mainFrameFailed)
        // Old nav callback is rejected
        assertFalse(tracker.canFinish(nav1))
        // New nav callback succeeds
        assertTrue(tracker.canFinish(nav2))
    }
    @Test fun mainFrameHttp500EntersErrorAndRetainsRecovery() {
        val initial = SessionState(serverPhase = ServerPhase.READY, browserPhase = BrowserPhase.LOADING)
        val errorState = initial.copy(
            browserPhase = BrowserPhase.ERROR,
            browserDetail = "页面返回 HTTP 500",
            mainHttpError = 500,
            pageLoaded = false
        )
        assertTrue(errorState.serverUsable)
        assertTrue(errorState.browserRecoverable)
        assertEquals("browser-error", errorState.displayPhase)
        assertEquals("页面返回 HTTP 500", errorState.displayDetail)
    }

    @Test fun domCheckTimeoutEntersErrorAndPreservesNode() {
        val initial = SessionState(serverPhase = ServerPhase.READY, browserPhase = BrowserPhase.LOADING, pageLoaded = true)
        val timeoutState = initial.copy(
            browserPhase = BrowserPhase.ERROR,
            browserDetail = "页面加载超时，聊天界面未就绪",
            domCheck = "timeout"
        )
        assertTrue(timeoutState.serverUsable)
        assertTrue(timeoutState.browserRecoverable)
        assertEquals("browser-error", timeoutState.displayPhase)
    }

    @Test fun successfulDomCheckReachesReady() {
        val dom = DomStatus(chatInput = true, publicApi = true)
        assertTrue(dom.ready)
        val readyState = SessionState(
            serverPhase = ServerPhase.READY,
            browserPhase = BrowserPhase.READY,
            dom = dom,
            pageLoaded = true
        )
        assertTrue(readyState.serverUsable)
        assertFalse(readyState.browserRecoverable)
        assertEquals("ready", readyState.displayPhase)
        assertEquals(true, readyState.toJson().optJSONObject("dom")?.optBoolean("chatInput"))
    }

    @Test fun progressUnitSurvivesTheDiagnosticRoundTrip() {
        val state = SessionState(serverPhase = ServerPhase.DOWNLOADING,
            progress = Progress(1_048_576, 4_194_304, ProgressUnit.BYTES))
        val json = state.toJson()
        assertEquals("bytes", json.getString("progressUnit"))
        assertEquals(1_048_576, json.getInt("done"))
        assertEquals(ServerPhase.DOWNLOADING.step, json.getInt("step"))
        assertEquals(25, state.progress.percent)
    }

    @Test fun domPollingStaysInsideItsBudget() {
        var elapsed = 0L
        for (attempt in 0 until DomPollSchedule.attempts) {
            val delay = DomPollSchedule.delay(attempt)
            assertTrue("polling must never busy-loop", delay >= 250L)
            elapsed += delay
        }
        assertTrue("budget overrun: $elapsed", elapsed <= DomPollSchedule.BUDGET_MS)
        // The next attempt would exceed the budget, so the schedule is maximal.
        assertTrue(elapsed + DomPollSchedule.delay(DomPollSchedule.attempts) > DomPollSchedule.BUDGET_MS)
        // Early attempts stay fast so a quick page is detected immediately.
        assertTrue(DomPollSchedule.delay(0) < DomPollSchedule.delay(DomPollSchedule.attempts - 1))
        assertTrue(DomPollSchedule.attempts in 20..60)
    }
}
