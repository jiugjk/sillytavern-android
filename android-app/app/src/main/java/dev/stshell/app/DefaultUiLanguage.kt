// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** First-run ST UI locale. ST itself stores this in WebView localStorage, not config.yaml. */
object DefaultUiLanguage {
    const val CODE = "zh-cn"
    const val PREF_FILE = "st-shell"
    const val PREF_SEEDED = "ui_language_seeded"
    fun continueUrl(origin: LocalOrigin) = "${origin.value}/index.html"
    fun document(origin: LocalOrigin): ByteArray {
        val dest = JSONObject.quote(continueUrl(origin))
        return ("<!DOCTYPE html><html><head><meta charset=utf-8><script>" +
            "try{if(!localStorage.getItem('language'))localStorage.setItem('language','$CODE');}catch(e){}" +
            "location.replace($dest);</script></head><body></body></html>")
            .toByteArray(StandardCharsets.UTF_8)
    }
}
