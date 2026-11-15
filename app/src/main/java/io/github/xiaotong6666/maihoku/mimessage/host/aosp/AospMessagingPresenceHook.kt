package io.github.xiaotong6666.maihoku.mimessage.host.aosp

import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import io.github.xiaotong6666.maihoku.mimessage.host.common.MessagingHostOutgoingUi
import java.lang.reflect.Method
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Hooks the un-obfuscated AOSP/Lineage Messaging conversation lifecycle. */
internal object AospMessagingPresenceHook {
    fun install(
        runtime: HookRuntime,
        modulePackageName: String,
        sendAdapter: AospMessagingSendAdapter?,
    ) {
        val fragmentClass = runtime.classLoader.loadClass(CONVERSATION_FRAGMENT_CLASS)
        val composeViewClass = runtime.classLoader.loadClass(COMPOSE_MESSAGE_VIEW_CLASS)
        val conversationDataClass = runtime.classLoader.loadClass(CONVERSATION_DATA_CLASS)
        val participantsLoaded = fragmentClass.getDeclaredMethod(
            "onConversationParticipantDataLoaded",
            conversationDataClass,
        ).apply { isAccessible = true }
        val onResume = fragmentClass.declaredMethods.singleOrNull(::isOnResume)
            ?.apply { isAccessible = true }
            ?: error("AOSP ConversationFragment.onResume() was not found")
        val onPause = fragmentClass.declaredMethods.singleOrNull(::isOnPause)
            ?.apply { isAccessible = true }
        val sendMessageInternal = composeViewClass.getDeclaredMethod("sendMessageInternal", Boolean::class.javaPrimitiveType!!)
            .apply { isAccessible = true }

        runtime.hooks.method(participantsLoaded, "aosp-messaging.presence.participants-loaded") {
            after {
                val fragment = thisObject ?: return@after
                val conversationData = arg(0) ?: return@after
                runCatching {
                    AospMessagingComposeUi.bindAospConversation(fragment, conversationData, modulePackageName)
                }.onFailure { error ->
                    Log.w(TAG, "Could not bind AOSP conversation Presence state: ${error.javaClass.simpleName}")
                }
            }
        }

        runtime.hooks.method(onResume, "aosp-messaging.presence.refresh-on-resume") {
            after {
                val fragment = thisObject ?: return@after
                runCatching {
                    AospMessagingComposeUi.bindAospCurrentConversation(fragment, modulePackageName)
                }.onFailure { error ->
                    Log.w(TAG, "Could not refresh AOSP conversation Presence state: ${error.javaClass.simpleName}")
                }
            }
        }

        if (onPause != null) {
            runtime.hooks.method(onPause, "aosp-messaging.presence.stop-refresh-on-pause") {
                after {
                    val fragment = thisObject ?: return@after
                    AospMessagingComposeUi.pauseAospConversation(fragment)
                }
            }
        }

        // User-selected network mode must never silently fall through to carrier SMS.
        runtime.hooks.around(sendMessageInternal, "aosp-messaging.presence.guard-network-mode") {
            val composeView = thisObject
            val miMessageSelected = AospMessagingComposeUi.isMiMessageSelected(composeView)
            Log.i(TAG, "AOSP send attempt observed mode=${if (miMessageSelected) "MI_MESSAGE" else "SMS"}")
            if (miMessageSelected) {
                val request = AospMessagingComposeUi.captureAospSend(composeView)
                if (sendAdapter == null) {
                    Log.e(TAG, "Blocked AOSP native SMS fallback: native Mi Message sender adapter is unavailable")
                    AospMessagingComposeUi.notifyMiMessageSendNotReady(composeView)
                } else if (request == null) {
                    Log.w(TAG, "Blocked AOSP native SMS fallback: Mi Message requires one online recipient and a non-empty text")
                    AospMessagingComposeUi.notifyMiMessageSendError(
                        composeView,
                        R.string.mx_send_single_recipient,
                    )
                } else {
                    MessagingHostOutgoingUi.enqueue(
                        context = request.context,
                        modulePackageName = modulePackageName,
                        phoneNumber = request.phoneNumber,
                        body = request.body,
                        subscriptionId = request.subscriptionId,
                        editor = request.editor,
                        adapter = sendAdapter,
                    )
                }
                null
            } else {
                proceed()
            }
        }

        Log.i(TAG, "Installed AOSP conversation Presence hooks on ${fragmentClass.name}")
    }

    private fun isOnResume(method: Method): Boolean = method.name == "onResume" && method.returnType == Void.TYPE && method.parameterCount == 0

    private fun isOnPause(method: Method): Boolean = method.name == "onPause" && method.returnType == Void.TYPE && method.parameterCount == 0

    private const val CONVERSATION_FRAGMENT_CLASS = "com.android.messaging.ui.conversation.ConversationFragment"
    private const val CONVERSATION_DATA_CLASS = "com.android.messaging.datamodel.data.ConversationData"
    private const val COMPOSE_MESSAGE_VIEW_CLASS = "com.android.messaging.ui.conversation.ComposeMessageView"
    private const val TAG = "AospMsgPresence"
}
