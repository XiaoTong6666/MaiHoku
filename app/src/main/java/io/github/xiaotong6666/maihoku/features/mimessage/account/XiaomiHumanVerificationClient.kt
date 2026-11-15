package io.github.xiaotong6666.maihoku.features.mimessage.account

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Base64
import android.view.accessibility.AccessibilityManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal client for the Xiaomi Account SDK's man-machine verification bootstrap.
 * It creates the same encrypted environment envelope, then leaves the actual
 * challenge to Xiaomi's official WebView page. No CAPTCHA is solved or bypassed.
 */
internal object XiaomiHumanVerificationClient {
    private const val VERIFY_HOST = "verify.sec.xiaomi.com"
    private const val VERIFY_SITE_KEY = "8027422fb0eb42fbac1b521ec4a7961f"
    private const val VERIFY_SDK_VERSION = "5.8.6.master.release.3"
    private const val VERIFY_ENDPOINT = "https://$VERIFY_HOST/captcha/v2/data"
    private val fixedIv = "0102030405060708".toByteArray(Charsets.UTF_8)

    // z5.a initializes this process-wide AccountSDK cookie once.
    private val accountSdkRequestId = java.util.UUID.randomUUID().toString().take(8)

    sealed interface BootstrapResult {
        data class OpenPage(val url: String) : BootstrapResult
        data class Completed(val token: String) : BootstrapResult
    }

