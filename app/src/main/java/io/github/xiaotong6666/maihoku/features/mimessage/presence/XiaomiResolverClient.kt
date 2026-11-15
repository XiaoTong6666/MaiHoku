package io.github.xiaotong6666.maihoku.features.mimessage.presence

import android.content.Context
import android.net.Network
import android.net.Uri
import android.telephony.TelephonyManager
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig
import io.github.xiaotong6666.maihoku.features.mimessage.transport.SlimProtocol
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Minimal XMSF-compatible GSLB resolver for the single Mi Message Push host. */
internal object XiaomiResolverClient {
    data class Endpoint(val address: InetAddress, val port: Int)

    private data class CachedResolution(val expiresAtMs: Long, val endpoints: List<Endpoint>)

    private const val TAG = "XiaomiResolver"
    private const val SIGNING_SUFFIX = "be988a6134bc8254465424e5a70ef037"
    private const val PUSH_SDK_VERSION = "60075"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private val cache = ConcurrentHashMap<String, CachedResolution>()

    fun resolve(
        context: Context,
        network: Network?,
        pushHost: String,
        networkTag: String,
    ): List<Endpoint> {
        val cached = cache[pushHost]
        if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) return cached.endpoints

        val networkType = if (networkTag == "wifi") "wifi" else "wap"
        val baseUrl = "https://${MiMessageConfig.DEFAULT_RESOLVER_HOST}/gslb/?ver=5.0"
        val countryCode = countryCode(context)
        val url = Uri.parse(baseUrl).buildUpon()
            .appendQueryParameter("type", networkType)
            .appendQueryParameter("reserved", "1")
            .appendQueryParameter("uuid", SlimProtocol.deviceUuid(context))
            .appendQueryParameter("list", listOf(pushHost, MiMessageConfig.DEFAULT_RESOLVER_HOST).joinToString(","))
            .appendQueryParameter("countrycode", countryCode)
            .appendQueryParameter("push_sdk_vc", PUSH_SDK_VERSION)
            .build()
            .toString()
        // XMSF w0.d.c() signs the complete URL before adding the key query parameter.
        val signedUrl = "$url&key=${md5Hex(url + SIGNING_SUFFIX)}"

        val connection = (network?.openConnection(URL(signedUrl)) ?: URL(signedUrl).openConnection())
            as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            val responseCode = connection.responseCode
            check(responseCode == HttpURLConnection.HTTP_OK) { "GSLB HTTP $responseCode" }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(body)
            check(root.optString("S").equals("OK", ignoreCase = true)) { "GSLB response status is not OK" }
            val response = root.getJSONObject("R")
            val networkBuckets = response.optJSONObject(networkType)
                ?: error("GSLB response has no $networkType bucket")
            val addressArray = networkBuckets.optJSONArray(pushHost)
                ?: error("GSLB response has no bucket for $pushHost")

            val endpoints = buildList {
                for (index in 0 until addressArray.length()) {
                    val raw = addressArray.optString(index).trim()
                    parseEndpoint(raw)?.let(::add)
                }
            }.distinctBy { "${it.address.hostAddress}:${it.port}" }
            check(endpoints.isNotEmpty()) { "GSLB returned an empty Push endpoint list" }

            val ttlMs = (response.optLong("ttl", 300L).coerceIn(30L, 86_400L)) * 1000L
            cache[pushHost] = CachedResolution(System.currentTimeMillis() + ttlMs, endpoints)
            Log.i(TAG, "GSLB resolved host=$pushHost network=$networkType endpoints=${endpoints.size} ttl_ms=$ttlMs")
            return endpoints
        } catch (error: Throwable) {
            Log.w(TAG, "GSLB lookup failed host=$pushHost error=${error.javaClass.simpleName}")
            return emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun parseEndpoint(value: String): Endpoint? {
        if (value.isBlank()) return null
        val host: String
        val port: Int
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 1) return null
            host = value.substring(1, end)
            port = value.substring(end + 1).removePrefix(":").toIntOrNull() ?: 443
        } else {
            val colon = value.lastIndexOf(':')
            if (colon > 0 && value.indexOf(':') == colon) {
                host = value.substring(0, colon)
                port = value.substring(colon + 1).toIntOrNull() ?: 443
            } else {
                host = value
                port = 443
            }
        }
        if (port !in 1..65535) return null
        return runCatching { Endpoint(InetAddress.getByName(host), port) }.getOrNull()
    }

    private fun countryCode(context: Context): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val value = runCatching { telephony?.simCountryIso }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: runCatching { telephony?.networkCountryIso }.getOrNull()?.takeIf(String::isNotBlank)
            ?: Locale.getDefault().country.takeIf(String::isNotBlank)
            ?: "CN"
        return value.uppercase(Locale.ROOT)
    }

    private fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
