package io.github.xiaotong6666.maihoku.features.mimessage.host.google

import android.app.Activity
import android.os.Bundle
import android.view.MenuItem
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.mimessage.host.common.MessagingHostApplicationHook
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Resolves Google Messages' settings components from behaviour, not R8-generated class names. */
object GoogleMessagesHook : BaseHook() {
    override val name: String = "GoogleMessagesXiaomiNetworkMessages"

    private data class ResolvedTargets(
        val settingsClass: Class<*>,
        val createPreferences: Method,
        val inflatePreferences: Method,
        val organizationActivity: Class<*>,
        val organizationCreatePreferences: Method,
        val organizationActivityResume: Method,
        val organizationActivityPause: Method,
        val organizationNavigateUp: Method,
    )

    override fun init(runtime: HookRuntime) {
        val smsDeliverAdapter = GoogleMessagesSmsDeliverAdapter.resolve(runtime)
        val sendAdapter = GoogleMessagesSendAdapter.resolve(
            runtime,
            runtime.module.moduleApplicationInfo.packageName,
        )
        if (smsDeliverAdapter == null) {
            Log.e(TAG, "Google Messages SMS_DELIVER processor could not be resolved; native inbox import is unavailable")
        }
        if (sendAdapter == null) {
            Log.e(TAG, "Google Messages DexKit SMS sender could not be resolved; native carrier SMS remains unchanged")
        }
        MessagingHostApplicationHook.install(
            runtime = runtime,
            hookPrefix = "google-messages",
            modulePackageName = runtime.module.moduleApplicationInfo.packageName,
            googleMessagesSmsDeliverAdapter = smsDeliverAdapter,
            googleMessagesSendAdapter = sendAdapter,
        )
        if (sendAdapter != null) {
            installConversationPresenceUi(runtime)
        } else {
            Log.w(TAG, "Google Messages Mi Message selector is disabled because its transport hook is unavailable")
        }
        val targets = resolveTargets(runtime) ?: run {
            Log.e(TAG, "Could not resolve Google Messages settings components; Xiaomi settings were not installed")
            return
        }
        val preferenceSymbols = runCatching { GoogleMessagesPreferenceSymbols.resolve(runtime) }
            .getOrElse {
                Log.e(TAG, "Could not resolve bundled Preference behavior via DexKit", it)
                return
            }
        GoogleMessagesPreferences.initialize(preferenceSymbols)

        runtime.hooks.method(targets.createPreferences, "google-messages.xiaomi-settings.preferences") {
            after {
                val fragment = thisObject ?: return@after
                runCatching {
                    GoogleMessagesPreferences.installSingleEntry(
                        fragment,
                        runtime.classLoader,
                        targets.inflatePreferences,
                        targets.organizationActivity,
                        runtime.module.moduleApplicationInfo,
                    )
                }.onFailure {
                    Log.e(TAG, "Unable to install Xiaomi network-message preferences", it)
                }
            }
        }

        // The full-screen host is an existing Google Messages settings Activity. Its fragment
        // builds the native Preference hierarchy first; replace that hierarchy only for our
        // explicit launch, so the app's own toolbar, activity transition and back stack remain.
        runtime.hooks.method(
            targets.organizationCreatePreferences,
            "google-messages.xiaomi-settings.fullscreen-preferences",
        ) {
            after {
                val fragment = thisObject ?: return@after
                if (!GoogleMessagesPreferences.isConfigurationLaunch(fragment)) return@after
                runCatching {
                    GoogleMessagesPreferences.installConfigurationPage(
                        fragment,
                        runtime.classLoader,
                        targets.inflatePreferences,
                        runtime.module.moduleApplicationInfo,
                    )
                }.onFailure {
                    Log.e(TAG, "Unable to install the full-screen Xiaomi SMS settings page", it)
                }
            }
        }

        runtime.hooks.method(
            targets.organizationActivityResume,
            "google-messages.xiaomi-settings.refresh-on-resume",
        ) {
            after {
                val activity = thisObject as? android.app.Activity ?: return@after
                if (!targets.organizationActivity.isInstance(activity)) return@after
                runCatching {
                    GoogleMessagesPreferences.refreshConfigurationActivity(
                        activity,
                        runtime.classLoader,
                        runtime.module.moduleApplicationInfo,
                    )
                }.onFailure {
                    Log.w(TAG, "Could not refresh Xiaomi SIM status when returning to Google Messages", it)
                }
            }
        }

        runtime.hooks.method(
            targets.organizationActivityPause,
            "google-messages.xiaomi-settings.stop-runtime-refresh-on-pause",
        ) {
            after {
                val activity = thisObject as? android.app.Activity ?: return@after
                if (!targets.organizationActivity.isInstance(activity)) return@after
                GoogleMessagesPreferences.stopRuntimeRefresh(activity)
            }
        }

        runtime.hooks.around(
            targets.organizationNavigateUp,
            "google-messages.xiaomi-settings.back-to-parent",
        ) {
            val activity = thisObject as? android.app.Activity
            val menuItem = arg(0) as? MenuItem
            if (activity != null &&
                menuItem?.itemId == android.R.id.home &&
                activity.intent?.getBooleanExtra(GoogleMessagesPreferences.CONFIG_PAGE_INTENT_EXTRA, false) == true
            ) {
                activity.finish()
                true
            } else {
                proceed()
            }
        }

        Log.i(
            TAG,
            "Resolved settings fragment ${targets.settingsClass.name} and preference inflater " +
                "${targets.inflatePreferences.declaringClass.name}.${targets.inflatePreferences.name} via DexKit",
        )
    }

