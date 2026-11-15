package io.github.xiaotong6666.maihoku.features.mimessage.account

import android.content.Context
import android.content.Intent
import io.github.xiaotong6666.maihoku.BuildConfig
import io.github.xiaotong6666.maihoku.features.mimessage.ui.PassportCaptchaActivity
import org.json.JSONArray
import org.json.JSONObject

/** A Passport challenge is kept separate from SIM activation credentials. */
internal data class XiaomiPassportCaptchaChallenge(
    val businessCode: Int,
    val type: String,
    val captchaUrl: String?,
    val responseSetCookies: List<String>,
    val userAgent: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    override fun toString(): String = "XiaomiPassportCaptchaChallenge(code=$businessCode, type=$type, captchaUrlPresent=${!captchaUrl.isNullOrBlank()}, setCookieCount=${responseSetCookies.size})"
}

/** Result produced only after the user completes Xiaomi's verification page. */
internal data class XiaomiPassportVerificationResult(
    val vToken: String? = null,
    val captchaCode: String? = null,
    val captchaIck: String? = null,
) {
    init {
        require(!vToken.isNullOrBlank() || (!captchaCode.isNullOrBlank() && !captchaIck.isNullOrBlank())) {
            "A Passport verification token or a complete image-captcha pair is required"
        }
    }

    override fun toString(): String = "XiaomiPassportVerificationResult(vTokenPresent=${!vToken.isNullOrBlank()}, captchaCodePresent=${!captchaCode.isNullOrBlank()}, captchaIckPresent=${!captchaIck.isNullOrBlank()})"
}

internal data class XiaomiPassportChallengeSession(
    val slotIndex: Int,
    val simIdHash: String,
    val activationDeviceIdHash: String,
    val challenge: XiaomiPassportCaptchaChallenge,
    val verificationResult: XiaomiPassportVerificationResult? = null,
    val verificationPageUrl: String? = null,
    val oaid: String? = null,
    val stsDeviceIdHash: String? = null,
    val userSpaceId: String? = null,
) {
    override fun toString(): String = "XiaomiPassportChallengeSession(slot=$slotIndex, simIdHashPresent=${simIdHash.isNotBlank()}, " +
        "activationDeviceIdPresent=${activationDeviceIdHash.isNotBlank()}, challenge=$challenge, " +
        "verificationResultPresent=${verificationResult != null}, verificationPageUrlPresent=${!verificationPageUrl.isNullOrBlank()}, " +
        "oaidPresent=${!oaid.isNullOrBlank()}, stsDeviceIdPresent=${!stsDeviceIdHash.isNullOrBlank()}, " +
        "userSpaceIdPresent=${!userSpaceId.isNullOrBlank()})"
}

/** MaiHoku-private, one-slot challenge checkpoint. No credentials are placed in Activity extras. */
internal object XiaomiPassportChallengeStore {
    private fun key(slot: Int) = "passport_challenge_slot_$slot"

    fun save(context: Context, session: XiaomiPassportChallengeSession): Boolean {
        require(session.slotIndex in 0..1)
        require(session.simIdHash.matches(Regex("[A-Za-z0-9_-]{16}")))
        require(session.activationDeviceIdHash.length == 16)
        return MiMessageCredentialStore.put(context, key(session.slotIndex), encode(session))
    }

    fun load(context: Context, slot: Int): XiaomiPassportChallengeSession? {
        require(slot in 0..1)
        val encoded = MiMessageCredentialStore.get(context, key(slot)) ?: return null
        return runCatching { decode(JSONObject(encoded)) }.getOrNull()
    }

    fun saveVerificationResult(
        context: Context,
        slot: Int,
        result: XiaomiPassportVerificationResult,
    ): Boolean {
        val session = load(context, slot) ?: return false
        return save(context, session.copy(verificationResult = result))
    }

    fun clear(context: Context, slot: Int): Boolean {
        require(slot in 0..1)
        return MiMessageCredentialStore.put(context, key(slot), "")
    }

    private fun encode(session: XiaomiPassportChallengeSession): String {
        val challenge = session.challenge
        val cookies = JSONArray().apply { challenge.responseSetCookies.forEach(::put) }
        val root = JSONObject()
            .put("version", 1)
            .put("slot", session.slotIndex)
            .put("simIdHash", session.simIdHash)
            .put("activationDeviceIdHash", session.activationDeviceIdHash)
            .put("businessCode", challenge.businessCode)
            .put("type", challenge.type)
            .put("captchaUrl", challenge.captchaUrl ?: JSONObject.NULL)
            .put("setCookies", cookies)
            .put("userAgent", challenge.userAgent)
            .put("createdAtMillis", challenge.createdAtMillis)
            .put("verificationPageUrl", session.verificationPageUrl ?: JSONObject.NULL)
            .put("oaid", session.oaid ?: JSONObject.NULL)
            .put("stsDeviceIdHash", session.stsDeviceIdHash ?: JSONObject.NULL)
            .put("userSpaceId", session.userSpaceId ?: JSONObject.NULL)
        session.verificationResult?.let { result ->
            root.put(
                "verificationResult",
                JSONObject()
                    .put("vToken", result.vToken ?: JSONObject.NULL)
                    .put("captchaCode", result.captchaCode ?: JSONObject.NULL)
                    .put("captchaIck", result.captchaIck ?: JSONObject.NULL),
            )
        }
        return root.toString()
    }

