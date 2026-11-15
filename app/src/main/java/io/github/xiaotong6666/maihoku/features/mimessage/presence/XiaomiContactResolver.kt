package io.github.xiaotong6666.maihoku.features.mimessage.presence

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** Phone-number -> Xiaomi account MID lookup used by the original Mi Message client. */
internal object XiaomiContactResolver {
    private const val ENDPOINT = "https://api.account.xiaomi.com/pass/v3/user@id"
    private const val CLOUD_ID_PROVIDER = "content://com.xiaomi.cloud.cloudidprovider"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    data class Resolution(
        val mid: String,
        val httpStatus: Int,
        val deviceIdSource: String,
    )

    /**
     * Mirrors MiuiMms x8.b.a() + x8.a.b(): GET user@id?type=MXPH&externalId=...
     * with a deviceId Cookie, then read data.userId when code == 0.
     *
     * AOSP AVD has no MIUI TelephonyManager.getMiuiDeviceId(). We therefore use the same
     * privacy-preserving fallback family as XMSF's restricted-IMEI branch: Xiaomi Cloud ID
     * when its provider exists, otherwise Android ID. The raw identifier is never logged.
     */
    fun resolvePhoneToMid(context: Context, phoneNumber: String): Resolution {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "Xiaomi contact lookup must run off the main thread"
        }
        val phone = phoneNumber.trim()
        require(phone.isNotEmpty() && phone.length <= 64) { "Phone number is empty or too long" }
        require(phone.none(Char::isISOControl)) { "Phone number contains control characters" }

        val (deviceIdCookie, deviceIdSource) = deviceIdCookie(context)
        val uri = Uri.parse(ENDPOINT).buildUpon()
            .appendQueryParameter("type", "MXPH")
            .appendQueryParameter("externalId", phone)
            .build()
        val network = context.getSystemService(ConnectivityManager::class.java)?.activeNetwork
        val connection = openConnection(network, URL(uri.toString()))
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", buildUserAgent())
            connection.setRequestProperty("Cookie", "deviceId=$deviceIdCookie")

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_MOVED_TEMP) {
                throw IOException("Xiaomi contact lookup HTTP $status")
            }
            val stream = connection.inputStream
            val body = stream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw IOException("Xiaomi contact lookup response is too large")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
            val root = JSONObject(body)
            val apiCode = root.optInt("code", Int.MIN_VALUE)
            if (apiCode != 0) throw IOException("Xiaomi contact lookup API code=$apiCode")
            val data = root.optJSONObject("data") ?: throw IOException("Xiaomi contact lookup has no data")
            val userId = when (val raw = data.opt("userId")) {
                is Number -> raw.toLong().toString()
                is String -> raw.toLongOrNull()?.toString()
                else -> null
            } ?: throw IOException("Xiaomi contact lookup returned no numeric userId")
            if (userId == "0" || userId.startsWith('-')) {
                throw IOException("Xiaomi contact lookup returned an invalid userId")
            }
            return Resolution(userId, status, deviceIdSource)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(network: Network?, url: URL): HttpURLConnection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection

    private fun deviceIdCookie(context: Context): Pair<String, String> {
        val cloudId = runCatching {
            context.contentResolver.call(Uri.parse(CLOUD_ID_PROVIDER), "getCloudId", null, null)
                ?.getString("result_id")
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
        val (rawId, source) = if (cloudId != null) {
            cloudId to "cloud_id"
        } else {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf(String::isNotBlank)
                ?: throw IOException("No supported AVD device identifier is available")
            androidId to "android_id"
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(rawId.toByteArray(Charsets.UTF_8))
        // hf.a.a(): Base64(SHA1(deviceId), URL_SAFE).substring(0, 16).
        val cookie = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP).take(16)
        return cookie to source
    }

    private fun buildUserAgent(): String {
        val model = Build.MODEL.orEmpty().ifBlank { "Android" }
        val incremental = Build.VERSION.INCREMENTAL.orEmpty().ifBlank { "unknown" }
        val locale = Locale.getDefault()
        val language = locale.language.ifBlank { "en" }
        val country = locale.country.uppercase(Locale.ROOT)
        val localeTag = if (country.isEmpty()) language else "$language-$country"
        return "$model/$model; Android/$incremental L/$localeTag"
    }
}
