package io.github.xiaotong6666.maihoku.mimessage.transport

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import io.github.xiaotong6666.maihoku.mimessage.account.MiMessageIdentity
import io.github.xiaotong6666.maihoku.mimessage.core.MiMessageConfig
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HashMap
import java.util.Locale
import java.util.TreeMap
import java.util.zip.Adler32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import io.github.xiaotong6666.maihoku.mimessage.transport.MiMessageXml.escape as escapeXml

/** Xiaomi XMSF Slim Blob framing, protobuf fields, and ChID=3 authentication primitives. */
internal object SlimProtocol {
    const val MAGIC = 0xC2FE
    const val VERSION = 5
    const val MAX_BLOB_SIZE = 32 * 1024

    private const val DEVICE_PREFS = "maihoku_push_device"
    private const val DEVICE_UUID_KEY = "device_uuid"
    private const val BIND_ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val bindIdRandom = SecureRandom()
    private val bindIdPrefix = buildString(5) {
        repeat(5) { append(BIND_ID_ALPHABET[bindIdRandom.nextInt(BIND_ID_ALPHABET.length)]) }
    }
    private var bindIdSequence = 0L
    private val packetIdPrefix = buildString(5) {
        repeat(5) { append(BIND_ID_ALPHABET[bindIdRandom.nextInt(BIND_ID_ALPHABET.length)]) }
    } + "-"
    private var packetIdSequence = 0L

    data class Frame(
        val blobType: Int,
        val metadata: ByteArray,
        val payload: ByteArray,
        val isEmptyPing: Boolean = false,
    ) {
        fun command(): String? = if (isEmptyPing) "PING" else readStringField(metadata, 5)
        fun channelId(): Long? = if (isEmptyPing) 0L else readVarintField(metadata, 1)
    }

    data class PresencePacket(
        val packetId: String,
        val from: String?,
        val to: String?,
        val channelId: String?,
        val type: String,
        val clientAttributes: String?,
    )

    fun encodeFrame(
        blobType: Int,
        metadata: ByteArray,
        payload: ByteArray,
        encryptionKey: ByteArray? = null,
    ): ByteArray {
        require(metadata.size <= 0x7FFF && payload.size <= MAX_BLOB_SIZE)
        val blobLength = 8 + metadata.size + payload.size
        require(blobLength in 8..MAX_BLOB_SIZE)
        // Big-endian wire layout: magic:u16, version:u16, bodyLength:i32, body, adler32:i32.
        // Body: blobType:u16, metadataLength:u16, payloadLength:i32, metadata protobuf, payload.
        val frame = ByteBuffer.allocate(8 + blobLength + 4).order(ByteOrder.BIG_ENDIAN)
        frame.putShort(MAGIC.toShort())
        frame.putShort(VERSION.toShort())
        frame.putInt(blobLength)
        frame.putShort(blobType.toShort())
        frame.putShort(metadata.size.toShort())
        frame.putInt(payload.size)
        frame.put(metadata)
        frame.put(payload)
        // Restart RC4 per frame. Keep the outer header readable to bound allocation on receive;
        // checksum the header and encrypted body, never the checksum field itself.
        if (encryptionKey != null) applyRc4(encryptionKey, frame.array(), 8, blobLength)
        val checksum = Adler32().apply { update(frame.array(), 0, frame.position()) }.value
        frame.putInt(checksum.toInt())
        return frame.array()
    }

    /** XMSF p2.h emits an empty Blob for its ordinary ChID=0 PING. */
    fun encodeEmptyPingFrame(): ByteArray {
        val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putShort(MAGIC.toShort())
            .putShort(VERSION.toShort())
            .putInt(0)
            .array()
        val checksum = Adler32().apply { update(header) }.value
        return ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .put(header)
            .putInt(checksum.toInt())
            .array()
    }

