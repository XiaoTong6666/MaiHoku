package io.github.xiaotong6666.maihoku.features.mimessage.host.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Shared reflection helpers for AOSP Messaging and Google Messages host adapters. */
internal object MessagingHostReflection {
    // Prefer the most-derived override; retain field declarations for semantic owner/type matching.
    fun methods(type: Class<*>): List<Method> = generateSequence(type as Class<*>?) { it.superclass }
        .flatMap { it.declaredMethods.asSequence() }
        .distinctBy { it.name + it.parameterTypes.joinToString { parameter -> parameter.name } }.toList()

    fun fields(type: Class<*>): List<Field> = generateSequence(type as Class<*>?) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }.toList()

    fun activity(context: Context): Activity? {
        var current = context
        val visited = HashSet<Context>()
        while (visited.add(current)) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }
}
