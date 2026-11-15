package io.github.xiaotong6666.maihoku.mimessage.account

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Base64
import io.github.xiaotong6666.maihoku.BuildConfig
import org.json.JSONObject
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

internal data class XiaomiMixinServiceCredentials(
    /** MID is the SIM activation userId, not Binder's user_cid. */
    val mid: String,
    val userToken: String,
    val userSecurity: String,
    val userCid: String,
) {
    override fun toString(): String = "XiaomiMixinServiceCredentials(mid=masked, userToken=masked, userSecurity=masked, userCid=masked)"
}

/** A request at the same abstraction level as the original AccountSDK SimpleRequest. */
internal data class XiaomiPassportHttpRequest(
    val url: String,
    val method: String,
    val form: Map<String, String>,
    val cookies: Map<String, String>,
    val userAgent: String,
) {
    override fun toString(): String = "XiaomiPassportHttpRequest(method=$method, url=${runCatching { URL(url).host + URL(url).path }.getOrDefault("<invalid>")}, formKeys=${form.keys}, cookieNames=${cookies.keys}, userAgentPresent=${userAgent.isNotBlank()})"
}

internal data class XiaomiPassportHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>>,
) {
    override fun toString(): String = "XiaomiPassportHttpResponse(status=$status, bodyLength=${body.length}, headerNames=${headers.keys})"

    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.firstOrNull()

    /** Parse all Set-Cookie headers, including multiple cookies on one response. */
    fun cookie(name: String): String? {
        val values = headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) || it.key.equals("Set-Cookie2", ignoreCase = true) }
            .flatMap { it.value }
        for (value in values) {
            val parsed = runCatching { HttpCookie.parse(value) }.getOrDefault(emptyList())
            parsed.firstOrNull { it.name == name }?.let { return it.value }
            // Some OEM gateways return a non-RFC-compliant cookie. Keep a narrow fallback.
            val pair = value.substringBefore(';').split('=', limit = 2)
            if (pair.size == 2 && pair[0].trim() == name) return pair[1].trim()
        }
        return null
    }

    fun cookieNames(): Set<String> = headers.entries
        .filter { it.key.equals("Set-Cookie", ignoreCase = true) || it.key.equals("Set-Cookie2", ignoreCase = true) }
        .flatMap { it.value }
        .mapNotNull { value ->
            runCatching { HttpCookie.parse(value).firstOrNull()?.name }.getOrNull()
                ?: value.substringBefore(';').substringBefore('=').trim().takeIf(String::isNotBlank)
        }
        .toSet()

    fun setCookieHeaders(): List<String> = headers.entries
        .filter { it.key.equals("Set-Cookie", ignoreCase = true) || it.key.equals("Set-Cookie2", ignoreCase = true) }
        .flatMap { it.value }

    fun jsonFieldNames(): Set<String> {
        val source = body.removePrefix("&&&START&&&")
        val json = runCatching { JSONObject(source) }.getOrNull() ?: return emptySet()
        val names = json.keys()
        return buildSet { while (names.hasNext()) add(names.next()) }
    }

    fun businessCode(): Int? {
        val source = body.removePrefix("&&&START&&&")
        val json = runCatching { JSONObject(source) }.getOrNull() ?: return null
        return if (json.has("code")) json.optInt("code") else null
    }
}

/** Safe protocol telemetry. It deliberately contains field names/status only, never values. */
internal data class XiaomiPassportTraceEvent(
    val stage: String,
    val phase: String,
    val method: String? = null,
    val host: String? = null,
    val path: String? = null,
    val httpStatus: Int? = null,
    val businessCode: Int? = null,
    val responseFields: Set<String> = emptySet(),
    val cookieNames: Set<String> = emptySet(),
    val failureKind: String? = null,
    val responseLength: Int? = null,
) {
    override fun toString(): String = "XiaomiPassportTraceEvent(stage=$stage, phase=$phase, method=$method, host=$host, path=$path, httpStatus=$httpStatus, businessCode=$businessCode, responseFields=$responseFields, cookieNames=$cookieNames, failureKind=$failureKind, responseLength=$responseLength)"
}

internal fun interface XiaomiPassportHttpTransport {
    fun execute(request: XiaomiPassportHttpRequest): XiaomiPassportHttpResponse
}

