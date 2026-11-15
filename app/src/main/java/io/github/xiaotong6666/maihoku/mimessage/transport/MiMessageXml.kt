package io.github.xiaotong6666.maihoku.mimessage.transport

/** Shared XML escaping for Presence and message stanzas; escape ampersands before adding entities. */
internal object MiMessageXml {
    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
