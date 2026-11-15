package io.github.xiaotong6666.maihoku.mimessage.account

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import io.github.xiaotong6666.maihoku.mimessage.core.MiMessageConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64 as AndroidBase64
import java.util.Base64 as JavaBase64

/**
 * Static protocol implementation recovered from SimActivateService's
 * ActivateServerRequest / ActivateHttp / CodeUtils classes.
 *
 * This class does not send an SMS and does not perform network requests during
 * construction. Callers must explicitly invoke each operation after obtaining
 * the required SIM-owner consent. All secrets stay in memory until the caller
 * commits them through MiMessageCredentialStore.
 */
internal data class SimActivationAttempt(
    val slotIndex: Int,
    val subscriptionId: Int,
    val simIdHash: String,
    val deviceIdHash: String,
    val mccMnc: String,
    val activationHost: String,
    val gatewayNumber: String?,
    val traceId: String = UUID.randomUUID().toString().take(15),
    val t2: String = randomHex(8),
    val feature: Int = FEATURE_MX,
) {
    override fun toString(): String = "SimActivationAttempt(slotIndex=$slotIndex, subscriptionId=$subscriptionId, simIdHash=masked, deviceIdHash=masked, mccMnc=$mccMnc, activationHost=$activationHost, gatewayNumberPresent=${!gatewayNumber.isNullOrBlank()}, traceId=masked, t2=masked, feature=$feature)"

    init {
        require(slotIndex in 0..1) { "Only physical SIM slots 0 and 1 are supported" }
        require(subscriptionId >= 0) { "A valid Subscription ID is required" }
        require(simIdHash.length == 16) { "simIdHash must be the original 16-character SHA1/Base64 value" }
        require(deviceIdHash.length == 16) { "deviceIdHash must be the original 16-character SHA1/Base64 value" }
        require(mccMnc.length in 5..6 && mccMnc.all(Char::isDigit)) { "Invalid MCC/MNC" }
        require(activationHost.isNotBlank() && !activationHost.contains("/")) { "Invalid activation host" }
        require(traceId.length == 15) { "traceId must contain 15 characters" }
        require(t2.length == 16) { "_t2 must contain 16 hexadecimal characters" }
        require(feature > 0) { "Activation feature mask must be non-zero" }
    }

    fun commonParams(): LinkedHashMap<String, String> = linkedMapOf(
        "sid" to "micloud",
        "traceId" to traceId,
        "simId" to simIdHash,
        "devId" to deviceIdHash,
        "_t2" to t2,
        "_ft" to feature.toString(),
    )

    companion object {
        const val FEATURE_MX = 1
    }
}

internal data class XiaomiVKeyPair(val vkey1: String, val vkey2: String) {
    override fun toString(): String = "XiaomiVKeyPair(vkey1=masked, vkey2=masked)"

    init {
        require(vkey1.matches(Regex("[0-9a-fA-F]{32}"))) { "VKey1 must be 32 hexadecimal characters" }
        require(vkey2.matches(Regex("[0-9a-fA-F]{16}"))) { "VKey2 must be 16 hexadecimal characters" }
    }

    fun uplinkSmsBody(feature: Int, postfix: String = SMS_POSTFIX): String {
        require(postfix.isNotBlank()) { "The original activation SMS postfix is required" }
        return "AC/$vkey1:$vkey2/$feature:$postfix"
    }

    companion object {
        const val SMS_POSTFIX = "MI"

        /** Mirrors the original 32-hex VKey1 and timestamp+8-hex VKey2 shapes. */
        fun generate(deviceIdHash: String, nowMillis: Long = System.currentTimeMillis()): XiaomiVKeyPair {
            require(deviceIdHash.isNotBlank())
            val vkey1 = legacyRandomHex(32, deviceIdHash)
            val vkey2 = String.format(Locale.US, "%08x", nowMillis / 1000L) + legacyRandomHex(8, deviceIdHash)
            return XiaomiVKeyPair(vkey1, vkey2)
        }
    }
}

internal data class XiaomiClientFidSignature(val fid: String, val signature: ByteArray) {
    override fun toString(): String = "XiaomiClientFidSignature(fid=masked, signature=masked)"
}

internal data class XiaomiFidNonce(val nonce: String, val signature: String) {
    override fun toString(): String = "XiaomiFidNonce(nonce=masked, signature=masked)"
}

/** Optional OEM FID integration. Return null/false when the device has no signing provider. */
internal interface XiaomiActivationFidProvider {
    fun canSign(): Boolean
    fun signClient(devInfoJson: ByteArray): XiaomiClientFidSignature?
    fun nativeNonce(): XiaomiFidNonce?
}

