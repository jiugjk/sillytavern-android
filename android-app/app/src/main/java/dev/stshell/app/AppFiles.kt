// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID

object AppFiles {
    // Installed payloads are immutable: the launcher drops write permission on
    // the whole tree so upstream code that resolves a writable path relative to
    // the server directory (notably global extension installs, which use
    // public/scripts/extensions/third-party) fails loudly at install time
    // instead of silently corrupting the payload and blocking the NEXT startup
    // with an integrity failure. User-level extensions live under the state
    // directory and are unaffected.
    private const val PAYLOAD_FILE_MODE = 0x100   // 0400, owner only
    private const val PAYLOAD_DIR_MODE = 0x140    // 0500, owner only
    private const val STAGE_FILE_MODE = 0x180     // 0600
    private const val STAGE_DIR_MODE = 0x1C0      // 0700

    fun state(context: Context) = File(context.noBackupFilesDir, "st-state").apply {
        require(!Files.isSymbolicLink(toPath())); check(mkdirs() || isDirectory)
    }
    fun readJson(file: File, maxBytes: Long = 1024 * 1024): JSONObject {
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= maxBytes) { "Invalid private metadata file" }
        return JSONObject(file.readText())
    }
    fun writeJson(file: File, value: JSONObject) {
        require(!Files.isSymbolicLink(file.toPath()))
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(value.toString(2).toByteArray()); it.fd.sync() }
            check(temporary.renameTo(file)) { "Cannot publish private metadata" }
        } finally { temporary.delete() }
    }
    fun port(state: File): Int {
        val transport = File(state, "transport.json")
        val selection = File(state, "port.json")
        val value = when {
            transport.exists() -> readJson(transport).getInt("port")
            selection.exists() -> readJson(selection).getInt("port")
            else -> ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }
        require(value in 1024..65535)
        writeJson(selection, JSONObject().put("port", value))
        return value
    }

    /** Applies [fileMode]/[dirMode] bottom-up so directories stay traversable while we walk them. */
    private fun applyMode(root: File, fileMode: Int, dirMode: Int) {
        require(!Files.isSymbolicLink(root.toPath()))
        if (root.isDirectory) {
            for (child in root.listFiles() ?: error("Cannot enumerate payload directory")) applyMode(child, fileMode, dirMode)
            Os.chmod(root.absolutePath, dirMode)
        } else {
            Os.chmod(root.absolutePath, fileMode)
        }
    }

    /** Makes the installed payload read-only; call before handing it to Node. */
    fun sealPayload(payload: File) {
        // A read-only root does not prove that all descendants are read-only.
        applyMode(payload, PAYLOAD_FILE_MODE, PAYLOAD_DIR_MODE)
    }

    /** Restores owner write access so a staging tree can be cleaned up or replaced. */
    private fun unsealForCleanup(tree: File) = applyMode(tree, STAGE_FILE_MODE, STAGE_DIR_MODE)

    fun preparePayload(context: Context, contract: JSONObject, progress: (String, Int, Int) -> Unit): File {
        val pointer = contract.getJSONObject("payload")
        val bytes = context.assets.open("payload/manifest.json").use { it.readBytes() }
        val archive = PayloadArchive(bytes, pointer.getString("manifestSha256"), pointer.getString("archiveSha256"))
        require(archive.id == pointer.getString("payloadId"))
        val parent = File(context.filesDir, "runtime").apply { check(mkdirs() || isDirectory) }
        val installed = File(parent, archive.id)
        if (installed.exists()) {
            progress("verifying", 0, archive.files.size)
            archive.verifyInstalled(installed) { n, all -> progress("verifying", n, all) }
            // Re-seal: an older build may have installed a writable tree.
            sealPayload(installed)
            reclaim(parent, installed)
            return installed
        }
        require(parent.usableSpace > archive.totalBytes + pointer.getLong("archiveBytes") + 64 * 1024 * 1024) { "Insufficient space to unpack payload" }
        val zip = File(context.cacheDir, "payload-${UUID.randomUUID()}.zip")
        val stage = File(parent, ".install-${UUID.randomUUID()}")
        try {
            progress("copying", 0, 0)
            context.assets.open("payload/payload.zip").use { input ->
                FileOutputStream(zip).use { output ->
                    val buffer = ByteArray(128 * 1024); var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= pointer.getLong("archiveBytes"))
                        output.write(buffer, 0, count)
                    }
                    require(total == pointer.getLong("archiveBytes")); output.fd.sync()
                }
            }
            archive.extract(zip, stage) { n, all -> progress("unpacking", n, all) }
            // Seal before publishing so the tree is never writable under its final name.
            sealPayload(stage)
            check(!installed.exists() && stage.renameTo(installed)) { "Cannot publish payload" }
            reclaim(parent, installed)
            return installed
        } finally {
            zip.delete()
            // Only a freshly created, regular-only staging tree; never user state.
            if (stage.exists()) {
                try { unsealForCleanup(stage) } catch (_: Exception) { }
                stage.deleteRecursively()
            }
        }
    }

    /**
     * Reclaims superseded payloads and abandoned staging trees. Only entries
     * directly under the launcher-owned `runtime` directory are considered, and
     * only names this code creates: a 64-hex payload id, or a `.install-<uuid>`
     * staging directory. User state lives elsewhere and is never touched.
     */
    private val PAYLOAD_ID = Regex("[a-f0-9]{64}")
    private val STAGING = Regex("\\.install-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    fun reclaim(parent: File, keep: File) {
        val entries = parent.listFiles() ?: return
        for (entry in entries) {
            if (entry.absolutePath == keep.absolutePath) continue
            if (Files.isSymbolicLink(entry.toPath()) || !entry.isDirectory) continue
            val name = entry.name
            if (!PAYLOAD_ID.matches(name) && !STAGING.matches(name)) continue
            try {
                unsealForCleanup(entry)
                entry.deleteRecursively()
            } catch (_: Exception) { /* Reclaiming disk space must never block startup. */ }
        }
    }
}
