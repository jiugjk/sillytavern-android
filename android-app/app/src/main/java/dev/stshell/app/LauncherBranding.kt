// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.ceil

object LauncherBranding {
    fun applyTaskIcon(activity: Activity) {
        activity.setTaskDescription(ActivityManager.TaskDescription.Builder()
            .setLabel(activity.getString(R.string.app_name))
            .setIcon(R.mipmap.ic_launcher)
            .build())
    }

    fun notificationBuilder(context: Context, channel: String, largeIcon: Bitmap): Notification.Builder =
        Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_sillytavern)
            .setLargeIcon(largeIcon)

    fun notificationLargeIcon(context: Context): Bitmap {
        val size = ceil(64 * context.resources.displayMetrics.density).toInt().coerceIn(64, 256)
        val drawable = requireNotNull(context.getDrawable(R.mipmap.ic_launcher)).mutate()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
