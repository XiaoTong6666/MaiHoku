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
        if (tag == LOG_TAG) return message
        return "[$tag] $message"
    }

    private const val LOG_TAG = "MaiHoku"
}