internal data class XiaomiActivationHttpRequest(
    val url: String,
    val form: Map<String, String>,
    val vkey1: String? = null,
) {
    override fun toString(): String = "XiaomiActivationHttpRequest(url=${runCatching { java.net.URL(url).host + java.net.URL(url).path }.getOrDefault("<invalid>")}, formKeys=${form.keys}, vkey1Present=${!vkey1.isNullOrBlank()})"
}

internal data class XiaomiCheckClientResult(val status: String)
internal data class XiaomiVerifiedPhone(val phone: String) {
    override fun toString(): String = "XiaomiVerifiedPhone(phone=masked)"
}

internal data class XiaomiSimUserCredentials(val userId: String, val passToken: String) {
    override fun toString(): String = "XiaomiSimUserCredentials(userId=masked, passToken=masked)"
}

internal open class XiaomiActivationException(
    val kind: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal class XiaomiPassportCaptchaRequiredException(
    val challenge: XiaomiPassportCaptchaChallenge,
) : XiaomiActivationException(
    "PASSPORT_CAPTCHA_REQUIRED",
    "Xiaomi Passport requires an interactive verification step (code=${challenge.businessCode}, type=${challenge.type})",
)

internal class XiaomiVKeyExpiredException : XiaomiActivationException("VKEY_EXPIRED", "The activation VKey has expired")
internal class XiaomiVKeyNotFoundException(val retryable: Boolean) : XiaomiActivationException("VKEY_NOT_FOUND", "The activation VKey was not found")

/** Original Xiaomi AES-CBC + SHA1/Base64 request wrapper. */
internal object XiaomiActivationCrypto {
    private val fixedIv = byteArrayOf(
        0x30, 0x31, 0x30, 0x32, 0x30, 0x33, 0x30, 0x34,
        0x30, 0x35, 0x30, 0x36, 0x30, 0x37, 0x30, 0x38,
    )

    fun hash16(value: String): String = JavaBase64.getUrlEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8)),
    ).take(16)

    fun vkey1ToBase64(vkey1: String): String {
        require(vkey1.matches(Regex("[0-9a-fA-F]{32}"))) { "VKey1 is not a 16-byte hexadecimal key" }
        val bytes = ByteArray(vkey1.length / 2) { index ->
            vkey1.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return JavaBase64.getEncoder().encodeToString(bytes)
    }

    fun encryptValue(value: String, keyBase64: String): String = JavaBase64.getEncoder().encodeToString(cipher(Cipher.ENCRYPT_MODE, keyBase64).doFinal(value.toByteArray(Charsets.UTF_8)))

    fun decryptResponse(value: String, keyBase64: String): String {
        val encrypted = JavaBase64.getDecoder().decode(value.trim())
        return String(cipher(Cipher.DECRYPT_MODE, keyBase64).doFinal(encrypted), Charsets.UTF_8)
    }

    fun encryptPostForm(
        url: String,
        parameters: Map<String, String>,
        vkey1: String,
        deviceIdHash: String,
    ): LinkedHashMap<String, String> {
        val keyBase64 = vkey1ToBase64(vkey1)
        val withNonce = LinkedHashMap(parameters)
        withNonce["nonce"] = requestNonce(deviceIdHash)
        val encrypted = LinkedHashMap<String, String>()
        for ((key, value) in withNonce) {
            encrypted[key] = if (key.startsWith("_")) value else encryptValue(value, keyBase64)
        }
        encrypted["signature"] = signature("POST", url, encrypted, keyBase64)
        return encrypted
    }

    fun signature(method: String, url: String, parameters: Map<String, String>, keyBase64: String): String {
        val path = URI(url).rawPath
        val parts = ArrayList<String>(parameters.size + 3)
        parts += method.uppercase(Locale.US)
        parts += path
        for ((key, value) in parameters.toSortedMap()) parts += "$key=$value"
        parts += keyBase64
        val digest = MessageDigest.getInstance("SHA-1").digest(parts.joinToString("&").toByteArray(Charsets.UTF_8))
        return JavaBase64.getEncoder().encodeToString(digest)
    }

    private fun cipher(mode: Int, keyBase64: String): Cipher {
        val key = JavaBase64.getDecoder().decode(keyBase64)
        require(key.size == 16) { "Activation AES key must be 16 bytes" }
        val ivSpec = IvParameterSpec(fixedIv)
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(key, "AES"), ivSpec)
        }
    }

    private fun requestNonce(deviceIdHash: String): String = legacyRandomHex(16, deviceIdHash)
}

