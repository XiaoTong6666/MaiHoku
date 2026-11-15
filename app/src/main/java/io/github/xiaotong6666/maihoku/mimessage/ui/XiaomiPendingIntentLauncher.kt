package io.github.xiaotong6666.maihoku.mimessage.ui

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build

/**
 * Sends an activity PendingIntent only from an explicit, visible settings-page click.
 * Android 14+ requires the sender to opt in to background activity launch privileges
 * independently of the PendingIntent creator's opt-in.
 */
internal object XiaomiPendingIntentLauncher {
    fun sendFromUserInteraction(context: Context, pendingIntent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            val backgroundStartMode = if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            } else {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(backgroundStartMode)
                .toBundle()
            pendingIntent.send(context, 0, null, null, null, null, options)
        } else {
            pendingIntent.send()
        }
    }
}
