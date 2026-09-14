// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID

object AppFiles {
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
            check(!installed.exists() && stage.renameTo(installed)) { "Cannot publish payload" }
            return installed
        } finally {
            zip.delete()
            // Only a freshly created, regular-only staging tree; never user state.
            if (stage.exists()) stage.deleteRecursively()
        }
    }
}
