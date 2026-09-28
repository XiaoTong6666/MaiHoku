package io.github.xiaotong6666.maihoku.hook.telegram

import android.os.SystemClock
import android.util.Log
import io.github.xiaotong6666.maihoku.translation.GoogleTranslationBackend
import io.github.xiaotong6666.maihoku.translation.TranslationBackend
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class TranslationSymbols(
    val featureAvailable: Method,
    val dialogFeatureAvailable: Method,
    val chatTranslateEnabled: Method,
    val contextTranslateEnabled: Method,
    val pushToTranslate: Method,
    val messageGetId: Method,
    val messageGetDialogId: Method,
    val messageOwner: Field,
    val ownerMessage: Field,
    val sendRequest: Method,
    val cancelRequest: Method,
    val translateRequestClass: Class<*>,
    val requestPeer: Field,
    val requestIds: Field,
    val requestTexts: Field,
    val requestTargetLanguage: Field,
    val inputPeerClass: Class<*>,
    val getPeerDialogId: Method,
    val textWithEntitiesConstructor: Constructor<*>,
    val textValue: Field,
    val textEntities: Field,
    val translateResultConstructor: Constructor<*>,
    val translateResultValues: Field,
    val errorConstructor: Constructor<*>,
    val errorCode: Field,
    val errorText: Field,
    val requestDelegateRun: Method,
)

internal object TranslationFeature : TelegramFeature<TranslationSymbols>() {
    override val id: String = "telegram.translation"

    private const val SOURCE_TTL_MS = 2 * 60 * 1000L
    private const val MAX_PARALLEL_TRANSLATIONS = 6
    private const val FAKE_REQUEST_TOKEN_START = -0x4d480000

