package io.github.xiaotong6666.maihoku.features.mimessage.host.aosp

import android.content.Context
import android.content.Intent
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.mimessage.host.common.MessagingHostReflection.activity
import io.github.xiaotong6666.maihoku.features.mimessage.host.common.MessagingHostReflection.fields
import io.github.xiaotong6666.maihoku.features.mimessage.host.common.MessagingHostReflection.methods
import io.github.xiaotong6666.maihoku.features.mimessage.host.common.MiMessagePreferencePage
import io.github.xiaotong6666.maihoku.features.mimessage.host.common.MiMessageSettingsController
import io.github.xiaotong6666.maihoku.features.mimessage.ui.MessagingResources
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy

/** Builds native AOSP preferences; the shared controller owns account interactions. */
internal object AospMessagingPreferences {
    private const val CONFIG_PAGE_EXTRA = "io.github.xiaotong6666.maihoku.AOSP_CONFIG_PAGE"
    private const val ENTRY_KEY = "maihoku_xiaomi_sms_entry"
    private const val PREFERENCE = "androidx.preference.Preference"
    private const val SCREEN = "androidx.preference.PreferenceScreen"
    private const val CATEGORY = "androidx.preference.PreferenceCategory"

    fun install(fragment: Any, classLoader: ClassLoader, modulePackageName: String) {
        val context = fragmentContext(fragment)
        val host = activity(context) ?: error("AOSP settings Activity is unavailable")
        if (host.intent?.getBooleanExtra(CONFIG_PAGE_EXTRA, false) != true) {
            if (find(fragment, ENTRY_KEY) != null) return
            val entry = preference(context, classLoader, ENTRY_KEY, R.string.mx_title, R.string.mx_entry_summary)
            onClick(entry, classLoader) { host.startActivity(Intent(host, host.javaClass).putExtra(CONFIG_PAGE_EXTRA, true)) }
            add(checkNotNull(invoke(fragment, "getPreferenceScreen")), entry, classLoader, 900, true)
            return
        }
        if (find(fragment, "maihoku_xiaomi_sim1_status") != null) return
        val manager = checkNotNull(invoke(fragment, "getPreferenceManager"))
        val screenClass = classLoader.loadClass(SCREEN)
        val screen = screenClass.getConstructor(Context::class.java, android.util.AttributeSet::class.java).newInstance(context, null)
        attach(screen, manager)
        for (slot in 0..1) {
            val category = preference(context, classLoader, null, R.string.mx_sim_title, null, CATEGORY, slot + 1)
            add(screen, category, classLoader, slot)
            fun row(suffix: String, title: Int, summary: Int, order: Int, selectable: Boolean = true) {
                val item = preference(context, classLoader, "maihoku_xiaomi_sim${slot + 1}_$suffix", title, summary)
                setter(item, "setSelectable", Boolean::class.javaPrimitiveType!!, selectable)
                add(category, item, classLoader, order)
            }
            row("status", R.string.mx_account_status, R.string.mx_status_loading, 0, false)
            row("activate", R.string.mx_activate, R.string.mx_activate_summary, 1)
            row("refresh_mixin_credentials", R.string.mx_refresh_credentials, R.string.mx_refresh_credentials_summary, 2)
            row("passport", R.string.mx_passport_continue, R.string.mx_passport_summary, 3)
            if (slot == 0) {
                val items = listOf(
                    Triple("status", R.string.mx_push_status, R.string.mx_push_loading),
                    Triple("enabled", R.string.mx_feature, R.string.mx_feature_loading),
                    Triple("start", R.string.mx_reconnect, R.string.mx_reconnect_summary),
                )
                items.forEachIndexed { index, (suffix, title, summary) ->
                    val item = preference(context, classLoader, "maihoku_xiaomi_push_$suffix", title, summary)
                    setter(item, "setSelectable", Boolean::class.javaPrimitiveType!!, index != 0)
                    add(category, item, classLoader, 4 + index)
                }
            }
        }
        val refresh = preference(context, classLoader, "maihoku_xiaomi_account_refresh", R.string.mx_refresh_status, R.string.mx_refresh_status_summary)
        add(screen, refresh, classLoader, 2)
        setter(fragment, "setPreferenceScreen", screenClass, screen)
        val weakFragment = WeakReference(fragment)
        val weakContext = WeakReference(context)
        MiMessageSettingsController.attach(
            fragment,
            object : MiMessagePreferencePage {
                override val context: Context? get() = weakContext.get()
                override fun isVisible() = context?.let { activity(it) }?.let {
                    !it.isFinishing && !it.isDestroyed && it.intent?.getBooleanExtra(CONFIG_PAGE_EXTRA, false) == true
                } == true
                override fun find(key: String) = weakFragment.get()?.let { AospMessagingPreferences.find(it, key) }
                override fun summary(preference: Any, text: String) = setter(preference, "setSummary", CharSequence::class.java, text)
                override fun enabled(preference: Any, enabled: Boolean) = setter(preference, "setEnabled", Boolean::class.javaPrimitiveType!!, enabled)
                override fun onClick(preference: Any, action: () -> Unit) = AospMessagingPreferences.onClick(preference, classLoader, action)
            },
            modulePackageName,
        )
        host.title = MessagingResources.text(context, R.string.mx_settings_title)
    }