    private fun decode(root: JSONObject): XiaomiPassportChallengeSession {
        require(root.optInt("version") == 1)
        val cookiesJson = root.optJSONArray("setCookies") ?: JSONArray()
        val cookies = buildList {
            for (index in 0 until cookiesJson.length()) {
                cookiesJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
        val challenge = XiaomiPassportCaptchaChallenge(
            businessCode = root.getInt("businessCode"),
            type = root.getString("type"),
            captchaUrl = root.optString("captchaUrl").takeIf { it.isNotBlank() && it != "null" },
            responseSetCookies = cookies,
            userAgent = root.optString("userAgent"),
            createdAtMillis = root.optLong("createdAtMillis", System.currentTimeMillis()),
        )
        val resultJson = root.optJSONObject("verificationResult")
        val result = resultJson?.let {
            val vToken = it.optString("vToken").takeIf(String::isNotBlank)
            val code = it.optString("captchaCode").takeIf(String::isNotBlank)
            val ick = it.optString("captchaIck").takeIf(String::isNotBlank)
            if (vToken != null || (code != null && ick != null)) {
                XiaomiPassportVerificationResult(vToken, code, ick)
            } else {
                null
            }
        }
        return XiaomiPassportChallengeSession(
            slotIndex = root.getInt("slot"),
            simIdHash = root.getString("simIdHash"),
            activationDeviceIdHash = root.getString("activationDeviceIdHash"),
            challenge = challenge,
            verificationResult = result,
            verificationPageUrl = root.optString("verificationPageUrl").takeIf { it.isNotBlank() && it != "null" },
            oaid = root.optString("oaid").takeIf { it.isNotBlank() && it != "null" },
            stsDeviceIdHash = root.optString("stsDeviceIdHash").takeIf { it.isNotBlank() && it != "null" },
            userSpaceId = root.optString("userSpaceId").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

internal object XiaomiPassportChallengeCoordinator {
    const val EXTRA_SLOT = "io.github.xiaotong6666.maihoku.extra.PASSPORT_SLOT"

    fun saveAndOpen(
        context: Context,
        slot: Int,
        simIdHash: String,
        activationDeviceIdHash: String,
        challenge: XiaomiPassportCaptchaChallenge,
        oaid: String? = null,
        stsDeviceIdHash: String? = null,
        userSpaceId: String? = null,
    ) {
        check(context.applicationContext.packageName == BuildConfig.APPLICATION_ID) {
            "Passport challenges must be stored by MaiHoku's own application UID, not a host app Context"
        }
        check(
            XiaomiPassportChallengeStore.save(
                context,
                XiaomiPassportChallengeSession(
                    slotIndex = slot,
                    simIdHash = simIdHash,
                    activationDeviceIdHash = activationDeviceIdHash,
                    challenge = challenge,
                    oaid = oaid,
                    stsDeviceIdHash = stsDeviceIdHash,
                    userSpaceId = userSpaceId,
                ),
            ),
        ) { "Could not save the Passport challenge in MaiHoku private storage" }
        val intent = Intent(context, PassportCaptchaActivity::class.java)
            .putExtra(EXTRA_SLOT, slot)
        if (findActivity(context) == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun findActivity(context: Context): android.app.Activity? {
        var current = context
        val visited = HashSet<Context>()
        while (visited.add(current)) {
            if (current is android.app.Activity) return current
            current = (current as? android.content.ContextWrapper)?.baseContext ?: return null
        }
        return null
    }
}

internal object XiaomiPassportVerificationUrls {
    fun requireOfficial(url: String): String {
        val uri = java.net.URI(url)
        val host = uri.host?.lowercase().orEmpty()
        val official = host == "xiaomi.com" || host.endsWith(".xiaomi.com") ||
            host == "mi.com" || host.endsWith(".mi.com")
        require(
            uri.scheme.equals("https", ignoreCase = true) && official &&
                uri.userInfo == null && (uri.port == -1 || uri.port == 443),
        ) {
            "Passport verification URL is not an official HTTPS Xiaomi URL"
        }
        return uri.toASCIIString()
    }
}
