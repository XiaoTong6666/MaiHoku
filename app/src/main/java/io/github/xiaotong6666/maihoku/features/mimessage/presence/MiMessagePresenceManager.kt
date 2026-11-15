package io.github.xiaotong6666.maihoku.features.mimessage.presence

import android.content.Context
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig
import io.github.xiaotong6666.maihoku.features.mimessage.ipc.MiMessageInboxBridge
import io.github.xiaotong6666.maihoku.features.mimessage.transport.SlimProtocol
import io.github.xiaotong6666.maihoku.features.mimessage.transport.XiaomiPushClient
import io.github.xiaotong6666.maihoku.features.mimessage.transport.XiaomiPushState
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Production Presence lifecycle owned by the long-lived ChID=3 Core Service. */
internal class MiMessagePresenceManager(
    context: Context,
    private val pushClient: () -> XiaomiPushClient?,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val store = MiMessagePresenceStore(appContext)
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MaiHoku-MiMessage-Presence").apply { isDaemon = true }
    }
    private val resolverPool = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "MaiHoku-MiMessage-MID-Resolver").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    // These collections are accessed only on worker.
    private val resolvingPhones = mutableSetOf<String>()
    private val pendingMids = linkedSetOf<String>()
    private val expiredHandledMids = mutableSetOf<String>()
    private val revalidatingMids = mutableSetOf<String>()
    private val lastProbeAt = mutableMapOf<String, Long>()
    private val watchedPhones = LinkedHashMap<String, Long>()

    init {
        worker.scheduleAtFixedRate(
            {
                runCatching(::expireAndRefresh).onFailure { error ->
                    Log.w(TAG, "Presence cache maintenance failed: ${error.javaClass.simpleName}")
                }
            },
            CACHE_MAINTENANCE_INTERVAL_MS,
            CACHE_MAINTENANCE_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Called by the SMS host when a conversation recipient becomes active. */
    fun request(phoneNumber: String) {
        if (closed.get()) return
        runCatching { worker.execute { requestOnWorker(phoneNumber) } }
    }

    /** Called from XiaomiPushClient's IO thread; all storage and broadcasts are serialized. */
    fun onPresence(packet: SlimProtocol.PresencePacket) {
        if (closed.get()) return
        runCatching { worker.execute { processPresence(packet) } }
    }

    fun onBindAccepted() {
        if (closed.get()) return
        runCatching {
            worker.execute {
                refreshWatchedMids()
                flushPendingProbes()
            }
        }
    }

    fun onPushState(state: XiaomiPushState) {
        if (state == XiaomiPushState.BOUND) onBindAccepted()
    }

    /** Resolve only from a fresh, server-confirmed Presence record; never infer support from MID alone. */
    fun sendTargetMid(phoneNumber: String, now: Long = System.currentTimeMillis()): String? {
        val phoneKey = MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) ?: return null
        val mid = store.findMidByPhoneKey(phoneKey) ?: return null
        val presence = store.getPresence(mid, now) ?: return null
        return mid.takeIf {
            MiMessagePresencePolicy.supportsSms(presence.status, presence.capabilities, presence.expiresAt, now)
        }
    }

    private fun requestOnWorker(phoneNumber: String) {
        val phoneKey = MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) ?: run {
            Log.w(TAG, "Rejected invalid Presence recipient")
            return
        }
        Log.d(TAG, "Presence lookup requested recipient_digits=${phoneKey.count(Char::isDigit)}")
        val now = System.currentTimeMillis()
        watchedPhones[phoneKey] = now
        trimWatchedPhones(now)

        val mid = store.findMidByPhoneKey(phoneKey)
        if (mid != null) {
            deliverCachedOrProbe(phoneKey, mid, now)
            return
        }

        if (!resolvingPhones.add(phoneKey)) return
        resolverPool.execute {
            val result = runCatching { XiaomiContactResolver.resolvePhoneToMid(appContext, phoneNumber) }
            if (closed.get()) return@execute
            runCatching {
                worker.execute {
                    resolvingPhones.remove(phoneKey)
                    result.onSuccess { resolution ->
                        runCatching {
                            store.saveContact(phoneNumber, resolution.mid)
                            Log.i(
                                TAG,
                                "Phone-to-MID resolved phone_digits=${phoneKey.count(Char::isDigit)} " +
                                    "mid_length=${resolution.mid.length} device_id_source=${resolution.deviceIdSource}",
                            )
                            deliverCachedOrProbe(phoneKey, resolution.mid, System.currentTimeMillis())
                        }.onFailure { error ->
                            dispatchUnknown(phoneNumber)
                            Log.w(TAG, "Could not persist phone-to-MID association: ${error.javaClass.simpleName}")
                        }
                    }.onFailure { error ->
                        dispatchUnknown(phoneNumber)
                        Log.w(TAG, "Phone-to-MID lookup failed: ${error.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun deliverCachedOrProbe(phoneKey: String, mid: String, now: Long) {
        val phoneNumber = store.findPhoneNumber(phoneKey) ?: phoneKey
        val cached = store.getPresence(mid, now)
        if (cached != null) {
            expiredHandledMids.remove(mid)
            revalidatingMids.remove(mid)
            dispatch(phoneNumber, cached.status, cached.capabilities, cached.expiresAt)
            return
        }
        if (store.hasPresenceRecord(mid)) {
            beginRevalidation(mid, now)
        } else if (mid !in expiredHandledMids) {
            dispatchUnknown(phoneNumber)
        }
        queueOrSendProbe(mid, now)
    }

    private fun processPresence(packet: SlimProtocol.PresencePacket) {
        if (packet.channelId != null && packet.channelId != MiMessageConfig.CHANNEL_ID) return
        val mid = jidMid(packet.from) ?: run {
            Log.w(TAG, "Ignored Presence with an invalid sender JID")
            return
        }
        val status = when (packet.type.lowercase()) {
            "available" -> MiMessagePresencePolicy.STATUS_AVAILABLE

            "unavailable" -> MiMessagePresencePolicy.STATUS_UNAVAILABLE

            else -> {
                Log.d(TAG, "Ignored non-availability Presence type=${packet.type}")
                return
            }
        }
        val capabilities = if (status == MiMessagePresencePolicy.STATUS_AVAILABLE) {
            MiMessagePresencePolicy.parseCapabilities(packet.clientAttributes)
        } else {
            0L
        }
        val now = System.currentTimeMillis()
        val changed = store.updatePresence(mid, status, capabilities, now)
        pendingMids.remove(mid)
        expiredHandledMids.remove(mid)
        revalidatingMids.remove(mid)
        val phones = store.findPhoneNumbers(mid)
        // Refresh the host-side expiry even when status/capabilities are unchanged; otherwise
        // its UI cache could expire earlier than the Core cache after a repeated Presence.
        val expiresAt = now + MiMessagePresencePolicy.CACHE_TTL_MS
        phones.forEach { phoneNumber -> dispatch(phoneNumber, status, capabilities, expiresAt) }
        Log.i(
            TAG,
            "Presence updated type=${status.lowercase()} mid_length=${mid.length} " +
                "sms=${capabilities and MiMessagePresencePolicy.CAP_SMS != 0L} " +
                "mapped_contacts=${phones.size} changed=$changed",
        )
    }

    private fun queueOrSendProbe(mid: String, now: Long) {
        val client = pushClient()
        if (client?.state != XiaomiPushState.BOUND) {
            pendingMids += mid
            return
        }
        val lastSent = lastProbeAt[mid] ?: 0L
        if (now - lastSent < PROBE_COOLDOWN_MS) return
        try {
            client.sendPresenceProbe("$mid@xiaomi.com")
            lastProbeAt[mid] = now
            pendingMids.remove(mid)
            Log.i(TAG, "Presence probe sent chid=3 mid_length=${mid.length}")
        } catch (error: Throwable) {
            pendingMids += mid
            Log.w(TAG, "Presence probe deferred: ${error.javaClass.simpleName}")
        }
    }

    private fun flushPendingProbes() {
        if (pushClient()?.state != XiaomiPushState.BOUND) return
        val now = System.currentTimeMillis()
        pendingMids.toList().forEach { mid -> queueOrSendProbe(mid, now) }
    }

    private fun expireAndRefresh() {
        if (closed.get()) return
        val now = System.currentTimeMillis()
        val expiredMids = store.expireDue(now)
        expiredMids.forEach { mid ->
            val phones = store.findPhoneNumbers(mid)
            val isWatched = phones.any { phone ->
                val key = MiMessagePresencePolicy.normalizePhoneNumber(phone)
                key != null && watchedPhones[key]?.let { now - it <= MiMessagePresencePolicy.WATCH_TTL_MS } == true
            }
            if (isWatched) {
                beginRevalidation(mid, now)
                pendingMids += mid
            } else {
                store.deletePresence(mid)
                pendingMids.remove(mid)
                expiredHandledMids.remove(mid)
                revalidatingMids.remove(mid)
                lastProbeAt.remove(mid)
            }
        }

        trimWatchedPhones(now)
        refreshWatchedMids(now)
        flushPendingProbes()
    }

    /** Keep the last UI mode during a short revalidation window to avoid a 60-second flicker. */
    private fun beginRevalidation(mid: String, now: Long) {
        if (!expiredHandledMids.add(mid)) return
        revalidatingMids += mid
        queueOrSendProbe(mid, now)
        worker.schedule(
            {
                if (!closed.get() && revalidatingMids.remove(mid) && store.getPresence(mid) == null) {
                    store.deletePresence(mid)
                    store.findPhoneNumbers(mid).forEach(::dispatchUnknown)
                }
            },
            REVALIDATION_GRACE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun refreshWatchedMids(now: Long = System.currentTimeMillis()) {
        watchedPhones.keys.toList().forEach { phoneKey ->
            val mid = store.findMidByPhoneKey(phoneKey) ?: return@forEach
            if (store.getPresence(mid, now) != null) return@forEach
            val lastSent = lastProbeAt[mid] ?: 0L
            if (now - lastSent >= PROBE_COOLDOWN_MS) pendingMids += mid
        }
    }

    private fun trimWatchedPhones(now: Long) {
        val iterator = watchedPhones.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > MiMessagePresencePolicy.WATCH_TTL_MS) iterator.remove()
        }
        while (watchedPhones.size > MAX_WATCHED_PHONES) {
            val oldest = watchedPhones.entries.firstOrNull()?.key ?: break
            watchedPhones.remove(oldest)
        }
        val activeMids = watchedPhones.keys.mapNotNull(store::findMidByPhoneKey).toSet()
        pendingMids.retainAll(activeMids)
        expiredHandledMids.retainAll(activeMids)
        revalidatingMids.retainAll(activeMids)
        lastProbeAt.keys.retainAll(activeMids)
    }

    private fun dispatchUnknown(phoneNumber: String) {
        dispatch(phoneNumber, MiMessagePresencePolicy.STATUS_UNKNOWN, 0L, 0L)
    }

    private fun dispatch(phoneNumber: String, status: String, capabilities: Long, expiresAt: Long) {
        MiMessageInboxBridge.dispatchPresenceChanged(appContext, phoneNumber, status, capabilities, expiresAt)
    }

    private fun jidMid(jid: String?): String? {
        if (jid.isNullOrBlank() || jid.length > 256) return null
        val bareJid = jid.substringBefore('/')
        val separator = bareJid.indexOf('@')
        if (separator <= 0 || bareJid.substring(separator + 1).lowercase() != "xiaomi.com") return null
        return bareJid.substring(0, separator).takeIf { it.toLongOrNull()?.let { value -> value > 0L } == true }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.shutdownNow()
        resolverPool.shutdownNow()
        store.close()
    }

    companion object {
        private const val TAG = "MiMessagePresence"
        private const val PROBE_COOLDOWN_MS = MiMessagePresencePolicy.CACHE_TTL_MS
        private const val CACHE_MAINTENANCE_INTERVAL_MS = 5_000L
        private const val REVALIDATION_GRACE_MS = 3_000L
        private const val MAX_WATCHED_PHONES = 128
    }
}
