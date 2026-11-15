package io.github.xiaotong6666.maihoku.features.mimessage.host.google

import android.app.Activity
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.mimessage.host.common.MessagingHostBridge
import io.github.xiaotong6666.maihoku.features.mimessage.presence.MiMessagePresencePolicy
import io.github.xiaotong6666.maihoku.features.mimessage.ui.MessagingResources
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Inline SMS/Mi Message prefix selector for Google's ComposeView-backed conversation composer. */
internal object GoogleMessagesPresenceUi {
    private enum class Mode { AUTO, MI_MESSAGE, SMS }

    private data class Presence(
        val status: String,
        val capabilities: Long,
        val expiresAt: Long,
    )

    private data class RequestStamp(val requestedAt: Long)

    private data class ActiveConversationAccessor(val peer: Field, val currentId: Method)

    private data class EditorPadding(
        val start: Int,
        val top: Int,
        val end: Int,
        val bottom: Int,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val presences = ConcurrentHashMap<String, Presence>()
    private val modes = ConcurrentHashMap<String, Mode>()
    private val lastRequests = ConcurrentHashMap<String, RequestStamp>()
    private val activeConversationAccessors = ConcurrentHashMap<Class<*>, List<ActiveConversationAccessor>>()
    private var activityRef: WeakReference<Activity>? = null
    private var rootRef: WeakReference<FrameLayout>? = null
    private var editorRef: WeakReference<EditText>? = null
    private var paddedEditorRef: WeakReference<EditText>? = null
    private var originalEditorPadding: EditorPadding? = null
    private var selectorRef: WeakReference<TextView>? = null
    private var lastSelectorMiMode: Boolean? = null

    @Volatile private var currentPhone: String? = null
    private var lastConversationLookupId: Long? = null
    private var lastConversationLookupPhone: String? = null
    private var modulePackageName: String? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    fun bind(activity: Activity, modulePackage: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        modulePackageName = modulePackage
        activityRef = WeakReference(activity)
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        Log.i(TAG, "Binding Google Messages Presence UI activity=${activity.javaClass.name} root_found=true")
        val previousRoot = rootRef?.get()
        val existing = layoutListener
        if (existing == null || previousRoot !== root) {
            existing?.let { listener ->
                previousRoot?.viewTreeObserver?.takeIf(ViewTreeObserver::isAlive)?.removeOnGlobalLayoutListener(listener)
            }
            val listener = ViewTreeObserver.OnGlobalLayoutListener { refreshComposer(activity, root) }
            layoutListener = listener
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }
        rootRef = WeakReference(root)
        val intent = activity.intent
        lastConversationLookupId = null
        lastConversationLookupPhone = null
        val intentRecipient = resolvePhoneFromConversationIntent(activity, intent)
            ?: intent?.data
                ?.takeIf { it.scheme in setOf("sms", "smsto", "mms", "mmsto") }
                ?.schemeSpecificPart
                ?.let(::extractPhoneCandidate)
        currentPhone = intentRecipient
        if (intentRecipient != null) {
            currentPhone = intentRecipient
            requestPresence(intentRecipient)
        }
        refreshComposer(activity, root)
    }

    private fun resolvePhoneFromConversationIntent(activity: Activity, intent: android.content.Intent?): String? {
        // Messages stores the current conversation in MainActivity's peer/UI state. Internal list
        // navigation often leaves Activity.intent as the launcher intent, and mapi_conversation_id
        // is a Parcelable ConversationId rather than a Long. Support both sources and old extras.
        val rawId = intent?.extras?.get("mapi_conversation_id") ?: intent?.extras?.get("conversation_id")
        val threadId = rawId?.let { conversationDatabaseId(activity, it) }
            ?: activeConversationDatabaseId(activity)
            ?: return null
        if (threadId <= 0L) return null
        if (threadId == lastConversationLookupId) return lastConversationLookupPhone
        val recipient = resolvePhoneFromBugleDatabase(activity, threadId) ?: run {
            val recipientIds = runCatching {
                activity.contentResolver.query(
                    CONVERSATIONS_URI,
                    arrayOf("recipient_ids"),
                    "_id=?",
                    arrayOf(threadId.toString()),
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow("recipient_ids")) else null
                }
            }.getOrNull()
            val ids = recipientIds?.split(RECIPIENT_ID_SEPARATOR)?.filter(String::isNotBlank)?.distinct().orEmpty()
            if (ids.size != 1) {
                null
            } else {
                runCatching {
                    activity.contentResolver.query(
                        CANONICAL_ADDRESSES_URI,
                        arrayOf("address"),
                        "_id=?",
                        arrayOf(ids.single()),
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow("address")) else null
                    }
                }.getOrNull()?.let(::extractPhoneCandidate)
            }
        }
        lastConversationLookupId = threadId
        lastConversationLookupPhone = recipient
        Log.i(TAG, "Google active conversation lookup complete idPresent=true recipientResolved=${recipient != null}")
        return recipient
    }

    private fun activeConversationDatabaseId(activity: Activity): Long? {
        val accessors = activeConversationAccessors.computeIfAbsent(activity.javaClass, ::resolveActiveConversationAccessors)
        val idMethod = conversationIdStringMethod(activity.classLoader) ?: return null
        for (accessor in accessors) {
            val peer = runCatching { accessor.peer.get(activity) }.getOrNull() ?: continue
            val conversation = runCatching { accessor.currentId.invoke(peer) }.getOrNull() ?: continue
            val rawId = runCatching { idMethod.invoke(conversation) as? String }.getOrNull() ?: continue
            val id = rawId.toLongOrNull()?.takeIf { it > 0L } ?: continue
            return id
        }
        return null
    }

    private fun conversationDatabaseId(activity: Activity, rawId: Any): Long? {
        if (rawId is Number) return rawId.toLong().takeIf { it > 0L }
        rawId.toString().toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        val apiClass = runCatching { activity.classLoader.loadClass(CONVERSATION_ID_CLASS) }
            .getOrNull()?.takeIf { it.isInstance(rawId) } ?: return null
        val kindGetter = apiClass.methods.singleOrNull { it.parameterCount == 0 && it.returnType.isEnum } ?: return null
        if (runCatching { kindGetter.invoke(rawId).toString() }.getOrNull() != BUGLE_CONVERSATION_KIND) return null
        val idGetter = conversationIdStringMethod(activity.classLoader) ?: return null
        return runCatching { (idGetter.invoke(rawId) as? String)?.toLongOrNull()?.takeIf { it > 0L } }.getOrNull()
    }

    private fun conversationIdStringMethod(classLoader: ClassLoader): Method? = runCatching {
        classLoader.loadClass(CONVERSATION_ID_CLASS).methods.singleOrNull {
            it.parameterCount == 0 && it.returnType == String::class.java
        }?.apply { isAccessible = true }
    }.getOrNull()

    private fun resolveActiveConversationAccessors(activityClass: Class<*>): List<ActiveConversationAccessor> {
        val conversationIdClass = runCatching { activityClass.classLoader.loadClass(CONVERSATION_ID_CLASS) }.getOrNull()
            ?: return emptyList()
        return generateSequence(activityClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) || it.type.isPrimitive }
            .mapNotNull { field ->
                val getters = generateSequence(field.type as Class<*>?) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .filter { it.parameterCount == 0 && conversationIdClass.isAssignableFrom(it.returnType) }
                    .distinctBy { it.name + it.returnType.name }
                    .toList()
                val getter = getters.singleOrNull() ?: return@mapNotNull null
                runCatching {
                    field.isAccessible = true
                    getter.isAccessible = true
                }.getOrElse { return@mapNotNull null }
                ActiveConversationAccessor(field, getter)
            }.toList()
    }

    /** Resolve BugleConversationId through Google Messages' own DB, not Telephony thread IDs. */
    private fun resolvePhoneFromBugleDatabase(context: Context, conversationId: Long): String? {
        val databaseName = runCatching {
            context.databaseList().firstOrNull { it == BUGLE_DATABASE_NAME || it.contains(BUGLE_DATABASE_NAME) }
        }.getOrNull() ?: return null
        val databaseFile = context.getDatabasePath(databaseName)
        if (!databaseFile.isFile) return null
        val database = runCatching {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
        }.getOrNull() ?: return null
        return try {
            val columns = database.rawQuery("PRAGMA table_info(conversations)", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
            val tableNames = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'",
                null,
            ).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            val destinationColumn = when {
                "participant_normalized_destination" in columns -> "participant_normalized_destination"
                "participant_display_destination" in columns -> "participant_display_destination"
                else -> null
            }
            if (destinationColumn != null) {
                val result = database.rawQuery(
                    "SELECT $destinationColumn, participant_count FROM conversations WHERE _id=?",
                    arrayOf(conversationId.toString()),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        null
                    } else {
                        val count = cursor.getInt(cursor.getColumnIndexOrThrow("participant_count"))
                        cursor.getString(cursor.getColumnIndexOrThrow(destinationColumn))
                            ?.takeIf { count == 1 }
                    }
                }
                result?.let(::extractPhoneCandidate)?.let { phone ->
                    Log.i(TAG, "Google conversation recipient resolved from Bugle DB digits=${phone.count(Char::isDigit)}")
                    return phone
                }
            }
            if ("conversation_to_participants" in tableNames && "participants" in tableNames) {
                val addresses = database.rawQuery(
                    "SELECT p.normalized_destination FROM conversation_to_participants cp " +
                        "JOIN participants p ON p._id=cp.participant_id WHERE cp.conversation_id=? " +
                        "AND p.normalized_destination IS NOT NULL",
                    arrayOf(conversationId.toString()),
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                if (addresses.size == 1) {
                    extractPhoneCandidate(addresses.single())?.let { phone ->
                        Log.i(TAG, "Google conversation recipient resolved from Bugle participants digits=${phone.count(Char::isDigit)}")
                        return phone
                    }
                }
            }
            null
        } catch (error: Throwable) {
            Log.w(TAG, "Google Bugle conversation lookup failed: ${error.javaClass.simpleName}")
            null
        } finally {
            database.close()
        }
    }

    fun unbind(activity: Activity) {
        if (activityRef?.get() !== activity) return
        val root = rootRef?.get()
        layoutListener?.let { listener ->
            root?.viewTreeObserver?.takeIf(ViewTreeObserver::isAlive)?.removeOnGlobalLayoutListener(listener)
        }
        selectorRef?.get()?.let { selector -> (selector.parent as? ViewGroup)?.removeView(selector) }
        restoreEditorPadding()
        activityRef = null
        rootRef = null
        editorRef = null
        selectorRef = null
        lastSelectorMiMode = null
        layoutListener = null
        currentPhone = null
        lastConversationLookupId = null
        lastConversationLookupPhone = null
    }

    fun onNewIntent(activity: Activity, intent: android.content.Intent, modulePackage: String) {
        activity.intent = intent
        currentPhone = null
        bind(activity, modulePackage)
    }

    fun onPresenceChanged(phoneNumber: String, status: String, capabilities: Long, expiresAt: Long) {
        val key = MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) ?: return
        presences[key] = Presence(status, capabilities, expiresAt)
        handler.post {
            val activity = activityRef?.get() ?: return@post
            val root = rootRef?.get() ?: return@post
            if (currentPhone?.let(MiMessagePresencePolicy::normalizePhoneNumber) == key) {
                updateSelector(activity, root)
            }
        }
    }

    fun shouldInterceptMiMessage(phoneNumber: String): Boolean {
        val key = MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) ?: return false
        return when (modes[key] ?: Mode.AUTO) {
            Mode.SMS -> false
            Mode.MI_MESSAGE -> true
            Mode.AUTO -> canSendMiMessage(phoneNumber)
        }
    }

    fun shouldInterceptCurrentConversation(): Boolean = currentPhone?.let(::shouldInterceptMiMessage) == true

    /** Sender-side fallback for Google builds whose conversation header is rendered by custom Views. */
    fun onRecipientResolvedBySender(phoneNumber: String) {
        val normalized = MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) ?: return
        handler.post {
            val previous = currentPhone
            currentPhone = normalized
            if (previous != normalized) requestPresence(normalized) else requestPresenceIfStale(normalized)
            val activity = activityRef?.get() ?: return@post
            val root = rootRef?.get() ?: return@post
            ensureSelector(activity, root)
            updateSelector(activity, root)
        }
    }

    fun canSendMiMessage(phoneNumber: String): Boolean {
        val key = MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) ?: return false
        val presence = presences[key] ?: return false
        return MiMessagePresencePolicy.supportsSms(
            presence.status,
            presence.capabilities,
            presence.expiresAt,
            System.currentTimeMillis(),
        )
    }

    fun applicationContext(): Context? = activityRef?.get()?.applicationContext

    fun showSendError(message: Int) {
        handler.post {
            activityRef?.get()?.let { Toast.makeText(it, MessagingResources.text(it, message), Toast.LENGTH_LONG).show() }
        }
    }

    private fun refreshComposer(activity: Activity, root: FrameLayout) {
        val editorId = activity.resources.getIdentifier(COMPOSE_EDITOR_ID, "id", activity.packageName)
        if (editorId == 0) return
        val editor = activity.findViewById<EditText>(editorId)
        if (editor == null) return
        // MainActivity can resume before Bugle restores its conversation UI state. Retry after
        // navigation/data binding; Activity.intent may still be the launcher's stale intent.
        if (currentPhone == null) {
            resolvePhoneFromConversationIntent(activity, activity.intent)?.let { recipient ->
                currentPhone = recipient
                requestPresence(recipient)
            }
        }
        if (paddedEditorRef?.get() !== editor) {
            restoreEditorPadding()
            paddedEditorRef = WeakReference(editor)
            originalEditorPadding = EditorPadding(
                editor.paddingStart,
                editor.paddingTop,
                editor.paddingEnd,
                editor.paddingBottom,
            )
        }
        editorRef = WeakReference(editor)
        // Recipient identity comes from the conversation model/intent or the DexKit-resolved
        // sender getter. Visible message text and accessibility labels are not identities.
        currentPhone?.let(::requestPresenceIfStale)
        ensureSelector(activity, root)
        updateSelector(activity, root)
    }

    private fun extractPhoneCandidate(text: String): String? {
        val candidate = PHONE_PATTERN.find(text)?.value?.trim() ?: text
        return MiMessagePresencePolicy.normalizePhoneNumber(candidate)
            ?.takeIf { it.count(Char::isDigit) in 7..20 }
    }

    private fun ensureSelector(activity: Activity, root: FrameLayout) {
        if (selectorRef?.get()?.parent === root) return
        selectorRef?.get()?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
        val selector = TextView(activity).apply {
            textSize = 12f
            setTextColor(0xff777777.toInt())
            includeFontPadding = false
            setPadding(dp(activity, 3), 0, dp(activity, 3), 0)
            minWidth = dp(activity, 48)
            minHeight = dp(activity, 48)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = null
            contentDescription = MessagingResources.text(activity, R.string.mx_compose_switch)
            setOnClickListener { toggleCurrentMode(activity, this) }
        }
        root.addView(selector, FrameLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
        selectorRef = WeakReference(selector)
        lastSelectorMiMode = null
    }

    private fun updateSelector(activity: Activity, root: FrameLayout) {
        val editor = editorRef?.get() ?: return
        val selector = selectorRef?.get() ?: return
        if (paddedEditorRef?.get() !== editor || originalEditorPadding == null) {
            restoreEditorPadding()
            paddedEditorRef = WeakReference(editor)
            originalEditorPadding = EditorPadding(
                editor.paddingStart,
                editor.paddingTop,
                editor.paddingEnd,
                editor.paddingBottom,
            )
        }
        val phone = currentPhone
        if (phone == null) {
            selector.visibility = View.GONE
            restoreEditorPadding(editor)
            return
        }
        selector.visibility = View.VISIBLE
        val key = MiMessagePresencePolicy.normalizePhoneNumber(phone) ?: return
        val available = canSendMiMessage(phone)
        val mode = modes[key] ?: Mode.AUTO
        val usingMiMessage = when (mode) {
            Mode.SMS -> false
            Mode.MI_MESSAGE -> available
            Mode.AUTO -> available
        }
        val label = MessagingResources.text(activity, if (usingMiMessage) R.string.mx_compose_network_label else R.string.mx_compose_sms_label)
        if (selector.text != label) selector.text = label
        if (lastSelectorMiMode != usingMiMessage) {
            val baseColor = editor.currentTextColor
            selector.setTextColor(
                if (usingMiMessage) {
                    resolveAccentColor(activity)
                } else {
                    Color.argb(190, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                },
            )
            selector.contentDescription = if (usingMiMessage) {
                MessagingResources.text(activity, R.string.mx_compose_switch_sms)
            } else {
                MessagingResources.text(activity, R.string.mx_compose_switch_network)
            }
            lastSelectorMiMode = usingMiMessage
        }
        val editorLocation = IntArray(2)
        val rootLocation = IntArray(2)
        editor.getLocationOnScreen(editorLocation)
        root.getLocationOnScreen(rootLocation)
        val originalPadding = originalEditorPadding ?: EditorPadding(
            editor.paddingStart,
            editor.paddingTop,
            editor.paddingEnd,
            editor.paddingBottom,
        )
        selector.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val selectorWidth = maxOf(selector.measuredWidth, dp(activity, 48))
        val selectorHeight = maxOf(editor.height, dp(activity, 48))
        val desiredPaddingStart = originalPadding.start + selectorWidth + dp(activity, 3)
        if (editor.paddingStart != desiredPaddingStart ||
            editor.paddingTop != originalPadding.top ||
            editor.paddingEnd != originalPadding.end ||
            editor.paddingBottom != originalPadding.bottom
        ) {
            editor.setPaddingRelative(
                desiredPaddingStart,
                originalPadding.top,
                originalPadding.end,
                originalPadding.bottom,
            )
        }
        val params = selector.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(selectorWidth, selectorHeight)
        val leftMargin = editorLocation[0] - rootLocation[0] + originalPadding.start
        val topMargin = editorLocation[1] - rootLocation[1]
        if (params.width != selectorWidth || params.height != selectorHeight ||
            params.leftMargin != leftMargin || params.topMargin != topMargin ||
            params.gravity != (Gravity.TOP or Gravity.START)
        ) {
            params.gravity = Gravity.TOP or Gravity.START
            params.width = selectorWidth
            params.height = selectorHeight
            params.leftMargin = leftMargin
            params.topMargin = topMargin
            selector.layoutParams = params
        }
        selector.bringToFront()
    }

    private fun restoreEditorPadding(editor: EditText? = paddedEditorRef?.get()) {
        val original = originalEditorPadding ?: return
        if (editor != null && paddedEditorRef?.get() === editor) {
            if (editor.paddingStart != original.start || editor.paddingTop != original.top ||
                editor.paddingEnd != original.end || editor.paddingBottom != original.bottom
            ) {
                editor.setPaddingRelative(original.start, original.top, original.end, original.bottom)
            }
        }
        if (editor == null || paddedEditorRef?.get() === editor) {
            paddedEditorRef = null
            originalEditorPadding = null
        }
    }

    private fun toggleCurrentMode(activity: Activity, selector: TextView) {
        val phone = currentPhone ?: return
        val key = MiMessagePresencePolicy.normalizePhoneNumber(phone) ?: return
        val current = modes[key] ?: Mode.AUTO
        val canSend = canSendMiMessage(phone)
        if (current == Mode.SMS) {
            if (!canSend) {
                Toast.makeText(activity, MessagingResources.text(activity, R.string.mx_send_recipient_unavailable), Toast.LENGTH_SHORT).show()
                return
            }
            modes[key] = Mode.MI_MESSAGE
        } else {
            modes[key] = Mode.SMS
        }
        updateSelector(activity, rootRef?.get() ?: return)
    }

    private fun requestPresence(phone: String) {
        val key = MiMessagePresencePolicy.normalizePhoneNumber(phone) ?: return
        lastRequests[key] = RequestStamp(System.currentTimeMillis())
        val context = activityRef?.get() ?: return
        val modulePackage = modulePackageName ?: return
        MessagingHostBridge.requestPresence(context, modulePackage, phone)
    }

    private fun requestPresenceIfStale(phone: String) {
        val key = MiMessagePresencePolicy.normalizePhoneNumber(phone) ?: return
        val last = lastRequests[key]?.requestedAt ?: 0L
        if (System.currentTimeMillis() - last >= PRESENCE_REFRESH_MS) requestPresence(phone)
    }

    private fun resolveAccentColor(context: Context): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.colorAccent, value, true)) {
            if (value.resourceId != 0) runCatching { context.getColor(value.resourceId) }.getOrDefault(value.data) else value.data
        } else {
            0xff3f51b5.toInt()
        }
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private const val TAG = "GoogleMsgPresence"
    private const val COMPOSE_EDITOR_ID = "compose_message_text"
    private const val PRESENCE_REFRESH_MS = 30_000L
    private const val BUGLE_DATABASE_NAME = "bugle_db"
    private const val CONVERSATION_ID_CLASS = "com.google.android.apps.messaging.shared.api.messaging.conversation.ConversationId"
    private const val BUGLE_CONVERSATION_KIND = "BUGLE"
    private val CONVERSATIONS_URI = android.net.Uri.parse("content://mms-sms/conversations?simple=true")
    private val CANONICAL_ADDRESSES_URI = android.net.Uri.parse("content://mms-sms/canonical-addresses")
    private val RECIPIENT_ID_SEPARATOR = Regex("[^0-9]+")
    private val PHONE_PATTERN = Regex("(?<![0-9])\\+?[0-9](?:[0-9\\s().-]{1,24}[0-9])?(?![0-9])")
}
