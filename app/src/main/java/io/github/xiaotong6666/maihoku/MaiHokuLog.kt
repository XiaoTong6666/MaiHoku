package io.github.xiaotong6666.maihoku

import android.util.Log as AndroidLog

/** One searchable Logcat tag; retain subsystem context as a compact message prefix. */
internal object MaiHokuLog {
    fun v(tag: String, message: String) = AndroidLog.v(LOG_TAG, format(tag, message))
    fun d(tag: String, message: String) = AndroidLog.d(LOG_TAG, format(tag, message))
    fun i(tag: String, message: String) = AndroidLog.i(LOG_TAG, format(tag, message))
    fun w(tag: String, message: String) = AndroidLog.w(LOG_TAG, format(tag, message))
    fun w(tag: String, message: String, error: Throwable) = AndroidLog.w(LOG_TAG, format(tag, message), error)
    fun e(tag: String, message: String) = AndroidLog.e(LOG_TAG, format(tag, message))
    fun e(tag: String, message: String, error: Throwable) = AndroidLog.e(LOG_TAG, format(tag, message), error)

    private fun format(tag: String, message: String): String {
        val scope = when {
            tag == LOG_TAG -> null
            tag.startsWith("MaiHoku-") -> tag.removePrefix("MaiHoku-")
            else -> tag
        } ?: return message
        val label = when {
            scope.startsWith("GoogleMessages") -> "GoogleMsg"
            scope.startsWith("AospMessaging") -> "AospMsg"
            scope.startsWith("MiMessage") -> scope.removePrefix("MiMessage").ifBlank { "MiMessage" }
            scope.startsWith("MessagingHost") -> scope.removePrefix("MessagingHost").ifBlank { "MessagingHost" }
            scope.startsWith("Xiaomi") -> scope.removePrefix("Xiaomi").ifBlank { "Xiaomi" }
            scope.startsWith("SimActivation") -> "SimActivation"
            scope.startsWith("PrivilegedSim") -> "PrivilegedSim"
            scope.startsWith("AccountBridge") -> "AccountBridge"
            scope.startsWith("Telegram") -> "Telegram"
            scope.startsWith("Termux") -> scope.removePrefix("Termux")
            else -> scope
        }
        return "[$label] $message"
    }

    private const val LOG_TAG = "MaiHoku"
}
