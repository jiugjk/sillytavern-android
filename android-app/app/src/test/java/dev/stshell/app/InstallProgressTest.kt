// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.junit.Assert.*
import org.junit.Test

class InstallProgressTest {
    @Test fun bytesAreRenderedAtHumanScale() {
        assertEquals("0 B", InstallProgress.formatBytes(0))
        assertEquals("512 B", InstallProgress.formatBytes(512))
        assertEquals("2 KB", InstallProgress.formatBytes(2048))
        assertEquals("1.5 MB", InstallProgress.formatBytes(1024L * 1024 * 3 / 2))
        assertEquals("1.50 GB", InstallProgress.formatBytes(1024L * 1024 * 1024 * 3 / 2))
        // A negative counter is a bug upstream, never a negative size on screen.
        assertEquals("0 B", InstallProgress.formatBytes(-5))
    }

    @Test fun percentIsClampedAndUnknownWithoutATotal() {
        assertNull(InstallProgress.percentOf(5, 0))
        assertNull(InstallProgress.percentOf(-1, 10))
        assertEquals(0, InstallProgress.percentOf(0, 10))
        assertEquals(50, InstallProgress.percentOf(5, 10))
        assertEquals(100, InstallProgress.percentOf(10, 10))
        // Byte counters can overshoot a Content-Length; the bar must not.
        assertEquals(100, InstallProgress.percentOf(11, 10))
        assertEquals(33, InstallProgress.percentOf(1_000_000, 3_000_000))
    }

    @Test fun progressCarriesWhatItCounts() {
        assertEquals(ProgressUnit.ITEMS, ProgressUnit.from(null))
        assertEquals(ProgressUnit.ITEMS, ProgressUnit.from("nonsense"))
        assertEquals(ProgressUnit.BYTES, ProgressUnit.from("bytes"))
        assertEquals(ProgressUnit.ITEMS, Progress().unit)
        assertEquals(75, Progress(3, 4).percent)
        assertNull(Progress(3, 0, ProgressUnit.BYTES).percent)
        assertFalse(Progress(3, 0).known)
    }

    @Test fun everyPhaseHasItsOwnLabelAndTone() {
        val phases = ServerPhase.entries.map { it.id } + BrowserPhase.ERROR.id
        val labels = phases.map { InstallProgress.labelRes(it) }
        assertEquals(phases.size, labels.toSet().size)
        assertFalse(labels.contains(0))
        assertEquals(R.string.phase_browser_error, InstallProgress.labelRes(BrowserPhase.ERROR.id))
        assertEquals(R.string.phase_ready, InstallProgress.labelRes("ready"))
        // An unknown identifier must degrade to "idle", never to a blank label.
        assertEquals(R.string.phase_idle, InstallProgress.labelRes("something-else"))
        assertEquals(R.color.launcher_success, InstallProgress.toneRes(ServerPhase.READY.id))
        assertEquals(R.color.launcher_danger, InstallProgress.toneRes(ServerPhase.FAILED.id))
        assertEquals(R.color.launcher_danger, InstallProgress.toneRes(BrowserPhase.ERROR.id))
        assertEquals(R.color.launcher_warning, InstallProgress.toneRes(ServerPhase.DOWNLOADING.id))
    }

    @Test fun startupStepsFollowTheInstallPipeline() {
        val steps = ServerPhase.entries.filter { it.step > 0 }
        assertEquals(InstallProgress.STEP_COUNT, steps.size)
        assertEquals((1..InstallProgress.STEP_COUNT).toList(), steps.map { it.step }.sorted())
        // Ordering mirrors a first run: unpack, verify, download, install, start.
        assertTrue(ServerPhase.UNPACKING.step < ServerPhase.VERIFYING.step)
        assertTrue(ServerPhase.VERIFYING.step < ServerPhase.DOWNLOADING.step)
        assertTrue(ServerPhase.DOWNLOADING.step < ServerPhase.DEPENDENCIES.step)
        assertTrue(ServerPhase.DEPENDENCIES.step < ServerPhase.STARTING.step)
        assertTrue(ServerPhase.STARTING.step < ServerPhase.CHECKING.step)
        // Terminal phases are not steps of a startup.
        for (phase in listOf(ServerPhase.IDLE, ServerPhase.READY, ServerPhase.STOPPING,
            ServerPhase.STOPPED, ServerPhase.FAILED)) assertEquals(0, phase.step)
        assertTrue(steps.all { it.busy })
    }

    @Test fun serviceStateMachineKeepsTheUnitWithTheCounters() {
        val machine = ServiceStateMachine(currentSession = 1)
        assertTrue(machine.transition(1, "downloading", "ST", 1024, 4096,
            isProgress = true, progressUnit = ProgressUnit.BYTES))
        assertEquals(ProgressUnit.BYTES, machine.unit)
        assertEquals(1024, machine.done)
        assertTrue(machine.transition(1, "verifying", "tree", 3, 9, isProgress = true))
        assertEquals(ProgressUnit.ITEMS, machine.unit)
    }
}