/**
 * Recreates ActivateServerRequest's checkClient, verify and createOrUpdateUser
 * parameter construction. FID fields are omitted unless a real signer is
 * available; no synthetic FID or signature is generated.
 */
internal class XiaomiActivationRequestFactory(
    private val attempt: SimActivationAttempt,
    private val fidProvider: XiaomiActivationFidProvider? = null,
) {
    fun checkClient(): XiaomiActivationHttpRequest {
        val devInfo = buildDevInfo()
        val client = JSONObject().put("devInfo", devInfo)
        fidProvider?.takeIf { it.canSign() }?.let { provider ->
            runCatching { provider.signClient(devInfo.toString().toByteArray(Charsets.UTF_8)) }
                .getOrNull()?.let { signature ->
                    if (signature.fid.isNotBlank()) client.put("fid", signature.fid)
                    if (signature.signature.isNotEmpty()) {
                        client.put("sign", AndroidBase64.encodeToString(signature.signature, AndroidBase64.NO_WRAP))
                    }
                }
        }
        return XiaomiActivationHttpRequest(
            url = activationUrl("/pass/activation/checkClient"),
            form = linkedMapOf("client" to client.toString()),
        )
    }

    fun verify(vkeys: XiaomiVKeyPair): XiaomiActivationHttpRequest {
        val params = attempt.commonParams()
        params["vkey2"] = vkeys.vkey2
        params["_vkey2"] = vkeys.vkey2
        params["_mnc"] = XiaomiActivationCrypto.hash16(attempt.mccMnc)
        attempt.gatewayNumber?.takeIf(String::isNotBlank)?.let {
            params["_ucc"] = XiaomiActivationCrypto.hash16(it)
        }
        runCatching { fidProvider?.nativeNonce() }.getOrNull()?.let {
            if (it.nonce.isNotBlank() && it.signature.isNotBlank()) {
                params["fidNonce"] = it.nonce
                params["fidNonceSign"] = it.signature
            }
        }
        return XiaomiActivationHttpRequest(
            url = activationUrl("/pass/activation/safe/verify"),
            form = params,
            vkey1 = vkeys.vkey1,
        )
    }

    fun createOrUpdateUser(vkeys: XiaomiVKeyPair, phone: String): XiaomiActivationHttpRequest {
        require(phone.isNotBlank()) { "The phone must come from a successful verify response" }
        val params = attempt.commonParams()
        params["userType"] = "MXPH"
        params["vkey2"] = vkeys.vkey2
        params["phone"] = phone
        params["_vkey2"] = vkeys.vkey2
        return XiaomiActivationHttpRequest(
            url = activationUrl("/pass/activation/safe/createOrUpdateUser"),
            form = params,
            vkey1 = vkeys.vkey1,
        )
    }

    private fun activationUrl(path: String): String {
        val scheme = if (path.endsWith("/checkClient") || Build.VERSION.SDK_INT > 26) "https" else "http"
        return "$scheme://${attempt.activationHost}$path"
    }

    private fun buildDevInfo(): JSONObject {
        val stat = StatFs(Environment.getRootDirectory().path)
        val blockSize = stat.blockSizeLong
        val blockCount = stat.blockCountLong
        val freeBlocks = stat.freeBlocksLong
        val info = JSONObject()
            .put("blockSize", blockSize)
            .put("blockCount", blockCount)
            .put("availCount", stat.availableBlocksLong)
            .put("freeCount", freeBlocks)
            .put("sysSize", ((blockCount - freeBlocks) * blockSize) / 1024L)
            .put("fingerprint", Build.FINGERPRINT)
            .put("device", Build.DEVICE)
            .put("incremental", Build.VERSION.INCREMENTAL)
            .put("lockstate", systemProperty("ro.secureboot.lockstate"))
            .put("nonce", clientNonce())
        return info
    }

    private fun clientNonce(): String {
        val bytes = java.nio.ByteBuffer.allocate(12)
            .putLong(SecureRandom().nextLong())
            .putInt((System.currentTimeMillis() / 60_000L).toInt())
            .array()
        return AndroidBase64.encodeToString(bytes, AndroidBase64.URL_SAFE or AndroidBase64.NO_WRAP)
    }

    private fun systemProperty(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as String
    }.getOrDefault("")
}

