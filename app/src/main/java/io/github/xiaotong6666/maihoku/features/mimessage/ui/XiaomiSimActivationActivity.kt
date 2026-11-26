package io.github.xiaotong6666.maihoku.features.mimessage.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.mimessage.account.MiMessageCredentialStore
import io.github.xiaotong6666.maihoku.features.mimessage.account.MiMessageIdentityStore
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiActivationException
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiActivationPendingStore
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiActivationSmsSentReceiver
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiDeviceIdProvider
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiPendingActivation
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimAccountBridge
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimActivationClient
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimActivationCredentialStore
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimActivationSession
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimIdentity
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimIdentityStore
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimIdentityTransferPolicy
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiVKeyPair
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageManager
import java.util.concurrent.Executors
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** First-time SIM activation UI. The only SMS send occurs after the user confirms it here. */
class XiaomiSimActivationActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MaiHoku-SIM-Activation").apply { isDaemon = true }
    }
    private var slot = -1
    private lateinit var statusView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var primaryButton: Button
    private var pending: XiaomiPendingActivation? = null
    private var session: XiaomiSimActivationSession? = null
    private var finishing = false
    private var receiverRegistered = false

    private val smsResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(XiaomiActivationSmsSentReceiver.EXTRA_SLOT, -1) != slot) return
            if (intent.getBooleanExtra(XiaomiActivationSmsSentReceiver.EXTRA_SUCCESS, false)) {
                showBusy(getString(R.string.mx_sms_sent_verifying))
                verifyAndContinue()
            } else {
                val code = intent.getIntExtra(XiaomiActivationSmsSentReceiver.EXTRA_RESULT_CODE, -1)
                showError(getString(R.string.mx_sms_send_failed, code))
                primaryButton.setText(R.string.mx_sms_retry)
                primaryButton.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slot = intent.getIntExtra(EXTRA_SLOT, -1)
        if (slot !in 0..1) {
            finish()
            return
        }
        buildContent()
        registerSmsResultReceiver()
        val readyIdentity = runCatching { MiMessageIdentityStore.load(this, slot) }.getOrNull()
        if (readyIdentity != null) {
            showBusy(getString(R.string.mx_ready_connecting))
            runCatching { MiMessageManager.start(this) }
                .onFailure { error ->
                    Log.e(TAG, "Mixin credentials are ready but Xiaomi Push core could not start", error)
                    showError(getString(R.string.mx_ready_connect_failed, error.javaClass.simpleName))
                }
            primaryButton.setText(R.string.mx_return_settings)
            primaryButton.setOnClickListener { finish() }
            return
        }
        pending = null
        val restored = XiaomiActivationPendingStore.load(this, slot)
        if (restored != null) {
            showBusy(getString(R.string.mx_checking_previous_session))
            restorePending(restored)
        } else {
            showStatus(getString(R.string.mx_activation_explanation), false)
            primaryButton.setText(R.string.mx_prepare_activation)
        }
    }

    private fun buildContent() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        content.addView(
            TextView(this).apply {
                text = getString(R.string.mx_activation_title, slot + 1)
                textSize = 23f
                setTextColor(0xff202124.toInt())
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        content.addView(
            TextView(this).apply {
                setText(R.string.mx_activation_subtitle)
                textSize = 14f
                setTextColor(0xff5f6368.toInt())
                setPadding(0, dp(10), 0, dp(20))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        progressView = ProgressBar(this).apply { visibility = View.GONE }
        content.addView(
            progressView,
            LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            },
        )
        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(0xff303134.toInt())
            setPadding(0, 0, 0, dp(24))
        }
        content.addView(statusView, LinearLayout.LayoutParams(-1, -2))
        primaryButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { onPrimaryAction() }
        }
        content.addView(primaryButton, LinearLayout.LayoutParams(-1, -2))
        content.addView(
            Button(this).apply {
                setText(R.string.mx_return_settings)
                isAllCaps = false
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
    }

    private fun onPrimaryAction() {
        val current = pending
        when {
            current == null -> requestActivationPermissions()
            current.smsState == "sent" -> verifyAndContinue()
            current.smsState == "sending" -> showStatus(getString(R.string.mx_sms_waiting_receipt), false)
            else -> confirmAndSendSms(current)
        }
    }

    private fun restorePending(value: XiaomiPendingActivation) {
        showBusy(getString(R.string.mx_checking_session))
        worker.execute {
            val result = runCatching { requirePendingIdentity(value) }
            runOnUiThread {
                result.onSuccess {
                    pending = value
                    session = createSession(value)
                    when (value.smsState) {
                        "sent" -> {
                            showBusy(getString(R.string.mx_session_matches))
                            verifyAndContinue()
                        }

                        "sending" -> {
                            showStatus(getString(R.string.mx_sms_waiting_result), false)
                            primaryButton.setText(R.string.mx_sms_check_result)
                        }

                        else -> showPrepared(value)
                    }
                }.onFailure { error ->
                    val kind = (error as? XiaomiActivationException)?.kind ?: error.javaClass.simpleName
                    if (isStalePendingError(kind)) {
                        XiaomiActivationPendingStore.clear(this, slot)
                        pending = null
                        session = null
                        showStatus(
                            getString(R.string.mx_session_changed, kind),
                            true,
                        )
                        primaryButton.setText(R.string.mx_prepare_again)
                        primaryButton.setOnClickListener { requestActivationPermissions() }
                    } else {
                        pending = null
                        session = null
                        showStatus(
                            getString(R.string.mx_session_unverified, kind),
                            true,
                        )
                        primaryButton.setText(R.string.mx_check_identity_again)
                        primaryButton.setOnClickListener { restorePending(value) }
                    }
                }
            }
        }
    }

    private fun requirePendingIdentity(value: XiaomiPendingActivation): XiaomiSimIdentity {
        val current = XiaomiSimIdentityStore.currentSubscription(this, slot)
        if (!XiaomiSimIdentityTransferPolicy.matchesCurrentSubscription(
                value.attempt.subscriptionId,
                value.attempt.mccMnc,
                current.subscriptionId,
                current.mccMnc,
            )
        ) {
            // Also clear an old privileged snapshot if it belongs to the previous card.
            runCatching { XiaomiSimIdentityStore.loadForCurrentSubscription(this, slot) }
            throw XiaomiActivationException(
                "SIM_ACTIVATION_PENDING_STALE",
                "Pending activation subscription does not match the currently active SIM subscription",
            )
        }
        val identity = XiaomiSimIdentityStore.loadForCurrentSubscription(this, slot)
        if (!XiaomiActivationPendingStore.matchesIdentity(value, identity)) {
            throw XiaomiActivationException(
                "SIM_ACTIVATION_PENDING_STALE",
                "Pending activation SIM hash, Mixin sim_id, phoneType, subscription ID, or MCC/MNC does not match the current privileged SIM identity",
            )
        }
        return identity
    }

    private fun isStalePendingError(kind: String): Boolean = kind in setOf(
        "SIM_ACTIVATION_PENDING_STALE",
        "SIM_CURRENT_NOT_READY",
        "SIM_CURRENT_SUBSCRIPTION_UNAVAILABLE",
        "SIM_IDENTITY_STALE_SUBSCRIPTION",
    )

    private fun rejectStalePending(value: XiaomiPendingActivation, error: Throwable) {
        val kind = (error as? XiaomiActivationException)?.kind ?: error.javaClass.simpleName
        if (isStalePendingError(kind)) {
            XiaomiActivationPendingStore.clear(this, slot)
            pending = null
            session = null
            showStatus(getString(R.string.mx_session_changed, kind), true)
            primaryButton.setText(R.string.mx_prepare_again)
            primaryButton.setOnClickListener { requestActivationPermissions() }
        } else {
            showStatus(getString(R.string.mx_session_unverified, kind), true)
            primaryButton.setText(R.string.mx_check_identity_again)
            primaryButton.setOnClickListener { restorePending(value) }
        }
    }

    private fun requestActivationPermissions() {
        // High-privilege ICCID/IMSI reads happen in the AOSP phone process
        // (com.android.phone, UID 1001 on the target MI 5s), never under MaiHoku's UID.
        val required = arrayOf(Manifest.permission.SEND_SMS)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            prepareActivation()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            showError(getString(R.string.mx_sms_permission_missing))
            return
        }
        prepareActivation()
    }

    private fun prepareActivation() {
        showBusy(getString(R.string.mx_activation_preparing))
        worker.execute {
            val result = runCatching {
                val identity = XiaomiSimIdentityStore.loadForCurrentSubscription(this, slot)
                val existingHash = MiMessageCredentialStore.get(this, MiMessageConfig.simKey(slot, "activation_sim_id_hash"))
                if (!existingHash.isNullOrBlank()) {
                    val existing = XiaomiSimActivationCredentialStore.loadSimUser(this, slot, existingHash)
                    if (existing != null) {
                        if (existingHash == identity.simIdHash) {
                            throw XiaomiActivationException("SIM_USER_ALREADY_EXISTS", "This SIM is already activated. Refresh its Mixin credentials instead of creating another SIM user.")
                        }
                        throw XiaomiActivationException("SIM_ACTIVATION_IDENTITY_MISMATCH", "This slot contains activation credentials for a different SIM. They were not overwritten.")
                    }
                }
                val device = XiaomiDeviceIdProvider.resolve(this)
                val configuration = XiaomiSimActivationClient.fetchConfiguration()
                val attempt = configuration.createAttempt(
                    context = this,
                    slotIndex = slot,
                    subscriptionId = identity.subscriptionId,
                    simIdHash = identity.simIdHash,
                    deviceIdHash = device.deviceIdHash,
                    mccMnc = identity.mccMnc,
                )
                if (attempt.gatewayNumber.isNullOrBlank()) {
                    throw XiaomiActivationException("SMS_GATEWAY_UNAVAILABLE", "Xiaomi activation configuration did not provide an SMS gateway for this SIM's MCC/MNC")
                }
                val activationSession = XiaomiSimActivationSession(this, attempt, device, identity.mixinSimId)
                activationSession.checkClient()
                val vkeys = XiaomiVKeyPair.generate(device.deviceIdHash)
                check(XiaomiActivationPendingStore.save(this, attempt, device, identity.mixinSimId, identity.phoneType, vkeys)) {
                    "Could not save the temporary activation checkpoint in MaiHoku private storage"
                }
                XiaomiPendingActivation(attempt, device, identity.mixinSimId, identity.phoneType, vkeys, "prepared")
            }
            runOnUiThread {
                result.onSuccess { prepared ->
                    pending = prepared
                    session = createSession(prepared)
                    showPrepared(prepared)
                }.onFailure { error ->
                    val activationError = error as? XiaomiActivationException
                    val kind = activationError?.kind ?: error.javaClass.simpleName
                    if (kind == "SIM_IDENTITY_FROM_PRIVILEGED_TARGET_REQUIRED" ||
                        kind == "SIM_IDENTITY_PRIVILEGED_SNAPSHOT_MISSING" ||
                        kind == "SIM_IDENTITY_STALE_SUBSCRIPTION"
                    ) {
                        showStatus(
                            getString(R.string.mx_privileged_identity_missing, kind),
                            true,
                        )
                        primaryButton.setText(R.string.mx_check_identity_again)
                        primaryButton.setOnClickListener { prepareActivation() }
                    } else {
                        showError(getString(R.string.mx_activation_prepare_failed, kind, error.message.orEmpty()))
                    }
                }
            }
        }
    }

    private fun showPrepared(value: XiaomiPendingActivation) {
        showStatus(
            getString(R.string.mx_activation_prepared, value.attempt.slotIndex + 1, value.attempt.subscriptionId, value.attempt.mccMnc, value.attempt.gatewayNumber),
            false,
        )
        primaryButton.setText(if (value.smsState == "failed") R.string.mx_sms_retry else R.string.mx_sms_send_continue)
        primaryButton.setOnClickListener { onPrimaryAction() }
        primaryButton.isEnabled = true
    }

    private fun confirmAndSendSms(value: XiaomiPendingActivation) {
        runCatching { requirePendingIdentity(value) }.onFailure {
            rejectStalePending(value, it)
            return
        }
        val gateway = value.attempt.gatewayNumber ?: run {
            showError(getString(R.string.mx_sms_gateway_missing))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.mx_sms_confirm_title)
            .setMessage(getString(R.string.mx_sms_confirm_message, slot + 1, gateway))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mx_send) { _, _ -> sendActivationSms(value) }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun sendActivationSms(value: XiaomiPendingActivation) {
        runCatching { requirePendingIdentity(value) }.onFailure {
            rejectStalePending(value, it)
            return
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            showError(getString(R.string.mx_sms_permission_revoked))
            return
        }
        val gateway = value.attempt.gatewayNumber ?: return
        val body = value.vkeys.uplinkSmsBody(value.attempt.feature)
        showBusy(getString(R.string.mx_sms_submitting, slot + 1))
        XiaomiActivationPendingStore.markSmsState(this, slot, "sending")
        try {
            val callbackIntent = Intent(this, XiaomiActivationSmsSentReceiver::class.java)
                .setAction(XiaomiActivationSmsSentReceiver.ACTION_SMS_SENT)
                .putExtra(XiaomiActivationSmsSentReceiver.EXTRA_SLOT, slot)
            val callback = PendingIntent.getBroadcast(
                this,
                0xA200 + slot,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val smsManager = SmsManager.getSmsManagerForSubscriptionId(value.attempt.subscriptionId)
            smsManager.sendTextMessage(gateway, null, body, callback, null)
            showStatus(getString(R.string.mx_sms_submitted), false)
            primaryButton.setText(R.string.mx_sms_receipt_pending)
            primaryButton.isEnabled = false
        } catch (error: Throwable) {
            XiaomiActivationPendingStore.markSmsState(this, slot, "failed")
            showError(getString(R.string.mx_sms_submit_failed, error.javaClass.simpleName))
            primaryButton.setText(R.string.mx_sms_retry)
            primaryButton.isEnabled = true
        }
    }

    private fun verifyAndContinue() {
        val current = pending ?: XiaomiActivationPendingStore.load(this, slot)?.also { pending = it } ?: run {
            showError(getString(R.string.mx_session_missing))
            return
        }
        if (current.smsState != "sent") {
            showStatus(getString(R.string.mx_sms_not_sent), true)
            return
        }
        runCatching { requirePendingIdentity(current) }.onFailure {
            rejectStalePending(current, it)
            return
        }
        if (finishing) return
        finishing = true
        showBusy(getString(R.string.mx_sms_verifying))
        val activationSession = session ?: createSession(current).also { session = it }
        worker.execute {
            val result = runCatching {
                requirePendingIdentity(current)
                val verifiedPhone = activationSession.verify(current.vkeys)
                Log.i(TAG, "SIM verify succeeded slot=$slot; phone present=true, value redacted")
                requirePendingIdentity(current)
                activationSession.createUserAndGetMixinCredentials(
                    current.vkeys,
                    verifiedPhone,
                    beforePersistSimUser = { requirePendingIdentity(current) },
                    beforePersistMixin = { requirePendingIdentity(current) },
                )
            }
            val activationHash = MiMessageCredentialStore.get(this, MiMessageConfig.simKey(slot, "activation_sim_id_hash"))
            val activationSaved = !activationHash.isNullOrBlank() &&
                runCatching { XiaomiSimActivationCredentialStore.loadSimUser(this, slot, current.attempt.simIdHash) }.getOrNull() != null
            if (activationSaved) {
                XiaomiActivationPendingStore.clear(this, slot)
            } else {
                XiaomiActivationPendingStore.markSmsState(this, slot, "sent")
            }
            runOnUiThread {
                finishing = false
                result.onSuccess {
                    pending = null
                    showStatus(getString(R.string.mx_activation_success), false)
                    primaryButton.setText(R.string.mx_return_settings)
                    primaryButton.isEnabled = true
                    primaryButton.setOnClickListener { finish() }
                }.onFailure { error ->
                    val kind = (error as? XiaomiActivationException)?.kind ?: error.javaClass.simpleName
                    if (kind == "PASSPORT_CAPTCHA_REQUIRED") {
                        showStatus(getString(R.string.mx_activation_challenge), false)
                    } else if (activationSaved) {
                        showError(getString(R.string.mx_activation_credentials_failed, kind))
                    } else {
                        showError(getString(R.string.mx_activation_failed, kind, error.message.orEmpty()))
                    }
                    primaryButton.setText(R.string.mx_return_settings)
                    primaryButton.isEnabled = true
                    primaryButton.setOnClickListener { finish() }
                }
            }
        }
    }

    private fun createSession(value: XiaomiPendingActivation): XiaomiSimActivationSession = XiaomiSimActivationSession(
        context = this,
        attempt = value.attempt,
        deviceIdentifiers = value.device,
        mixinSimId = value.mixinSimId,
        passportTrace = { event -> Log.i(TAG, event.toString()) },
    )

    override fun onResume() {
        super.onResume()
        if (slot !in 0..1 || !::statusView.isInitialized) return
        val state = runCatching { XiaomiSimAccountBridge.status(this, slot) }.getOrNull() ?: return
        if (state.getBoolean(XiaomiSimAccountBridge.KEY_MIXIN_READY)) {
            showStatus(getString(R.string.mx_credentials_returning), false)
            primaryButton.postDelayed({ if (!isFinishing) finish() }, 700)
        }
    }

    private fun registerSmsResultReceiver() {
        val filter = IntentFilter(XiaomiActivationSmsSentReceiver.ACTION_SMS_SENT_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(smsResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(smsResultReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun showBusy(message: String) {
        progressView.visibility = View.VISIBLE
        statusView.text = message
        statusView.setTextColor(0xff303134.toInt())
        primaryButton.isEnabled = false
    }

    private fun showStatus(message: String, isError: Boolean) {
        progressView.visibility = View.GONE
        statusView.text = message
        statusView.setTextColor(if (isError) 0xffb3261e.toInt() else 0xff303134.toInt())
        if (!finishing) primaryButton.isEnabled = true
    }

    private fun showError(message: String) = showStatus(message, true)

    override fun onDestroy() {
        if (receiverRegistered) runCatching { unregisterReceiver(smsResultReceiver) }
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SLOT = "io.github.xiaotong6666.maihoku.extra.ACTIVATION_SLOT"
        private const val REQUEST_PERMISSIONS = 0xA301
        private const val TAG = "SimActivation"
    }
}
