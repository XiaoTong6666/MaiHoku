package io.github.xiaotong6666.maihoku.mimessage.ui

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import io.github.xiaotong6666.maihoku.BuildConfig

/**
 * A hook's Context belongs to the SMS host, but R belongs to this module. Resolve strings from
 * the module APK with the page's configuration so numeric resource IDs cannot collide across apps.
 * Cache Resources rather than an Activity, and rebuild the cache when the configuration changes.
 */
internal object MessagingResources {
    @Volatile private var moduleApplicationInfo: ApplicationInfo? = null

    @Volatile private var cached: Resources? = null

    fun initialize(applicationInfo: ApplicationInfo) {
        moduleApplicationInfo = applicationInfo
        cached = null
    }

    fun text(context: Context, id: Int, vararg arguments: Any): String {
        if (context.packageName == BuildConfig.APPLICATION_ID) return context.getString(id, *arguments)
        val configuration = context.resources.configuration
        val moduleInfo = checkNotNull(moduleApplicationInfo) { "MaiHoku module resources were not initialized" }
        // A hooked SMS host cannot necessarily query the Xposed module package under Android
        // package-visibility rules. Passing ApplicationInfo directly avoids createPackageContext's
        // package-name visibility check while still loading strings from the module APK.
        val resources = cached?.takeIf { it.configuration == configuration } ?: context.packageManager
            .getResourcesForApplication(moduleInfo)
            .also {
                @Suppress("DEPRECATION")
                it.updateConfiguration(configuration, context.resources.displayMetrics)
                cached = it
            }
        return resources.getString(id, *arguments)
    }
}

/** Pure policies return resource descriptions so each IPC consumer can render in its own locale. */
internal data class MessagingText(val resource: Int, val arguments: List<Any> = emptyList()) {
    fun resolve(context: Context): String = MessagingResources.text(
        context,
        resource,
        *arguments.map { if (it is MessagingText) it.resolve(context) else it }.toTypedArray(),
    )
}

internal fun Bundle.pendingIntent(key: String): PendingIntent? = if (Build.VERSION.SDK_INT >= 33) {
    getParcelable(key, PendingIntent::class.java)
} else {
    @Suppress("DEPRECATION")
    getParcelable(key)
}