internal data class XiaomiActivationConfiguration(
    private val activationHosts: Map<String, String>,
    private val smsGateways: Map<String, List<String>>,
) {
    fun activationHostFor(mccMnc: String): String = matchingValue(activationHosts, mccMnc)
        ?: activationHosts["default"]
        ?: "ac.account.xiaomi.com"

    fun smsGatewaysFor(mccMnc: String): List<String> = matchingList(smsGateways, mccMnc)
        ?: smsGateways["default"]
        ?: emptyList()

    fun createAttempt(
        context: Context,
        slotIndex: Int,
        subscriptionId: Int,
        simIdHash: String,
        deviceIdHash: String,
        mccMnc: String,
    ): SimActivationAttempt {
        require(slotIndex in 0..1)
        val t2Key = MiMessageConfig.simKey(slotIndex, "activation_t2")
        val existingT2 = MiMessageCredentialStore.get(context, t2Key)
        val t2 = existingT2 ?: randomHex(8).also { generated ->
            check(MiMessageCredentialStore.put(context, t2Key, generated)) {
                "Could not persist the per-SIM activation _t2 value"
            }
        }
        return SimActivationAttempt(
            slotIndex = slotIndex,
            subscriptionId = subscriptionId,
            simIdHash = simIdHash,
            deviceIdHash = deviceIdHash,
            mccMnc = mccMnc,
            activationHost = activationHostFor(mccMnc),
            gatewayNumber = smsGatewaysFor(mccMnc).firstOrNull(),
            t2 = t2,
        )
    }

    private fun matchingValue(values: Map<String, String>, mccMnc: String): String? = values.entries.firstOrNull { (pattern, _) -> pattern != "default" && matches(pattern, mccMnc) }?.value

    private fun matchingList(values: Map<String, List<String>>, mccMnc: String): List<String>? = values.entries.firstOrNull { (pattern, _) -> pattern != "default" && matches(pattern, mccMnc) }?.value

    private fun matches(pattern: String, value: String): Boolean = runCatching { Pattern.matches(pattern, value) }.getOrDefault(false)

    companion object {
        fun parse(data: JSONObject): XiaomiActivationConfiguration {
            val hostObject = data.optJSONObject("ac.url") ?: JSONObject()
            val gatewayObject = data.optJSONObject("mo") ?: JSONObject()
            val hosts = linkedMapOf<String, String>()
            val gateways = linkedMapOf<String, List<String>>()
            val hostKeys = hostObject.keys()
            while (hostKeys.hasNext()) {
                val key = hostKeys.next()
                hosts[key] = hostObject.optString(key)
            }
            val gatewayKeys = gatewayObject.keys()
            while (gatewayKeys.hasNext()) {
                val key = gatewayKeys.next()
                val array = gatewayObject.optJSONArray(key) ?: continue
                gateways[key] = (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf(String::isNotBlank)
                }
            }
            return XiaomiActivationConfiguration(hosts, gateways)
        }
    }
}

