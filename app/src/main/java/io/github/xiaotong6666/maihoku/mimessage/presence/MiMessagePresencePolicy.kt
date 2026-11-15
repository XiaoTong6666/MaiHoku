package io.github.xiaotong6666.maihoku.mimessage.presence

/** Capability bits and cache rules mirrored from MIUI Messages' MxMessageService / w8.f. */
internal object MiMessagePresencePolicy {
    const val CACHE_TTL_MS = 60_000L
    const val WATCH_TTL_MS = 5 * 60_000L

    const val STATUS_UNKNOWN = "UNKNOWN"
    const val STATUS_AVAILABLE = "AVAILABLE"
    const val STATUS_UNAVAILABLE = "UNAVAILABLE"

    const val CAP_SMS = 1L
    const val CAP_MMS = 1L shl 1
    const val CAP_MX2_IMAGE = 1L shl 32
    const val CAP_MX2_AUDIO = 1L shl 33
    const val CAP_MX_V2_MMS2 = 1L shl 34

    /**
     * MIUI sends comma-separated client_attrs entries and separates capabilities with '#'.
     * The original MxMessageService defaults to SMS when client_attrs/cap is absent or empty.
     */
    fun parseCapabilities(clientAttributes: String?): Long {
        val cap = clientAttributes
            ?.split(',')
            ?.mapNotNull { entry ->
                val fields = entry.split(':')
                fields.getOrNull(1)?.takeIf { fields.size == 2 && fields[0].trim() == "cap" }?.trim()
            }
            ?.lastOrNull()
        if (cap.isNullOrBlank()) return CAP_SMS

        return cap.split('#').fold(0L) { bits, rawCapability ->
            bits or when (rawCapability.trim()) {
                "sms" -> CAP_SMS
                "mms" -> CAP_MMS
                "mx2image" -> CAP_MX2_IMAGE
                "mx2audio" -> CAP_MX2_AUDIO
                "mxV2mms2" -> CAP_MX_V2_MMS2
                else -> 0L
            }
        }
    }

    fun normalizePhoneNumber(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > 64 || trimmed.any(Char::isISOControl)) return null
        val digits = trimmed.filter { it in '0'..'9' }
        val international = trimmed.startsWith('+') || trimmed.startsWith("00")
        val normalizedDigits = if (trimmed.startsWith("00")) digits.drop(2) else digits
        if (normalizedDigits.length !in 3..20) return null
        return (if (international) "+" else "") + normalizedDigits
    }

    fun isFresh(expiresAt: Long, now: Long): Boolean = expiresAt > now

    fun supportsSms(status: String, capabilities: Long, expiresAt: Long, now: Long): Boolean = status == STATUS_AVAILABLE &&
        capabilities and CAP_SMS != 0L &&
        isFresh(expiresAt, now)
}
