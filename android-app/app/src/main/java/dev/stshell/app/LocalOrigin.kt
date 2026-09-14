// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import java.net.URI

class LocalOrigin(val port: Int) {
    init { require(port in 1024..65535) }
    val value = "http://127.0.0.1:$port"
    fun allows(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == port && uri.rawUserInfo == null
    } catch (_: Exception) { false }
    fun localDocument(url: String) = allows(url) || url == "about:blank" || url.startsWith("data:") || url.startsWith("blob:$value/")
    fun authHost(host: String) = host == "127.0.0.1" || host == "127.0.0.1:$port"
    fun isRoot(url: String): Boolean = try {
        val uri = URI(url)
        allows(url) && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")
    } catch (_: Exception) { false }
}