/** Explicit network client. It never retries createOrUpdateUser automatically. */
internal class XiaomiSimActivationClient(
    private val factory: XiaomiActivationRequestFactory,
    private val deviceIdHash: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    fun checkClient(): XiaomiCheckClientResult {
        val request = factory.checkClient()
        val data = execute(request, encrypted = false)
        val status = data.optString("status").uppercase(Locale.US)
        if (status != "OK") throw XiaomiActivationException("ILLEGAL_CLIENT", "checkClient rejected the client")
        return XiaomiCheckClientResult(status)
    }

    fun verify(vkeys: XiaomiVKeyPair, retryVKeyNotFound: Boolean = true): XiaomiVerifiedPhone {
        val request = factory.verify(vkeys)
        val delays = longArrayOf(2_000, 5_000, 10_000, 20_000, 20_000, 20_000, 20_000)
        var attemptIndex = 0
        while (true) {
            val data = execute(request, encrypted = true)
            when (data.optString("status").uppercase(Locale.US)) {
                "OK" -> {
                    val phone = data.optString("phone")
                    if (phone.isBlank()) throw XiaomiActivationException("INVALID_RESPONSE", "verify returned no phone")
                    return XiaomiVerifiedPhone(phone)
                }

                "EXPIRATION" -> throw XiaomiVKeyExpiredException()

                "VKEY_NOT_FOUND" -> {
                    val mayRetry = retryVKeyNotFound && attemptIndex < delays.size && factoryHasUcc(request)
                    if (!mayRetry) throw XiaomiVKeyNotFoundException(retryable = false)
                    try {
                        Thread.sleep(delays[attemptIndex++])
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw XiaomiActivationException("INTERRUPTED", "verify retry was interrupted", interrupted)
                    }
                }

                else -> throw XiaomiActivationException(
                    "VERIFY_REJECTED",
                    "verify returned status ${data.optString("status", "missing")}",
                )
            }
        }
    }

    fun createOrUpdateUser(vkeys: XiaomiVKeyPair, verifiedPhone: XiaomiVerifiedPhone): XiaomiSimUserCredentials {
        val request = factory.createOrUpdateUser(vkeys, verifiedPhone.phone)
        val data = execute(request, encrypted = true)
        when (data.optString("status").uppercase(Locale.US)) {
            "OK" -> {
                val userId = data.optString("userId")
                val passToken = data.optString("passToken")
                if (userId.isBlank() || passToken.isBlank()) {
                    throw XiaomiActivationException("INVALID_RESPONSE", "createOrUpdateUser omitted userId/passToken")
                }
                return XiaomiSimUserCredentials(userId, passToken)
            }

            "EXPIRATION" -> throw XiaomiVKeyExpiredException()

            else -> throw XiaomiActivationException(
                "CREATE_USER_REJECTED",
                "createOrUpdateUser returned status ${data.optString("status", "missing")}",
            )
        }
    }

    private fun factoryHasUcc(request: XiaomiActivationHttpRequest): Boolean = request.form.containsKey("_ucc")

    private fun execute(request: XiaomiActivationHttpRequest, encrypted: Boolean): JSONObject {
        val form = if (encrypted) {
            val key = request.vkey1 ?: throw XiaomiActivationException("MISSING_VKEY1", "Encrypted request requires VKey1")
            XiaomiActivationCrypto.encryptPostForm(request.url, request.form, key, deviceIdHash)
        } else {
            LinkedHashMap(request.form)
        }
        val raw = postForm(request.url, form)
        val decoded = if (encrypted) {
            val key = request.vkey1 ?: throw XiaomiActivationException("MISSING_VKEY1", "Encrypted response requires VKey1")
            XiaomiActivationCrypto.decryptResponse(raw, XiaomiActivationCrypto.vkey1ToBase64(key))
        } else {
            raw
        }
        val envelope = runCatching { JSONObject(decoded) }.getOrElse {
            throw XiaomiActivationException("INVALID_ENVELOPE", "Activation server returned invalid JSON", it)
        }
        val code = envelope.optInt("code", Int.MIN_VALUE)
        if (code != 0) throw XiaomiActivationException("ENVELOPE_ERROR", "Activation server envelope code=$code")
        return envelope.optJSONObject("data")
            ?: throw XiaomiActivationException("INVALID_ENVELOPE", "Activation server response has no data object")
    }

    private fun postForm(url: String, parameters: Map<String, String>): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doInput = true
            doOutput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        try {
            val body = encodeForm(parameters)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status != 200) {
                throw XiaomiActivationException("HTTP_ERROR", "Activation endpoint returned HTTP $status")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: XiaomiActivationException) {
            throw error
        } catch (error: Exception) {
            throw XiaomiActivationException("NETWORK_ERROR", "Activation endpoint request failed", error)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun fetchConfiguration(
            connectTimeoutMs: Int = 15_000,
            readTimeoutMs: Int = 30_000,
            sdkInt: Int = Build.VERSION.SDK_INT,
        ): XiaomiActivationConfiguration {
            val scheme = if (sdkInt > 26) "https" else "http"
            val url = URL("$scheme://api.account.xiaomi.com/pass/configuration")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                instanceFollowRedirects = false
            }
            try {
                val status = connection.responseCode
                if (status != 200) throw XiaomiActivationException("CONFIG_HTTP_ERROR", "Activation configuration returned HTTP $status")
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val envelope = JSONObject(body)
                val code = envelope.optInt("code", Int.MIN_VALUE)
                if (code != 0) throw XiaomiActivationException("CONFIG_ERROR", "Activation configuration code=$code")
                val data = envelope.optJSONObject("data")
                    ?: throw XiaomiActivationException("CONFIG_ERROR", "Activation configuration has no data")
                return XiaomiActivationConfiguration.parse(data)
            } catch (error: XiaomiActivationException) {
                throw error
            } catch (error: Exception) {
                throw XiaomiActivationException("CONFIG_NETWORK_ERROR", "Could not load activation configuration", error)
            } finally {
                connection.disconnect()
            }
        }

        private fun encodeForm(parameters: Map<String, String>): String = parameters.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
    }
}

private fun legacyRandomHex(length: Int, deviceId: String): String {
    val random = SecureRandom()
    val seed = buildString {
        repeat(3) { append(random.nextInt()) }
        append(deviceId)
    }
    return MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(length)
}

private fun randomHex(byteCount: Int): String {
    val bytes = ByteArray(byteCount).also(SecureRandom()::nextBytes)
    return bytes.joinToString("") { "%02X".format(it) }
}
