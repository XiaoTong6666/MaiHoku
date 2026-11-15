package io.github.xiaotong6666.maihoku.features.mimessage.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.xiaotong6666.maihoku.features.mimessage.account.MiMessageIdentity
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig
import io.github.xiaotong6666.maihoku.features.mimessage.presence.XiaomiResolverClient
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

internal enum class XiaomiPushState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    BINDING,
    BOUND,
    RECONNECTING,
    FAILED,
    CLOSED,
}

/**
 * A single-ChID=3 Xiaomi Slim client. It owns its socket and worker threads; callers must not
 * invoke blocking methods from the main thread. Credentials remain in memory and MaiHoku's private app storage.
 */
internal class XiaomiPushClient(
    context: Context,
    private val identity: MiMessageIdentity,
    private val listener: Listener = Listener.None,
    private val autoReconnect: Boolean = false,
    private val keepAliveIntervalMs: Long = DEFAULT_KEEPALIVE_INTERVAL_MS,
    private val clientAttributes: String = "cap:${MiMessageConfig.DEFAULT_CAPABILITIES}",
) : Closeable {

    interface Listener {
        fun onStateChanged(state: XiaomiPushState, detail: String?) = Unit
        fun onBindAccepted(endpoint: String) = Unit
        fun onServerConfigReceived(payloadBytes: Int) = Unit
        fun onServerConfig(payload: ByteArray?) = Unit
        fun onPingSent() = Unit
        fun onServerPing() = Unit
        fun onFrame(command: String, channelId: Long, payloadBytes: Int) = Unit
        fun onRawFrame(frame: SlimProtocol.Frame) = Unit
        fun onPresence(packet: SlimProtocol.PresencePacket) = Unit
        fun onMessage(packet: MiMessagePacketCodec.MessagePacket) = Unit
        fun onKicked(type: String?, reason: String?) = Unit
        fun onError(kind: String, detail: String?) = Unit

        object None : Listener
    }

    private data class ConnectedSocket(
        val socket: Socket,
        val endpoint: String,
        val networkTag: String,
        val network: Network?,
    )

    private class BindRejectedException(
        val errorType: String?,
        val errorReasonClass: String,
    ) : IOException("Xiaomi Push rejected ChID=3 BIND: type=${errorType ?: "absent"}, reason=$errorReasonClass")

    private val appContext = context.applicationContext
    private val stateRef = AtomicReference(XiaomiPushState.DISCONNECTED)
    private val closed = AtomicBoolean(false)
    private val writerLock = Any()
    private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MaiHoku-XiaomiPush-Heartbeat").apply { isDaemon = true }
    }
    private var worker: Thread? = null

    @Volatile private var socket: Socket? = null

    @Volatile private var input: DataInputStream? = null

    @Volatile private var output: DataOutputStream? = null

    @Volatile private var cipherKey: ByteArray? = null

    @Volatile private var endpoint: String? = null

    @Volatile private var socketNetwork: Network? = null

    @Volatile private var heartbeatTask: ScheduledFuture<*>? = null

    val state: XiaomiPushState
        get() = stateRef.get()

    /** Send one original Mi Message Presence probe over the authenticated ChID=3 channel. */
    fun sendPresenceProbe(targetJid: String): String {
        check(stateRef.get() == XiaomiPushState.BOUND) { "ChID=3 is not bound" }
        val connectionKey = cipherKey?.copyOf() ?: error("Slim connection cipher is unavailable")
        val (packetId, frame) = SlimProtocol.encodePresenceProbeFrame(identity, targetJid, connectionKey)
        writeFrame(frame)
        Log.i(TAG, "Presence probe sent chid=3 packet_id_length=${packetId.length} frame_bytes=${frame.size}")
        return packetId
    }

    /** Close a socket bound to an obsolete Android Network so the normal reconnect loop rebinds. */
    fun reconnectIfNetworkChanged(currentNetwork: Network?) {
        if (closed.get()) return
        val activeSocket = socket ?: return
        val boundNetwork = socketNetwork ?: return
        if (activeSocket.isClosed || boundNetwork == currentNetwork) return
        Log.w(TAG, "Active Android Network changed; reconnecting Xiaomi Push")
        closeSocket()
    }

    /** Build and send one original-format normal SMS stanza; callers must gate real sends explicitly. */
    fun sendSms(recipientMidOrJid: String, text: String): String {
        val packetId = MiMessagePacketCodec.nextSmsPacketId()
        val stanza = MiMessagePacketCodec.buildSmsStanza(identity, recipientMidOrJid, text, packetId)
        sendXmppStanza(packetId, stanza)
        Log.i(TAG, "Mi Message SMS stanza sent chid=3 packet_id_length=${packetId.length}")
        return packetId
    }

    /** Send a body-less XMPP receipt for an incoming normal text message. */
    fun sendReceivedAck(originalSenderJid: String, originalPacketId: String): String {
        val packetId = SlimProtocol.nextPacketId()
        val stanza = MiMessagePacketCodec.buildReceivedAckStanza(
            identity,
            originalSenderJid,
            originalPacketId,
            packetId,
        )
        sendXmppStanza(packetId, stanza)
        Log.i(TAG, "Mi Message received ACK frame written chid=3 ack_packet_id_length=${packetId.length}")
        return packetId
    }

    private fun sendXmppStanza(packetId: String, stanza: String) {
        check(stateRef.get() == XiaomiPushState.BOUND) { "ChID=3 is not bound" }
        val connectionKey = cipherKey?.copyOf() ?: error("Slim connection cipher is unavailable")
        val frame = SlimProtocol.encodeSecmsgFrame(identity, packetId, stanza, connectionKey)
        writeFrame(frame)
        listener.onFrame("SECMSG", 3, stanza.toByteArray(Charsets.UTF_8).size)
    }

    @Synchronized
    fun start(): Boolean {
        if (closed.get() || stateRef.get() != XiaomiPushState.DISCONNECTED) return false
        identity.validate()
        worker = Thread(::runLoop, "MaiHoku-XiaomiPush-IO").apply {
            isDaemon = true
            start()
        }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        heartbeatTask?.cancel(true)
        heartbeatExecutor.shutdownNow()
        closeSocket()
        worker?.interrupt()
        transition(XiaomiPushState.CLOSED, "client closed")
    }

    private fun runLoop() {
        var reconnectDelayMs = 2_000L
        while (!closed.get()) {
            try {
                connectAndBind()
                reconnectDelayMs = 2_000L
                readLoop()
                if (!closed.get()) throw IOException("Slim socket closed by remote")
            } catch (rejected: BindRejectedException) {
                listener.onError("BIND_REJECTED", rejected.message)
                transition(XiaomiPushState.FAILED, rejected.message)
                break // Authentication failures must never be retried automatically.
            } catch (error: Throwable) {
                if (closed.get()) break
                listener.onError(error.javaClass.simpleName, safeDetail(error))
                closeSocket()
                if (!autoReconnect) {
                    transition(XiaomiPushState.FAILED, error.javaClass.simpleName)
                    break
                }
                transition(XiaomiPushState.RECONNECTING, "retry in ${reconnectDelayMs}ms")
                try {
                    Thread.sleep(reconnectDelayMs)
                } catch (_: InterruptedException) {
                    if (closed.get()) break
                }
                reconnectDelayMs = nextBackoff(reconnectDelayMs)
            }
        }
        if (closed.get()) transition(XiaomiPushState.CLOSED, "client closed")
    }

    private fun connectAndBind() {
        transition(XiaomiPushState.CONNECTING, null)
        val connected = connectSocket()
        socket = connected.socket
        endpoint = connected.endpoint
        socketNetwork = connected.network
        input = DataInputStream(connected.socket.getInputStream())
        output = DataOutputStream(connected.socket.getOutputStream())
        connected.socket.soTimeout = CONNECT_READ_TIMEOUT_MS

        transition(XiaomiPushState.AUTHENTICATING, connected.endpoint)
        val connFrame = SlimProtocol.encodeFrame(
            blobType = 2,
            metadata = SlimProtocol.encodeConnMetadata(),
            payload = SlimProtocol.encodeConnPayload(appContext, connected.networkTag, connected.endpoint),
        )
        writeFrame(connFrame)

        val connResponse = SlimProtocol.readFrame(requireNotNull(input))
        val connCommand = connResponse.command()
        check(connCommand == "CONN") { "Unexpected first Slim response command=${connCommand ?: "unknown"}" }
        val challenge = SlimProtocol.readStringField(connResponse.payload, 1)
            ?.takeIf(String::isNotEmpty)
            ?: error("CONN response did not include a challenge")
        val serverConfig = SlimProtocol.readBytesField(connResponse.payload, 3)
        listener.onServerConfigReceived(serverConfig?.size ?: 0)
        listener.onServerConfig(serverConfig?.copyOf())
        val key = SlimProtocol.deriveBlobCipherKey(challenge, SlimProtocol.deviceUuid(appContext))
        cipherKey = key

        transition(XiaomiPushState.BINDING, connected.endpoint)
        val bindId = SlimProtocol.nextBindId()
        val metadata = SlimProtocol.encodeBindMetadata(identity, bindId)
        val payload = SlimProtocol.encodeBindPayload(identity, challenge, bindId, clientAttributes)
        val bindFrame = SlimProtocol.encodeFrame(
            blobType = 2,
            metadata = metadata,
            payload = payload,
            encryptionKey = key,
        )
        Log.i(TAG, "Sending ChID=3 BIND endpoint=${connected.endpoint} metadata_bytes=${metadata.size} payload_bytes=${payload.size}")
        writeFrame(bindFrame)

        // CONN establishes transport, not SMS channel authorization. Wait for explicit ChID=3
        // BIND acceptance before publishing BOUND, sending Presence or flushing the outbox.
        // Earlier PING/config frames must not be mistaken for the BIND response.
        val bindDeadline = System.nanoTime() + BIND_RESPONSE_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < bindDeadline) {
            val remainingMs = ((bindDeadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
            connected.socket.soTimeout = remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val response = SlimProtocol.readFrame(requireNotNull(input), key)
            val command = response.command()
            val channelId = response.channelId()
            if (command == "PING") {
                listener.onServerPing()
                continue
            }
            if (command == "KICK" && channelId == 3L) {
                val type = SlimProtocol.readStringField(response.payload, 1)
                val reason = SlimProtocol.readStringField(response.payload, 2)
                listener.onKicked(type, reason)
                throw IOException("Server KICK during BIND: type=${type ?: "absent"}, reason=${SlimProtocol.classifyBindError(reason)}")
            }
            if (command == "BIND" && channelId == 3L) {
                val succeeded = SlimProtocol.readVarintField(response.payload, 1) == 1L
                val errorType = SlimProtocol.readStringField(response.payload, 2)
                val errorReason = SlimProtocol.readStringField(response.payload, 3)
                if (!succeeded) {
                    throw BindRejectedException(errorType, SlimProtocol.classifyBindError(errorReason))
                }
                connected.socket.soTimeout = 0
                transition(XiaomiPushState.BOUND, connected.endpoint)
                listener.onBindAccepted(connected.endpoint)
                scheduleHeartbeat()
                Log.i(TAG, "Xiaomi Push accepted ChID=3 BIND endpoint=${connected.endpoint}")
                return
            }
            listener.onRawFrame(response)
            listener.onFrame(command ?: "unknown", channelId ?: -1L, response.payload.size)
        }
        throw IOException("Timed out waiting for ChID=3 BIND response")
    }

    private fun readLoop() {
        val stream = requireNotNull(input)
        val key = requireNotNull(cipherKey)
        while (!closed.get()) {
            val frame = SlimProtocol.readFrame(stream, key)
            val command = frame.command() ?: "unknown"
            val channelId = frame.channelId() ?: -1L
            when {
                command == "PING" -> listener.onServerPing()

                command == "KICK" && channelId == 3L -> {
                    val type = SlimProtocol.readStringField(frame.payload, 1)
                    val reason = SlimProtocol.readStringField(frame.payload, 2)
                    listener.onKicked(type, reason)
                    throw IOException("Server KICK: type=${type ?: "absent"}, reason=${SlimProtocol.classifyBindError(reason)}")
                }

                command == "CLOSE" && channelId == 0L -> throw IOException("Server closed the Slim connection")

                else -> {
                    val presence = SlimProtocol.decodePresencePacket(frame, identity)
                    if (presence != null) {
                        listener.onPresence(presence)
                    } else {
                        SlimProtocol.decodeMessagePacket(frame, identity)?.let(listener::onMessage)
                    }
                    listener.onRawFrame(frame)
                    listener.onFrame(command, channelId, frame.payload.size)
                }
            }
        }
    }

    private fun scheduleHeartbeat() {
        if (keepAliveIntervalMs <= 0L || heartbeatExecutor.isShutdown) return
        heartbeatTask?.cancel(true)
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(
            {
                if (!closed.get() && stateRef.get() == XiaomiPushState.BOUND) {
                    runCatching {
                        writeFrame(SlimProtocol.encodeEmptyPingFrame())
                        listener.onPingSent()
                    }.onFailure { error ->
                        listener.onError("PING_SEND_FAILED", error.javaClass.simpleName)
                        closeSocket()
                    }
                }
            },
            keepAliveIntervalMs,
            keepAliveIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun connectSocket(): ConnectedSocket {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: connectivity.allNetworks.firstOrNull { candidate ->
            connectivity.getNetworkCapabilities(candidate)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        val networkTag = getNetworkTag(connectivity, network)
        val host = MiMessageConfig.DEFAULT_PUSH_HOST
        val port = MiMessageConfig.DEFAULT_PUSH_PORT.toInt()
        val resolverEndpoints = XiaomiResolverClient.resolve(appContext, network, host, networkTag)
        val resolved = runCatching {
            if (network != null) network.getAllByName(host).toList() else InetAddress.getAllByName(host).toList()
        }.getOrDefault(emptyList())
        val fallback = MiMessageConfig.DEFAULT_PUSH_FALLBACKS.split(';').mapNotNull { item ->
            item.substringBefore(':').takeIf(String::isNotBlank)?.let { ip ->
                runCatching { InetAddress.getByName(ip) }.getOrNull()
            }
        }
        val candidates = (
            resolverEndpoints + resolved.map { XiaomiResolverClient.Endpoint(it, port) } +
                fallback.map { XiaomiResolverClient.Endpoint(it, port) }
            )
            .distinctBy { "${it.address.hostAddress}:${it.port}" }
        check(candidates.isNotEmpty()) { "No Push host addresses resolved" }

        var lastError: Throwable? = null
        for (candidate in candidates) {
            if (closed.get()) throw IOException("Client closed before connect")
            val candidateSocket = network?.socketFactory?.createSocket() ?: Socket()
            try {
                candidateSocket.tcpNoDelay = true
                candidateSocket.keepAlive = true
                candidateSocket.connect(InetSocketAddress(candidate.address, candidate.port), CONNECT_TIMEOUT_MS)
                val remote = candidateSocket.inetAddress.hostAddress ?: candidate.address.hostAddress
                Log.i(TAG, "Slim TCP connected host=$host endpoint=$remote:${candidate.port} candidates=${candidates.size}")
                return ConnectedSocket(candidateSocket, remote, networkTag, network)
            } catch (error: Throwable) {
                lastError = error
                runCatching { candidateSocket.close() }
            }
        }
        throw IOException("No Push endpoint accepted TCP (${lastError?.javaClass?.simpleName ?: "unknown"})")
    }

    private fun writeFrame(frame: ByteArray) {
        synchronized(writerLock) {
            val stream = output ?: throw IOException("Slim writer is not available")
            stream.write(frame)
            stream.flush()
        }
    }

    private fun closeSocket() {
        synchronized(writerLock) {
            heartbeatTask?.cancel(true)
            heartbeatTask = null
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
            cipherKey = null
            endpoint = null
            socketNetwork = null
        }
    }

    private fun transition(next: XiaomiPushState, detail: String?) {
        val previous = stateRef.getAndSet(next)
        if (previous != next) listener.onStateChanged(next, detail)
    }

    private fun safeDetail(error: Throwable): String = when (error) {
        is BindRejectedException -> error.message.orEmpty()
        else -> error.message?.take(120) ?: error.javaClass.simpleName
    }

    private fun nextBackoff(current: Long): Long = when {
        current < 4_000L -> 4_000L
        current < 8_000L -> 8_000L
        current < 15_000L -> 15_000L
        current < 30_000L -> 30_000L
        else -> 60_000L
    }

    @Suppress("DEPRECATION")
    private fun getNetworkTag(connectivity: ConnectivityManager, network: Network?): String {
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        return when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"

            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                val info = connectivity.activeNetworkInfo
                val parts = listOf(info?.typeName.orEmpty(), info?.subtypeName.orEmpty())
                    .filter(String::isNotBlank)
                parts.joinToString("-").lowercase(Locale.ROOT).ifBlank { "mobile" }
            }

            else -> "unknown"
        }
    }

    companion object {
        private const val TAG = "XiaomiPush"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val CONNECT_READ_TIMEOUT_MS = 15_000
        private const val BIND_RESPONSE_TIMEOUT_MS = 15_000L
        const val DEFAULT_KEEPALIVE_INTERVAL_MS = 330_000L
    }
}
