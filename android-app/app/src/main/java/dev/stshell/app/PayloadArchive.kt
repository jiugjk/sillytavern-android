// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Platform-neutral verifier, exercised by JVM tests. APK-pinned archive hash is mandatory. */
class PayloadArchive(manifestBytes: ByteArray, manifestSha: String, val archiveSha: String) {
    data class Member(val size: Long, val sha: String)
    val rawManifest = manifestBytes
    val manifest: JSONObject
    val id: String
    val files: Map<String, Member>
    val directories: Set<String>
    val totalBytes: Long
    init {
        require(manifestBytes.size <= 32 * 1024 * 1024 && hash(manifestBytes) == manifestSha) { "Manifest checksum mismatch" }
        manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        require(manifest.getInt("schemaVersion") == 1)
        id = manifest.getString("payloadId")
        require(id.matches(Regex("[a-f0-9]{64}")) && archiveSha.matches(Regex("[a-f0-9]{64}")))
        val entries = manifest.getJSONObject("files")
        files = entries.keys().asSequence().associateWith { name ->
            safeName(name)
            val entry = entries.getJSONObject(name)
            val size = entry.getLong("size"); val digest = entry.getString("sha256")
            require(size in 0..134217728 && digest.matches(Regex("[a-f0-9]{64}")))
            Member(size, digest)
        }
        val dirs = manifest.getJSONArray("directories")
        val list = (0 until dirs.length()).map { dirs.getString(it).also(::safeName) }
        require(list.distinct().size == list.size)
        directories = list.toSet()
        require(files.keys.intersect(directories).isEmpty() && files.size + directories.size <= 60000)
        totalBytes = files.values.sumOf { it.size }
        require(totalBytes <= 1073741824 && totalBytes == manifest.getLong("totalBytes"))
        for (name in files.keys + directories) {
            var parent = name.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent in directories) { "Missing parent directory" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        val required = if (manifest.optString("kind") == "online-installer")
            setOf("shell/first-install.mjs", "shell/first-install.json", "shell/npm/package.json", "shell/bootstrap.mjs", "shell/policy.mjs", "shell/mobile-policy.json")
        else setOf("server/server.js", "server/package.json", "shell/bootstrap.mjs", "shell/policy.mjs", "shell/mobile-policy.json")
        require(required.all { it in files })
    }

    fun extract(archive: File, destination: File, progress: (Int, Int) -> Unit = { _, _ -> }) {
        require(!destination.exists()) { "Never overwrite an installed payload" }
        require(archive.inputStream().use(::hash) == archiveSha) { "Archive checksum mismatch" }
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size == files.size + directories.size) { "ZIP inventory mismatch" }
            val seen = mutableSetOf<String>()
            for (entry in entries) {
                val name = if (entry.isDirectory) entry.name.dropLast(1) else entry.name
                safeName(name)
                require(seen.add(name)) { "Duplicate ZIP member" }
                require(entry.method in setOf(0, 8)) { "Unsupported compression" }
                if (entry.isDirectory) require(name in directories && entry.size == 0L)
                else require(files[name]?.size == entry.size) { "ZIP member size mismatch" }
            }
            require(seen == files.keys + directories)
            check(destination.mkdirs())
            for (name in directories.sortedBy { it.count { c -> c == '/' } }) check(File(destination, name).mkdirs() || File(destination, name).isDirectory)
            var count = 0
            for (entry in entries) {
                if (entry.isDirectory) continue
                val expected = files.getValue(entry.name)
                val target = File(destination, entry.name)
                require(target.canonicalFile.toPath().startsWith(destination.canonicalFile.toPath()))
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            size += n; require(size <= expected.size) { "Expanded size exceeded" }
                            digest.update(buffer, 0, n); output.write(buffer, 0, n)
                        }
                    }
                }
                require(size == expected.size && hex(digest.digest()) == expected.sha) { "Member hash mismatch: ${entry.name}" }
                count++
                if (count % 128 == 0 || count == files.size) progress(count, files.size)
            }
        }
        // We never materialize ZIP symlinks/permissions. Every member above is a
        // regular file. The mandatory archive hash also binds the host-verified,
        // regular-only ZIP headers before any member is written.
        File(destination, "payload-manifest.json").writeBytes(rawManifest)
    }

    fun verifyInstalled(root: File, progress: (Int, Int) -> Unit = { _, _ -> }) {
        require(!Files.isSymbolicLink(root.toPath()) && root.isDirectory)
        for (name in directories) {
            val directory = File(root, name)
            require(!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory) { "Installed directory missing" }
        }
        val seen = mutableSetOf<String>()
        var checked = 0
        fun visit(directory: File) {
            for (entry in directory.listFiles() ?: error("Cannot read installed payload")) {
                require(!Files.isSymbolicLink(entry.toPath())) { "Installed payload contains a symlink" }
                val name = entry.relativeTo(root).invariantSeparatorsPath
                if (entry.isDirectory) {
                    require(name in directories) { "Unexpected installed directory" }; visit(entry)
                } else {
                    require(entry.isFile())
                    if (name == "payload-manifest.json") require(entry.length() == rawManifest.size.toLong() && entry.readBytes().contentEquals(rawManifest))
                    else {
                        val expected = files[name] ?: error("Unexpected installed file: $name")
                        require(entry.length() == expected.size && entry.inputStream().use(::hash) == expected.sha) { "Installed payload was modified: $name" }
                        seen.add(name); checked++
                        if (checked % 128 == 0 || checked == files.size) progress(checked, files.size)
                    }
                }
            }
        }
        visit(root)
        require(seen == files.keys && File(root, "payload-manifest.json").isFile)
    }

    companion object {
        fun safeName(name: String) {
            require(name.isNotEmpty() && !name.startsWith('/') && !name.endsWith('/') && !name.contains('\\') && !name.contains(':')) { "Unsafe ZIP path" }
            require(name.none { it.code < 32 } && name.split('/').none { it.isEmpty() || it == "." || it == ".." || it == ".git" }) { "Unsafe ZIP path" }
            require(name == "server" || name.startsWith("server/") || name == "shell" || name.startsWith("shell/") || name == "dependency-inventory.json")
            require(name !in setOf("server/config.yaml", "server/whitelist.txt"))
            require(!name.startsWith("server/data/") || name == "server/data/.gitkeep") { "State in payload" }
        }
        fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun hash(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(128 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            return hex(digest.digest())
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
