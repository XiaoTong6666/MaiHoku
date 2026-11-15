package io.github.xiaotong6666.maihoku.features.mimessage.host.common

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimAccountBridge
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageRuntimeStatus
import io.github.xiaotong6666.maihoku.features.mimessage.ipc.MiMessageAccountBridgeClient
import io.github.xiaotong6666.maihoku.features.mimessage.ipc.MiMessageAccountStatus
import io.github.xiaotong6666.maihoku.features.mimessage.ui.MessagingResources
import io.github.xiaotong6666.maihoku.features.mimessage.ui.XiaomiPendingIntentLauncher
import io.github.xiaotong6666.maihoku.features.mimessage.ui.pendingIntent
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Exposes only host-specific preference operations, keeping reflection out of account workflows. */
internal interface MiMessagePreferencePage {
    val context: Context?
    fun isVisible(): Boolean
    fun find(key: String): Any?
    fun summary(preference: Any, text: String)
    fun enabled(preference: Any, enabled: Boolean)
    fun onClick(preference: Any, action: () -> Unit)
}

/**
 * Both hosts share activation, Passport, credential refresh and Push controls. Pages receive only
 * operation results and status; the account bridge accesses private credentials under the module UID.
 *
 * Lifecycle: attach -> resume -> background query -> main-thread render. Pause stops the timer and
 * advances generation, invalidating in-flight status results. Broadcast requests cannot be recalled;
 * a late result must be discarded rather than restart polling on a paused page.
 */
