// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * Read-only update check against the same GitHub endpoints the on-device
 * installer already uses. It downloads nothing but metadata: the actual update
 * is the existing reinstall path, which re-resolves every ref under the native
 * lock. Requests run on one daemon thread; callbacks land on the main thread.
 */
object UpdateChecker {
    private const val API_HOST = "api.github.com"
    private const val USER_AGENT = "ST-Android-Launcher"
    private const val METADATA_LIMIT = 1024 * 1024
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 20_000
    private val REF = Regex("[A-Za-z0-9._/-]{1,128}")

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "UpdateChecker").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private val flight = UpdateFlight<Pair<UpdateReport?, String?>>()

    /** UI-thread only: a new observer replaces the previous dialog/Activity. */
    fun check(context: Context, callback: (Pair<UpdateReport?, String?>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!flight.observe(callback)) return
        val app = context.applicationContext
        io.execute {
            var report: UpdateReport? = null
            var failure: String? = null
            try {
                report = collect(app)
            } catch (error: Exception) {
                failure = describe(app, error)
            } finally {
                val result = report
                val message = failure
                main.post {
                    flight.complete(result to message)
                }
            }
        }
    }

    fun detach(callback: (Pair<UpdateReport?, String?>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        flight.detach(callback)
    }

    private fun collect(app: Context): UpdateReport =
        collectUpdates(installedComponents(app), { appUpdate(app) }) { component ->
            try {
                ComponentUpdate(component, latestCommit(component.slug, component.ref))
            } catch (error: Exception) {
                ComponentUpdate(component, error = describe(app, error))
            }
        }

    /** The launcher's own release, which the user installs by hand. */
    private fun appUpdate(app: Context): AppUpdate {
        val version = AppVersions.normalize(installedVersion(app))
        val fallback = app.getString(R.string.about_releases_url)
        val slug = UpdateCatalog.slug(app.getString(R.string.about_repository_url))
            ?: return AppUpdate(version, releaseUrl = fallback, error = app.getString(R.string.update_state_unknown))
        return try {
            val release = JSONObject(get("https://$API_HOST/repos/$slug/releases/latest",
                "application/vnd.github+json", METADATA_LIMIT))
            val tag = AppVersions.normalize(release.optString("tag_name"))
            val page = release.optString("html_url")
            val url = if (page.startsWith("https://github.com/$slug/releases/")) page else fallback
            val code = app.packageManager.getPackageInfo(app.packageName,
                PackageManager.PackageInfoFlags.of(0)).longVersionCode
            AppUpdate(version, tag, url, installedCode = code)
        } catch (error: Exception) {
            AppUpdate(version, releaseUrl = fallback, error = describe(app, error))
        }
    }

    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName,
            PackageManager.PackageInfoFlags.of(0)).versionName ?: ""
    } catch (_: Exception) { "" }

    /** Newest commit for a moving ref, exactly as the installer would resolve it. */
    private fun latestCommit(slug: String, ref: String): String {
        require(REF.matches(ref)) { "Unsupported ref" }
        val encoded = URLEncoder.encode(ref, "UTF-8")
        // The `.sha` media type answers with the bare commit; a proxy that drops
        // it hands back the ordinary commit JSON, which carries the same field.
        val body = get("https://$API_HOST/repos/$slug/commits/$encoded",
            "application/vnd.github.sha", METADATA_LIMIT).trim()
        if (UpdateCatalog.isCommit(body)) return body.lowercase()
        val parsed = JSONObject(body).optString("sha")
        check(UpdateCatalog.isCommit(parsed)) { "Unexpected commit response" }
        return parsed.lowercase()
    }

    /** HTTPS GET restricted to the GitHub API host, with manual redirect vetting. */
    private fun get(url: String, accept: String, limit: Int): String {
        var target = url
        for (attempt in 0 until 4) {
            val parsed = URL(target)
            require(parsed.protocol == "https" && parsed.host == API_HOST && parsed.userInfo == null) {
                "Untrusted update origin"
            }
            val connection = parsed.openConnection() as HttpsURLConnection
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                when (val status = connection.responseCode) {
                    HttpsURLConnection.HTTP_OK -> return connection.inputStream.use { read(it, limit) }
                    HttpsURLConnection.HTTP_MOVED_PERM, HttpsURLConnection.HTTP_MOVED_TEMP,
                    HttpsURLConnection.HTTP_SEE_OTHER, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                        requireNotNull(location) { "Missing redirect location" }
                        target = URL(parsed, location).toString()
                    }
                    HttpsURLConnection.HTTP_FORBIDDEN, 429 -> error("GitHub API rate limit (HTTP $status)")
                    else -> error("GitHub API HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }

    private fun read(stream: InputStream, limit: Int): String {
        val buffer = ByteArray(16 * 1024)
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            check(output.size() + count <= limit) { "Update metadata is too large" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /**
     * What the device believes is installed. installation-info.json is rewritten
     * on every launch; the committed pointer plus its payload manifest keeps the
     * check working when the service has not run in this session.
     */
    private fun installedComponents(context: Context): List<InstalledComponent> {
        val current = File(AppFiles.state(context), "installation-info.json")
        if (current.isFile) {
            try {
                return UpdateCatalog.installed(AppFiles.readJson(current, METADATA_LIMIT.toLong()))
            } catch (_: Exception) { /* Fall through to the committed installation. */ }
        }
        return try {
            val base = File(context.noBackupFilesDir, "st-installations")
            val pointer = AppFiles.readJson(File(base, "current.json"), 65536)
            val payloadId = pointer.getString("payloadId")
            require(Regex("[a-f0-9]{64}").matches(payloadId))
            UpdateCatalog.installed(AppFiles.readJson(File(File(base, payloadId), "payload-manifest.json"),
                32L * 1024 * 1024))
        } catch (_: Exception) { emptyList() }
    }

    /** Failure text a user can act on, without leaking paths or credentials. */
    private fun describe(app: Context, error: Exception): String {
        val message = error.message?.takeIf { it.isNotBlank() }
        return when (error) {
            is java.net.UnknownHostException, is java.net.ConnectException,
            is java.net.SocketTimeoutException, is javax.net.ssl.SSLException ->
                app.getString(R.string.update_network_unavailable)
            else -> message ?: error.javaClass.simpleName
        }
    }
}