    fun prepare(context: Context, action: String = "pwd-login"): BootstrapResult {
        require(action.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid Passport verification action" }
        val sensorStart = System.currentTimeMillis()
        val sensors = collectSensors(context, sensorStart)
        val now = System.currentTimeMillis()
        val environment = buildEnvironment(context, sensorStart, now, action, sensors)
        val encrypted = encryptEnvironment(environment.toString())
        val locale = java.util.Locale.getDefault().let {
            if (it.country.isNullOrBlank()) it.language else "${it.language}_${it.country}"
        }
        val url = "$VERIFY_ENDPOINT?" + listOf(
            "k" to VERIFY_SITE_KEY,
            "locale" to locale,
            "errorAction" to "false",
        ).joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val form = linkedMapOf(
            "subscription" to encrypted.subscription,
            "d" to encrypted.environment,
            "a" to action,
        )
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            doOutput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", verificationUserAgent())
            setRequestProperty("Cookie", "rnd=$accountSdkRequestId")
        }
        try {
            val body = form.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw XiaomiActivationException(
                    "PASSPORT_VERIFY_BOOTSTRAP_HTTP_ERROR",
                    "Xiaomi verification bootstrap returned HTTP $status",
                )
            }
            val root = runCatching { JSONObject(responseText) }.getOrElse {
                throw XiaomiActivationException("PASSPORT_VERIFY_BOOTSTRAP_INVALID", "Xiaomi verification bootstrap returned invalid JSON", it)
            }
            val code = root.optInt("code", Int.MIN_VALUE)
            val data = root.optJSONObject("data")
            if (code == 500) return BootstrapResult.Completed(randomToken())
            if (code != 0 || data == null) {
                val rootFields = root.keys().asSequence().toList().sorted().joinToString(",")
                val dataFields = data?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(",").orEmpty()
                throw XiaomiActivationException(
                    "PASSPORT_VERIFY_BOOTSTRAP_REJECTED",
                    "Xiaomi verification bootstrap rejected the request (code=$code, fields=$rootFields, dataFields=$dataFields)",
                )
            }
            val challengeUrl = data.optString("url").takeIf(String::isNotBlank)
            if (challengeUrl != null) {
                val page = XiaomiPassportVerificationUrls.requireOfficial(challengeUrl)
                val separator = if (page.contains('?')) '&' else '?'
                return BootstrapResult.OpenPage("${page}${separator}_modifyStyle=true")
            }
            val token = data.optString("token").takeIf(String::isNotBlank)
            if (data.optBoolean("result") && token != null) {
                return BootstrapResult.Completed(token)
            }
            throw XiaomiActivationException(
                "PASSPORT_VERIFY_BOOTSTRAP_NO_CHALLENGE",
                "Xiaomi verification bootstrap returned neither a challenge URL nor a verification token",
            )
        } catch (error: XiaomiActivationException) {
            throw error
        } catch (error: Exception) {
            throw XiaomiActivationException("PASSPORT_VERIFY_BOOTSTRAP_NETWORK_ERROR", "Could not initialize Xiaomi verification", error)
        } finally {
            connection.disconnect()
        }
    }

    private data class EncryptedEnvironment(val subscription: String, val environment: String)

    private fun encryptEnvironment(value: String): EncryptedEnvironment {
        val keyGenerator = KeyGenerator.getInstance("AES").apply { init(128, SecureRandom()) }
        val aesKey = keyGenerator.generateKey().encoded
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(fixedIv))
        }
        val encryptedEnvironment = Base64.encodeToString(
            aes.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(VERIFY_CERTIFICATE.trimIndent().trim().toByteArray(Charsets.UTF_8)),
        ) as X509Certificate
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.ENCRYPT_MODE, certificate.publicKey)
        }
        val encodedAesKey = Base64.encodeToString(aesKey, Base64.URL_SAFE or Base64.NO_WRAP)
        val encryptedKey = Base64.encodeToString(
            rsa.doFinal(encodedAesKey.toByteArray(Charsets.UTF_8)),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )
        return EncryptedEnvironment(encryptedKey, encryptedEnvironment)
    }

    private fun collectSensors(context: Context, startMillis: Long): JSONObject {
        val names = mapOf(
            Sensor.TYPE_ACCELEROMETER to "acceleration",
            Sensor.TYPE_GYROSCOPE to "rotation_speed",
            Sensor.TYPE_MAGNETIC_FIELD to "magnetic",
            Sensor.TYPE_LIGHT to "light",
            Sensor.TYPE_PRESSURE to "barometer",
        )
        // Preserve SensorHelper.r()'s output member order.
        val arrays = listOf("rotation_speed", "acceleration", "magnetic", "light", "barometer")
            .associateWith { JSONArray() }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return JSONObject().apply { arrays.forEach { (name, values) -> put(name, values) } }
        val thread = HandlerThread("MaiHoku-VerifySensors").apply { start() }
        val handler = Handler(thread.looper)
        val finished = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val name = names[event.sensor.type] ?: return
                val sample = JSONArray().put(System.currentTimeMillis() - startMillis)
                event.values.forEach { value ->
                    val precision = if (event.sensor.type == Sensor.TYPE_LIGHT) 1 else 4
                    sample.put(String.format(Locale.US, "%.${precision}f", value).toDouble())
                }
                synchronized(arrays) { arrays[name]?.put(sample) }
            }
        }
        try {
            names.keys.forEach { type ->
                manager.getDefaultSensor(type)?.let { manager.registerListener(listener, it, 50_000, handler) }
            }
            finished.await(5_000L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { manager.unregisterListener(listener) }
            finished.countDown()
            thread.quitSafely()
        }
        return JSONObject().apply {
            synchronized(arrays) { arrays.forEach { (name, values) -> put(name, values) } }
        }
    }

    private fun buildEnvironment(
        context: Context,
        startMillis: Long,
        now: Long,
        action: String,
        sensors: JSONObject,
    ): JSONObject {
        val app = context.applicationContext
        // VerificationManager uses the Activity's application context. When
        // embedded in Google Messages this must describe the host app, not
        // MaiHoku's LSPosed module package.
        val appPackageName = app.packageName
        val appInfo = app.applicationInfo
        val packageInfo = runCatching { app.packageManager.getPackageInfo(appPackageName, 0) }.getOrNull()
        val appName = runCatching { app.packageManager.getApplicationLabel(appInfo).toString() }.getOrDefault("")
        val sourceModified = runCatching {
            java.io.File(appInfo.sourceDir).lastModified()
        }.getOrDefault(0L)
        val battery = runCatching {
            val intent = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            (intent?.getIntExtra("level", -1) ?: -1).coerceAtLeast(0)
        }.getOrDefault(0)
        val charging = runCatching {
            val intent = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            intent?.getIntExtra("status", 0) == android.os.BatteryManager.BATTERY_STATUS_CHARGING
        }.getOrDefault(false)
        val brightness = runCatching { Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(0)
        val adbEnabled = runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) }.getOrDefault(0)
        val screenTimeout = runCatching { Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrDefault(0)

        @Suppress("DEPRECATION")
        val simulator = runCatching {
            (app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
                ?.networkOperatorName?.equals("android", ignoreCase = true) == true
        }.getOrDefault(false)
        val deviceId = resolvePassportDeviceId(app)
        val talkBack = runCatching {
            val manager = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            manager?.isEnabled == true && manager.isTouchExplorationEnabled
        }.getOrDefault(false)

        val env = JSONObject()
            .put("battery", battery)
            .put("deviceId", deviceId)
            .put("app_version", packageInfo?.versionName.orEmpty())
            .put("sdk_version", VERIFY_SDK_VERSION)
            .put("vpn", if (isVpnActive()) 1 else 0)
            .put("brand_model", Build.MODEL)
            .put("system_version", "Android ${Build.VERSION.RELEASE}")
            .put("customed_system", Build.FINGERPRINT)
            .put("screen_brightness", brightness)
            .put("debug", if (adbEnabled > 0) 1 else 0)
            .put("simulator", if (simulator) 1 else 0)
            .put("charging", if (charging) 1 else 0)
            .put("never_lock_screen", if (screenTimeout == Int.MAX_VALUE) 1 else 0)
            .put("aps", JSONArray())
            .put("xposed", if (isPackageInstalled(app, "de.robv.android.xposed.installer")) 1 else 0)
            .put("substrate", if (isPackageInstalled(app, "com.saurik.substrate")) 1 else 0)
            .put("root", if (hasSafeRootIndicators(app)) 1 else 0)
            .put("boot_time", now - android.os.SystemClock.elapsedRealtime())
            .put("install_time", sourceModified)
            .put("package_name", appPackageName)
            .put("app_name", appName)
            .put("virtualapp", 0)
            .put("voiceover", 0)
        return JSONObject()
            .put("type", 2)
            .put("startTs", startMillis)
            .put("endTs", now)
            .put("env", env)
            .put("action", sensors)
            // VerificationManager defaults mForce=false unless its caller
            // explicitly invokes t0(true). The original pwd-login callsite
            // does not do that.
            .put("force", false)
            .put("talkBack", talkBack)
            .put("uid", JSONObject.NULL)
            .put("version", "2.0")
            .put("scene", action)
            .put("nonce", JSONObject().put("r", SecureRandom().nextLong()).put("t", now / 1000L))
    }

    private fun verificationUserAgent(): String = "${System.getProperty("http.agent").orEmpty()} AndroidVerifySDK/$VERIFY_SDK_VERSION"

    fun webViewUserAgent(context: Context): String = "${android.webkit.WebSettings.getDefaultUserAgent(context)} androidVerifySDK/$VERIFY_SDK_VERSION " +
        "androidVerifySDK/VersionCode/5863 AppPackageName/${context.packageName}"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun randomToken(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    /** Mirrors AccountSDK d6.e's non-system-app policy: cached, runtime, OAID, then Android ID. */
    private fun resolvePassportDeviceId(context: Context): String {
        // AccountSDK d6.e stores this value under the same SharedPreferences
        // file/key so its cached-device-ID behavior is reproducible.
        val preferences = context.getSharedPreferences("deviceId", Context.MODE_PRIVATE)
        preferences.getString("hashedDeviceId", null)?.takeIf(String::isNotBlank)?.let { return it }
        XiaomiAccountSdkDeviceIdProvider.resolve(context)?.let {
            preferences.edit().putString("hashedDeviceId", it).apply()
            return it
        }
        val oaid = runCatching { XiaomiDeviceIdProvider.resolveOaid(context) }.getOrNull()
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val fallback = when {
            !oaid.isNullOrBlank() -> "oa_${md5Hex(oaid)}"
            !androidId.isNullOrBlank() -> "an_${md5Hex(androidId)}"
            else -> "android_${java.util.UUID.randomUUID()}"
        }
        // AccountSDK only SHA1/Base64-hashes a runtime Telephony ID. OAID and
        // Android-ID fallbacks are returned as prefixed MD5 strings; the
        // pseudo-ID is returned verbatim. Do not hash these a second time.
        preferences.edit().putString("hashedDeviceId", fallback).apply()
        return fallback
    }

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.US, it) }

    private fun isVpnActive(): Boolean = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
        Collections.list(interfaces).any { network ->
            network.isUp && network.interfaceAddresses.isNotEmpty() &&
                (network.name == "tun0" || network.name == "ppp0")
        }
    }.getOrDefault(false)

    private fun isPackageInstalled(context: Context, vararg packageNames: String): Boolean = packageNames.any { name ->
        runCatching { context.packageManager.getPackageInfo(name, PackageManager.GET_ACTIVITIES) }.isSuccess
    }

    /** Safe, read-only indicators only; do not reproduce the SDK's /data write probe. */
    private fun hasSafeRootIndicators(context: Context): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
            "/data/adb/ap/bin/su",
        )
        if (paths.any { java.io.File(it).exists() }) return true
        return isPackageInstalled(
            context,
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
            "me.weishu.kernelsu.manager",
            "me.bmax.apatch",
            "com.rifsxd.ksunext",
        )
    }

    private const val VERIFY_CERTIFICATE = """
-----BEGIN CERTIFICATE-----
MIIDXTCCAkWgAwIBAgIJAOMjETkYAg3lMA0GCSqGSIb3DQEBCwUAMEUxCzAJBgNV
BAYTAkFVMRMwEQYDVQQIDApTb21lLVN0YXRlMSEwHwYDVQQKDBhJbnRlcm5ldCBX
aWRnaXRzIFB0eSBMdGQwHhcNMTgwMTExMDk0ODQ3WhcNMTgwMjEwMDk0ODQ3WjBF
MQswCQYDVQQGEwJBVTETMBEGA1UECAwKU29tZS1TdGF0ZTEhMB8GA1UECgwYSW50
ZXJuZXQgV2lkZ2l0cyBQdHkgTHRkMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB
CgKCAQEArxfNLkuAQ/BYHzkzVwtug+0abmYRBVCEScSzGxJIOsfxVzcuqaKO87H2
o2wBcacD3bRHhMjTkhSEqxPjQ/FEXuJ1cdbmr3+b3EQR6wf/cYcMx2468/QyVoQ7
BADLSPecQhtgGOllkC+cLYN6Md34Uii6U+VJf0p0q/saxUTZvhR2ka9fqJ4+6C6c
OghIecjMYQNHIaNW+eSKunfFsXVU+QfMD0q2EM9wo20aLnos24yDzRjh9HJc6xfr
37jRlv1/boG/EABMG9FnTm35xWrVR0nw3cpYF7GZg13QicS/ZwEsSd4HyboAruMx
JBPvK3Jdr4ZS23bpN0cavWOJsBqZVwIDAQABo1AwTjAdBgNVHQ4EFgQU0AWcCdJQ
ruwyc3Hjt2dEmYXmUCUwHwYDVR0jBBgwFoAU0AWcCdJQruwyc3Hjt2dEmYXmUCUw
DAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEASssjoOG4RbRjy6ivCDkZ
teOLur7jQ6/AZru8n8Fow8WGd9zM1uFZs9+c6okvlmUeCPuOiYDxps6xfL23hkQc
KghkMofjBn2eLoqTiQ+woAoZHAqLuiFUHae85KH7185EV8NTm2LpJJoqh2x5KU4Q
lCWm+5uadtqPEbO9wmI1er5Kob/njPI0QtQn2/5H9u7t71MCjHIGuQ5OSrZmmyzV
TFUVbdkf0mwoL44JO7ErbdOQ/nq+dElYiGnq7tI9gdt78k3Pgi7ykU5W7pnDbxLN
o66M6umwCy2ies0zzsxS9pKzurddXd+Zt4NBh74MV0RCf2PbDCtUODTBowLeDluq
Xw==
-----END CERTIFICATE-----
"""
}
