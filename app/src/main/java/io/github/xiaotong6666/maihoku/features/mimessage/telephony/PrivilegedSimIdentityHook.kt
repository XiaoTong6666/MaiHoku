package io.github.xiaotong6666.maihoku.features.mimessage.telephony

import android.app.Activity
import android.app.Application
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.telephony.SubscriptionManager
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiActivationException
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimIdentityProvider
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimIdentityStore
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig
import io.github.xiaotong6666.maihoku.features.mimessage.ipc.MiMessageAccountBridgeReceiver
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/**
 * Reads SIM identity in the process that already owns READ_PRIVILEGED_PHONE_STATE.
 * MIUI uses com.android.mms; AOSP uses the android.uid.phone shared process com.android.phone.
 */
object PrivilegedSimIdentityHook : BaseHook() {
    override val name: String = "PrivilegedTargetSimIdentity"

    private const val MIUI_MESSAGES = "com.android.mms"
    private const val AOSP_PHONE = "com.android.phone"
    private const val TAG = "PrivilegedSim"
    private val RECEIVER_CLASS = MiMessageAccountBridgeReceiver::class.java.name
    private const val ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"
    private const val ACTION_SIM_CARD_STATE_CHANGED = "android.telephony.action.SIM_CARD_STATE_CHANGED"
    private const val ACTION_SIM_APPLICATION_STATE_CHANGED = "android.telephony.action.SIM_APPLICATION_STATE_CHANGED"
    private val lastReportAt = ConcurrentHashMap<String, Long>()
    private val refreshReceivers = ConcurrentHashMap<String, BroadcastReceiver>()
    private val subscriptionListeners = ConcurrentHashMap<String, SubscriptionManager.OnSubscriptionsChangedListener>()
    private val refreshSchedules = ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MaiHoku-Privileged-SIM-Identity").apply { isDaemon = true }
    }
    private val retryDelaysMs = longArrayOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)
    private val retryableFailureKinds = setOf(
        "SIM_SUBSCRIPTION_NOT_READY",
        "SIM_NOT_READY",
        "SIM_STATE_UNAVAILABLE",
        "SUBSCRIPTION_MANAGER_UNAVAILABLE",
        "TELEPHONY_MANAGER_UNAVAILABLE",
        "PHONE_TYPE_UNAVAILABLE",
        "SIM_ICCID_UNAVAILABLE",
        "SIM_IMSI_UNAVAILABLE",
        "SIM_MCC_MNC_UNAVAILABLE",
    )

    override fun init(runtime: HookRuntime) {
        if (runtime.packageName != MIUI_MESSAGES && runtime.packageName != AOSP_PHONE) return

        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java).apply {
            isAccessible = true
        }
        runtime.hooks.method(attach, "privileged-sim-identity.${runtime.packageName}.application-attach") {
            after {
                val context = arg(0) as? Context ?: return@after
                if (context.packageName != runtime.packageName) return@after
                val appContext = context.applicationContext ?: context
                val modulePackage = runtime.module.moduleApplicationInfo.packageName
                registerRefreshTriggers(appContext, runtime.classLoader, modulePackage)
                scheduleReport(appContext, runtime.classLoader, modulePackage, "application_attach", force = true)
            }
        }

        // MIUI Messages can remain alive while the user returns to it. Refresh on foreground
        // entry so the SIM snapshot is not tied only to the process's first creation.
        if (runtime.packageName == MIUI_MESSAGES) {
            val onResume = Activity::class.java.getDeclaredMethod("onResume").apply { isAccessible = true }
            runtime.hooks.method(onResume, "privileged-sim-identity.miui-mms.activity-resume") {
                after {
                    val activity = thisObject as? Activity ?: return@after
                    if (activity.packageName != MIUI_MESSAGES) return@after
                    scheduleReport(activity.applicationContext, runtime.classLoader, runtime.module.moduleApplicationInfo.packageName, "activity_resume", force = true)
                }
            }
        }

        Log.i(TAG, "Installed target-UID SIM identity hook package=${runtime.packageName}")
    }

    private fun registerRefreshTriggers(context: Context, targetClassLoader: ClassLoader, modulePackage: String) {
        val packageName = context.packageName
        val uid = Process.myUid()
        if (uid != context.applicationInfo.uid) return
        val key = "$packageName:$uid"

        if (!refreshReceivers.containsKey(key)) {
            val filter = IntentFilter().apply {
                addAction(ACTION_SIM_STATE_CHANGED)
                addAction(ACTION_SIM_CARD_STATE_CHANGED)
                addAction(ACTION_SIM_APPLICATION_STATE_CHANGED)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiveContext: Context, intent: Intent) {
                    val slot = readSlot(intent)
                    val state = intent.getStringExtra("ss")
                        ?: intent.getStringExtra("android.telephony.extra.SIM_STATE")
                    Log.i(TAG, "SIM state event package=$packageName action=${intent.action} slot=$slot state=${state ?: "unknown"}")
                    val affectedSlots = if (slot in 0..1) listOf(slot) else listOf(0, 1)
                    if (state != null && state != "READY") {
                        affectedSlots.forEach { invalidateSlot(receiveContext, modulePackage, packageName, uid, it, "sim_state_$state") }
                    }
                    scheduleReport(
                        receiveContext.applicationContext ?: receiveContext,
                        targetClassLoader,
                        modulePackage,
                        "broadcast_${intent.action?.substringAfterLast('.') ?: "sim_state"}",
                        force = true,
                        slots = affectedSlots,
                    )
                }
            }
            if (refreshReceivers.putIfAbsent(key, receiver) == null) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        context.registerReceiver(
                            receiver,
                            filter,
                            "android.permission.READ_PRIVILEGED_PHONE_STATE",
                            null,
                            Context.RECEIVER_EXPORTED,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        context.registerReceiver(receiver, filter, "android.permission.READ_PRIVILEGED_PHONE_STATE", null)
                    }
                    Log.i(TAG, "Registered SIM state broadcasts package=$packageName uid=$uid")
                }.onFailure { error ->
                    refreshReceivers.remove(key, receiver)
                    Log.w(TAG, "Could not register SIM state broadcasts package=$packageName kind=${error.javaClass.simpleName}")
                }
            }
        }

        if (!subscriptionListeners.containsKey(key)) {
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            if (subscriptions != null) {
                val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                    override fun onSubscriptionsChanged() {
                        Log.i(TAG, "Subscription mapping changed package=$packageName uid=$uid; refreshing both SIM slots")
                        scheduleReport(
                            context,
                            targetClassLoader,
                            modulePackage,
                            "subscriptions_changed",
                            force = true,
                            slots = listOf(0, 1),
                        )
                    }
                }
                if (subscriptionListeners.putIfAbsent(key, listener) == null) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            subscriptions.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
                        } else {
                            @Suppress("DEPRECATION")
                            subscriptions.addOnSubscriptionsChangedListener(listener)
                        }
                        Log.i(TAG, "Registered SubscriptionManager listener package=$packageName uid=$uid")
                    }.onFailure { error ->
                        subscriptionListeners.remove(key, listener)
                        Log.w(TAG, "Could not register subscription listener package=$packageName kind=${error.javaClass.simpleName}")
                    }
                }
            } else {
                Log.w(TAG, "SubscriptionManager unavailable while registering SIM refresh triggers package=$packageName")
            }
        }

        if (!refreshSchedules.containsKey(key)) {
            val future = worker.scheduleAtFixedRate(
                {
                    scheduleReport(context, targetClassLoader, modulePackage, "periodic_refresh", force = true)
                },
                60,
                60,
                TimeUnit.SECONDS,
            )
            refreshSchedules.putIfAbsent(key, future)?.cancel(false)
        }
    }

    private fun readSlot(intent: Intent): Int = sequenceOf(
        "android.telephony.extra.SLOT_INDEX",
        "phone",
        "slot",
    ).firstNotNullOfOrNull { key ->
        if (intent.hasExtra(key)) intent.getIntExtra(key, -1) else null
    } ?: -1

    private fun scheduleReport(
        context: Context,
        targetClassLoader: ClassLoader,
        modulePackage: String,
        reason: String,
        force: Boolean = false,
        slots: List<Int> = listOf(0, 1),
    ) {
        val packageName = context.packageName
        val uid = Process.myUid()
        if (uid != context.applicationInfo.uid) {
            Log.w(TAG, "Rejected target identity context package=$packageName processUid=$uid appUid=${context.applicationInfo.uid}")
            return
        }
        val now = SystemClock.elapsedRealtime()
        for (slot in slots.distinct().filter { it in 0..1 }) {
            val key = "$packageName:$uid:$slot"
            val previous = lastReportAt[key] ?: 0L
            val minimumInterval = if (force) 750L else 10_000L
            if (now - previous < minimumInterval) continue
            lastReportAt[key] = now
            worker.execute {
                reportSlot(context, targetClassLoader, modulePackage, packageName, uid, slot, reason, attempt = 0)
            }
        }
    }

    private fun invalidateSlot(
        context: Context,
        modulePackage: String,
        packageName: String,
        uid: Int,
        slot: Int,
        reason: String,
    ) {
        val intent = Intent(MiMessageConfig.ACCOUNT_BRIDGE_ACTION)
            .setClassName(modulePackage, RECEIVER_CLASS)
            .putExtra(MiMessageAccountBridgeReceiver.EXTRA_METHOD, MiMessageConfig.ACCOUNT_BRIDGE_METHOD_INVALIDATE_SIM_IDENTITY)
            .putExtra(MiMessageAccountBridgeReceiver.EXTRA_SLOT, slot)
            .putExtra(XiaomiSimIdentityStore.EXTRA_SLOT, slot)
            .putExtra(XiaomiSimIdentityStore.EXTRA_SOURCE_PACKAGE, packageName)
            .putExtra(XiaomiSimIdentityStore.EXTRA_SOURCE_UID, uid)
            .putExtra(XiaomiSimIdentityStore.EXTRA_INVALIDATION_REASON, reason)
            .putExtra(XiaomiSimIdentityStore.EXTRA_INVALIDATION_REQUESTED_AT, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= 34) {
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
            context.sendBroadcast(intent, null, options)
        } else {
            context.sendBroadcast(intent)
        }
        Log.i(TAG, "Requested stale SIM identity invalidation package=$packageName uid=$uid slot=$slot reason=$reason")
    }

    private fun reportSlot(
        context: Context,
        targetClassLoader: ClassLoader,
        modulePackage: String,
        packageName: String,
        uid: Int,
        slot: Int,
        reason: String,
        attempt: Int,
    ) {
        try {
            val identity = XiaomiSimIdentityProvider.resolveFromPrivilegedTarget(context, slot, targetClassLoader)
            val intent = Intent(MiMessageConfig.ACCOUNT_BRIDGE_ACTION)
                .setClassName(modulePackage, RECEIVER_CLASS)
                .putExtra(MiMessageAccountBridgeReceiver.EXTRA_METHOD, MiMessageConfig.ACCOUNT_BRIDGE_METHOD_IMPORT_SIM_IDENTITY)
                .putExtra(MiMessageAccountBridgeReceiver.EXTRA_SLOT, identity.slotIndex)
                .putExtra(XiaomiSimIdentityStore.EXTRA_SLOT, identity.slotIndex)
                .putExtra(XiaomiSimIdentityStore.EXTRA_SUBSCRIPTION_ID, identity.subscriptionId)
                .putExtra(XiaomiSimIdentityStore.EXTRA_PHONE_TYPE, identity.phoneType)
                .putExtra(XiaomiSimIdentityStore.EXTRA_MIXIN_SIM_ID, identity.mixinSimId)
                .putExtra(XiaomiSimIdentityStore.EXTRA_SIM_ID_HASH, identity.simIdHash)
                .putExtra(XiaomiSimIdentityStore.EXTRA_MCC_MNC, identity.mccMnc)
                .putExtra(XiaomiSimIdentityStore.EXTRA_SIM_STATE, identity.simState)
                .putExtra(XiaomiSimIdentityStore.EXTRA_ICCID_LENGTH, identity.iccidLength)
                .putExtra(XiaomiSimIdentityStore.EXTRA_IMSI_LENGTH, identity.imsiLength)
                .putExtra(XiaomiSimIdentityStore.EXTRA_SOURCE, identity.source)
                .putExtra(XiaomiSimIdentityStore.EXTRA_SOURCE_PACKAGE, packageName)
                .putExtra(XiaomiSimIdentityStore.EXTRA_SOURCE_UID, uid)

            if (Build.VERSION.SDK_INT >= 34) {
                val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
                context.sendBroadcast(intent, null, options)
            } else {
                context.sendBroadcast(intent)
            }
            Log.i(
                TAG,
                "Privileged SIM identity reported package=$packageName uid=$uid slot=$slot " +
                    "subId=${identity.subscriptionId} phoneType=${identity.phoneType} " +
                    "iccidLength=${identity.iccidLength} imsiLength=${identity.imsiLength} " +
                    "mixinSimIdPresent=true activationHashPresent=true reason=$reason attempt=${attempt + 1}; values redacted",
            )
        } catch (error: Throwable) {
            val kind = (error as? XiaomiActivationException)?.kind ?: error.javaClass.simpleName
            invalidateSlot(context, modulePackage, packageName, uid, slot, "identity_read_failed_$kind")
            Log.i(
                TAG,
                "Privileged SIM identity unavailable package=$packageName uid=$uid slot=$slot " +
                    "kind=$kind attempt=${attempt + 1}",
            )
            val delayMs = retryDelaysMs.getOrNull(attempt)
            if (kind in retryableFailureKinds && delayMs != null) {
                Log.i(
                    TAG,
                    "Retrying privileged SIM identity package=$packageName uid=$uid slot=$slot " +
                        "nextAttempt=${attempt + 2} delayMs=$delayMs",
                )
                worker.schedule(
                    { reportSlot(context, targetClassLoader, modulePackage, packageName, uid, slot, reason, attempt + 1) },
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }
}