    private val backend: TranslationBackend = GoogleTranslationBackend
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "MaiHoku-Translation").apply {
            isDaemon = true
        }
    }
    private val networkSlots = Semaphore(MAX_PARALLEL_TRANSLATIONS)
    private val nextRequestToken = AtomicInteger(FAKE_REQUEST_TOKEN_START)
    private val sourceTexts = ConcurrentHashMap<MessageKey, CapturedSource>()
    private val jobs = ConcurrentHashMap<Int, TranslationJob>()

    override fun isEnabled(runtime: TelegramRuntime): Boolean = runtime.config.translationEnabled

    override fun resolve(runtime: TelegramRuntime): TranslationSymbols {
        val classLoader = runtime.classLoader
        val translateControllerClass = Class.forName(
            "org.telegram.messenger.TranslateController",
            false,
            classLoader,
        )
        val messageObjectClass = Class.forName(
            "org.telegram.messenger.MessageObject",
            false,
            classLoader,
        )
        val messageClass = Class.forName(
            "org.telegram.tgnet.TLRPC\$Message",
            false,
            classLoader,
        )
        val tlObjectClass = Class.forName(
            "org.telegram.tgnet.TLObject",
            false,
            classLoader,
        )
        val connectionsManagerClass = Class.forName(
            "org.telegram.tgnet.ConnectionsManager",
            false,
            classLoader,
        )
        val requestDelegateClass = Class.forName(
            "org.telegram.tgnet.RequestDelegate",
            false,
            classLoader,
        )
        val inputPeerClass = Class.forName(
            "org.telegram.tgnet.TLRPC\$InputPeer",
            false,
            classLoader,
        )

        data class CapabilityMethods(
            val featureAvailable: Method,
            val dialogFeatureAvailable: Method,
            val chatTranslateEnabled: Method,
            val contextTranslateEnabled: Method,
        )

        val capabilityMethods = runtime.dexKit.useBridge { bridge ->
            val controller = bridge.getClassData(translateControllerClass)
                ?: error("Unable to inspect Telegram TranslateController")

            val chatCandidates = controller.methods.filter { method ->
                method.returnTypeName == Boolean::class.javaPrimitiveType!!.name &&
                    method.paramTypeNames.isEmpty() &&
                    "translate_chat_button" in method.usingStrings
            }
            check(chatCandidates.size == 1) {
                "Expected one chat translation capability method, found " + chatCandidates.size
            }
            val chat = chatCandidates.single()

            val contextCandidates = controller.methods.filter { method ->
                method.returnTypeName == Boolean::class.javaPrimitiveType!!.name &&
                    method.paramTypeNames.isEmpty() &&
                    "translate_button" in method.usingStrings
            }
            check(contextCandidates.size == 1) {
                "Expected one context translation capability method, found " + contextCandidates.size
            }
            val context = contextCandidates.single()

            val featureCandidates = chat.callers.filter { caller ->
                caller.declaredClassName == controller.name &&
                    caller.returnTypeName == Boolean::class.javaPrimitiveType!!.name &&
                    caller.paramTypeNames.isEmpty()
            }.distinctBy { it.methodSign }
            check(featureCandidates.size == 1) {
                "Expected one translation feature gate, found " + featureCandidates.size
            }

            val dialogFeatureCandidates = chat.callers.filter { caller ->
                caller.declaredClassName == controller.name &&
                    caller.returnTypeName == Boolean::class.javaPrimitiveType!!.name &&
                    caller.paramTypeNames == listOf(Long::class.javaPrimitiveType!!.name)
            }.distinctBy { it.methodSign }
            check(dialogFeatureCandidates.size == 1) {
                "Expected one dialog translation feature gate, found " +
                    dialogFeatureCandidates.size
            }

            CapabilityMethods(
                featureAvailable = featureCandidates.single().getMethodInstance(classLoader),
                dialogFeatureAvailable = dialogFeatureCandidates.single()
                    .getMethodInstance(classLoader),
                chatTranslateEnabled = chat.getMethodInstance(classLoader),
                contextTranslateEnabled = context.getMethodInstance(classLoader),
            )
        }

        val pushCandidates = translateControllerClass.declaredMethods.filter { method ->
            val params = method.parameterTypes
            method.returnType == Void.TYPE &&
                params.size == 3 &&
                params[0] == messageObjectClass &&
                params[1] == String::class.java &&
                params[2].isInterface &&
                params[2].declaredMethods.any { callback ->
                    callback.returnType == Void.TYPE && callback.parameterCount == 4
                }
        }
        check(pushCandidates.size == 1) {
            "Expected one TranslateController message translation enqueue method, found " +
                pushCandidates.size
        }

        val messageGetId = messageObjectClass.getMethod("getId").apply {
            isAccessible = true
        }
        val messageGetDialogId = messageObjectClass.getMethod("getDialogId").apply {
            isAccessible = true
        }
        val messageOwner = messageObjectClass.fields.single { field ->
            field.type == messageClass
        }.apply {
            isAccessible = true
        }
        val ownerMessage = messageClass.getField("message").apply {
            check(type == String::class.java)
            isAccessible = true
        }

        val sendCandidates = connectionsManagerClass.declaredMethods.filter { method ->
            val params = method.parameterTypes
            method.returnType == Int::class.javaPrimitiveType &&
                params.size == 9 &&
                params[0] == tlObjectClass &&
                params[1] == requestDelegateClass
        }
        check(sendCandidates.size == 1) {
            "Expected one terminal ConnectionsManager.sendRequest overload, found " +
                sendCandidates.size
        }

        val cancelCandidates = connectionsManagerClass.declaredMethods.filter { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Runnable::class.java,
                    ),
                )
        }
        check(cancelCandidates.size == 1) {
            "Expected one terminal ConnectionsManager.cancelRequest overload, found " +
                cancelCandidates.size
        }

        val translateRequestClass = Class.forName(
            "org.telegram.tgnet.TLRPC\$TL_messages_translateText",
            false,
            classLoader,
        )
        val requestPeer = translateRequestClass.getField("peer").apply { isAccessible = true }
        val requestIds = translateRequestClass.getField("id").apply { isAccessible = true }
        val requestTexts = translateRequestClass.getField("text").apply { isAccessible = true }
        val requestTargetLanguage = translateRequestClass.getField("to_lang").apply {
            check(type == String::class.java)
            isAccessible = true
        }

        val dialogObjectClass = Class.forName(
            "org.telegram.messenger.DialogObject",
            false,
            classLoader,
        )
        val getPeerDialogId = dialogObjectClass.declaredMethods.single { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnType == Long::class.javaPrimitiveType &&
                method.parameterTypes.contentEquals(arrayOf(inputPeerClass))
        }.apply {
            isAccessible = true
        }

        val textWithEntitiesClass = Class.forName(
            "org.telegram.tgnet.TLRPC\$TL_textWithEntities",
            false,
            classLoader,
        )
        val textWithEntitiesConstructor = textWithEntitiesClass.getDeclaredConstructor().apply {
            isAccessible = true
        }
        val textValue = textWithEntitiesClass.getField("text").apply {
            check(type == String::class.java)
            isAccessible = true
        }
        val textEntities = textWithEntitiesClass.getField("entities").apply {
            check(ArrayList::class.java.isAssignableFrom(type))
            isAccessible = true
        }

        val translateResultClass = Class.forName(
            "org.telegram.tgnet.TLRPC\$TL_messages_translateResult",
            false,
            classLoader,
        )
        val translateResultConstructor = translateResultClass.getDeclaredConstructor().apply {
            isAccessible = true
        }
        val translateResultValues = translateResultClass.getField("result").apply {
            check(ArrayList::class.java.isAssignableFrom(type))
            isAccessible = true
        }

        val errorClass = Class.forName(
            "org.telegram.tgnet.TLRPC\$TL_error",
            false,
            classLoader,
        )
        val errorConstructor = errorClass.getDeclaredConstructor().apply {
            isAccessible = true
        }
        val errorCode = errorClass.getField("code").apply {
            check(type == Int::class.javaPrimitiveType)
            isAccessible = true
        }
        val errorText = errorClass.getField("text").apply {
            check(type == String::class.java)
            isAccessible = true
        }

        val requestDelegateRun = requestDelegateClass.declaredMethods.single { method ->
            method.returnType == Void.TYPE && method.parameterCount == 2
        }.apply {
            isAccessible = true
        }

        return TranslationSymbols(
            featureAvailable = capabilityMethods.featureAvailable.apply { isAccessible = true },
            dialogFeatureAvailable = capabilityMethods.dialogFeatureAvailable.apply {
                isAccessible = true
            },
            chatTranslateEnabled = capabilityMethods.chatTranslateEnabled.apply { isAccessible = true },
            contextTranslateEnabled = capabilityMethods.contextTranslateEnabled.apply { isAccessible = true },
            pushToTranslate = pushCandidates.single().apply { isAccessible = true },
            messageGetId = messageGetId,
            messageGetDialogId = messageGetDialogId,
            messageOwner = messageOwner,
            ownerMessage = ownerMessage,
            sendRequest = sendCandidates.single().apply { isAccessible = true },
            cancelRequest = cancelCandidates.single().apply { isAccessible = true },
            translateRequestClass = translateRequestClass,
            requestPeer = requestPeer,
            requestIds = requestIds,
            requestTexts = requestTexts,
            requestTargetLanguage = requestTargetLanguage,
            inputPeerClass = inputPeerClass,
            getPeerDialogId = getPeerDialogId,
            textWithEntitiesConstructor = textWithEntitiesConstructor,
            textValue = textValue,
            textEntities = textEntities,
            translateResultConstructor = translateResultConstructor,
            translateResultValues = translateResultValues,
            errorConstructor = errorConstructor,
            errorCode = errorCode,
            errorText = errorText,
            requestDelegateRun = requestDelegateRun,
        )
    }

    override fun install(runtime: TelegramRuntime, resolution: TranslationSymbols) {
        val hookIds = ArrayList<String>()
        try {
            listOf(
                "feature" to resolution.featureAvailable,
                "dialog_feature" to resolution.dialogFeatureAvailable,
                "chat" to resolution.chatTranslateEnabled,
                "context" to resolution.contextTranslateEnabled,
            ).forEach { (suffix, method) ->
                val hookId = id + ".capability." + suffix
                runtime.hooks.around(method, hookId) {
                    true
                }
                hookIds += hookId
            }

            val captureHookId = id + ".capture"
            runtime.hooks.method(resolution.pushToTranslate, captureHookId) {
                before {
                    val messageObject = arg(0) ?: return@before
                    runCatching {
                        captureSource(resolution, messageObject)
                    }.onFailure { error ->
                        Log.w(
                            TelegramRuntime.TAG,
                            id + " failed to capture source message",
                            error,
                        )
                    }
                }
            }
            hookIds += captureHookId

            val requestHookId = id + ".request"
            runtime.hooks.around(resolution.sendRequest, requestHookId) {
                val request = arg(0)
                val delegate = arg(1)
                if (
                    request == null ||
                    delegate == null ||
                    !resolution.translateRequestClass.isInstance(request)
                ) {
                    return@around proceed()
                }

                val targetLanguage = resolution.requestTargetLanguage.get(request) as? String
                if (targetLanguage.isNullOrBlank()) {
                    return@around dispatchRejectedRequest(
                        resolution,
                        delegate,
                        IllegalArgumentException("Translation request has no target language"),
                    )
                }

                val source = runCatching {
                    extractRequestSource(resolution, request)
                }.getOrElse { error ->
                    Log.w(
                        TelegramRuntime.TAG,
                        id + " unable to resolve translation request source",
                        error,
                    )
                    return@around dispatchRejectedRequest(
                        resolution,
                        delegate,
                        error,
                    )
                }

                val token = nextRequestToken.getAndDecrement()
                val job = TranslationJob()
                jobs[token] = job
                dispatchTranslation(
                    symbols = resolution,
                    token = token,
                    job = job,
                    delegate = delegate,
                    source = source,
                    targetLanguage = targetLanguage,
                )
                Log.i(
                    TelegramRuntime.TAG,
                    id + " routed " + source.texts.size +
                        " text(s) to Google target=" + targetLanguage +
                        " token=" + token,
                )
                token
            }
            hookIds += requestHookId

            val cancelHookId = id + ".cancel"
            runtime.hooks.around(resolution.cancelRequest, cancelHookId) {
                val token = arg(0) as? Int ?: return@around proceed()
                if (token <= FAKE_REQUEST_TOKEN_START) {
                    jobs.remove(token)?.cancel()
                    (arg(2) as? Runnable)?.run()
                    null
                } else {
                    proceed()
                }
            }
            hookIds += cancelHookId

            Log.i(
                TelegramRuntime.TAG,
                id + " installed gate=" + resolution.featureAvailable.name +
                    " request=" + resolution.sendRequest.name +
                    " backend=nekogram-google",
            )
        } catch (t: Throwable) {
            hookIds.asReversed().forEach(runtime.hooks::unhook)
            throw t
        }
    }

    private fun captureSource(
        symbols: TranslationSymbols,
        messageObject: Any,
    ) {
        pruneSources()
        val messageId = (symbols.messageGetId.invoke(messageObject) as Number).toInt()
        val dialogId = (symbols.messageGetDialogId.invoke(messageObject) as Number).toLong()
        val owner = symbols.messageOwner.get(messageObject) ?: return
        val text = symbols.ownerMessage.get(owner) as? String ?: return
        if (text.isEmpty()) return

        sourceTexts[MessageKey(dialogId, messageId)] = CapturedSource(
            text = text,
            capturedAt = SystemClock.elapsedRealtime(),
        )
    }

    private fun extractRequestSource(
        symbols: TranslationSymbols,
        request: Any,
    ): RequestSource {
        @Suppress("UNCHECKED_CAST")
        val textObjects = symbols.requestTexts.get(request) as? List<Any?>
        if (!textObjects.isNullOrEmpty()) {
            val texts = textObjects.map { textObject ->
                checkNotNull(textObject) { "Translation text entry is null" }
                symbols.textValue.get(textObject) as? String
                    ?: error("Translation text entry has no text")
            }
            return RequestSource(texts = texts)
        }

        val peer = symbols.requestPeer.get(request)
            ?: error("ID translation request has no peer")
        check(symbols.inputPeerClass.isInstance(peer)) {
            "Unexpected translation peer type " + peer.javaClass.name
        }
        val dialogId = (symbols.getPeerDialogId.invoke(null, peer) as Number).toLong()

        @Suppress("UNCHECKED_CAST")
        val ids = symbols.requestIds.get(request) as? List<Int>
            ?: error("ID translation request has no ids")
        check(ids.isNotEmpty()) { "ID translation request is empty" }

        pruneSources()
        val keys = ids.map { MessageKey(dialogId, it) }
        val captured = keys.map { key ->
            sourceTexts[key] ?: error(
                "Missing captured translation source dialog=" + key.dialogId +
                    " message=" + key.messageId,
            )
        }
        keys.forEach(sourceTexts::remove)
        return RequestSource(texts = captured.map(CapturedSource::text))
    }

    private fun dispatchTranslation(
        symbols: TranslationSymbols,
        token: Int,
        job: TranslationJob,
        delegate: Any,
        source: RequestSource,
        targetLanguage: String,
    ) {
        val translations = source.texts.map { text ->
            executor.submit(
                Callable {
                    if (job.cancelled.get()) {
                        throw InterruptedException("Translation request cancelled")
                    }
                    networkSlots.acquire()
                    try {
                        backend.translate(text, targetLanguage).text
                    } finally {
                        networkSlots.release()
                    }
                },
            ).also(job.futures::add)
        }

        val coordinator = executor.submit {
            try {
                val translated = translations.map(Future<String>::get)
                if (!job.cancelled.get()) {
                    val result = createTranslateResult(symbols, translated)
                    symbols.requestDelegateRun.invoke(delegate, result, null)
                }
            } catch (t: Throwable) {
                if (!job.cancelled.get()) {
                    val cause = unwrap(t)
                    Log.w(
                        TelegramRuntime.TAG,
                        id + " Google translation failed token=" + token,
                        cause,
                    )
                    val error = createTranslateError(symbols, cause)
                    runCatching {
                        symbols.requestDelegateRun.invoke(delegate, null, error)
                    }.onFailure { callbackError ->
                        Log.w(
                            TelegramRuntime.TAG,
                            id + " failed to dispatch translation error token=" + token,
                            callbackError,
                        )
                    }
                }
            } finally {
                jobs.remove(token, job)
            }
        }
        job.futures += coordinator
    }

    private fun dispatchRejectedRequest(
        symbols: TranslationSymbols,
        delegate: Any,
        throwable: Throwable,
    ): Int {
        val token = nextRequestToken.getAndDecrement()
        val job = TranslationJob()
        jobs[token] = job
        val future = executor.submit {
            try {
                if (!job.cancelled.get()) {
                    symbols.requestDelegateRun.invoke(
                        delegate,
                        null,
                        createTranslateError(symbols, throwable),
                    )
                }
            } finally {
                jobs.remove(token, job)
            }
        }
        job.futures += future
        return token
    }

    private fun createTranslateResult(
        symbols: TranslationSymbols,
        translated: List<String>,
    ): Any {
        val result = symbols.translateResultConstructor.newInstance()

        @Suppress("UNCHECKED_CAST")
        val values = symbols.translateResultValues.get(result) as MutableList<Any>
        for (text in translated) {
            val translatedText = symbols.textWithEntitiesConstructor.newInstance()
            symbols.textValue.set(translatedText, text)
            @Suppress("UNCHECKED_CAST")
            (symbols.textEntities.get(translatedText) as? MutableList<Any>)?.clear()
            values += translatedText
        }
        return result
    }

    private fun createTranslateError(
        symbols: TranslationSymbols,
        throwable: Throwable,
    ): Any {
        val error = symbols.errorConstructor.newInstance()
        val rateLimited = throwable.javaClass.name.endsWith("Http429Exception")
        symbols.errorCode.setInt(error, if (rateLimited) 429 else 500)
        symbols.errorText.set(
            error,
            if (rateLimited) "QUOTA_EXCEEDED" else "TRANSLATION_FAILED",
        )
        return error
    }

    private fun pruneSources() {
        val cutoff = SystemClock.elapsedRealtime() - SOURCE_TTL_MS
        sourceTexts.entries.removeIf { it.value.capturedAt < cutoff }
    }

    private fun unwrap(throwable: Throwable): Throwable {
        var current = throwable
        while (
            current.cause != null &&
            (
                current is java.util.concurrent.ExecutionException ||
                    current is java.lang.reflect.InvocationTargetException
                )
        ) {
            current = current.cause!!
        }
        return current
    }

    private data class MessageKey(
        val dialogId: Long,
        val messageId: Int,
    )

    private data class CapturedSource(
        val text: String,
        val capturedAt: Long,
    )

    private data class RequestSource(
        val texts: List<String>,
    )

    private class TranslationJob {
        val cancelled = AtomicBoolean(false)
        val futures = CopyOnWriteArrayList<Future<*>>()

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            futures.forEach { future ->
                future.cancel(true)
            }
        }
    }
}
