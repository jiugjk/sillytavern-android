// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.probe

import org.json.JSONObject

/** Same per-run requirements as the host verifier. No Android APIs, so JVM-testable. */
internal object ProbeReportValidator {
    val requiredTests = setOf(
        "runtime-identity", "esm-tla", "fs-atomic", "crypto", "icu-unicode", "wasm",
        "wasm-simd", "worker", "fetch-http-stream", "dns", "tls-validation", "https-remote"
    )

    fun verifyRun(
        report: JSONObject, exit: JSONObject, nonce: String, nodeVersion: String,
        runnerPid: Int, previousPids: Set<Int>
    ): Int {
        check(report.opt("schemaVersion") == 1) { "Unknown probe schema" }
        check(report.opt("completed") == true && report.opt("passed") == true) { "Node probe failed or did not complete" }
        check(report.opt("mode") == "android" && report.opt("platform") == "android" && report.opt("arch") == "arm64") {
            "Actual Android arm64 execution is required"
        }
        check(report.opt("node") == nodeVersion && nodeVersion.substringBefore('.') == "26") { "Wrong Node runtime version" }
        check(report.opt("nonce") == nonce && exit.opt("nonce") == nonce) { "Probe nonce mismatch" }
        check(nonce.matches(Regex("[a-f0-9]{32}"))) { "Invalid probe nonce" }
        val tests = report.getJSONArray("tests")
        check(tests.length() == requiredTests.size) { "Missing/extra capability tests" }
        val names = mutableSetOf<String>()
        for (index in 0 until tests.length()) {
            val item = tests.getJSONObject(index)
            val name = item.getString("name")
            check(names.add(name)) { "Duplicate capability test: $name" }
            check(item.opt("passed") == true) { "Capability failed: $name" }
            check(!item.has("skipped") || item.isNull("skipped") || item.opt("skipped") == false) { "Skipped capability: $name" }
        }
        check(names == requiredTests) { "Capability test names differ from this probe" }
        check(exit.opt("exitCode") == 0 && exit.isNull("error")) { "Native entry point failed: ${exit.opt("error")}" }
        val pid = report.getInt("pid")
        check(pid > 0 && exit.getInt("pid") == pid && pid != runnerPid && pid !in previousPids) {
            "Node must run in a new service process, separate from the UI"
        }
        return pid
    }
}