    private fun installConversationPresenceUi(runtime: HookRuntime) {
        val mainActivity = runCatching {
            runtime.classLoader.loadClass(MAIN_ACTIVITY_CLASS)
        }.getOrElse { error ->
            Log.e(TAG, "Google Messages main Activity is unavailable for conversation Presence UI", error)
            return
        }
        val resume = generateSequence(mainActivity as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method -> method.name == "onResume" && method.returnType == Void.TYPE && method.parameterCount == 0 }
            ?.apply { isAccessible = true }
        val pause = generateSequence(mainActivity as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method -> method.name == "onPause" && method.returnType == Void.TYPE && method.parameterCount == 0 }
            ?.apply { isAccessible = true }
        val newIntent = runCatching {
            mainActivity.getDeclaredMethod("onNewIntent", android.content.Intent::class.java).apply { isAccessible = true }
        }.getOrNull()
        if (resume == null || pause == null || newIntent == null) {
            Log.e(TAG, "Google Messages Activity lifecycle methods could not be resolved")
            return
        }
        runtime.hooks.method(resume, "google-messages.conversation-presence.bind") {
            after {
                val activity = thisObject as? Activity ?: return@after
                if (mainActivity.isInstance(activity)) {
                    Log.i(TAG, "Google Messages MainActivity onResume observed; binding conversation Presence UI")
                    GoogleMessagesPresenceUi.bind(activity, runtime.module.moduleApplicationInfo.packageName)
                }
            }
        }
        runtime.hooks.method(pause, "google-messages.conversation-presence.unbind") {
            after {
                val activity = thisObject as? Activity ?: return@after
                if (mainActivity.isInstance(activity)) {
                    Log.i(TAG, "Google Messages MainActivity onPause observed; unbinding conversation Presence UI")
                    GoogleMessagesPresenceUi.unbind(activity)
                }
            }
        }
        runtime.hooks.method(newIntent, "google-messages.conversation-presence.refresh-on-new-intent") {
            after {
                val activity = thisObject as? Activity ?: return@after
                val intent = arg(0) as? android.content.Intent ?: return@after
                if (mainActivity.isInstance(activity)) {
                    Log.i(TAG, "Google Messages MainActivity onNewIntent observed; refreshing conversation Presence")
                    GoogleMessagesPresenceUi.onNewIntent(
                        activity,
                        intent,
                        runtime.module.moduleApplicationInfo.packageName,
                    )
                }
            }
        }
        Log.i(
            TAG,
            "Installed Google Messages Compose conversation Presence selector on ${mainActivity.name}; " +
                "resume=${resume.declaringClass.name}.${resume.name} pause=${pause.declaringClass.name}.${pause.name} " +
                "newIntent=${newIntent.declaringClass.name}.${newIntent.name}",
        )
    }