    @Throws(IOException::class)
    fun readFrame(input: DataInputStream, decryptionKey: ByteArray? = null): Frame {
        val header = ByteArray(8)
        input.readFully(header)
        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        require((headerBuffer.short.toInt() and 0xFFFF) == MAGIC) { "Invalid Slim magic" }
        require((headerBuffer.short.toInt() and 0xFFFF) == VERSION) { "Unsupported Slim frame version" }
        val blobLength = headerBuffer.int
        require(blobLength in 0..MAX_BLOB_SIZE) { "Invalid Slim blob length: $blobLength" }

        val body = ByteArray(blobLength)
        if (blobLength > 0) input.readFully(body)
        val checksumBytes = ByteArray(4)
        input.readFully(checksumBytes)
        val receivedChecksum = ByteBuffer.wrap(checksumBytes).order(ByteOrder.BIG_ENDIAN).int
        val checksum = Adler32().apply {
            update(header)
            update(body)
        }.value.toInt()
        require(checksum == receivedChecksum) { "Slim frame checksum mismatch" }

        if (blobLength == 0) {
            return Frame(blobType = 2, metadata = ByteArray(0), payload = ByteArray(0), isEmptyPing = true)
        }
        // Validate ciphertext first: calculating Adler32 after decryption would change its input.
        if (decryptionKey != null) applyRc4(decryptionKey, body, 0, body.size)

        val bodyBuffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        val blobType = bodyBuffer.short.toInt() and 0xFFFF
        val metadataLength = bodyBuffer.short.toInt() and 0xFFFF
        val payloadLength = bodyBuffer.int
        require(payloadLength >= 0) { "Negative Slim payload length" }
        require(metadataLength + payloadLength == bodyBuffer.remaining()) { "Malformed Slim blob lengths" }
        val metadata = ByteArray(metadataLength).also(bodyBuffer::get)
        val payload = ByteArray(payloadLength).also(bodyBuffer::get)
        return Frame(blobType, metadata, payload)
    }

    fun encodeConnMetadata(): ByteArray = ByteArrayOutputStream().apply {
        writeVarintField(this, 1, 0) // chid=0; XMSF explicitly marks this field present
        writeStringField(this, 3, "xiaomi.com")
        writeStringField(this, 5, "CONN")
    }.toByteArray()

    fun encodeConnPayload(
        context: Context,
        networkTag: String,
        remoteAddress: String,
    ): ByteArray = ByteArrayOutputStream().apply {
        writeVarintField(this, 1, 106) // XMSF Slim protocol version
        writeStringField(this, 2, Build.MODEL)
        writeStringField(this, 3, "${Build.BRAND}_${Build.VERSION.INCREMENTAL}")
        writeStringField(this, 4, deviceUuid(context))
        writeVarintField(this, 5, 48) // XMSF SDK version
        writeStringField(this, 6, networkTag)
        writeStringField(this, 7, remoteAddress)
        writeStringField(this, 8, Locale.getDefault().toString())
        writeVarintField(this, 10, Build.VERSION.SDK_INT.toLong())
        writeVarintField(this, 13, 0) // XMSF package version is absent in the standalone AVD client
    }.toByteArray()

    fun nextBindId(): String = synchronized(this) { "$bindIdPrefix-${bindIdSequence++}" }

    fun nextPacketId(): String = synchronized(this) { "$packetIdPrefix${packetIdSequence++}" }

    /**
     * MiuiMms sends Presence through XMSF as an encrypted SECMSG Blob:
     * XML is RC4-encrypted with Base64(Security) + '_' + packetId, then the
     * complete Blob body is encrypted with the connection-level Slim key.
     */
    fun encodePresenceProbeFrame(
        identity: MiMessageIdentity,
        targetJid: String,
        connectionCipherKey: ByteArray,
    ): Pair<String, ByteArray> {
        identity.validate()
        val target = targetJid.trim().let { value ->
            require(value.isNotEmpty()) { "Presence target is empty" }
            if ('@' in value) value else "$value@xiaomi.com"
        }
        val packetId = nextPacketId()
        val from = "${identity.mid}@xiaomi.com/${identity.resource}"
        val xml = buildString {
            append("<presence id=\"").append(escapeXml(packetId))
            append("\" to=\"").append(escapeXml(target))
            append("\" from=\"").append(escapeXml(from))
            append("\" chid=\"3\" type=\"probe\"></presence>")
        }
        return packetId to encodeSecmsgFrame(identity, packetId, xml, connectionCipherKey)
    }

    /** Decode an incoming ChID=3 SECMSG only when its XML root is a Presence stanza. */
    fun decodePresencePacket(frame: Frame, identity: MiMessageIdentity): PresencePacket? {
        if (frame.blobType != 2 || frame.command() != "SECMSG" || frame.channelId() != 3L) return null
        val packetId = readStringField(frame.metadata, 7) ?: return null
        val cipher = readVarintField(frame.metadata, 9) ?: 0L
        val xmlBytes = runCatching { decryptSecmsgPayload(identity.security, packetId, frame.payload, cipher) }
            .getOrNull() ?: return null
        return parsePresencePacket(xmlBytes, packetId)
    }

