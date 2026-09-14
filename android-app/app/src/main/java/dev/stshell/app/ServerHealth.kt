// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

object ServerHealth {
    data class Result(val status: Int, val text: String, val headers: Map<String, List<String>>)
    fun check(origin: LocalOrigin, username: String, password: String, version: String): JSONObject {
        val authorization = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        val cookies = linkedMapOf<String, String>()
        fun request(path: String, body: String? = null, authenticated: Boolean = true, csrf: String? = null): Result {
            val address = origin.value + path
            check(origin.allows(address))
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (authenticated) connection.setRequestProperty("Authorization", authorization)
            if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookies.values.joinToString("; "))
            if (csrf != null) connection.setRequestProperty("X-CSRF-Token", csrf)
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            return try {
                val status = connection.responseCode
                val headers = connection.headerFields.filterKeys { it != null }
                for ((key, values) in headers) if (key.equals("Set-Cookie", true)) {
                    for (value in values) { val pair = value.substringBefore(';'); cookies[pair.substringBefore('=')] = pair }
                }
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val text = stream?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(8192); val result = StringBuilder()
                    while (true) { val n = reader.read(buffer); if (n < 0) break; check(result.length + n <= 1024 * 1024); result.append(buffer, 0, n) }
                    result.toString()
                } ?: ""
                Result(status, text, headers)
            } finally { connection.disconnect() }
        }
        val tests = JSONArray()
        fun step(name: String, block: () -> Unit) { block(); tests.put(JSONObject().put("name", name).put("passed", true)) }
        step("basic-auth-required") { check(request("/csrf-token", authenticated = false).status == 401) }
        step("version") { val r = request("/version"); check(r.status == 200 && JSONObject(r.text).getString("pkgVersion") == version) }
        val token = request("/csrf-token").let { check(it.status == 200); JSONObject(it.text).getString("token") }
        step("csrf-required") { check(request("/api/ping", "{}").status == 403) }
        step("csrf-valid") { check(request("/api/ping", "{}", csrf = token).status == 204) }
        step("openai-tokenizer") {
            val r = request("/api/tokenizers/openai/encode?model=gpt-4", JSONObject().put("text", "你好 Android!").toString(), csrf = token)
            check(r.status == 200 && JSONObject(r.text).getInt("count") == 4)
        }
        return JSONObject().put("passed", true).put("tests", tests)
    }
}