    private fun resolveTargets(runtime: HookRuntime): ResolvedTargets? = runtime.dexKit.useBridge { bridge ->
        val preferenceClass = runtime.classLoader.loadClass(PREFERENCE_CLASS)

        // The main application-settings fragment owns the notification-sound preference.
        // Its ringtone-picker action is a semantic fingerprint that does not depend on R8 names.
        val settingsClasses = bridge.findClass {
            matcher {
                methods {
                    add {
                        returnType = "boolean"
                        paramTypes(PREFERENCE_CLASS)
                        usingStrings(RINGTONE_PICKER_ACTION)
                    }
                }
            }
        }.mapNotNull { data ->
            runCatching { data.getInstance(runtime.classLoader) }.getOrNull()
        }.filter { clazz ->
            clazz.declaredMethods.any { method ->
                method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.contentEquals(arrayOf(preferenceClass))
            } && clazz.declaredMethods.any(::isPreferenceCreationMethod)
        }.distinct()

        if (settingsClasses.size != 1) {
            Log.e(TAG, "Expected one main settings fragment, found ${settingsClasses.size}")
            return@useBridge null
        }
        val settingsClass = settingsClasses.single()
        val createPreferences = settingsClass.declaredMethods.singleOrNull(::isPreferenceCreationMethod)
            ?.apply { isAccessible = true }
            ?: return@useBridge null

        // The XML inflater is found by its parser error text and then verified by its full
        // runtime signature, including the app-specific PreferenceManager parameter type.
        val inflaterCandidates = bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher.create().apply {
                    usingStrings(PREFERENCE_INFLATER_FINGERPRINT)
                },
            ),
        ).mapNotNull { data ->
            runCatching { data.getMethodInstance(runtime.classLoader) }.getOrNull()
        }.filter { method ->
            val params = method.parameterTypes
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.returnType == preferenceClass &&
                params.size == 6 &&
                params[0].name == "org.xmlpull.v1.XmlPullParser" &&
                params[1].name == "androidx.preference.PreferenceGroup" &&
                params[2] == android.content.Context::class.java &&
                params[3].isArray && params[3].componentType == Any::class.java &&
                params[5] == Array<String>::class.java
        }
        if (inflaterCandidates.size != 1) {
            Log.e(TAG, "Expected one verified Preference XML inflater, found ${inflaterCandidates.size}")
            return@useBridge null
        }
        val inflater = inflaterCandidates.single().apply { isAccessible = true }

        val organizationTargets = resolveOrganizationTargets(bridge, runtime.classLoader)
            ?: return@useBridge null

        ResolvedTargets(
            settingsClass,
            createPreferences,
            inflater,
            organizationTargets.activity,
            organizationTargets.createPreferences,
            organizationTargets.resume,
            organizationTargets.pause,
            organizationTargets.navigateUp,
        )
    }

    private data class OrganizationTargets(
        val activity: Class<*>,
        val createPreferences: Method,
        val resume: Method,
        val pause: Method,
        val navigateUp: Method,
    )

    private fun resolveOrganizationTargets(
        bridge: org.luckypray.dexkit.DexKitBridge,
        classLoader: ClassLoader,
    ): OrganizationTargets? {
        val fragmentClasses = bridge.findClass {
            matcher {
                methods {
                    add {
                        returnType = "void"
                        paramTypes("android.os.Bundle", "java.lang.String")
                        usingStrings(MESSAGE_ORGANIZATION_FRAGMENT_FINGERPRINT)
                    }
                }
            }
        }.mapNotNull { data ->
            runCatching { data.getInstance(classLoader) }.getOrNull()
        }.filter { clazz -> clazz.declaredMethods.any(::isPreferenceCreationMethod) }.distinct()

        if (fragmentClasses.size != 1) {
            Log.e(TAG, "Expected one message-organization PreferenceFragment, found ${fragmentClasses.size}")
            return null
        }

        val organizationActivity = runCatching {
            classLoader.loadClass(MESSAGE_ORGANIZATION_ACTIVITY)
        }.getOrNull() ?: run {
            Log.e(TAG, "Google Messages message-organization Activity is unavailable")
            return null
        }
        val organizationFragment = fragmentClasses.single()
        val createPreferences = organizationFragment.declaredMethods
            .singleOrNull(::isPreferenceCreationMethod)
            ?.apply { isAccessible = true } ?: return null
        val resume = generateSequence(organizationActivity as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                method.name == "onResume" && method.returnType == Void.TYPE && method.parameterCount == 0
            }
            ?.apply { isAccessible = true }
            ?: run {
                Log.e(TAG, "Could not resolve organization Activity.onResume()")
                return null
            }
        val navigateUp = organizationActivity.declaredMethods.singleOrNull { method ->
            method.name == "onOptionsItemSelected" &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.contentEquals(arrayOf(MenuItem::class.java))
        }?.apply { isAccessible = true } ?: return null

        val pause = generateSequence(organizationActivity as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                method.name == "onPause" && method.returnType == Void.TYPE && method.parameterCount == 0
            }
            ?.apply { isAccessible = true }
            ?: run {
                Log.e(TAG, "Could not resolve organization Activity.onPause()")
                return null
            }

        return OrganizationTargets(organizationActivity, createPreferences, resume, pause, navigateUp)
    }

    private fun isPreferenceCreationMethod(method: Method): Boolean = method.returnType == Void.TYPE &&
        method.parameterTypes.contentEquals(arrayOf(Bundle::class.java, String::class.java))

    private const val PREFERENCE_CLASS = "androidx.preference.Preference"
    private const val RINGTONE_PICKER_ACTION = "android.intent.action.RINGTONE_PICKER"
    private const val PREFERENCE_INFLATER_FINGERPRINT = "No start tag found!"
    private const val MESSAGE_ORGANIZATION_FRAGMENT_FINGERPRINT =
        "MessageOrganizationSettingsFragmentPeer:toggleOtpDeletionEnabled"
    private const val MESSAGE_ORGANIZATION_ACTIVITY =
        "com.google.android.apps.messaging.label.settings.MessageOrganizationSettingsActivity"
    private const val MAIN_ACTIVITY_CLASS = "com.google.android.apps.messaging.main.MainActivity"
    private const val TAG = "GoogleMsg"
}
