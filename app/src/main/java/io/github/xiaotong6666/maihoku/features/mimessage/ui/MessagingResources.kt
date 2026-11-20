package io.github.xiaotong6666.maihoku.features.mimessage.ui

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle
import io.github.xiaotong6666.maihoku.hook.ModuleResources

/** Compatibility shim for existing message-host callers. */
internal object MessagingResources {
    fun text(context: Context, id: Int, vararg arguments: Any): String = ModuleResources.text(context, id, *arguments)
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
