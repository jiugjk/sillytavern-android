// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.probe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProbeReportValidatorTest {
    private val nonce = "a".repeat(32)
    private fun report() = JSONObject().put("schemaVersion", 1).put("mode", "android")
        .put("platform", "android").put("arch", "arm64").put("node", "26.8.2")
        .put("completed", true).put("passed", true).put("pid", 20).put("nonce", nonce)
        .put("tests", JSONArray(ProbeReportValidator.requiredTests.sorted().map { name ->
            JSONObject().put("name", name).put("passed", true)
        }))
    private fun exit() = JSONObject().put("exitCode", 0).put("error", JSONObject.NULL).put("pid", 20).put("nonce", nonce)
    private fun verify(value: JSONObject = report(), native: JSONObject = exit(), previous: Set<Int> = emptySet()) =
        ProbeReportValidator.verifyRun(value, native, nonce, "26.8.2", 10, previous)
    private fun rejected(action: () -> Unit) { assertThrows(IllegalStateException::class.java, action) }

    @Test fun acceptsCompleteAndroidRunAndZeroCpuInfo() {
        val value = report()
        val tests = value.getJSONArray("tests")
        for (index in 0 until tests.length()) {
            if (tests.getJSONObject(index).getString("name") == "worker") {
                tests.getJSONObject(index).put("detail", JSONObject().put("cpus", 0).put("availableParallelism", 3))
            }
        }
        assertEquals(20, verify(value))
    }
    @Test fun rejectsHostOrWrongNode() {
        for ((key, value) in listOf("mode" to "host", "platform" to "linux", "arch" to "x64", "node" to "18.20.4")) {
            rejected { verify(report().put(key, value)) }
        }
    }
    @Test fun rejectsFailedIncompleteAndStringSuccess() {
        rejected { verify(report().put("completed", false)) }
        rejected { verify(report().put("passed", false)) }
        rejected { verify(report().put("passed", "true")) }
    }
    @Test fun rejectsMissingDuplicateUnknownAndSkippedTests() {
        val missing = report(); missing.getJSONArray("tests").remove(0)
        rejected { verify(missing) }
        val duplicate = report(); val tests = duplicate.getJSONArray("tests")
        tests.put(1, tests.getJSONObject(0))
        rejected { verify(duplicate) }
        val unknown = report(); unknown.getJSONArray("tests").getJSONObject(0).put("name", "not-a-real-test")
        rejected { verify(unknown) }
        val skipped = report(); skipped.getJSONArray("tests").getJSONObject(0).put("skipped", true)
        rejected { verify(skipped) }
        val failed = report(); failed.getJSONArray("tests").getJSONObject(0).put("passed", false)
        rejected { verify(failed) }
    }
    @Test fun rejectsNonceMismatchAndInvalidNonce() {
        rejected { verify(native = exit().put("nonce", "b".repeat(32))) }
        rejected { verify(report().put("nonce", "short")) }
    }
    @Test fun rejectsNativeFailureAndPidReuse() {
        rejected { verify(native = exit().put("exitCode", 1)) }
        rejected { verify(native = exit().put("error", "failure")) }
        rejected { verify(native = exit().put("pid", 21)) }
        rejected { verify(report().put("pid", 10), exit().put("pid", 10)) }
        rejected { verify(previous = setOf(20)) }
    }
}