internal class MiMessageSettingsController private constructor(
    private val page: MiMessagePreferencePage,
    private val modulePackageName: String,
) {
    private val appContext = checkNotNull(page.context).applicationContext
    private var generation = 0
    private var polling = false
    private val statusInFlight = mutableSetOf<Int>()
    private val busySlots = mutableSetOf<Int>()
    private val poll = Runnable { pollRuntime() }

    private fun text(id: Int, vararg args: Any) = MessagingResources.text(appContext, id, *args)
    private fun simKey(slot: Int, suffix: String) = "maihoku_xiaomi_sim${slot + 1}_$suffix"
    private fun summary(key: String, id: Int, vararg args: Any) {
        page.find(key)?.let { page.summary(it, text(id, *args)) }
    }
    private fun enabled(key: String, enabled: Boolean) {
        page.find(key)?.let { page.enabled(it, enabled) }
    }
    private fun click(key: String, action: () -> Unit) {
        page.find(key)?.let { page.onClick(it, action) }
    }

    private fun install() {
        for (slot in 0..1) {
            enabled(simKey(slot, "refresh_mixin_credentials"), false)
            enabled(simKey(slot, "passport"), false)
            click(simKey(slot, "activate")) {
                launch(slot, MiMessageConfig.ACCOUNT_BRIDGE_METHOD_START_ACTIVATION, R.string.mx_activation_opening)
            }
            click(simKey(slot, "passport")) {
                launch(slot, MiMessageConfig.ACCOUNT_BRIDGE_METHOD_OPEN_PASSPORT, R.string.mx_passport_opening)
            }
            click(simKey(slot, "refresh_mixin_credentials")) { refreshCredentials(slot) }
        }
        enabled(PUSH_RECONNECT, false)
        click(PUSH_RECONNECT) { launch(0, MiMessageConfig.ACCOUNT_BRIDGE_METHOD_START_PUSH_CORE, R.string.mx_push_preparing) }
        click(PUSH_TOGGLE) { togglePush() }
        click("maihoku_xiaomi_account_refresh") { refreshAll() }
    }

    fun resume() {
        pause()
        polling = true
        refreshAll()
        main.postDelayed(poll, POLL_INTERVAL_MS)
    }

    fun pause() {
        polling = false
        generation++
        main.removeCallbacks(poll)
    }

    private fun refreshAll() {
        for (slot in 0..1) refreshStatus(slot)
    }

    private fun refreshStatus(slot: Int) {
        if (!statusInFlight.add(slot)) return
        val expectedGeneration = generation
        request(MiMessageConfig.ACCOUNT_BRIDGE_METHOD_STATUS, slot) { result ->
            statusInFlight.remove(slot)
            if (expectedGeneration != generation || !page.isVisible()) return@request
            result.onSuccess { applyStatus(slot, it) }.onFailure {
                summary(simKey(slot, "status"), R.string.mx_status_failed, it.javaClass.simpleName)
            }
        }
    }

    private fun pollRuntime() {
        if (!polling || !page.isVisible()) {
            pause()
            return
        }
        // Coalesce per-slot STATUS requests instead of queuing one every two seconds during a timeout.
        refreshStatus(0)
        main.postDelayed(poll, POLL_INTERVAL_MS)
    }

    private fun applyStatus(slot: Int, state: Bundle) {
        val account = MiMessageAccountStatus.fromBundle(state)
        page.find(simKey(slot, "status"))?.let { page.summary(it, account.summary.resolve(appContext)) }
        val idle = slot !in busySlots
        enabled(simKey(slot, "activate"), idle && account.simIdentityAvailable && !account.simUserAvailable)
        enabled(simKey(slot, "refresh_mixin_credentials"), idle && account.simUserAvailable)
        enabled(simKey(slot, "passport"), idle && account.challengePending)
        if (slot == 0) applyRuntimeStatus(state)
    }

    private fun applyRuntimeStatus(state: Bundle) {
        val enabled = state.getBoolean(XiaomiSimAccountBridge.KEY_RUNTIME_ENABLED)
        val ready = state.getBoolean(XiaomiSimAccountBridge.KEY_MIXIN_READY)
        val pushState = state.getString(XiaomiSimAccountBridge.KEY_PUSH_STATE) ?: MiMessageRuntimeStatus.DISCONNECTED
        page.find(PUSH_STATUS)?.let {
            // Render status codes locally instead of using another process's possibly stale locale.
            page.summary(it, MiMessageRuntimeStatus.connectionSummary(enabled, pushState).resolve(appContext))
        }
        page.find(PUSH_TOGGLE)?.let { page.summary(it, MiMessageRuntimeStatus.featureSummary(enabled).resolve(appContext)) }
        enabled(PUSH_TOGGLE, 0 !in busySlots && (enabled || ready))
        enabled(PUSH_RECONNECT, 0 !in busySlots && ready)
    }

    private fun launch(slot: Int, method: String, preparing: Int) {
        if (!busySlots.add(slot)) return
        summary(simKey(slot, "status"), preparing)
        request(method, slot) { result ->
            busySlots.remove(slot)
            if (!page.isVisible()) return@request
            result.onSuccess { response ->
                val pending = response.pendingIntent(XiaomiSimAccountBridge.KEY_PENDING_INTENT)
                if (pending == null) {
                    summary(simKey(slot, "status"), R.string.mx_action_failed, response.errorKind())
                } else {
                    // Only a click-originated operation may launch a page; STATUS polling never does.
                    runCatching { XiaomiPendingIntentLauncher.sendFromUserInteraction(checkNotNull(page.context), pending) }
                        .onFailure { summary(simKey(slot, "status"), R.string.mx_page_failed, it.javaClass.simpleName) }
                }
            }.onFailure { summary(simKey(slot, "status"), R.string.mx_bridge_failed, it.javaClass.simpleName) }
            refreshStatus(slot)
        }
    }

    private fun refreshCredentials(slot: Int) {
        if (!busySlots.add(slot)) return
        enabled(simKey(slot, "refresh_mixin_credentials"), false)
        summary(simKey(slot, "status"), R.string.mx_credentials_loading)
        request(MiMessageConfig.ACCOUNT_BRIDGE_METHOD_REFRESH_MIXIN_CREDENTIALS, slot) { result ->
            busySlots.remove(slot)
            if (!page.isVisible()) return@request
            result.onSuccess { response ->
                when (response.getString(XiaomiSimAccountBridge.RESULT)) {
                    "success" -> summary(simKey(slot, "status"), R.string.mx_credentials_saved)
                    "verification_required" -> summary(simKey(slot, "status"), R.string.mx_verification_required)
                    else -> summary(simKey(slot, "status"), R.string.mx_credentials_failed, response.errorKind())
                }
            }.onFailure { summary(simKey(slot, "status"), R.string.mx_credentials_failed, it.javaClass.simpleName) }
            // An operation result is not a full snapshot. Re-query state to restore button eligibility.
            enabled(simKey(slot, "refresh_mixin_credentials"), true)
            refreshStatus(slot)
        }
    }

    private fun togglePush() {
        if (!busySlots.add(0)) return
        enabled(PUSH_TOGGLE, false)
        request(MiMessageConfig.ACCOUNT_BRIDGE_METHOD_STATUS, 0) { current ->
            val state = current.getOrElse {
                busySlots.remove(0)
                enabled(PUSH_TOGGLE, true)
                summary(PUSH_STATUS, R.string.mx_bridge_failed, it.javaClass.simpleName)
                return@request
            }
            val method = if (state.getBoolean(XiaomiSimAccountBridge.KEY_RUNTIME_ENABLED)) {
                MiMessageConfig.ACCOUNT_BRIDGE_METHOD_STOP_PUSH_CORE
            } else {
                MiMessageConfig.ACCOUNT_BRIDGE_METHOD_START_PUSH_CORE
            }
            request(method, 0) { result ->
                busySlots.remove(0)
                if (!page.isVisible()) return@request
                result.onSuccess { response ->
                    response.pendingIntent(XiaomiSimAccountBridge.KEY_PENDING_INTENT)?.let { pending ->
                        runCatching { XiaomiPendingIntentLauncher.sendFromUserInteraction(checkNotNull(page.context), pending) }
                            .onFailure { summary(PUSH_STATUS, R.string.mx_page_failed, it.javaClass.simpleName) }
                    } ?: run {
                        if (response.getString(XiaomiSimAccountBridge.RESULT) != "success") {
                            summary(PUSH_STATUS, R.string.mx_action_failed, response.errorKind())
                        }
                    }
                }.onFailure { summary(PUSH_STATUS, R.string.mx_bridge_failed, it.javaClass.simpleName) }
                enabled(PUSH_TOGGLE, true)
                refreshStatus(0)
            }
        }
    }

    private fun request(method: String, slot: Int, completed: (Result<Bundle>) -> Unit) {
        worker.execute {
            val result = runCatching { MiMessageAccountBridgeClient.call(appContext, modulePackageName, method, slot) }
            main.post { completed(result) }
        }
    }

    private fun Bundle.errorKind() = getString(XiaomiSimAccountBridge.KEY_ERROR_KIND) ?: "UNKNOWN"

    companion object {
        private const val PUSH_STATUS = "maihoku_xiaomi_push_status"
        private const val PUSH_TOGGLE = "maihoku_xiaomi_push_enabled"
        private const val PUSH_RECONNECT = "maihoku_xiaomi_push_start"
        private const val POLL_INTERVAL_MS = 2_000L
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "MaiHoku-Settings-Bridge").apply { isDaemon = true } }

        // Weak values prevent a listener/controller/page cycle from defeating weak registry keys.
        private val controllers = WeakHashMap<Any, WeakReference<MiMessageSettingsController>>()

        fun attach(owner: Any, page: MiMessagePreferencePage, modulePackageName: String) {
            stop(owner)
            val controller = MiMessageSettingsController(page, modulePackageName)
            controllers[owner] = WeakReference(controller)
            controller.install()
            controller.resume()
        }

        fun refresh(owner: Any) {
            controllers[owner]?.get()?.resume()
        }
        fun stop(owner: Any) {
            controllers[owner]?.get()?.pause()
        }
    }
}
