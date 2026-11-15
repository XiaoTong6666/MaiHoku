package io.github.xiaotong6666.maihoku.features.mimessage.host.common

import android.content.Context

/** Host-owned local conversation row adapter; the Xiaomi Push transport remains in MaiHoku UID. */
internal interface MessagingHostSendAdapter {
    fun createPendingMessage(
        context: Context,
        requestId: String,
        phoneNumber: String,
        body: String,
        subscriptionId: Int,
    ): MessagingHostOutgoingRecord

    fun applyStatus(
        context: Context,
        record: MessagingHostOutgoingRecord,
        status: String,
        error: String?,
    )
}
