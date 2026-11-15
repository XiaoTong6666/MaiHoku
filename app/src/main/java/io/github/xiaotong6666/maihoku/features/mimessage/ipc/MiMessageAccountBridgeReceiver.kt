package io.github.xiaotong6666.maihoku.features.mimessage.ipc

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiActivationException
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimAccountBridge
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig
import java.util.concurrent.Executors
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Explicit broadcast IPC. The receiver authenticates the source UID before accepting target-process SIM identity. */
class MiMessageAccountBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MiMessageConfig.ACCOUNT_BRIDGE_ACTION) return
        // Privileged phone-process identity refreshes use sendBroadcast(), while the
        // messaging settings page uses sendOrderedBroadcast() to collect a result.
        // Broadcast result APIs are illegal on a normal non-ordered broadcast.
        val orderedBroadcast = isOrderedBroadcast
        // AOSP stores sender identity in PendingResult, not in the Binder identity
        // of onReceive(). Both callers opt into identity sharing on Android 14+.
        // Earlier APIs have no authenticated broadcast sender identity: fail closed.
        val senderUid = if (Build.VERSION.SDK_INT >= 34) sentFromUid else Process.INVALID_UID
        val senderPackage = if (Build.VERSION.SDK_INT >= 34) sentFromPackage else null
        val senderPackages = if (senderUid >= 0) {
            runCatching { context.packageManager.getPackagesForUid(senderUid) }.getOrNull()
        } else {
            null
        }
        if (!AccountBridgeCallerPolicy.isTrusted(senderUid, senderPackage, senderPackages)) {
            Log.w(TAG, "Rejected account bridge broadcast uid=$senderUid package=$senderPackage")
            if (orderedBroadcast) {
                resultCode = Activity.RESULT_CANCELED
                setResultExtras(failure("UNTRUSTED_CALLER"))
            }
            return
        }

        val method = intent.getStringExtra(EXTRA_METHOD).orEmpty()
        if (!AccountBridgeCallerPolicy.canInvoke(senderPackage, method)) {
            Log.w(TAG, "Rejected account bridge method=$method from package=$senderPackage")
            if (orderedBroadcast) {
                resultCode = Activity.RESULT_CANCELED
                setResultExtras(failure("UNAUTHORIZED_METHOD"))
            }
            return
        }
        val slot = intent.getIntExtra(EXTRA_SLOT, -1)
        Log.i(TAG, "Accepted account bridge request senderUid=$senderUid method=$method slot=$slot")
        val pending = goAsync()
        EXECUTOR.execute {
            val response = runCatching {
                when (method) {
                    MiMessageConfig.ACCOUNT_BRIDGE_METHOD_STATUS -> XiaomiSimAccountBridge.status(context.applicationContext, slot)
                    MiMessageConfig.ACCOUNT_BRIDGE_METHOD_REFRESH_MIXIN_CREDENTIALS -> XiaomiSimAccountBridge.refreshMixinCredentials(context.applicationContext, slot)
                    MiMessageConfig.ACCOUNT_BRIDGE_METHOD_OPEN_PASSPORT -> XiaomiSimAccountBridge.openPassport(context.applicationContext, slot)
                    MiMessageConfig.ACCOUNT_BRIDGE_METHOD_START_ACTIVATION -> XiaomiSimAccountBridge.startActivation(context.applicationContext, slot)
                    MiMessageConfig.ACCOUNT_BRIDGE_METHOD_IMPORT_SIM_IDENTITY -> XiaomiSimAccountBridge.importPrivilegedSimIdentity(context.applicationContext, intent, senderUid)
                    MiMessageConfig.ACCOUNT_BRIDGE_METHOD_INVALIDATE_SIM_IDENTITY -> XiaomiSimAccountBridge.invalidatePrivilegedSimIdentity(context.applicationContext, intent, senderUid)
                    MiMessageConfig.ACCOUNT_BRIDGE_METHOD_START_PUSH_CORE -> XiaomiSimAccountBridge.startPushCore(context.applicationContext, slot)
                    MiMessageConfig.ACCOUNT_BRIDGE_METHOD_STOP_PUSH_CORE -> XiaomiSimAccountBridge.stopPushCore(context.applicationContext, slot)
                    else -> failure("UNKNOWN_METHOD")
                }
            }.getOrElse { error ->
                Log.w(TAG, "Account bridge operation failed method=$method slot=$slot kind=${error.javaClass.simpleName}")
                failure((error as? XiaomiActivationException)?.kind ?: error.javaClass.simpleName)
            }
            Log.i(
                TAG,
                "Completed account bridge request method=$method slot=$slot " +
                    "result=${response.getString(XiaomiSimAccountBridge.RESULT)} " +
                    "error=${response.getString(XiaomiSimAccountBridge.KEY_ERROR_KIND) ?: "none"}",
            )
            if (orderedBroadcast) {
                pending.setResultCode(Activity.RESULT_OK)
                pending.setResultExtras(response)
            }
            pending.finish()
        }
    }

    private fun failure(kind: String) = Bundle().apply {
        putString(XiaomiSimAccountBridge.RESULT, "failed")
        putString(XiaomiSimAccountBridge.KEY_ERROR_KIND, kind)
    }

    companion object {
        const val EXTRA_METHOD = "method"
        const val EXTRA_SLOT = "slot"
        private const val TAG = "AccountBridge"
        private val EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "MaiHoku-AccountBridge").apply { isDaemon = true }
        }
    }
}