/** Production transport. Redirects are deliberately not followed: AccountSDK reads 302 cookies. */
internal class XiaomiUrlConnectionPassportTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : XiaomiPassportHttpTransport {
    override fun execute(request: XiaomiPassportHttpRequest): XiaomiPassportHttpResponse {
        XiaomiPassportProtocol.requireOfficialHttpsUrl(request.url)
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doInput = true
            doOutput = request.method == "POST"
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", request.userAgent)
            setRequestProperty("Cookie", request.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }

        try {
            if (request.method == "POST" && request.form.isNotEmpty()) {
                val body = encodeForm(request.form)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            // Preserve error bodies and Set-Cookie headers for safe diagnostics;
            // the protocol parser decides whether the status is acceptable.
            val stream = if (status >= 400) connection.errorStream else runCatching { connection.inputStream }.getOrNull()
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val headers = connection.headerFields.entries
                .filter { it.key != null && it.value != null }
                .associate { it.key!! to it.value!!.toList() }
            return XiaomiPassportHttpResponse(status, body, headers)
        } catch (error: XiaomiActivationException) {
            throw error
        } catch (error: Exception) {
            throw XiaomiActivationException("PASSPORT_NETWORK_ERROR", "Xiaomi Passport request failed", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun encodeForm(values: Map<String, String>): String = values.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
}

/** Inputs recovered from ActivateServiceBinder → AccountHttpClient → XMPassport. */
internal data class XiaomiSimServiceTokenParameters(
    val simUserId: String,
    val simPassToken: String,
    val serviceId: String,
    /** The hashed MIUI/cloud device ID returned by ActivateService's SysInterface. */
    val activationDeviceIdHash: String,
    /** AccountSDK's separately obtained hashed TelephonyManager device ID. */
    val stsDeviceIdHash: String?,
    val oaid: String?,
    val userSpaceId: String?,
    val locale: String,
    val userAgent: String,
) {
    override fun toString(): String = "XiaomiSimServiceTokenParameters(serviceId=$serviceId, locale=$locale, simUserId=masked, simPassToken=masked, activationDeviceIdHash=masked, stsDeviceIdHashPresent=${stsDeviceIdHash != null}, oaidPresent=${!oaid.isNullOrBlank()}, userSpaceIdPresent=${!userSpaceId.isNullOrBlank()})"

    init {
        require(simUserId.isNotBlank()) { "SIM userId is required" }
        require(simPassToken.isNotBlank()) { "SIM passToken is required" }
        require(serviceId.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid serviceId" }
        require(activationDeviceIdHash.length == 16) { "Activation device ID hash must contain 16 characters" }
        require(stsDeviceIdHash == null || stsDeviceIdHash.length == 16) {
            "STS device ID hash must contain 16 characters when available"
        }
    }
}

internal data class XiaomiPassportLoginState(
    val security: String,
    val psecurity: String?,
    val nonce: Long,
    val encryptedUserId: String,
    val autoLoginUrl: String,
) {
    override fun toString(): String = "XiaomiPassportLoginState(security=masked, psecurity=masked, noncePresent=${nonce > 0}, encryptedUserId=masked, autoLoginUrlHost=${runCatching { URL(autoLoginUrl).host }.getOrDefault("<invalid>")})"
}

/** Pure Passport/STS request and response logic, independent of Android credential storage. */
internal object XiaomiPassportProtocol {
    // SimActivateService calls XMPassport.h() → e.g() with PassTokenLoginParams;
    // d.Q resolves to /pass/serviceLogin (not serviceLoginAuth2).
    const val ACCOUNT_LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin"
    const val SDK_VERSION = "accountsdk-2020.01.09"

    fun buildPassTokenLoginRequest(
        parameters: XiaomiSimServiceTokenParameters,
        fidNonce: XiaomiFidNonce? = null,
        verificationResult: XiaomiPassportVerificationResult? = null,
        responseSetCookies: List<String> = emptyList(),
    ): XiaomiPassportHttpRequest {
        // SimActivateService → AccountHttpClient → XMPassport uses the
        // PassTokenLoginParams GET path: sid/_json/_locale are URL parameters;
        // userId/passToken/deviceId/pass_o/userSpaceId are cookies.
        val query = linkedMapOf(
            "sid" to parameters.serviceId,
            "_json" to "true",
            "_locale" to parameters.locale,
        )
        val cookies = linkedMapOf<String, String>()
        responseSetCookies.forEach { header ->
            runCatching { HttpCookie.parse(header) }.getOrDefault(emptyList()).forEach { cookie ->
                if (cookie.name.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && cookie.value.isNotBlank()) {
                    cookies[cookie.name] = cookie.value
                }
            }
        }
        // The SIM activation credentials and device identity always win over
        // cookies received with the challenge response.
        cookies["userId"] = parameters.simUserId
        cookies["passToken"] = parameters.simPassToken
        cookies["deviceId"] = parameters.activationDeviceIdHash
        // n0.o.j() appends this cookie to every AccountSDK HTTPS request.
        cookies["sdkVersion"] = SDK_VERSION
        // Experimental extension only: SimActivateService's byPassToken
        // request does not expose CAPTCHA fields. The original SDK converts
        // NeedCaptchaException into "Unexpected NeedCaptchaException".
        // Keep these values opt-in (only supplied after an explicit UI action)
        // and do not treat this as proven OEM-compatible behavior.
        verificationResult?.captchaIck?.takeIf(String::isNotBlank)?.let { cookies["ick"] = it }
        parameters.oaid?.takeIf(String::isNotBlank)?.let { cookies["pass_o"] = it }
        parameters.userSpaceId?.takeIf(String::isNotBlank)?.let { cookies["userSpaceId"] = it }
        fidNonce?.takeIf { it.nonce.isNotBlank() && it.signature.isNotBlank() }?.let {
            cookies["fidNonce"] = it.nonce
            cookies["fidNonceSign"] = it.signature
        }
        verificationResult?.vToken?.takeIf(String::isNotBlank)?.let { query["vToken"] = it }
        verificationResult?.captchaCode?.takeIf(String::isNotBlank)?.let { query["captCode"] = it }
        return XiaomiPassportHttpRequest(
            url = appendQuery(ACCOUNT_LOGIN_URL, query),
            method = "GET",
            form = emptyMap(),
            cookies = cookies,
            userAgent = parameters.userAgent,
        )
    }

    fun parsePassTokenLoginResponse(
        response: XiaomiPassportHttpResponse,
        expectedUserId: String,
        userAgent: String = "",
    ): XiaomiPassportLoginState {
        if (response.status != HttpURLConnection.HTTP_OK && response.status != HttpURLConnection.HTTP_MOVED_TEMP) {
            throw XiaomiActivationException("PASSPORT_HTTP_ERROR", "Xiaomi Passport login returned HTTP ${response.status}")
        }
        val jsonText = response.body.removePrefix("&&&START&&&")
        val json = runCatching { JSONObject(jsonText) }.getOrElse {
            throw XiaomiActivationException("PASSPORT_INVALID_RESPONSE", "Xiaomi Passport login returned invalid JSON", it)
        }
        val code = json.optInt("code", Int.MIN_VALUE)
        if (code != 0) {
            if (code == 87001 || code == 70016) {
                // XMPassport.processLoginContent(): 87001 is NeedCaptchaException;
                // 70016 is InvalidCredentialException(captcha=true) with MetaLoginData.
                val type = if (code == 70016) {
                    "captcha"
                } else {
                    json.optString("type")
                        .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                        ?: "manMachine"
                }
                val captchaUrl = json.optString("captchaUrl")
                    .takeIf { it.isNotBlank() && it != "null" }
                if (captchaUrl != null) {
                    val absoluteUrl = if (captchaUrl.startsWith("/")) {
                        "https://account.xiaomi.com$captchaUrl"
                    } else {
                        captchaUrl
                    }
                    XiaomiPassportVerificationUrls.requireOfficial(absoluteUrl)
                }
                throw XiaomiPassportCaptchaRequiredException(
                    XiaomiPassportCaptchaChallenge(
                        businessCode = code,
                        type = type,
                        captchaUrl = captchaUrl?.let {
                            if (it.startsWith("/")) "https://account.xiaomi.com$it" else it
                        },
                        responseSetCookies = response.setCookieHeaders(),
                        userAgent = userAgent,
                    ),
                )
            }
            val kind = when (code) {
                81003 -> "PASSPORT_VERIFICATION_REQUIRED"
                22009 -> "PASSPORT_PACKAGE_DENIED"
                70002 -> "PASSPORT_ACCOUNT_REJECTED"
                20003 -> "PASSPORT_REAUTH_REQUIRED"
                else -> "PASSPORT_LOGIN_REJECTED"
            }
            throw XiaomiActivationException(kind, "Xiaomi Passport rejected SIM passToken login (code=$code)")
        }

        // AccountSDK's normal HTTPS path (z4=false) reads these from cookies;
        // its optional CA-request path (z4=true) reads the same values from JSON.
        // Accept both shapes because the OEM SDK selects the transport at runtime.
        val responseUserId = response.cookie("userId") ?: json.optString("userId")
        val responsePassToken = response.cookie("passToken") ?: json.optString("passToken")
        val encryptedUserId = response.cookie("cUserId") ?: json.optString("cUserId")
        if (responseUserId.isNullOrBlank() || responsePassToken.isNullOrBlank() || encryptedUserId.isNullOrBlank()) {
            throw XiaomiActivationException("PASSPORT_MISSING_COOKIES", "Passport response omitted required account cookies")
        }
        if (expectedUserId.isBlank()) {
            throw XiaomiActivationException("PASSPORT_MISSING_USER", "SIM userId is empty")
        }

        val extensionPragma = response.header("extension-pragma")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val security = json.optString("ssecurity").takeIf(String::isNotBlank)
            ?: extensionPragma?.optString("ssecurity")?.takeIf(String::isNotBlank)
            ?: throw XiaomiActivationException("PASSPORT_MISSING_SECURITY", "Passport response omitted ssecurity")
        val nonceFromJson = if (json.has("nonce")) json.optLong("nonce", 0L) else 0L
        val nonce = if (nonceFromJson > 0L) nonceFromJson else extensionPragma?.optLong("nonce", 0L) ?: 0L
        if (nonce <= 0L) throw XiaomiActivationException("PASSPORT_MISSING_NONCE", "Passport response omitted a valid nonce")
        // AccountInfo carries psecurity as an optional field; Mixin STS signs
        // with ssecurity only, so absence must not reject an otherwise valid login.
        val psecurity = json.optString("psecurity").takeIf(String::isNotBlank)
            ?: extensionPragma?.optString("psecurity")?.takeIf(String::isNotBlank)
        val autoLoginUrl = json.optString("location").takeIf(String::isNotBlank)
            ?: throw XiaomiActivationException("PASSPORT_MISSING_STS_URL", "Passport response omitted STS location")
        requireOfficialHttpsUrl(autoLoginUrl)

        val securityStatus = json.optInt("securityStatus", 0)
        val disableHotfix = json.optBoolean("disableHotfixMiui73508", false)
        if (securityStatus != 0 && !disableHotfix) {
            // Original AccountSDK routes this to notificationUrl / NeedNotificationException.
            // Surface it to the owner; do not automate or bypass the verification.
            throw XiaomiActivationException(
                "NEED_ACCOUNT_VERIFICATION",
                "Xiaomi Passport requires an additional account verification step (securityStatus=$securityStatus)",
            )
        }

        return XiaomiPassportLoginState(
            security = security,
            psecurity = psecurity,
            nonce = nonce,
            encryptedUserId = encryptedUserId,
            autoLoginUrl = autoLoginUrl,
        )
    }

    fun buildStsRequest(
        parameters: XiaomiSimServiceTokenParameters,
        login: XiaomiPassportLoginState,
        fidNonce: XiaomiFidNonce? = null,
    ): XiaomiPassportHttpRequest {
        val clientSign = createClientSign(login.nonce, login.security)
        val url = appendQuery(
            login.autoLoginUrl,
            linkedMapOf("clientSign" to clientSign, "_userIdNeedEncrypt" to "true"),
        )
        // AccountSDK n0.p.a(map) uses its own TelephonyManager-derived hashed
        // device ID for STS. If that runtime-only ID is unavailable, the original
        // EasyMap serializes the null entry as the literal string "null".
        val cookies = linkedMapOf(
            "deviceId" to (parameters.stsDeviceIdHash ?: "null"),
            "sdkVersion" to SDK_VERSION,
        )
        parameters.userSpaceId?.takeIf(String::isNotBlank)?.let { cookies["userSpaceId"] = it }
        fidNonce?.takeIf { it.nonce.isNotBlank() && it.signature.isNotBlank() }?.let {
            cookies["fidNonce"] = it.nonce
            cookies["fidNonceSign"] = it.signature
        }
        return XiaomiPassportHttpRequest(
            url = url,
            method = "GET",
            form = emptyMap(),
            cookies = cookies,
            userAgent = parameters.userAgent,
        )
    }

    fun parseStsResponse(response: XiaomiPassportHttpResponse, serviceId: String): String {
        if (response.status != HttpURLConnection.HTTP_OK && response.status != HttpURLConnection.HTTP_MOVED_TEMP) {
            throw XiaomiActivationException("STS_HTTP_ERROR", "Xiaomi STS endpoint returned HTTP ${response.status}")
        }
        val serviceToken = response.cookie("${serviceId}_serviceToken")
            ?.takeIf(String::isNotBlank)
            ?: response.cookie("serviceToken")?.takeIf(String::isNotBlank)
            ?: throw XiaomiActivationException("STS_TOKEN_MISSING", "Xiaomi STS response contained no service token cookie")
        return serviceToken
    }

    fun createClientSign(nonce: Long, security: String): String {
        require(nonce > 0L) { "Passport nonce must be positive" }
        require(security.isNotBlank()) { "Passport security is required" }
        val input = "nonce=$nonce&$security"
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(digest)
    }

    fun requireOfficialHttpsUrl(value: String) {
        val uri = runCatching { URL(value).toURI() }.getOrElse {
            throw XiaomiActivationException("PASSPORT_INVALID_URL", "Xiaomi returned an invalid authentication URL", it)
        }
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        val official = host == "xiaomi.com" || host.endsWith(".xiaomi.com") ||
            host == "mi.com" || host.endsWith(".mi.com")
        if (uri.scheme != "https" || !official) {
            throw XiaomiActivationException(
                "PASSPORT_UNTRUSTED_URL",
                "Xiaomi returned a non-official or non-HTTPS authentication URL",
            )
        }
    }

    private fun appendQuery(url: String, parameters: Map<String, String>): String {
        val separator = if (url.contains('?')) '&' else '?'
        val query = parameters.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return "$url$separator$query"
    }
}

/** AccountSDK m0.d → m0.g → TelephonyManager.getDeviceId() → m0.c.a(). */
internal object XiaomiAccountSdkDeviceIdProvider {
    fun hashRuntimeDeviceId(rawDeviceId: String): String {
        require(rawDeviceId.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-1").digest(rawDeviceId.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getUrlEncoder().encodeToString(digest).take(16)
    }

    @Suppress("DEPRECATION")
    fun resolve(context: Context): String? = runCatching {
        val telephony = context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        val rawDeviceId = telephony.deviceId?.takeIf(String::isNotBlank) ?: return null
        hashRuntimeDeviceId(rawDeviceId)
    }.getOrNull()
}

/** Executes the exact two-request AccountSDK passToken → STS flow. */
internal class XiaomiSimServiceTokenFlow(
    private val parameters: XiaomiSimServiceTokenParameters,
    private val transport: XiaomiPassportHttpTransport,
    private val fidNonceProvider: () -> XiaomiFidNonce? = { null },
    private val trace: (XiaomiPassportTraceEvent) -> Unit = {},
) {
    fun execute(
        verificationResult: XiaomiPassportVerificationResult? = null,
        responseSetCookies: List<String> = emptyList(),
    ): XiaomiMixinServiceCredentials {
        val loginRequest = XiaomiPassportProtocol.buildPassTokenLoginRequest(
            parameters,
            fidNonceProvider(),
            verificationResult,
            responseSetCookies,
        )
        val loginResponse = executeTraced("passport", loginRequest)
        val login = try {
            XiaomiPassportProtocol.parsePassTokenLoginResponse(
                loginResponse,
                parameters.simUserId,
                loginRequest.userAgent,
            )
        } catch (error: XiaomiActivationException) {
            emit(
                XiaomiPassportTraceEvent(
                    "passport",
                    "parse_failed",
                    httpStatus = loginResponse.status,
                    businessCode = loginResponse.businessCode(),
                    responseFields = loginResponse.jsonFieldNames(),
                    cookieNames = loginResponse.cookieNames(),
                    failureKind = error.kind,
                    responseLength = loginResponse.body.length,
                ),
            )
            throw error
        }

        val stsRequest = XiaomiPassportProtocol.buildStsRequest(parameters, login, fidNonceProvider())
        val stsResponse = executeTraced("sts", stsRequest)
        val serviceToken = try {
            XiaomiPassportProtocol.parseStsResponse(stsResponse, parameters.serviceId)
        } catch (error: XiaomiActivationException) {
            emit(
                XiaomiPassportTraceEvent(
                    "sts",
                    "parse_failed",
                    httpStatus = stsResponse.status,
                    businessCode = stsResponse.businessCode(),
                    responseFields = stsResponse.jsonFieldNames(),
                    cookieNames = stsResponse.cookieNames(),
                    failureKind = error.kind,
                    responseLength = stsResponse.body.length,
                ),
            )
            throw error
        }

        return XiaomiMixinServiceCredentials(
            mid = parameters.simUserId,
            userToken = serviceToken,
            userSecurity = login.security,
            userCid = login.encryptedUserId,
        )
    }

    private fun executeTraced(stage: String, request: XiaomiPassportHttpRequest): XiaomiPassportHttpResponse {
        val url = runCatching { URL(request.url) }.getOrNull()
        emit(XiaomiPassportTraceEvent(stage, "request", request.method, url?.host, url?.path))
        val response = try {
            transport.execute(request)
        } catch (error: XiaomiActivationException) {
            emit(
                XiaomiPassportTraceEvent(
                    stage,
                    "transport_failed",
                    request.method,
                    url?.host,
                    url?.path,
                    failureKind = error.kind,
                ),
            )
            throw error
        }
        emit(
            XiaomiPassportTraceEvent(
                stage, "response", request.method, url?.host, url?.path,
                httpStatus = response.status, businessCode = response.businessCode(),
                responseFields = response.jsonFieldNames(), cookieNames = response.cookieNames(),
                responseLength = response.body.length,
            ),
        )
        return response
    }

    private fun emit(event: XiaomiPassportTraceEvent) {
        runCatching { trace(event) }
    }
}

/**
 * Context adapter for the Passport/STS protocol flow. All requests are explicit;
 * no network activity occurs until getSimAuthToken() is called.
 */
internal class XiaomiSimServiceTokenClient(
    context: Context,
    private val simUserId: String,
    private val simPassToken: String,
    private val activationDeviceIdHash: String,
    private val serviceId: String = "mixin",
    private val oaid: String? = null,
    private val stsDeviceIdHash: String? = null,
    private val userSpaceId: String? = null,
    private val fidProvider: XiaomiActivationFidProvider? = null,
    private val transport: XiaomiPassportHttpTransport? = null,
    private val trace: (XiaomiPassportTraceEvent) -> Unit = {},
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    private val appContext = context.applicationContext

    init {
        require(simUserId.isNotBlank()) { "SIM userId is required" }
        require(simPassToken.isNotBlank()) { "SIM passToken is required" }
        require(activationDeviceIdHash.length == 16) { "Activation device ID hash must contain 16 characters" }
        require(serviceId.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid serviceId" }
    }

    /** Network operation: call from a worker thread, never from the main thread. */
    fun getSimAuthToken(
        verificationResult: XiaomiPassportVerificationResult? = null,
        responseSetCookies: List<String> = emptyList(),
    ): XiaomiMixinServiceCredentials {
        val parameters = XiaomiSimServiceTokenParameters(
            simUserId = simUserId,
            simPassToken = simPassToken,
            serviceId = serviceId,
            activationDeviceIdHash = activationDeviceIdHash,
            stsDeviceIdHash = stsDeviceIdHash ?: XiaomiAccountSdkDeviceIdProvider.resolve(appContext),
            oaid = oaid,
            userSpaceId = userSpaceId,
            locale = accountSdkLocale(Locale.getDefault()),
            userAgent = accountSdkUserAgent(),
        )
        val effectiveTransport = transport ?: XiaomiUrlConnectionPassportTransport(connectTimeoutMs, readTimeoutMs)
        return XiaomiSimServiceTokenFlow(
            parameters = parameters,
            transport = effectiveTransport,
            fidNonceProvider = { runCatching { fidProvider?.nativeNonce() }.getOrNull() },
            trace = trace,
        ).execute(verificationResult, responseSetCookies)
    }

    private fun accountSdkUserAgent(): String {
        val pieces = ArrayList<String>()
        System.getProperty("http.agent")?.takeIf(String::isNotBlank)?.let(pieces::add)
        val modulePackageName = BuildConfig.APPLICATION_ID
        val packageParts = modulePackageName.split('.').filter(String::isNotBlank)
        val appName = if (packageParts.size > 2) packageParts.takeLast(2).joinToString(".") else modulePackageName
        pieces += "APP/$appName"
        runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(modulePackageName, 0).versionCode
        }.getOrNull()?.let { pieces += "APPV/$it" }
        val marketName = systemProperty("ro.product.marketname").ifBlank { Build.MODEL }.take(30)
        if (marketName.isNotBlank()) {
            pieces += "MK/${Base64.encodeToString(marketName.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}"
        }
        return pieces.joinToString(" ")
    }

    private fun systemProperty(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as String
    }.getOrDefault("")

    private fun accountSdkLocale(locale: Locale): String = if (locale.country.isNullOrEmpty()) locale.language else "${locale.language}_${locale.country}"
}