    /** Mirrors MiuiMms' comma-separated client_attrs and hash-separated cap parsing. */
    fun parseClientCapabilities(clientAttributes: String?): List<String> {
        if (clientAttributes.isNullOrBlank()) return emptyList()
        val capValue = clientAttributes.split(',').firstNotNullOfOrNull { entry ->
            val separator = entry.indexOf(':')
            if (separator <= 0) {
                null
            } else {
                val key = entry.substring(0, separator)
                val value = entry.substring(separator + 1)
                if (key == "cap") value else null
            }
        } ?: return emptyList()
        return capValue.split('#').map(String::trim).filter(String::isNotEmpty).distinct()
    }

    fun encodeBindMetadata(identity: MiMessageIdentity, bindId: String): ByteArray = ByteArrayOutputStream().apply {
        writeVarintField(this, 1, 3) // ChID=3
        writeVarintField(this, 2, identity.mid.toLong())
        writeStringField(this, 3, "xiaomi.com")
        writeStringField(this, 4, identity.resource)
        writeStringField(this, 5, "BIND")
        writeStringField(this, 7, bindId)
        // XMSF leaves the default cipher field absent; do not serialize an explicit zero.
    }.toByteArray()

    fun encodeBindPayload(
        identity: MiMessageIdentity,
        challenge: String,
        bindId: String,
        clientAttrs: String,
    ): ByteArray {
        val cloudAttrs = buildCloudAttributes(identity)
        val from = "${identity.mid}@xiaomi.com/${identity.resource}"
        val signedFields = TreeMap<String, String>().apply {
            put("challenge", challenge)
            put("token", identity.token)
            put("chid", "3")
            put("from", from)
            put("id", bindId)
            put("to", "xiaomi.com")
            put("kick", "0")
            put("client_attrs", clientAttrs)
            put("cloud_attrs", cloudAttrs)
        }
        val signatureInput = buildString {
            append(MiMessageConfig.AUTH_METHOD)
            for ((key, value) in signedFields) append('&').append(key).append('=').append(value)
            append('&').append(identity.security)
        }
        val signature = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest(signatureInput.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        return ByteArrayOutputStream().apply {
            writeStringField(this, 1, identity.token)
            writeStringField(this, 2, "0") // kick=false
            writeStringField(this, 3, MiMessageConfig.AUTH_METHOD)
            writeStringField(this, 4, clientAttrs)
            writeStringField(this, 5, cloudAttrs)
            writeStringField(this, 6, signature)
        }.toByteArray()
    }

    /**
     * Encodes an XMPP packet as the original XMSF ChID=3 SECMSG Blob.
     * The XML is first RC4-encrypted with Base64(Security) + '_' + packetId;
     * encodeFrame then applies the separate per-connection Slim cipher.
     */
    fun encodeSecmsgFrame(
        identity: MiMessageIdentity,
        packetId: String,
        xml: String,
        connectionCipherKey: ByteArray,
    ): ByteArray {
        require(packetId.isNotBlank()) { "XMPP packet ID is required" }
        require(xml.isNotBlank()) { "XMPP packet XML is required" }
        // The packet ID participates in inner-key derivation and must match stanza/metadata IDs.
        val innerKey = xmppCipherKey(identity.security, packetId)
        val encryptedPayload = rc4(innerKey, xml.toByteArray(Charsets.UTF_8))
        val metadata = ByteArrayOutputStream().apply {
            writeVarintField(this, 1, 3) // ChID=3
            writeVarintField(this, 2, identity.mid.toLong())
            writeStringField(this, 3, "xiaomi.com")
            writeStringField(this, 4, identity.resource)
            writeStringField(this, 5, "SECMSG")
            writeStringField(this, 7, packetId)
            writeVarintField(this, 9, 1) // XMSF p2.b.A(): channel-security RC4
        }.toByteArray()
        return encodeFrame(
            blobType = 2,
            metadata = metadata,
            payload = encryptedPayload,
            encryptionKey = connectionCipherKey,
        )
    }

    /** Decrypts the inner SECMSG payload after the outer Slim connection cipher is removed. */
    fun decryptSecmsgPayload(
        security: String,
        packetId: String,
        payload: ByteArray,
        cipher: Long,
    ): ByteArray = when (cipher) {
        0L -> payload.copyOf()

        1L -> {
            require(packetId.isNotBlank()) { "SECMSG packet ID is required for decryption" }
            rc4(xmppCipherKey(security, packetId), payload)
        }

        else -> error("Unsupported SECMSG cipher: $cipher")
    }

    /** Decode a ChID=3 SECMSG whose XMPP root is <message/>. */
    fun decodeMessagePacket(frame: Frame, identity: MiMessageIdentity): MiMessagePacketCodec.MessagePacket? {
        if (frame.blobType != 2 || frame.command() != "SECMSG" || frame.channelId() != 3L) return null
        val packetId = readStringField(frame.metadata, 7)?.takeIf(String::isNotBlank) ?: return null
        val cipher = readVarintField(frame.metadata, 9) ?: 0L
        val plaintext = runCatching {
            decryptSecmsgPayload(identity.security, packetId, frame.payload, cipher)
        }.getOrNull() ?: return null
        return MiMessagePacketCodec.parseMessage(String(plaintext, Charsets.UTF_8))
    }

    fun deriveBlobCipherKey(challenge: String, deviceId: String): ByteArray {
        // CONN is plaintext. Its challenge and the stable device UUID establish the outer frame
        // key; account Security is separate and encrypts the inner ChID=3 SECMSG payload.
        require(challenge.isNotEmpty() && deviceId.isNotEmpty())
        val challengeHalf = challenge.substring(challenge.length / 2)
        val deviceHalf = deviceId.substring(deviceId.length / 2)
        return rc4(challenge.toByteArray(Charsets.UTF_8), (challengeHalf + deviceHalf).toByteArray(Charsets.UTF_8))
    }

    fun deviceUuid(context: Context): String {
        val prefs = context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        prefs.getString(DEVICE_UUID_KEY, null)?.let { return it }
        // Preserve the established Push device identity when upgrading development builds.
        context.getSharedPreferences("maihoku_m1_device", Context.MODE_PRIVATE)
            .getString(DEVICE_UUID_KEY, null)?.let { previous ->
                check(prefs.edit().putString(DEVICE_UUID_KEY, previous).commit()) { "Could not migrate Push DeviceUUID" }
                return previous
            }
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val seed = androidId?.takeIf(String::isNotBlank) ?: java.util.UUID.randomUUID().toString()
        val digest = MessageDigest.getInstance("SHA-1").digest(seed.toByteArray(Charsets.UTF_8))
        val prefix = if (androidId.isNullOrBlank()) "d-" else "g-"
        val value = prefix + digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        check(prefs.edit().putString(DEVICE_UUID_KEY, value).commit()) { "Could not persist Xiaomi DeviceUUID" }
        return value
    }

    fun readBytesField(bytes: ByteArray, wantedField: Int): ByteArray? {
        var offset = 0
        while (offset < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, offset)
            offset = afterTag
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> offset = readVarint(bytes, offset).second

                1 -> offset += 8

                2 -> {
                    val (length, afterLength) = readVarint(bytes, offset)
                    offset = afterLength
                    require(length in 0..(bytes.size - offset).toLong()) { "Invalid protobuf field length" }
                    val end = offset + length.toInt()
                    if (field == wantedField) return bytes.copyOfRange(offset, end)
                    offset = end
                }

                5 -> offset += 4

                else -> error("Unsupported protobuf wire type")
            }
            require(offset <= bytes.size) { "Malformed protobuf payload" }
        }
        return null
    }

    fun readStringField(bytes: ByteArray, wantedField: Int): String? = readBytesField(bytes, wantedField)?.toString(Charsets.UTF_8)

    fun readVarintField(bytes: ByteArray, wantedField: Int): Long? {
        var offset = 0
        while (offset < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, offset)
            offset = afterTag
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> {
                    val (value, afterValue) = readVarint(bytes, offset)
                    offset = afterValue
                    if (field == wantedField) return value
                }

                1 -> offset += 8

                2 -> {
                    val (length, afterLength) = readVarint(bytes, offset)
                    require(length in 0..(bytes.size - afterLength).toLong()) { "Invalid protobuf field length" }
                    offset = afterLength + length.toInt()
                }

                5 -> offset += 4

                else -> error("Unsupported protobuf wire type")
            }
            require(offset <= bytes.size) { "Malformed protobuf payload" }
        }
        return null
    }

    fun classifyBindError(message: String?): String = when {
        message.isNullOrEmpty() -> "none"
        message.contains("invalid-token", ignoreCase = true) -> "invalid-token"
        message.contains("invalid-sig", ignoreCase = true) -> "invalid-sig"
        message.contains("token_expired", ignoreCase = true) -> "token_expired"
        else -> "other(length=${message.length})"
    }

    private fun buildCloudAttributes(identity: MiMessageIdentity): String {
        val attributes = HashMap<String, String>()
        if (identity.phone.isNotBlank()) {
            val key = Base64.decode(identity.security, Base64.DEFAULT)
            val iv = byteArrayOf(100, 23, 84, 114, 72, 0, 4, 97, 73, 97, 2, 52, 84, 102, 18, 32)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encryptedPhone = cipher.doFinal(identity.phone.toByteArray(Charsets.UTF_8))
            attributes["pid"] = Base64.encodeToString(encryptedPhone, Base64.NO_WRAP)
        }
        attributes["v"] = "1"
        // Original o9.y.e() flattens its HashMap using com.xiaomi.onetrack.util.z.b (comma).
        return attributes.entries.joinToString(",") { (key, value) -> "$key:$value" }
    }

    private fun xmppCipherKey(security: String, packetId: String): ByteArray {
        val securityBytes = Base64.decode(security, Base64.DEFAULT)
        require(securityBytes.size == 16) { "Mixin Security must decode to 16 bytes" }
        val packetBytes = packetId.toByteArray(Charsets.UTF_8)
        return ByteArray(securityBytes.size + 1 + packetBytes.size).also { key ->
            securityBytes.copyInto(key)
            key[securityBytes.size] = '_'.code.toByte()
            packetBytes.copyInto(key, securityBytes.size + 1)
        }
    }

    private fun parsePresencePacket(xmlBytes: ByteArray, metadataPacketId: String): PresencePacket? = runCatching {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", true)
            setInput(StringReader(String(xmlBytes, Charsets.UTF_8)))
        }
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            event = parser.next()
        }
        if (event != XmlPullParser.START_TAG || parser.name != "presence") return null

        val packetId = parser.getAttributeValue(null, "id") ?: metadataPacketId
        val from = parser.getAttributeValue(null, "from")
        val to = parser.getAttributeValue(null, "to")
        val channelId = parser.getAttributeValue(null, "chid")
        val type = parser.getAttributeValue(null, "type") ?: "available"
        var clientAttributes: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "client_attrs") {
                        clientAttributes = parser.nextText()
                    } else {
                        skipXmlElement(parser)
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "presence") break

                XmlPullParser.END_DOCUMENT -> break
            }
        }
        PresencePacket(packetId, from, to, channelId, type, clientAttributes)
    }.getOrNull()

    private fun skipXmlElement(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun applyRc4(key: ByteArray, data: ByteArray, offset: Int, length: Int) {
        require(key.isNotEmpty())
        val state = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = state[i]
            state[i] = state[j]
            state[j] = tmp
        }
        var i = 0
        j = 0
        for (index in offset until offset + length) {
            i = (i + 1) and 0xFF
            j = (j + state[i]) and 0xFF
            val tmp = state[i]
            state[i] = state[j]
            state[j] = tmp
            val streamByte = state[(state[i] + state[j]) and 0xFF]
            data[index] = (data[index].toInt() xor streamByte).toByte()
        }
    }

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray = data.copyOf().also {
        applyRc4(key, it, 0, it.size)
    }

    private fun writeStringField(out: ByteArrayOutputStream, field: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, (field shl 3 or 2).toLong())
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
        writeVarint(out, (field shl 3).toLong())
        writeVarint(out, value)
    }

    private fun writeVarint(out: ByteArrayOutputStream, rawValue: Long) {
        var value = rawValue
        while (value and -128L != 0L) {
            out.write(((value and 0x7F) or 0x80).toInt())
            value = value ushr 7
        }
        out.write(value.toInt())
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var offset = start
        while (offset < bytes.size && shift < 64) {
            val byte = bytes[offset++].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return value to offset
            shift += 7
        }
        error("Malformed protobuf varint")
    }
}
