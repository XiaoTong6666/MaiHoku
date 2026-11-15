package io.github.xiaotong6666.maihoku.features.mimessage.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Restores the user's explicitly enabled, own-UID foreground Push service. */
class MiMessageBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == ACTION_SIM_STATE_CHANGED) {
            val state = intent.getStringExtra(EXTRA_SIM_STATE)
            if (state != SIM_STATE_READY) return
        }
        if (!MiMessageManager.isEnabled(context)) return
        try {
            MiMessageManager.resumeIfEnabled(context)
            Log.i(TAG, "Persistent Xiaomi Push restore requested action=$action")
        } catch (error: Throwable) {
            Log.e(TAG, "Could not restore persistent Xiaomi Push action=$action", error)
        }
    }

    companion object {
        private const val TAG = "MiMessageBoot"
        private const val ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"
        private const val EXTRA_SIM_STATE = "ss"
        private const val SIM_STATE_READY = "READY"
    }
}
