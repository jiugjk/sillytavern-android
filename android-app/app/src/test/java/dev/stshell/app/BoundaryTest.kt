// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BoundaryTest {
    @Test fun realPayloadRoundTrip() {
        val assets = File(System.getProperty("st.app.assets") ?: error("Prepared app assets are required"))
        val contract = JSONObject(File(assets, "app-contract.json").readText()).getJSONObject("payload")
        val bytes = File(assets, "payload/manifest.json").readBytes()
        val spec = PayloadArchive(bytes, contract.getString("manifestSha256"), contract.getString("archiveSha256"))
        val directory = Files.createTempDirectory("st-real-payload-").toFile()
        try {
            val target = File(directory, "tree")
            spec.extract(File(assets, "payload/payload.zip"), target)
            spec.verifyInstalled(target)
            assertEquals("online-installer", spec.manifest.getString("kind"))
            assertTrue(File(target, "shell/first-install.mjs").isFile)
            assertTrue(File(target, "shell/npm/node_modules/@npmcli/arborist/lib/index.js").isFile)
            assertFalse("ST and extensions must be fetched on-device, not bundled", File(target, "server").exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun exactOriginAndAuthHost() {
        val policy = LocalOrigin(18766)
        assertTrue(policy.allows("http://127.0.0.1:18766/api/ping"))
        for (url in listOf("http://127.0.0.1:18767/", "http://127.0.0.1/", "https://127.0.0.1:18766/", "http://127.0.0.1:18766@evil.invalid/", "file:///data/private", "http://localhost:18766/")) assertFalse(url, policy.allows(url))
        assertTrue(policy.authHost("127.0.0.1"))
        assertFalse(policy.authHost("evil.invalid"))
        assertFalse(policy.authHost("127.0.0.1:18767"))
        assertTrue(policy.isRoot("http://127.0.0.1:18766/"))
        assertTrue(policy.isRoot("http://127.0.0.1:18766"))
        assertFalse(policy.isRoot("http://127.0.0.1:18766/index.html"))
        assertFalse(policy.isRoot("http://127.0.0.1:18766/?x=1"))
        assertFalse(policy.isRoot("http://127.0.0.1:18767/"))
    }
    @Test fun defaultUiLanguageIsSimplifiedChineseAndStaysOnLocalOrigin() {
        val origin = LocalOrigin(18766)
        val html = DefaultUiLanguage.document(origin).toString(Charsets.UTF_8)
        assertEquals("zh-cn", DefaultUiLanguage.CODE)
        assertEquals("http://127.0.0.1:18766/index.html", DefaultUiLanguage.continueUrl(origin))
        assertTrue(html.contains("localStorage.setItem('language','zh-cn')"))
        assertTrue(html.contains("http://127.0.0.1:18766/index.html"))
        assertFalse(html.contains("http://127.0.0.1:18767"))
    }
    @Test fun hostileNamesRejected() {
        for (name in listOf("../outside", "/outside", "server/../x", "server//x", "server\\x", "C:/x", "server/.git/config", "server/config.yaml", "server/data/default-user/secrets.json", "state/transport.json", "server/a\u0000b")) {
            assertThrows(name, IllegalArgumentException::class.java) { PayloadArchive.safeName(name) }
        }
    }
    private val fixture = linkedMapOf(
        "server/server.js" to "// fixture", "server/package.json" to "{}",
        "shell/bootstrap.mjs" to "// fixture", "shell/policy.mjs" to "// fixture", "shell/mobile-policy.json" to "{}"
    )
    private fun bytes(): ByteArray {
        val entries = JSONObject()
        for ((name, value) in fixture) entries.put(name, JSONObject().put("size", value.toByteArray().size).put("sha256", PayloadArchive.hash(value.toByteArray())))
        return JSONObject().put("schemaVersion", 1).put("payloadId", "a".repeat(64))
            .put("files", entries).put("directories", JSONArray(listOf("server", "shell")))
            .put("totalBytes", fixture.values.sumOf { it.toByteArray().size }).toString().toByteArray()
    }
    private fun archive(file: File, change: Boolean = false) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("server/", "shell/")) { zip.putNextEntry(ZipEntry(name)); zip.closeEntry() }
            for ((name, value) in fixture) {
                zip.putNextEntry(ZipEntry(name)); zip.write((if (change && name == "server/server.js") "// changed" else value).toByteArray()); zip.closeEntry()
            }
        }
    }
    @Test fun verifiedExtractionAndInstalledTamperChecks() {
        val directory = Files.createTempDirectory("st-archive-").toFile()
        try {
            val zip = File(directory, "payload.zip"); archive(zip)
            val data = bytes(); val spec = PayloadArchive(data, PayloadArchive.hash(data), zip.inputStream().use(PayloadArchive::hash))
            val output = File(directory, "result")
            spec.extract(zip, output)
            spec.verifyInstalled(output)
            assertThrows(IllegalArgumentException::class.java) { spec.extract(zip, output) }
            File(output, "server/server.js").appendText("tampered")
            assertThrows(IllegalArgumentException::class.java) { spec.verifyInstalled(output) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun mismatchedArchiveNeverCreatesDestination() {
        val directory = Files.createTempDirectory("st-archive-").toFile()
        try {
            val zip = File(directory, "payload.zip"); archive(zip)
            val data = bytes(); val spec = PayloadArchive(data, PayloadArchive.hash(data), zip.inputStream().use(PayloadArchive::hash))
            archive(zip, change = true)
            val output = File(directory, "result")
            assertThrows(IllegalArgumentException::class.java) { spec.extract(zip, output) }
            assertFalse(output.exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun memberHashIsCheckedEvenIfArchiveHashIsAccepted() {
        val directory = Files.createTempDirectory("st-archive-").toFile()
        try {
            val zip = File(directory, "payload.zip"); archive(zip, change = true)
            val data = bytes(); val spec = PayloadArchive(data, PayloadArchive.hash(data), zip.inputStream().use(PayloadArchive::hash))
            assertThrows(IllegalArgumentException::class.java) { spec.extract(zip, File(directory, "result")) }
        } finally { directory.deleteRecursively() }
    }
}
