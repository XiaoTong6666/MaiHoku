package io.github.xiaotong6666.maihoku.hook

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import io.github.xiaotong6666.maihoku.BuildConfig

/**
 * Host contexts belong to hooked apps, so their resource IDs do not match MaiHoku's.
 * Resolve IDs from the module APK, keeping the host's current configuration/locale.
 */
internal object ModuleResources {
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
        // ApplicationInfo avoids module-package visibility restrictions in the host process.
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
