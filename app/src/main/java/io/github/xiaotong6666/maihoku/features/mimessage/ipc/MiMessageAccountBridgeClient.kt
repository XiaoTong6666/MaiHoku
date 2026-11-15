package io.github.xiaotong6666.maihoku.features.mimessage.ipc

import android.app.Activity
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimAccountBridge
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig
import io.github.xiaotong6666.maihoku.features.mimessage.ui.MessagingText
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Shared host-side account IPC. Calls must run on a worker; results arrive on the main looper. */
internal object MiMessageAccountBridgeClient {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun call(context: Context, modulePackageName: String, method: String, slot: Int): Bundle {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Account bridge calls must not block the main thread" }
        val completed = CountDownLatch(1)
        val response = AtomicReference<Bundle?>()
        val failure = AtomicReference<Throwable?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (resultCode == Activity.RESULT_OK) {
                    response.set(getResultExtras(false))
                } else {
                    failure.set(IllegalStateException("Account bridge rejected request: $resultCode"))
                }
                completed.countDown()
            }
        }
        val intent = Intent(MiMessageConfig.ACCOUNT_BRIDGE_ACTION)
            .setComponent(ComponentName(modulePackageName, MiMessageAccountBridgeReceiver::class.java.name))
            .putExtra(MiMessageAccountBridgeReceiver.EXTRA_METHOD, method)
            .putExtra(MiMessageAccountBridgeReceiver.EXTRA_SLOT, slot)
        if (Build.VERSION.SDK_INT >= 34) {
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
            context.sendOrderedBroadcast(intent, null, options, receiver, mainHandler, Activity.RESULT_OK, null, null)
        } else {
            context.sendOrderedBroadcast(intent, null, receiver, mainHandler, Activity.RESULT_OK, null, null)
        }
        if (!completed.await(120, TimeUnit.SECONDS)) throw TimeoutException("Account bridge timed out")
        failure.get()?.let { throw it }
        return response.get() ?: error("Account bridge returned no response")
    }
}

/** The account facts are independent of either host's Preference implementation. */
internal data class MiMessageAccountStatus(
    val simUserAvailable: Boolean,
    val mixinReady: Boolean,
    val challengePending: Boolean,
    val simIdentityAvailable: Boolean,
    val simIdentityMismatch: Boolean,
) {
    val summary: MessagingText
        get() = when {
            !simIdentityAvailable -> R.string.mx_identity_unavailable
            simIdentityMismatch -> R.string.mx_identity_mismatch
            mixinReady -> R.string.mx_identity_ready
            challengePending -> R.string.mx_identity_challenge
            simUserAvailable -> R.string.mx_identity_activated
            else -> R.string.mx_identity_not_activated
        }.let(::MessagingText)

    companion object {
        fun fromBundle(state: Bundle) = MiMessageAccountStatus(
            simUserAvailable = state.getBoolean(XiaomiSimAccountBridge.KEY_SIM_USER_AVAILABLE),
            mixinReady = state.getBoolean(XiaomiSimAccountBridge.KEY_MIXIN_READY),
            challengePending = state.getBoolean(XiaomiSimAccountBridge.KEY_CHALLENGE_PENDING),
            simIdentityAvailable = state.getBoolean(XiaomiSimAccountBridge.KEY_SIM_IDENTITY_AVAILABLE),
            simIdentityMismatch = state.getBoolean(XiaomiSimAccountBridge.KEY_SIM_IDENTITY_MISMATCH),
        )
    }
}
