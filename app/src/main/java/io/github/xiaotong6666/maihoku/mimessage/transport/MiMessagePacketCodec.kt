package io.github.xiaotong6666.maihoku.mimessage.transport

import android.util.Base64
import android.util.Xml
import io.github.xiaotong6666.maihoku.mimessage.account.MiMessageIdentity
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import io.github.xiaotong6666.maihoku.mimessage.transport.MiMessageXml.escape as xmlEscape

/** Parser for the original Mi Message XMPP <message/> subset. */
internal object MiMessagePacketCodec {
    private val smsPacketIds = AtomicLong(
        ((System.currentTimeMillis() and 0xFFFFFFFFFFFL) shl 16) or SecureRandom().nextInt(1 shl 16).toLong(),
    )

    data class Extension(
        val name: String,
        val namespace: String?,
        val attributes: Map<String, String>,
        val text: String,
    ) {
        fun attribute(name: String): String? = attributes[name]
    }

    data class MessagePacket(
        val id: String?,
        val from: String?,
        val to: String?,
        val channelId: String?,
        val body: String?,
        val bodyEncoding: String?,
        val extensions: List<Extension>,
    ) {
        fun extension(name: String): Extension? = extensions.firstOrNull { it.name == name }

        fun decodedBody(): String? {
            val value = body ?: return null
            return if (bodyEncoding == "base64") {
                String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
            } else {
                value
            }
        }

        fun smsBodyJson(): JSONObject? {
            val decoded = decodedBody() ?: return null
            return runCatching { JSONObject(decoded) }.getOrNull()
        }

        val threadType: String?
            get() = extension("xmthread")?.attribute("type")

        val threadAddress: String?
            get() = extension("xmthread")?.text?.takeIf(String::isNotBlank)

        val sentMessageId: String?
            get() = extension("sent")?.attribute("id")

        val receivedMessageId: String?
            get() = extension("received")?.attribute("id")
    }

    /** Mirrors b9.f.f(false): normal SMS IDs use the low (non-MMS) numeric ID space. */
    fun nextSmsPacketId(): String = smsPacketIds.getAndIncrement().toString()

    /** Mirrors b9.f.d() + b9.f.g() for a regular phone-to-phone text message. */
    fun buildSmsStanza(
        identity: MiMessageIdentity,
        recipientMidOrJid: String,
        text: String,
        packetId: String = nextSmsPacketId(),
        sentAtMillis: Long = System.currentTimeMillis(),
    ): String {
        identity.validate()
        require(text.isNotEmpty()) { "Mi Message SMS body is empty" }
        // The same numeric SMS ID appears in XML id and JSON msgId. Keep it stable across
        // transport retries so receipts correlate with the existing logical message and local row.
        val numericId = packetId.toLongOrNull()
            ?: error("Original Mi Message SMS packet IDs must be numeric")
        require(numericId >= 0 && (numericId and -1152921504606846976L) == 0L) {
            "Packet ID is outside the original normal-SMS ID range"
        }
        val recipient = recipientMidOrJid.trim()
        require(recipient.isNotEmpty()) { "Recipient MID/JID is empty" }
        val to = if ('@' in recipient) recipient else "$recipient@xiaomi.com"
        val from = "${identity.mid}@xiaomi.com/${identity.resource}"
        val json = JSONObject()
            .put("type", "sms")
            .put("pdu", text)
            .put("msgId", numericId)
            .put("bodySentTime", sentAtMillis)
        val body = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return buildString {
            append("<message id=\"").append(xmlEscape(packetId)).append('"')
            append(" to=\"").append(xmlEscape(to)).append('"')
            append(" from=\"").append(xmlEscape(from)).append('"')
            append(" chid=\"3\"><body encode=\"base64\">")
            append(body)
            append("</body></message>")
        }
    }

    /** Mirrors b9.f.h() for the normal, non-B2C incoming-message receipt. */
    fun buildReceivedAckStanza(
        identity: MiMessageIdentity,
        originalSenderJid: String,
        originalPacketId: String,
        ackPacketId: String,
    ): String {
        require(identity.mid.toLongOrNull()?.let { it > 0L } == true) { "Mixin MID is invalid for received ACK" }
        require(identity.resource.isNotBlank()) { "Mi Message Resource is missing" }
        require(originalSenderJid.isNotBlank()) { "Original sender JID is empty" }
        // A received receipt references the incoming XML ID. Give its envelope a separate ID
        // and no body so it cannot be interpreted as another user message.
        require(originalPacketId.isNotBlank()) { "Original XMPP packet ID is empty" }
        require(ackPacketId.isNotBlank()) { "ACK packet ID is empty" }
        val to = if ('@' in originalSenderJid) originalSenderJid else "$originalSenderJid@xiaomi.com"
        val from = "${identity.mid}@xiaomi.com/${identity.resource}"
        return "<message id=\"${xmlEscape(ackPacketId)}\" to=\"${xmlEscape(to)}\" " +
            "from=\"${xmlEscape(from)}\" chid=\"3\"><received id=\"${xmlEscape(originalPacketId)}\"/></message>"
    }

    /** Parses only an XMPP message stanza; unrelated stanzas are ignored. */
    fun parseMessage(xml: String): MessagePacket? = runCatching {
        require(xml.isNotBlank()) { "XMPP packet XML is empty" }
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(xml))
        }
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            event = parser.next()
        }
        if (event != XmlPullParser.START_TAG || parser.name != "message") return null

        val id = parser.getAttributeValue(null, "id")
        val from = parser.getAttributeValue(null, "from")
        val to = parser.getAttributeValue(null, "to")
        val channelId = parser.getAttributeValue(null, "chid")
        var body: String? = null
        var bodyEncoding: String? = null
        val extensions = ArrayList<Extension>()

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val childName = parser.name
                    val childNamespace = parser.namespace?.takeIf(String::isNotEmpty)
                    val attributes = buildMap {
                        for (index in 0 until parser.attributeCount) {
                            put(parser.getAttributeName(index), parser.getAttributeValue(index))
                        }
                    }
                    val text = readElementText(parser)
                    when (childName) {
                        "body" -> {
                            body = text
                            bodyEncoding = attributes["encode"]
                        }

                        "subject", "thread" -> Unit

                        else -> extensions += Extension(childName, childNamespace, attributes, text)
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "message") break

                XmlPullParser.END_DOCUMENT -> error("Unexpected end of XMPP message")
            }
        }
        MessagePacket(id, from, to, channelId, body, bodyEncoding, extensions)
    }.getOrNull()

    /** Consumes a child element without failing when an extension contains nested XML. */
    private fun readElementText(parser: XmlPullParser): String {
        val text = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> text.append(parser.text)
                XmlPullParser.END_DOCUMENT -> error("Unexpected end of XMPP extension")
            }
        }
        return text.toString()
    }
}
