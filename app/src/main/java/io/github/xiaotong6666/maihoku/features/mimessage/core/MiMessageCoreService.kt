package io.github.xiaotong6666.maihoku.features.mimessage.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SubscriptionManager
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.mimessage.account.MiMessageCredentialStore
import io.github.xiaotong6666.maihoku.features.mimessage.account.MiMessageIdentityStore
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiActivationException
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimIdentityStore
import io.github.xiaotong6666.maihoku.features.mimessage.ipc.MiMessageInboxBridge
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageEventProcessor
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageInboxCoordinator
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageInboxDispatcher
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageInboxStore
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageOutboxStore
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageOutgoingRecord
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageOutgoingStatus
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageReceivedAckSender
import io.github.xiaotong6666.maihoku.features.mimessage.presence.MiMessagePresenceManager
import io.github.xiaotong6666.maihoku.features.mimessage.presence.MiMessagePresencePolicy
import io.github.xiaotong6666.maihoku.features.mimessage.transport.MiMessagePacketCodec
import io.github.xiaotong6666.maihoku.features.mimessage.transport.SlimProtocol
import io.github.xiaotong6666.maihoku.features.mimessage.transport.XiaomiPushClient
import io.github.xiaotong6666.maihoku.features.mimessage.transport.XiaomiPushState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Own-UID foreground service for the long-lived Xiaomi Push connection. */
internal class MiMessageCoreService : Service() {
    private var pushClient: XiaomiPushClient? = null
    private var presenceManager: MiMessagePresenceManager? = null
    private var boundSubscriptionId = -1
    private var boundSimIdHash: String? = null
    private var subscriptionManager: SubscriptionManager? = null
    private var subscriptionListener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var simMonitor: ScheduledExecutorService? = null
    private var inboxStore: MiMessageInboxStore? = null
    private var outgoingStore: MiMessageOutboxStore? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val outgoingExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MaiHoku-MiMessage-Outgoing").apply { isDaemon = true }
    }
    private val outgoingInFlight = ConcurrentHashMap.newKeySet<String>()
    private val outgoingTimeouts = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private var outboxRecovered = false

    @Volatile private var stoppingForSimChange = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MiMessageManager.stop(this)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!MiMessageManager.isEnabled(this)) {
            MiMessageManager.recordPushState(this, MiMessageRuntimeStatus.DISCONNECTED)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startAsForeground()
        val presence = presenceManager ?: MiMessagePresenceManager(this) { pushClient }
            .also { presenceManager = it }
        val outgoingRequestId = intent
            ?.takeIf { it.action == ACTION_QUEUE_OUTGOING }
            ?.getStringExtra(EXTRA_OUTGOING_REQUEST_ID)
        if (outgoingRequestId != null) {
            runCatching {
                val hostPackage = requireNotNull(intent?.getStringExtra(EXTRA_OUTGOING_HOST_PACKAGE))
                val phoneNumber = requireNotNull(intent?.getStringExtra(EXTRA_OUTGOING_PHONE))
                val body = requireNotNull(intent?.getStringExtra(EXTRA_OUTGOING_BODY))
                outgoingStore().enqueue(outgoingRequestId, hostPackage, phoneNumber, body)
                Log.i(TAG, "Outgoing Mi Message persisted request_id_length=${outgoingRequestId.length} body_chars=${body.length}")
            }.onFailure { error ->
                Log.e(TAG, "Could not persist outgoing Mi Message request: ${error.javaClass.simpleName}")
                intent?.getStringExtra(EXTRA_OUTGOING_HOST_PACKAGE)?.let { hostPackage ->
                    MiMessageInboxBridge.dispatchOutgoingStatus(
                        this,
                        hostPackage,
                        outgoingRequestId,
                        MiMessageOutgoingStatus.FAILED,
                        "OUTBOX_PERSIST_FAILED",
                    )
                }
            }
        }
        if (!outboxRecovered) {
            outboxRecovered = true
            runCatching {
                outgoingStore().recoverInterrupted()
                outgoingStore().recentHostStatusUpdates()
            }.getOrDefault(emptyList()).forEach { record ->
                MiMessageInboxBridge.dispatchOutgoingStatus(
                    this,
                    record.hostPackage,
                    record.requestId,
                    record.status,
                    record.error,
                )
            }
        }
        val requestedPresencePhone = intent
            ?.takeIf { it.action == ACTION_QUERY_PRESENCE }
            ?.getStringExtra(EXTRA_PRESENCE_PHONE)
        if (requestedPresencePhone != null) {
            val digits = MiMessagePresencePolicy.normalizePhoneNumber(requestedPresencePhone)?.count(Char::isDigit) ?: 0
            Log.i(TAG, "Core Service received Presence query recipient_digits=$digits")
        }
        val existingState = pushClient?.state
        if (existingState in setOf(
                XiaomiPushState.CONNECTING,
                XiaomiPushState.AUTHENTICATING,
                XiaomiPushState.BINDING,
                XiaomiPushState.BOUND,
                XiaomiPushState.RECONNECTING,
            )
        ) {
            requestedPresencePhone?.let(presence::request)
            if (existingState == XiaomiPushState.BOUND) flushPendingOutgoing()
            updateForegroundNotification(existingState?.name ?: MiMessageRuntimeStatus.CONNECTING)
            return START_STICKY
        }
        pushClient?.close()
        try {
            // Revalidate the SIM in the service process. The UI preflight is not a substitute
            // for checking the subscription immediately before opening a long-lived socket.
            val currentSubscription = XiaomiSimIdentityStore.currentSubscription(this, 0)
            val currentSim = XiaomiSimIdentityStore.loadForCurrentSubscription(this, 0)
            if (currentSubscription.subscriptionId != currentSim.subscriptionId) {
                throw XiaomiActivationException(
                    "SIM_IDENTITY_STALE_SUBSCRIPTION",
                    "Current SIM subscription changed during Push startup",
                )
            }
            val activationHash = MiMessageCredentialStore.get(
                this,
                MiMessageConfig.simKey(0, "activation_sim_id_hash"),
            )
            if (activationHash != currentSim.simIdHash) {
                throw XiaomiActivationException(
                    "SIM_ACTIVATION_IDENTITY_MISMATCH",
                    "Stored activation identity does not match the current SIM",
                )
            }
            val identity = MiMessageIdentityStore.load(this, 0)
            boundSubscriptionId = currentSubscription.subscriptionId
            boundSimIdHash = currentSim.simIdHash
            MiMessageManager.recordBoundIdentity(this, boundSubscriptionId, currentSim.simIdHash)
            registerSubscriptionMonitor()
            MiMessageManager.recordPushState(this, MiMessageRuntimeStatus.CONNECTING)
            val client = XiaomiPushClient(
                context = this,
                identity = identity,
                autoReconnect = true,
                keepAliveIntervalMs = XiaomiPushClient.DEFAULT_KEEPALIVE_INTERVAL_MS,
                listener = object : XiaomiPushClient.Listener {
                    override fun onStateChanged(state: XiaomiPushState, detail: String?) {
                        Log.i(TAG, "Push state=$state detail=${detail ?: "none"}")
                        presenceManager?.onPushState(state)
                        updateForegroundNotification(state.name)
                        if (state == XiaomiPushState.CLOSED && !MiMessageManager.isEnabled(this@MiMessageCoreService)) {
                            return
                        }
                        MiMessageManager.recordPushState(this@MiMessageCoreService, state.name)
                        if (state == XiaomiPushState.FAILED) {
                            MiMessageManager.disableAfterFatalError(this@MiMessageCoreService)
                            stopSelf()
                        }
                    }

                    override fun onBindAccepted(endpoint: String) {
                        Log.i(TAG, "ChID=3 BIND accepted endpoint=$endpoint")
                        presenceManager?.onBindAccepted()
                        flushPendingReceivedAcks()
                        flushPendingOutgoing()
                    }

                    override fun onServerConfigReceived(payloadBytes: Int) {
                        Log.i(TAG, "Server config received bytes=$payloadBytes")
                    }

                    override fun onServerPing() {
                        Log.d(TAG, "Server PING received")
                    }

                    override fun onPresence(packet: SlimProtocol.PresencePacket) {
                        val fromMid = packet.from?.substringBefore('/')?.substringBefore('@')
                        Log.i(
                            TAG,
                            "Presence received type=${packet.type} from_local_mid=${fromMid == identity.mid} " +
                                "capability_count=${SlimProtocol.parseClientCapabilities(packet.clientAttributes).size}",
                        )
                        presenceManager?.onPresence(packet)
                    }

                    override fun onFrame(command: String, channelId: Long, payloadBytes: Int) {
                        Log.d(TAG, "Slim frame command=$command chid=$channelId bytes=$payloadBytes")
                    }

                    override fun onMessage(packet: MiMessagePacketCodec.MessagePacket) {
                        when (val event = MiMessageEventProcessor.classify(packet, identity)) {
                            is MiMessageEventProcessor.Event.IncomingSms -> runCatching {
                                handleIncomingSms(event)
                            }.onFailure { error ->
                                Log.e(TAG, "Incoming Mi SMS could not be persisted; received ACK was not sent: ${error.javaClass.simpleName}")
                            }

                            is MiMessageEventProcessor.Event.ServerAccepted -> handleOutgoingServerAccepted(event.messageId)

                            is MiMessageEventProcessor.Event.DeliveryReceipt -> handleOutgoingDeliveryReceipt(event)

                            is MiMessageEventProcessor.Event.Unsupported -> Log.d(
                                TAG,
                                "Ignored unsupported Mi Message stanza reason=${event.reason}",
                            )
                        }
                    }

                    override fun onKicked(type: String?, reason: String?) {
                        Log.w(TAG, "Server KICK type=${type ?: "absent"} reason_length=${reason?.length ?: 0}")
                    }

                    override fun onError(kind: String, detail: String?) {
                        Log.w(TAG, "Push client error kind=$kind detail=${detail ?: "none"}")
                    }
                },
            )
            // Publish the client reference before starting its IO thread so callbacks can ACK
            // immediately after BIND without racing the property assignment.
            pushClient = client
            registerNetworkMonitor()
            requestedPresencePhone?.let(presence::request)
            check(client.start()) { "Push client was already started" }
        } catch (error: Throwable) {
            Log.e(TAG, "Could not start Xiaomi Push core: ${error.javaClass.simpleName}")
            val kind = (error as? XiaomiActivationException)?.kind.orEmpty()
            val state = when {
                kind.contains("MISMATCH", ignoreCase = true) || kind.contains("STALE_SUBSCRIPTION", ignoreCase = true) ->
                    MiMessageRuntimeStatus.SIM_CHANGED

                kind.startsWith("SIM_", ignoreCase = true) -> MiMessageRuntimeStatus.SIM_IDENTITY_UNAVAILABLE

                else -> MiMessageRuntimeStatus.FAILED
            }
            if (state == MiMessageRuntimeStatus.SIM_IDENTITY_UNAVAILABLE) {
                // SIM state and the privileged com.android.phone identity snapshot can arrive
                // after BOOT_COMPLETED. Keep the user's enabled preference so SIM_STATE_CHANGED
                // can restore the foreground Push service when the card becomes READY.
                MiMessageManager.recordPushState(this, state)
            } else {
                MiMessageManager.disableForStartupFailure(this, state)
            }
            stopSelf(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterSubscriptionMonitor()
        unregisterNetworkMonitor()
        pushClient?.close()
        pushClient = null
        presenceManager?.close()
        presenceManager = null
        inboxStore?.close()
        inboxStore = null
        outgoingTimeouts.values.forEach { it.cancel(true) }
        outgoingTimeouts.clear()
        outgoingExecutor.shutdownNow()
        outgoingStore?.close()
        outgoingStore = null
        releaseCpuWakeLock()
        if (MiMessageManager.isEnabled(this)) {
            MiMessageManager.recordPushState(this, MiMessageRuntimeStatus.DISCONNECTED)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Mi Message Push core stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleIncomingSms(event: MiMessageEventProcessor.Event.IncomingSms) {
        val client = pushClient
        val coordinator = MiMessageInboxCoordinator(
            repository = inboxRepository(),
            ackSender = MiMessageReceivedAckSender { senderJid, originalPacketId ->
                check(client?.state == XiaomiPushState.BOUND) { "ChID=3 is not bound; received ACK remains pending" }
                requireNotNull(client).sendReceivedAck(senderJid, originalPacketId)
            },
            dispatcher = MiMessageInboxDispatcher { message ->
                MiMessageInboxBridge.dispatch(this, message)
            },
        )
        val result = coordinator.receive(event, boundSubscriptionId)
        Log.i(
            TAG,
            "Incoming Mi SMS persisted inserted=${result.inserted} ack_frame_written=${result.ackFrameWritten} " +
                "provider_import_dispatched=${result.importDispatched} body_chars=${event.body.length} " +
                "message_id_length=${event.messageId.length}",
        )
    }

    private fun flushPendingReceivedAcks() {
        val client = pushClient ?: return
        if (client.state != XiaomiPushState.BOUND) return
        val coordinator = MiMessageInboxCoordinator(
            repository = inboxRepository(),
            ackSender = MiMessageReceivedAckSender { senderJid, originalPacketId ->
                client.sendReceivedAck(senderJid, originalPacketId)
            },
            dispatcher = MiMessageInboxDispatcher { MiMessageInboxBridge.dispatch(this, it) },
        )
        val written = coordinator.retryPendingAcks()
        if (written > 0) Log.i(TAG, "Deferred received ACK frames written count=$written")
    }

    private fun inboxRepository(): MiMessageInboxStore = inboxStore ?: MiMessageInboxStore(this).also { inboxStore = it }

    private fun outgoingStore(): MiMessageOutboxStore = outgoingStore ?: MiMessageOutboxStore(this).also { outgoingStore = it }

    private fun flushPendingOutgoing() {
        if (pushClient?.state != XiaomiPushState.BOUND) return
        val pending = runCatching { outgoingStore().pendingToSend() }.getOrElse { error ->
            Log.e(TAG, "Could not read pending outgoing Mi Messages: ${error.javaClass.simpleName}")
            return
        }
        pending.forEach { record ->
            if (!outgoingInFlight.add(record.requestId)) return@forEach
            runCatching {
                outgoingExecutor.execute { sendOutgoing(record) }
            }.onFailure { error ->
                outgoingInFlight.remove(record.requestId)
                Log.e(TAG, "Could not schedule outgoing Mi Message: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun sendOutgoing(record: MiMessageOutgoingRecord) {
        val store = outgoingStore()
        try {
            if (!store.markResolving(record.requestId)) return
            MiMessageInboxBridge.dispatchOutgoingStatus(
                this,
                record.hostPackage,
                record.requestId,
                MiMessageOutgoingStatus.RESOLVING,
            )
            val mid = presenceManager?.sendTargetMid(record.phoneNumber)
                ?: error("RECIPIENT_NOT_AVAILABLE_FOR_MI_MESSAGE")
            val client = pushClient?.takeIf { it.state == XiaomiPushState.BOUND }
                ?: error("XIAOMI_PUSH_NOT_BOUND")
            val packetId = client.sendSms(mid, record.body)
            check(store.markSubmitted(record.requestId, packetId, mid)) {
                "OUTBOX_STATE_CHANGED_BEFORE_SUBMIT"
            }
            Log.i(
                TAG,
                "Outgoing Mi Message submitted request_id_length=${record.requestId.length} " +
                    "packet_id_length=${packetId.length} body_chars=${record.body.length}",
            )
            MiMessageInboxBridge.dispatchOutgoingStatus(
                this,
                record.hostPackage,
                record.requestId,
                MiMessageOutgoingStatus.SUBMITTED,
            )
            val timeout = outgoingExecutor.schedule({
                val unconfirmed = store.markUnconfirmed(packetId)
                if (unconfirmed != null && unconfirmed.status == MiMessageOutgoingStatus.UNCONFIRMED) {
                    Log.w(TAG, "Outgoing Mi Message server receipt timed out packet_id_length=${packetId.length}")
                    MiMessageInboxBridge.dispatchOutgoingStatus(
                        this,
                        unconfirmed.hostPackage,
                        unconfirmed.requestId,
                        MiMessageOutgoingStatus.UNCONFIRMED,
                        unconfirmed.error,
                    )
                }
                outgoingTimeouts.remove(packetId)
            }, SERVER_SENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            outgoingTimeouts.put(packetId, timeout)?.cancel(false)
        } catch (error: Throwable) {
            val failed = store.markFailed(record.requestId, safeOutgoingError(error))
            Log.e(TAG, "Outgoing Mi Message failed request_id_length=${record.requestId.length} error=${error.javaClass.simpleName}")
            if (failed != null) {
                MiMessageInboxBridge.dispatchOutgoingStatus(
                    this,
                    failed.hostPackage,
                    failed.requestId,
                    MiMessageOutgoingStatus.FAILED,
                    failed.error,
                )
            }
        } finally {
            outgoingInFlight.remove(record.requestId)
        }
    }

    private fun handleOutgoingServerAccepted(packetId: String) {
        runCatching {
            outgoingExecutor.execute {
                outgoingTimeouts.remove(packetId)?.cancel(false)
                val accepted = outgoingStore().markServerAccepted(packetId) ?: return@execute
                Log.i(TAG, "Outgoing Mi Message server sent receipt matched=true request_id_length=${accepted.requestId.length}")
                MiMessageInboxBridge.dispatchOutgoingStatus(
                    this,
                    accepted.hostPackage,
                    accepted.requestId,
                    accepted.status,
                    accepted.error,
                )
            }
        }
    }

    private fun handleOutgoingDeliveryReceipt(event: MiMessageEventProcessor.Event.DeliveryReceipt) {
        runCatching {
            outgoingExecutor.execute {
                outgoingTimeouts.remove(event.messageId)?.cancel(false)
                val current = outgoingStore().findByPacketId(event.messageId) ?: return@execute
                val hasError = event.errorType != null || event.errorReason != null || event.errorText != null
                val updated = if (hasError) {
                    outgoingStore().markFailed(
                        current.requestId,
                        "DELIVERY_RECEIPT_${event.errorType ?: event.errorReason ?: "ERROR"}",
                    )
                } else {
                    outgoingStore().markDelivered(event.messageId)
                } ?: return@execute
                Log.i(TAG, "Outgoing Mi Message delivery receipt matched=true status=${updated.status}")
                MiMessageInboxBridge.dispatchOutgoingStatus(
                    this,
                    updated.hostPackage,
                    updated.requestId,
                    updated.status,
                    updated.error,
                )
            }
        }
    }

    private fun safeOutgoingError(error: Throwable): String = when (error.message) {
        "RECIPIENT_NOT_AVAILABLE_FOR_MI_MESSAGE" -> "RECIPIENT_NOT_AVAILABLE_FOR_MI_MESSAGE"
        "XIAOMI_PUSH_NOT_BOUND" -> "XIAOMI_PUSH_NOT_BOUND"
        "OUTBOX_STATE_CHANGED_BEFORE_SUBMIT" -> "OUTBOX_STATE_CHANGED_BEFORE_SUBMIT"
        else -> "${error.javaClass.simpleName.uppercase()}_DURING_SEND"
    }.take(96)

    private fun registerSubscriptionMonitor() {
        if (simMonitor != null) return
        val manager = getSystemService(SubscriptionManager::class.java) ?: return
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                simMonitor?.execute(::verifyBoundSubscription)
            }
        }
        subscriptionManager = manager
        subscriptionListener = listener
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.addOnSubscriptionsChangedListener(mainExecutor, listener)
            } else {
                @Suppress("DEPRECATION")
                manager.addOnSubscriptionsChangedListener(listener)
            }
        }.onFailure { error ->
            subscriptionListener = null
            subscriptionManager = null
            Log.w(TAG, "Could not register SIM subscription monitor: ${error.javaClass.simpleName}")
        }
        val executor = simMonitor ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "MaiHoku-MiMessage-SIM-Monitor").apply { isDaemon = true }
        }.also { simMonitor = it }
        executor.scheduleAtFixedRate(::verifyBoundSubscription, 60, 60, TimeUnit.SECONDS)
    }

    private fun verifyBoundSubscription() {
        if (stoppingForSimChange || boundSubscriptionId <= 0) return
        val current = runCatching { XiaomiSimIdentityStore.currentSubscription(this, 0) }.getOrElse { error ->
            Log.w(TAG, "Could not verify the active SIM subscription; stopping Push", error)
            stopForSimChange(MiMessageRuntimeStatus.SIM_IDENTITY_UNAVAILABLE)
            return
        }
        if (current.subscriptionId != boundSubscriptionId) {
            Log.w(TAG, "SIM subscription changed while Push was active; stopping old ChID=3 identity")
            stopForSimChange(MiMessageRuntimeStatus.SIM_CHANGED)
            return
        }

        // A fresh privileged snapshot can detect a replacement SIM even if the carrier reused
        // the same subscription slot mapping. An absent/stale snapshot alone is not treated as
        // proof of a SIM change; the subscription ID remains the primary monitor.
        val freshHash = runCatching { XiaomiSimIdentityStore.loadForCurrentSubscription(this, 0).simIdHash }
            .getOrNull()
        if (!freshHash.isNullOrBlank() && !boundSimIdHash.isNullOrBlank() && freshHash != boundSimIdHash) {
            Log.w(TAG, "Privileged SIM identity changed while Push was active; stopping old ChID=3 identity")
            stopForSimChange(MiMessageRuntimeStatus.SIM_CHANGED)
        }
    }

    private fun stopForSimChange(state: String) {
        if (stoppingForSimChange) return
        stoppingForSimChange = true
        MiMessageManager.disableForSimChange(this, state)
        pushClient?.close()
        pushClient = null
        stopSelf()
    }

    private fun unregisterSubscriptionMonitor() {
        val manager = subscriptionManager
        val listener = subscriptionListener
        if (manager != null && listener != null) {
            runCatching { manager.removeOnSubscriptionsChangedListener(listener) }
        }
        subscriptionListener = null
        subscriptionManager = null
        simMonitor?.shutdownNow()
        simMonitor = null
    }

    private fun registerNetworkMonitor() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                pushClient?.reconnectIfNetworkChanged(manager.activeNetwork)
            }

            override fun onLost(network: Network) {
                pushClient?.reconnectIfNetworkChanged(manager.activeNetwork)
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            connectivityManager = manager
            networkCallback = callback
            Log.i(TAG, "Registered active-network monitor for persistent Xiaomi Push")
        }.onFailure { error ->
            Log.w(TAG, "Could not register active-network monitor: ${error.javaClass.simpleName}")
        }
    }

    private fun unregisterNetworkMonitor() {
        val manager = connectivityManager
        val callback = networkCallback
        connectivityManager = null
        networkCallback = null
        if (manager != null && callback != null) {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    private fun startAsForeground() {
        if (foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    getString(R.string.mx_title),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        showForegroundNotification(pushClient?.state?.name ?: MiMessageRuntimeStatus.CONNECTING)
        foregroundStarted = true
        acquireCpuWakeLock()
    }

    private fun showForegroundNotification(state: String) {
        val notification = buildForegroundNotification(state)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(state: String): Notification {
        val message = when (state) {
            XiaomiPushState.BOUND.name -> getString(R.string.mx_notification_bound)

            XiaomiPushState.RECONNECTING.name -> getString(R.string.mx_notification_reconnecting)

            XiaomiPushState.CONNECTING.name,
            XiaomiPushState.AUTHENTICATING.name,
            XiaomiPushState.BINDING.name,
            -> getString(R.string.mx_notification_connecting)

            XiaomiPushState.FAILED.name -> getString(R.string.mx_notification_failed)

            else -> getString(R.string.mx_notification_running, state)
        }
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(getString(R.string.mx_notification_title))
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun updateForegroundNotification(state: String) {
        runCatching {
            // Update the notification owned by the foreground service itself. A regular
            // notify() can race with startForeground() and leave its initial text visible.
            showForegroundNotification(state)
        }.onFailure { error ->
            Log.w(TAG, "Could not update persistent Push notification: ${error.javaClass.simpleName}")
        }
    }

    private fun acquireCpuWakeLock() {
        if (cpuWakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        cpuWakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:MiMessagePush",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "Persistent Push CPU wake lock acquired")
    }

    private fun releaseCpuWakeLock() {
        val lock = cpuWakeLock ?: return
        cpuWakeLock = null
        if (lock.isHeld) runCatching { lock.release() }
        Log.i(TAG, "Persistent Push CPU wake lock released")
    }

    companion object {
        const val ACTION_START = "io.github.xiaotong6666.maihoku.action.START_MI_MESSAGE"
        const val ACTION_STOP = "io.github.xiaotong6666.maihoku.action.STOP_MI_MESSAGE"
        const val ACTION_QUERY_PRESENCE = "io.github.xiaotong6666.maihoku.action.QUERY_MI_MESSAGE_PRESENCE"
        const val EXTRA_PRESENCE_PHONE = "presence_phone"
        const val ACTION_QUEUE_OUTGOING = "io.github.xiaotong6666.maihoku.action.QUEUE_MI_MESSAGE_OUTGOING"
        const val EXTRA_OUTGOING_REQUEST_ID = "outgoing_request_id"
        const val EXTRA_OUTGOING_HOST_PACKAGE = "outgoing_host_package"
        const val EXTRA_OUTGOING_PHONE = "outgoing_phone"
        const val EXTRA_OUTGOING_BODY = "outgoing_body"
        private const val SERVER_SENT_TIMEOUT_MS = 20_000L
        private const val TAG = "MiMessageCore"
        private const val NOTIFICATION_CHANNEL = "maihoku-xiaomi-network-messages"
        private const val NOTIFICATION_ID = 0x4D49
    }
}
