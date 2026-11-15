package io.github.xiaotong6666.maihoku.features.mimessage.host.aosp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.mimessage.host.common.MessagingHostBridge
import io.github.xiaotong6666.maihoku.features.mimessage.presence.MiMessagePresencePolicy
import io.github.xiaotong6666.maihoku.features.mimessage.ui.MessagingResources
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedHashMap
import java.util.WeakHashMap
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** AOSP Messaging composer mode, recipient Presence and native send capture. */
internal object AospMessagingComposeUi {
    private data class ContactState(
        val status: String,
        val capabilities: Long,
        val expiresAt: Long,
    )

    private data class EditorBinding(
        val originalHint: CharSequence?,
        var phoneKeys: Set<String>,
        var phoneNumbers: List<String> = emptyList(),
        var miMessageShown: Boolean = false,
        var selectedMode: ComposeMode? = null,
        var canUseMiMessage: Boolean = false,
        var toggleView: WeakReference<TextView>? = null,
    )

    private enum class ComposeMode { SMS, MI_MESSAGE }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val editorBindings = WeakHashMap<EditText, EditorBinding>()
    private val refreshCallbacks = WeakHashMap<EditText, Runnable>()
    private val contacts = LinkedHashMap<String, ContactState>()

    /** Called by the AOSP ConversationFragment hook after participant data has loaded. */
    fun bindAospConversation(fragment: Any, conversationData: Any, modulePackageName: String) {
        val activity = invokeNoArg(fragment, "getActivity") as? Context ?: return
        val recipients = extractAospRecipients(conversationData)
        val editor = findAospComposeEditor(fragment)
        if (editor == null) {
            Log.w(TAG, "AOSP compose editor was not available when participant data loaded")
            return
        }

        val phoneKeys = recipients.mapNotNull(MiMessagePresencePolicy::normalizePhoneNumber).toSet()
        synchronized(lock) {
            val originalHint = editorBindings[editor]?.originalHint ?: editor.hint
            val binding = editorBindings[editor] ?: EditorBinding(originalHint, phoneKeys)
            binding.phoneKeys = phoneKeys
            binding.phoneNumbers = recipients
            editorBindings[editor] = binding
            ensureModeToggleLocked(editor, binding)
            applyEditorModeLocked(editor, binding)
        }

        recipients.forEach { phoneNumber ->
            MessagingHostBridge.requestPresence(activity, modulePackageName, phoneNumber)
        }
        scheduleAospRefresh(editor, activity, modulePackageName, recipients)
        Log.i(TAG, "AOSP conversation Presence requested recipients=${phoneKeys.size} compose_editor=true")
    }

    /** Rebind after a conversation returns to the foreground, using its current data model. */
    fun bindAospCurrentConversation(fragment: Any, modulePackageName: String) {
        val binding = findField(fragment.javaClass, "mBinding")?.let { field ->
            runCatching { field.get(fragment) }.getOrNull()
        } ?: return
        val conversationData = invokeNoArg(binding, "getData") ?: return
        bindAospConversation(fragment, conversationData, modulePackageName)
    }

    /** Stop the active-conversation refresh loop when the host fragment leaves the foreground. */
    fun pauseAospConversation(fragment: Any) {
        val editor = findAospComposeEditor(fragment) ?: return
        synchronized(lock) {
            refreshCallbacks.remove(editor)?.let(mainHandler::removeCallbacks)
        }
    }

