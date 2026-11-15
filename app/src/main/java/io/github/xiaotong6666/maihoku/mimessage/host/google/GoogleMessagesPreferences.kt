package io.github.xiaotong6666.maihoku.mimessage.host.google

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.mimessage.host.common.MessagingHostReflection.activity
import io.github.xiaotong6666.maihoku.mimessage.host.common.MessagingHostReflection.fields
import io.github.xiaotong6666.maihoku.mimessage.host.common.MessagingHostReflection.methods
import io.github.xiaotong6666.maihoku.mimessage.host.common.MiMessagePreferencePage
import io.github.xiaotong6666.maihoku.mimessage.host.common.MiMessageSettingsController
import io.github.xiaotong6666.maihoku.mimessage.ui.MessagingResources
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Adapts Google's inflater, R8 symbols and navigation without duplicating account workflows. */
internal object GoogleMessagesPreferences {
    private lateinit var symbols: GoogleMessagesPreferenceSymbols
    private const val ENTRY_KEY = "maihoku_xiaomi_sms_entry"
    const val CONFIG_PAGE_INTENT_EXTRA = "maihoku_xiaomi_sms_config_page"
    private const val PREFERENCE = "androidx.preference.Preference"
    private const val SCREEN = "androidx.preference.PreferenceScreen"
    private var configurationFragment = WeakReference<Any>(null)

    fun initialize(resolved: GoogleMessagesPreferenceSymbols) {
        symbols = resolved
    }

    fun isConfigurationLaunch(fragment: Any): Boolean = activity(fragmentContext(fragment))
        ?.intent?.getBooleanExtra(CONFIG_PAGE_INTENT_EXTRA, false) == true

    fun installSingleEntry(fragment: Any, classLoader: ClassLoader, preferenceInflater: Method, organizationActivityClass: Class<*>, moduleApplicationInfo: ApplicationInfo) {
        val context = fragmentContext(fragment)
        val screen = screen(fragment, classLoader)
        if (find(screen, ENTRY_KEY, classLoader) != null) return
        inflate(preferenceInflater, R.xml.maihoku_google_messages_entry, screen, context, manager(screen, classLoader), moduleApplicationInfo)
        val entry = checkNotNull(find(screen, ENTRY_KEY, classLoader))
        symbols.setTitle.invoke(entry, MessagingResources.text(context, R.string.mx_settings_title))
        symbols.setSummary.invoke(entry, MessagingResources.text(context, R.string.mx_entry_summary))
        val host = activity(context) ?: error("Google settings Activity is unavailable")
        fields(entry.javaClass).single { it.type == Intent::class.java }.apply { isAccessible = true }
            .set(entry, Intent(host, organizationActivityClass).putExtra(CONFIG_PAGE_INTENT_EXTRA, true))
        nativeRow(entry, context)
    }

