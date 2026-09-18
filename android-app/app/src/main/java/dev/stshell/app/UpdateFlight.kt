// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

/** Single-flight subscription, confined to the UI thread. Detaching only closes
 * the UI; reopening joins the same request, without retaining a dead Activity. */
internal class UpdateFlight<T> {
    var running = false
        private set
    private var observer: ((T) -> Unit)? = null

    /** True means the caller must start work; false means it joined existing work. */
    fun observe(callback: (T) -> Unit): Boolean {
        observer = callback
        if (running) return false
        running = true
        return true
    }

    fun detach(callback: (T) -> Unit) {
        if (observer === callback) observer = null
    }

    fun complete(value: T) {
        val callback = observer
        observer = null
        running = false
        callback?.invoke(value)
    }
}

/** APK lookup is independent of whether the content has ever been installed. */
internal fun collectUpdates(
    installed: List<InstalledComponent>,
    app: () -> AppUpdate,
    component: (InstalledComponent) -> ComponentUpdate,
): UpdateReport {
    val launcher = app()
    return UpdateReport(installed.map(component), launcher)
}