    fun onPresenceChanged(phoneNumber: String, status: String, capabilities: Long, expiresAt: Long) {
        val key = MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) ?: return
        mainHandler.post {
            runCatching {
                synchronized(lock) {
                    if (status == MiMessagePresencePolicy.STATUS_UNKNOWN || expiresAt <= System.currentTimeMillis()) {
                        contacts.remove(key)
                    } else {
                        contacts[key] = ContactState(status, capabilities, expiresAt)
                    }
                    trimContactStatesLocked()
                    editorBindings.entries.toList().forEach { (editor, binding) ->
                        if (key in binding.phoneKeys) applyEditorModeLocked(editor, binding)
                    }
                }
            }.onFailure { Log.e(TAG, "Could not refresh AOSP compose mode after Presence update", it) }
        }
    }

    /** Used by the send-path guard: Mi Message mode must never silently fall through to carrier SMS. */
    fun isMiMessageSelected(composeView: Any?): Boolean {
        if (composeView == null) return false
        val editor = findField(composeView.javaClass, "mComposeEditText")
            ?.let { field -> runCatching { field.get(composeView) as? EditText }.getOrNull() }
            ?: return false
        return synchronized(lock) {
            val binding = editorBindings[editor] ?: return@synchronized false
            binding.canUseMiMessage && binding.selectedMode != ComposeMode.SMS
        }
    }

    fun captureAospSend(composeView: Any?): AospComposeSendRequest? {
        if (composeView !is android.view.View) return null
        val editor = findField(composeView.javaClass, "mComposeEditText")
            ?.let { field -> runCatching { field.get(composeView) as? EditText }.getOrNull() }
            ?: return null
        val (phoneNumber, body) = synchronized(lock) {
            val binding = editorBindings[editor] ?: return null
            if (!binding.canUseMiMessage || binding.selectedMode == ComposeMode.SMS) return null
            if (binding.phoneNumbers.size != 1 || binding.phoneKeys.size != 1) return null
            val text = editor.text?.toString()?.takeIf(String::isNotBlank) ?: return null
            binding.phoneNumbers.single() to text
        }
        val host = findField(composeView.javaClass, "mHost")
            ?.let { field -> runCatching { field.get(composeView) }.getOrNull() }
        val subscriptionId = host?.let { invokeNoArg(it, "getConversationSelfSubId") as? Int }
            ?.takeIf { it >= 0 }
            ?: android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()
        return AospComposeSendRequest(
            context = composeView.context,
            editor = editor,
            phoneNumber = phoneNumber,
            body = body,
            subscriptionId = subscriptionId,
        )
    }

    fun notifyMiMessageSendNotReady(composeView: Any?) {
        notifyMiMessageSendError(composeView, R.string.mx_send_adapter_unavailable)
    }

    fun notifyMiMessageSendError(composeView: Any?, message: Int) {
        val context = (composeView as? android.view.View)?.context ?: return
        Toast.makeText(
            context,
            MessagingResources.text(context, message),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun extractAospRecipients(conversationData: Any): List<String> {
        val participants = invokeNoArg(conversationData, "getParticipants") ?: return emptyList()
        val entries = invokeNoArg(participants, "getParticipantListExcludingSelf") as? Iterable<*>
            ?: return emptyList()
        return entries.mapNotNull { participant ->
            participant?.let { invokeNoArg(it, "getSendDestination") as? String }
                ?.takeIf { MiMessagePresencePolicy.normalizePhoneNumber(it) != null }
        }.distinctBy { MiMessagePresencePolicy.normalizePhoneNumber(it) }
    }

    private fun findAospComposeEditor(fragment: Any): EditText? {
        val composeView = findField(fragment.javaClass, "mComposeMessageView")
            ?.let { field -> runCatching { field.get(fragment) }.getOrNull() }
            ?: return null
        val getter = findNoArgMethod(composeView.javaClass, "getComposeEditText") ?: return null
        return runCatching { getter.invoke(composeView) as? EditText }.getOrNull()
    }

    private fun applyEditorModeLocked(editor: EditText, binding: EditorBinding) {
        val now = System.currentTimeMillis()
        val canUseMiMessage = binding.phoneKeys.isNotEmpty() && binding.phoneKeys.all { key ->
            val state = contacts[key] ?: return@all false
            MiMessagePresencePolicy.supportsSms(state.status, state.capabilities, state.expiresAt, now)
        }
        binding.canUseMiMessage = canUseMiMessage
        if (!canUseMiMessage && binding.selectedMode == ComposeMode.MI_MESSAGE) {
            binding.selectedMode = null
        }
        val useMiMessage = canUseMiMessage && binding.selectedMode != ComposeMode.SMS
        if (binding.miMessageShown != useMiMessage) {
            binding.miMessageShown = useMiMessage
            Log.i(
                TAG,
                "AOSP compose mode=${if (useMiMessage) "mi_message" else "sms"} " +
                    "recipients=${binding.phoneKeys.size}",
            )
        }
        val desiredHint = if (useMiMessage) MessagingResources.text(editor.context, R.string.mx_compose_hint) else binding.originalHint
        if (editor.hint != desiredHint) editor.hint = desiredHint
        binding.toggleView?.get()?.let { toggle ->
            toggle.text = MessagingResources.text(editor.context, if (useMiMessage) R.string.mx_compose_network_label else R.string.mx_compose_sms_label)
            toggle.isEnabled = canUseMiMessage
            toggle.alpha = if (canUseMiMessage) 1f else 0.65f
            toggle.contentDescription = MessagingResources.text(editor.context, if (useMiMessage) R.string.mx_compose_switch_sms else R.string.mx_compose_switch_network)
        }
    }

    private fun ensureModeToggleLocked(editor: EditText, binding: EditorBinding) {
        if (binding.toggleView?.get()?.isAttachedToWindow == true) return
        val row = editor.parent as? ViewGroup ?: return
        val column = row.parent as? LinearLayout ?: run {
            Log.w(TAG, "AOSP compose layout parent is not the expected vertical LinearLayout")
            return
        }
        if (column.orientation != LinearLayout.VERTICAL) {
            Log.w(TAG, "AOSP compose layout column orientation changed; mode selector not inserted")
            return
        }

        val density = editor.resources.displayMetrics.density
        val toggle = TextView(editor.context).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((12 * density).toInt(), (5 * density).toInt(), (12 * density).toInt(), (5 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF586579.toInt())
                cornerRadius = 18 * density
            }
            isClickable = true
            isFocusable = true
            minHeight = (28 * density).toInt()
            setOnClickListener {
                synchronized(lock) {
                    val current = editorBindings[editor] ?: return@setOnClickListener
                    if (!current.canUseMiMessage) return@setOnClickListener
                    current.selectedMode = if (current.selectedMode == ComposeMode.SMS) {
                        ComposeMode.MI_MESSAGE
                    } else {
                        ComposeMode.SMS
                    }
                    applyEditorModeLocked(editor, current)
                    Log.i(TAG, "AOSP compose mode manually selected=${current.selectedMode ?: "AUTO"}")
                }
            }
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.END
            bottomMargin = (4 * density).toInt()
        }
        column.addView(toggle, 0, params)
        binding.toggleView = WeakReference(toggle)
    }

    private fun scheduleAospRefresh(
        editor: EditText,
        context: Context,
        modulePackageName: String,
        recipients: List<String>,
    ) {
        val editorReference = WeakReference(editor)
        val contextReference = WeakReference(context)
        val callback = object : Runnable {
            override fun run() {
                val currentEditor = editorReference.get()
                val currentContext = contextReference.get()
                if (currentEditor == null || currentContext == null || !currentEditor.isAttachedToWindow) {
                    if (currentEditor != null) synchronized(lock) { refreshCallbacks.remove(currentEditor) }
                    return
                }
                recipients.forEach { phoneNumber ->
                    MessagingHostBridge.requestPresence(currentContext, modulePackageName, phoneNumber)
                }
                mainHandler.postDelayed(this, PRESENCE_REFRESH_INTERVAL_MS)
            }
        }
        synchronized(lock) {
            refreshCallbacks.remove(editor)?.let(mainHandler::removeCallbacks)
            refreshCallbacks[editor] = callback
            mainHandler.postDelayed(callback, PRESENCE_REFRESH_INTERVAL_MS)
        }
    }

    private fun trimContactStatesLocked() {
        val now = System.currentTimeMillis()
        val iterator = contacts.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAt <= now) iterator.remove()
        }
        while (contacts.size > MAX_CONTACT_STATES) {
            val oldest = contacts.entries.firstOrNull()?.key ?: break
            contacts.remove(oldest)
        }
    }

    private fun invokeNoArg(instance: Any, methodName: String): Any? {
        val method = findNoArgMethod(instance.javaClass, methodName) ?: return null
        return runCatching { method.invoke(instance) }.getOrNull()
    }

    private fun findNoArgMethod(type: Class<*>, name: String): Method? = generateSequence(type as Class<*>?) { it.superclass }
        .flatMap { it.declaredMethods.asSequence() }
        .firstOrNull { it.name == name && it.parameterCount == 0 }
        ?.apply { isAccessible = true }

    private fun findField(type: Class<*>, name: String): Field? = generateSequence(type as Class<*>?) { it.superclass }
        .mapNotNull { clazz -> runCatching { clazz.getDeclaredField(name) }.getOrNull() }
        .firstOrNull()
        ?.apply { isAccessible = true }

    private const val TAG = "MessagingPresenceUI"
    private const val MAX_CONTACT_STATES = 512
    private const val PRESENCE_REFRESH_INTERVAL_MS = 30_000L
}

internal data class AospComposeSendRequest(
    val context: Context,
    val editor: EditText,
    val phoneNumber: String,
    val body: String,
    val subscriptionId: Int,
)