    fun installConfigurationPage(fragment: Any, classLoader: ClassLoader, preferenceInflater: Method, moduleApplicationInfo: ApplicationInfo) {
        val context = fragmentContext(fragment)
        val current = screen(fragment, classLoader)
        val replacement = inflate(preferenceInflater, R.xml.maihoku_google_messages_config, null, context, manager(current, classLoader), moduleApplicationInfo)
        val screenClass = classLoader.loadClass(SCREEN)
        check(screenClass.isInstance(replacement))
        applyLabels(replacement, context, classLoader)
        fragment.javaClass.methods.single { it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(screenClass)) }
            .apply { isAccessible = true }.invoke(fragment, replacement)
        configurationFragment = WeakReference(fragment)
        val weakFragment = WeakReference(fragment)
        val weakScreen = WeakReference(replacement)
        val weakContext = WeakReference(context)
        MiMessageSettingsController.attach(
            fragment,
            object : MiMessagePreferencePage {
                override val context: Context? get() = weakContext.get()
                override fun isVisible(): Boolean = weakFragment.get()?.let { owner ->
                    context?.let { activity(it) }?.let { !it.isFinishing && !it.isDestroyed && isConfigurationLaunch(owner) }
                } == true
                override fun find(key: String) = weakScreen.get()?.let { GoogleMessagesPreferences.find(it, key, classLoader) }
                override fun summary(preference: Any, text: String) {
                    symbols.setSummary.invoke(preference, text)
                }
                override fun enabled(preference: Any, enabled: Boolean) {
                    symbols.setEnabled.invoke(preference, enabled)
                }
                override fun onClick(preference: Any, action: () -> Unit) = click(preference, classLoader, action)
            },
            moduleApplicationInfo.packageName,
        )
        activity(context)?.title = MessagingResources.text(context, R.string.mx_settings_title)
    }

    fun refreshConfigurationPage(fragment: Any, classLoader: ClassLoader, moduleApplicationInfo: ApplicationInfo) {
        if (isConfigurationLaunch(fragment)) MiMessageSettingsController.refresh(fragment)
    }

    fun refreshConfigurationActivity(host: Activity, classLoader: ClassLoader, moduleApplicationInfo: ApplicationInfo) {
        val fragment = configurationFragment.get() ?: return
        if (activity(fragmentContext(fragment)) === host) refreshConfigurationPage(fragment, classLoader, moduleApplicationInfo)
    }

    fun stopRuntimeRefresh(host: Activity) {
        val fragment = configurationFragment.get() ?: return
        if (activity(fragmentContext(fragment)) === host) MiMessageSettingsController.stop(fragment)
    }

    private fun click(preference: Any, loader: ClassLoader, action: () -> Unit) {
        // R8 removed/renamed the setter, but the listener's boolean(Preference) contract survives.
        val preferenceClass = loader.loadClass(PREFERENCE)
        val field = fields(preference.javaClass).single {
            it.type.isInterface && it.type.declaredMethods.any { method ->
                method.returnType == Boolean::class.javaPrimitiveType && method.parameterTypes.contentEquals(arrayOf(preferenceClass))
            }
        }
        val listener = Proxy.newProxyInstance(loader, arrayOf(field.type)) { proxy, method, args ->
            when {
                method.returnType == Boolean::class.javaPrimitiveType && method.parameterTypes.contentEquals(arrayOf(preferenceClass)) -> {
                    action()
                    true
                }

                method.name == "toString" -> "MaiHokuPreferenceListener"

                method.name == "hashCode" -> System.identityHashCode(proxy)

                method.name == "equals" -> proxy === args?.firstOrNull()

                else -> null
            }
        }
        field.isAccessible = true
        field.set(preference, listener)
    }

    private fun applyLabels(screen: Any, context: Context, loader: ClassLoader) {
        // XML supplies structure and behavior only. The host inflater resolves @string using host
        // Resources, so module IDs may collide. Resolve module strings first, then call native setters.
        fun label(key: String, title: Int, summary: Int? = null, vararg args: Any) {
            val row = find(screen, key, loader) ?: return
            symbols.setTitle.invoke(row, MessagingResources.text(context, title, *args))
            summary?.let { symbols.setSummary.invoke(row, MessagingResources.text(context, it)) }
        }
        label("maihoku_xiaomi_protocol", R.string.mx_protocol_title)
        label("maihoku_xiaomi_service_channel", R.string.mx_protocol_channel, R.string.mx_protocol_channel_value)
        label("maihoku_xiaomi_auth_method", R.string.mx_protocol_auth, R.string.mx_protocol_auth_value)
        label("maihoku_xiaomi_account", R.string.mx_account_title)
        for (number in 1..2) {
            label("maihoku_xiaomi_sim${number}_status", R.string.mx_sim_status, R.string.mx_status_loading, number)
            label("maihoku_xiaomi_sim${number}_activate", R.string.mx_sim_activate, R.string.mx_activate_summary, number)
            label("maihoku_xiaomi_sim${number}_refresh_mixin_credentials", R.string.mx_sim_credentials, R.string.mx_refresh_credentials_summary, number)
            label("maihoku_xiaomi_sim${number}_passport", R.string.mx_sim_passport, R.string.mx_passport_summary, number)
        }
        label("maihoku_xiaomi_push_status", R.string.mx_push_status, R.string.mx_push_loading)
        label("maihoku_xiaomi_push_enabled", R.string.mx_feature, R.string.mx_feature_loading)
        label("maihoku_xiaomi_push_start", R.string.mx_reconnect, R.string.mx_reconnect_summary)
        label("maihoku_xiaomi_account_refresh", R.string.mx_refresh_status, R.string.mx_refresh_status_summary)
    }

    private fun inflate(inflater: Method, xml: Int, parent: Any?, context: Context, manager: Any, module: ApplicationInfo): Any {
        // Recent Android themes require a real XmlResourceParser and reject generic XmlPullAttributes.
        val parser = context.packageManager.getResourcesForApplication(module).getXml(xml)
        return try {
            inflater.invoke(null, parser, parent, context, arrayOfNulls<Any>(2), manager, arrayOf("androidx.preference.", "androidx.preference."))
                ?: error("Google Preference inflater returned null")
        } finally {
            parser.close()
        }
    }

    private fun screen(fragment: Any, loader: ClassLoader): Any {
        val type = loader.loadClass(SCREEN)
        return fragment.javaClass.methods.single { it.parameterCount == 0 && type.isAssignableFrom(it.returnType) }
            .apply { isAccessible = true }.invoke(fragment) ?: error("Google PreferenceScreen is unavailable")
    }

    private fun manager(screen: Any, loader: ClassLoader): Any {
        val screenClass = loader.loadClass(SCREEN)
        return loader.loadClass(PREFERENCE).declaredFields.single { field ->
            field.type.declaredMethods.any {
                it.returnType == screenClass && it.parameterTypes.contentEquals(arrayOf(Context::class.java, Int::class.javaPrimitiveType, screenClass))
            }
        }.apply { isAccessible = true }.get(screen) ?: error("Google PreferenceManager is unavailable")
    }

    private fun find(screen: Any, key: String, loader: ClassLoader): Any? {
        val type = loader.loadClass(PREFERENCE)
        return screen.javaClass.methods.first { it.parameterTypes.contentEquals(arrayOf(CharSequence::class.java)) && type.isAssignableFrom(it.returnType) }
            .apply { isAccessible = true }.invoke(screen, key)
    }

    private fun fragmentContext(fragment: Any): Context = methods(fragment.javaClass)
        .filter { it.parameterCount == 0 && Context::class.java.isAssignableFrom(it.returnType) }
        .firstNotNullOfOrNull {
            runCatching {
                it.isAccessible = true
                it.invoke(fragment) as? Context
            }.getOrNull()
        }
        ?: error("Google settings fragment has no Context")

    private fun nativeRow(preference: Any, context: Context) {
        val resources = context.resources
        val native = resources.getIdentifier("preference_material", "layout", context.packageName)
        check(native != 0) { "Google native Preference layout is unavailable" }
        val defaults = listOf("preference", "preference_material", "preference_item")
            .map { resources.getIdentifier(it, "layout", context.packageName) }.filter { it != 0 }.toSet()
        fields(preference.javaClass).first { field ->
            field.type == Int::class.javaPrimitiveType && java.lang.reflect.Modifier.isPublic(field.modifiers) &&
                runCatching {
                    field.isAccessible = true
                    field.getInt(preference) in defaults
                }.getOrDefault(false)
        }.setInt(preference, native)
        // Module XML lacks the host's private styleable IDs; mirror its native icon-space setting.
        symbols.clearIconSpace?.invoke(preference)
    }
}
