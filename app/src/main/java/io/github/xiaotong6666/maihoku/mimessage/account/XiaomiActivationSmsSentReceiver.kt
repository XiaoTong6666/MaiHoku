package io.github.xiaotong6666.maihoku.mimessage.account

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Receives only the carrier's SMS-sent result; the activation message is never logged. */
class XiaomiActivationSmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_SENT) return
        val slot = intent.getIntExtra(EXTRA_SLOT, -1)
        if (slot !in 0..1) return
        val success = resultCode == Activity.RESULT_OK
        val state = if (success) "sent" else "failed"
        XiaomiActivationPendingStore.markSmsState(context, slot, state)
        Log.i(TAG, "Activation SMS result slot=$slot sent=$success resultCode=$resultCode")
        context.sendBroadcast(
            Intent(ACTION_SMS_SENT_RESULT)
                .setPackage(context.packageName)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_SUCCESS, success)
                .putExtra(EXTRA_RESULT_CODE, resultCode),
        )
    }

    companion object {
        const val ACTION_SMS_SENT = "io.github.xiaotong6666.maihoku.action.ACTIVATION_SMS_SENT"
        const val ACTION_SMS_SENT_RESULT = "io.github.xiaotong6666.maihoku.action.ACTIVATION_SMS_RESULT"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_RESULT_CODE = "result_code"
        private const val TAG = "SimActivation"
    }
}
