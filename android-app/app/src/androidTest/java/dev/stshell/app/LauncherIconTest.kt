// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    @Test fun launcherTaskAndNotificationUseSillyTavernResources() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
            assertTrue(context.getDrawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
            assertTrue(context.getDrawable(R.mipmap.ic_launcher_round) is AdaptiveIconDrawable)
            val manager = context.getSystemService(ActivityManager::class.java)
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (manager.appTasks.firstOrNull { it.taskInfo.taskId == activity.taskId }?.taskInfo?.taskDescription?.label != context.getString(R.string.app_name)) {
                assertTrue("Task branding update did not arrive", SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(100)
            }
            val large = LauncherBranding.notificationLargeIcon(context)
            val notification = LauncherBranding.notificationBuilder(context, "icon-test", large)
                .setContentTitle("SillyTavern").build()
            assertEquals(R.drawable.ic_stat_sillytavern, notification.smallIcon.resId)
            assertNotNull(notification.getLargeIcon())
            assertTrue(large.width >= 64 && large.width == large.height)
            val pixels = IntArray(large.width * large.height)
            large.getPixels(pixels, 0, large.width, 0, 0, large.width, large.height)
            assertTrue(pixels.any { Color.alpha(it) > 200 && Color.red(it) > 2 * Color.green(it) && Color.red(it) > 2 * Color.blue(it) })
            assertTrue(pixels.any { Color.alpha(it) > 200 && Color.red(it) > 240 && Color.green(it) > 240 && Color.blue(it) > 240 })
            val small = requireNotNull(context.getDrawable(R.drawable.ic_stat_sillytavern))
            val mask = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
            small.setBounds(0, 0, 72, 72); small.draw(Canvas(mask))
            assertEquals(0, Color.alpha(mask.getPixel(0, 0)))
            val maskPixels = IntArray(72 * 72)
            mask.getPixels(maskPixels, 0, 72, 0, 0, 72, 72)
            assertTrue(maskPixels.any { Color.alpha(it) > 0 })
            assertTrue(maskPixels.filter { Color.alpha(it) > 0 }.all { Color.red(it) == 255 && Color.green(it) == 255 && Color.blue(it) == 255 })
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