    fun refreshConfigurationPage(fragment: Any, classLoader: ClassLoader, modulePackageName: String) {
        // The existing controller retains the adapter contract; resume does not register listeners again.
        MiMessageSettingsController.refresh(fragment)
    }

    fun stopRuntimeRefresh(fragment: Any) = MiMessageSettingsController.stop(fragment)

    private fun preference(context: Context, loader: ClassLoader, key: String?, title: Int, summary: Int?, type: String = PREFERENCE, vararg titleArgs: Any): Any {
        val result = loader.loadClass(type).getConstructor(Context::class.java, android.util.AttributeSet::class.java).newInstance(context, null)
        key?.let { setter(result, "setKey", String::class.java, it) }
        setter(result, "setTitle", CharSequence::class.java, MessagingResources.text(context, title, *titleArgs))
        summary?.let { setter(result, "setSummary", CharSequence::class.java, MessagingResources.text(context, it)) }
        setter(result, "setPersistent", Boolean::class.javaPrimitiveType!!, false)
        return result
    }

    /**
     * R8 removed addPreference in this APK but retained a same-shaped removePreference; signature
     * guessing could therefore delete a row. Follow AndroidX inflater ordering: inherit the parent's
     * disabled state, sort, attach to the manager, assign the parent, then notify the adapter.
     * Attaching groups before their children gives nested rows valid managers and stable IDs.
     */
    private fun add(group: Any, child: Any, loader: ClassLoader, order: Int, attachNow: Boolean = false) {
        val preferenceClass = loader.loadClass(PREFERENCE)
        val groupClass = loader.loadClass("androidx.preference.PreferenceGroup")
        setter(child, "setOrder", Int::class.javaPrimitiveType!!, order)
        val childrenField = fields(group.javaClass).single { it.declaringClass == groupClass && java.util.List::class.java.isAssignableFrom(it.type) }
        childrenField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val children = childrenField.get(group) as MutableList<Any>
        if (child in children) return
        val parentChanged = methods(child.javaClass).single { it.name == "onParentChanged" && it.parameterTypes.contentEquals(arrayOf(preferenceClass, Boolean::class.javaPrimitiveType)) }
        parentChanged.isAccessible = true
        parentChanged.invoke(child, group, invoke(group, "shouldDisableDependents") as? Boolean ?: false)
        val getOrder = methods(preferenceClass).single { it.name == "getOrder" && it.parameterCount == 0 }
        getOrder.isAccessible = true
        val index = children.indexOfFirst { (getOrder.invoke(it) as Int) > order }.let { if (it < 0) children.size else it }
        children.add(index, child)
        attach(child, checkNotNull(invoke(group, "getPreferenceManager")))
        setter(child, "assignParent", groupClass, group)
        if (attachNow) invoke(child, "onAttached")
        invoke(group, "notifyHierarchyChanged")
    }

    private fun attach(preference: Any, manager: Any) = setter(preference, "onAttachedToHierarchy", manager.javaClass, manager)

    private fun onClick(preference: Any, loader: ClassLoader, action: () -> Unit) {
        val setter = methods(preference.javaClass).single { it.name == "setOnPreferenceClickListener" && it.parameterCount == 1 }
        val preferenceClass = loader.loadClass(PREFERENCE)
        val listener = Proxy.newProxyInstance(loader, arrayOf(setter.parameterTypes.single())) { proxy, method, args ->
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
        setter.isAccessible = true
        setter.invoke(preference, listener)
    }

    private fun find(fragment: Any, key: String): Any? = methods(fragment.javaClass)
        .firstOrNull { it.name == "findPreference" && it.parameterTypes.contentEquals(arrayOf(CharSequence::class.java)) }
        ?.apply { isAccessible = true }?.invoke(fragment, key)

    private fun setter(target: Any, name: String, parameter: Class<*>, value: Any) {
        val method = methods(target.javaClass).firstOrNull { it.name == name && it.parameterTypes.contentEquals(arrayOf(parameter)) }
            ?: error("AOSP Preference.$name(${parameter.simpleName}) is unavailable")
        method.isAccessible = true
        method.invoke(target, value)
    }

    private fun invoke(target: Any, name: String): Any? = methods(target.javaClass)
        .firstOrNull { it.name == name && it.parameterCount == 0 }?.apply { isAccessible = true }?.invoke(target)

    private fun fragmentContext(fragment: Any): Context = (invoke(fragment, "getContext") ?: invoke(fragment, "getActivity")) as? Context
        ?: error("AOSP settings fragment has no Context")
}
