package io.github.xiaotong6666.maihoku.features.mimessage.host.aosp

import android.content.ContentValues
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Resolves and invokes AOSP Messaging's own receive action inside the Messaging UID. */
internal class AospMessagingReceiveActionAdapter private constructor(
    private val classLoader: ClassLoader,
    private val actionConstructor: Constructor<*>,
    private val actionKeyField: Field,
    private val startAction: Method,
    private val monitorConstructor: Constructor<*>,
    private val setCompletedListener: Method,
    private val listenerClass: Class<*>,
    private val resolution: String,
) {
    fun start(values: ContentValues, onSucceeded: () -> Unit, onFailed: () -> Unit) {
        val action = actionConstructor.newInstance(values)
        val actionKey = actionKeyField.get(action) as? String
            ?: throw IllegalStateException("AOSP receive action has no readable action key")
        val monitor = monitorConstructor.newInstance(ACTION_STATE_CREATED, actionKey, null)
        val listener = Proxy.newProxyInstance(classLoader, arrayOf(listenerClass)) { proxy, method, args ->
            when (method.name) {
                "onActionSucceeded" -> {
                    onSucceeded()
                    null
                }

                "onActionFailed" -> {
                    onFailed()
                    null
                }

                "toString" -> "MaiHokuAospReceiveSmsActionListener"

                "hashCode" -> System.identityHashCode(proxy)

                "equals" -> proxy === args?.firstOrNull()

                else -> null
            }
        }
        setCompletedListener.invoke(monitor, listener)
        startAction.invoke(action, monitor)
    }

    companion object {
        private const val TAG = "AospMsg"
        private const val ACTION_STATE_CREATED = 1
        private const val NAMED_RECEIVE_ACTION =
            "com.android.messaging.datamodel.action.ReceiveSmsMessageAction"
        private const val NAMED_ACTION_BASE = "com.android.messaging.datamodel.action.Action"
        private const val NAMED_ACTION_MONITOR = "com.android.messaging.datamodel.action.ActionMonitor"
        private const val NAMED_ACTION_LISTENER =
            "com.android.messaging.datamodel.action.ActionMonitor\$ActionCompletedListener"
        private const val RECEIVE_ACTION_FINGERPRINT =
            "Received an SMS without an address; using unknown sender."
        private const val MONITOR_FINGERPRINT = "Monitor key "

        fun resolve(runtime: HookRuntime): AospMessagingReceiveActionAdapter? {
            val named = runCatching {
                create(
                    classLoader = runtime.classLoader,
                    actionClass = runtime.classLoader.loadClass(NAMED_RECEIVE_ACTION),
                    actionBaseClass = runtime.classLoader.loadClass(NAMED_ACTION_BASE),
                    monitorClass = runtime.classLoader.loadClass(NAMED_ACTION_MONITOR),
                    listenerClass = runtime.classLoader.loadClass(NAMED_ACTION_LISTENER),
                    resolution = "stable class names",
                )
            }.getOrNull()
            if (named != null) {
                Log.i(TAG, "Resolved native SMS receive adapter using stable, non-R8 AOSP class names")
                return named
            }

            Log.w(TAG, "Stable AOSP receive-action lookup failed; retrying with DexKit fingerprints")
            return runCatching {
                runtime.dexKit.useBridge { bridge -> resolveWithDexKit(bridge, runtime.classLoader) }
            }.onFailure {
                Log.e(TAG, "DexKit fallback for AOSP native SMS receive action failed", it)
            }.getOrNull()?.also {
                Log.i(TAG, "Resolved native SMS receive adapter with DexKit fallback")
            }
        }

        private fun resolveWithDexKit(
            bridge: DexKitBridge,
            classLoader: ClassLoader,
        ): AospMessagingReceiveActionAdapter? {
            val actionCandidates = bridge.findClass {
                matcher {
                    methods {
                        add {
                            returnType = "java.lang.Object"
                            usingStrings(RECEIVE_ACTION_FINGERPRINT)
                        }
                    }
                }
            }.mapNotNull { data -> runCatching { data.getInstance(classLoader) }.getOrNull() }
                .filter { candidate ->
                    candidate.constructors.any {
                        it.parameterTypes.contentEquals(arrayOf(ContentValues::class.java))
                    } && candidate.declaredMethods.any {
                        it.returnType == Any::class.java && it.parameterCount == 0
                    }
                }.distinct()

            if (actionCandidates.size != 1) {
                Log.e(TAG, "DexKit expected one SMS receive action, found ${actionCandidates.size}")
                return null
            }

            val monitorCandidates = bridge.findClass {
                matcher {
                    methods {
                        add {
                            usingStrings(MONITOR_FINGERPRINT)
                        }
                    }
                }
            }.mapNotNull { data -> runCatching { data.getInstance(classLoader) }.getOrNull() }
                .filter { candidate ->
                    candidate.constructors.any { constructor ->
                        constructor.parameterTypes.contentEquals(
                            arrayOf(Int::class.javaPrimitiveType, String::class.java, Any::class.java),
                        )
                    } && candidate.methods.any { method ->
                        method.name == "setCompletedListener" &&
                            method.returnType == Void.TYPE &&
                            method.parameterCount == 1 && method.parameterTypes[0].isInterface
                    }
                }.distinct()

            if (monitorCandidates.size != 1) {
                Log.e(TAG, "DexKit expected one ActionMonitor implementation, found ${monitorCandidates.size}")
                return null
            }

            val actionClass = actionCandidates.single()
            val monitorClass = monitorCandidates.single()
            val actionBaseClass = actionClass.superclass
                ?: run {
                    Log.e(TAG, "DexKit receive action has no superclass")
                    return null
                }
            val listenerClass = monitorClass.methods.singleOrNull { method ->
                method.name == "setCompletedListener" && method.parameterCount == 1
            }?.parameterTypes?.singleOrNull()
                ?: run {
                    Log.e(TAG, "DexKit ActionMonitor listener interface is ambiguous")
                    return null
                }

            return runCatching {
                create(
                    classLoader = classLoader,
                    actionClass = actionClass,
                    actionBaseClass = actionBaseClass,
                    monitorClass = monitorClass,
                    listenerClass = listenerClass,
                    resolution = "DexKit",
                )
            }.onFailure {
                Log.e(TAG, "DexKit candidates failed runtime signature verification", it)
            }.getOrNull()
        }

        private fun create(
            classLoader: ClassLoader,
            actionClass: Class<*>,
            actionBaseClass: Class<*>,
            monitorClass: Class<*>,
            listenerClass: Class<*>,
            resolution: String,
        ): AospMessagingReceiveActionAdapter {
            val actionConstructor = actionClass.getConstructor(ContentValues::class.java)
                .apply { isAccessible = true }
            val monitorConstructor = monitorClass.getConstructor(
                Int::class.javaPrimitiveType,
                String::class.java,
                Any::class.java,
            ).apply { isAccessible = true }
            val startAction = actionBaseClass.getMethod("start", monitorClass).apply { isAccessible = true }
            val setCompletedListener = monitorClass.getMethod("setCompletedListener", listenerClass)
                .apply { isAccessible = true }
            check(listenerClass.isInterface) { "ActionCompletedListener is not an interface" }
            check(listenerClass.methods.any { it.name == "onActionSucceeded" && it.parameterCount == 4 }) {
                "ActionCompletedListener has no success callback"
            }
            check(listenerClass.methods.any { it.name == "onActionFailed" && it.parameterCount == 4 }) {
                "ActionCompletedListener has no failure callback"
            }
            val actionKeyField: Field = generateSequence(actionClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .single { it.name == "actionKey" && it.type == String::class.java }
                .apply { isAccessible = true }

            return AospMessagingReceiveActionAdapter(
                classLoader,
                actionConstructor,
                actionKeyField,
                startAction,
                monitorConstructor,
                setCompletedListener,
                listenerClass,
                resolution,
            )
        }
    }
}
