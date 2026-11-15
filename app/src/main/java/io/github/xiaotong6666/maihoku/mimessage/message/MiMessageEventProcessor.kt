package io.github.xiaotong6666.maihoku.mimessage.message

import io.github.xiaotong6666.maihoku.mimessage.account.MiMessageIdentity
import io.github.xiaotong6666.maihoku.mimessage.core.MiMessageConfig
import io.github.xiaotong6666.maihoku.mimessage.transport.MiMessagePacketCodec
import org.json.JSONObject

/** Converts the original Mi Message XMPP stanzas into typed events without side effects. */
internal object MiMessageEventProcessor {
    sealed interface Event {
        data class IncomingSms(
            val packetId: String,
            val senderMid: String,
            val senderJid: String,
            val recipientMid: String,
            val threadAddress: String,
            val body: String,
            val messageId: String,
            val sentAt: Long,
        ) : Event

        data class ServerAccepted(
            val packetId: String?,
            val messageId: String,
        ) : Event

        data class DeliveryReceipt(
            val packetId: String?,
            val messageId: String,
            val errorType: String?,
            val errorReason: String?,
            val errorText: String?,
        ) : Event

        data class Unsupported(val reason: String) : Event
    }

    /**
     * Mirrors MxPushMessageReceiver's new_msg dispatch: body + xmthread is an incoming
     * conversation message; body-less <sent/> and <received/> stanzas are status events.
     * This stage deliberately does not persist, acknowledge, or write to SMS Provider.
     */
    fun classify(
        packet: MiMessagePacketCodec.MessagePacket,
        localIdentity: MiMessageIdentity,
    ): Event {
        if (packet.channelId != MiMessageConfig.CHANNEL_ID) return Event.Unsupported("wrong_channel")

        if (packet.body != null) {
            val json = packet.smsBodyJson() ?: return Event.Unsupported("malformed_body_json")
            if (json.optString("type") != "sms") return Event.Unsupported("unsupported_message_type")

            val packetId = packet.id?.takeIf(String::isNotBlank)
                ?: return Event.Unsupported("missing_packet_id")
            val senderMid = jidMid(packet.from) ?: return Event.Unsupported("invalid_sender_jid")
            val recipientMid = jidMid(packet.to) ?: return Event.Unsupported("invalid_recipient_jid")
            if (recipientMid != localIdentity.mid) return Event.Unsupported("message_not_for_local_mid")

            val thread = packet.extension("xmthread") ?: return Event.Unsupported("missing_thread_extension")
            if (thread.attribute("type") != "phone") return Event.Unsupported("unsupported_thread_type")
            val address = thread.text.trim().takeIf(String::isNotEmpty)
                ?: return Event.Unsupported("missing_thread_address")
            val body = json.optString("pdu").takeIf(String::isNotEmpty)
                ?: return Event.Unsupported("empty_sms_body")
            val messageId = json.optString("msgId").takeIf { value ->
                value.toLongOrNull()?.let { it > 0L } == true
            } ?: return Event.Unsupported("invalid_message_id")

            return Event.IncomingSms(
                packetId = packetId,
                senderMid = senderMid,
                senderJid = packet.from ?: return Event.Unsupported("missing_sender_jid"),
                recipientMid = recipientMid,
                threadAddress = address,
                body = body,
                messageId = messageId,
                sentAt = json.optLong("bodySentTime", 0L),
            )
        }

        packet.sentMessageId?.takeIf(String::isNotBlank)?.let { messageId ->
            return Event.ServerAccepted(packet.id, messageId)
        }
        packet.receivedMessageId?.takeIf(String::isNotBlank)?.let { messageId ->
            val error = packet.extension("error")
            return Event.DeliveryReceipt(
                packetId = packet.id,
                messageId = messageId,
                errorType = error?.attribute("type"),
                errorReason = error?.attribute("reason"),
                errorText = error?.text?.takeIf(String::isNotBlank),
            )
        }
        return Event.Unsupported("unrecognized_message_stanza")
    }

    private fun jidMid(jid: String?): String? {
        val bare = jid?.substringBefore('/') ?: return null
        val separator = bare.indexOf('@')
        if (separator <= 0 || bare.substring(separator + 1) != "xiaomi.com") return null
        return bare.substring(0, separator).takeIf { it.toLongOrNull()?.let { mid -> mid > 0L } == true }
    }
}
