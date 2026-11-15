package io.github.xiaotong6666.maihoku.mimessage.account

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import java.security.MessageDigest

internal class XiaomiDeviceIdentifiers internal constructor(
    val deviceIdHash: String,
    val source: String,
    /** AccountSDK pass_o; deliberately resolved independently of Activate SysInterface deviceId. */
    internal val oaid: String?,
) {
    override fun toString(): String = "XiaomiDeviceIdentifiers(source=$source, deviceIdHash=masked)"
}

/** Exact device-selection policy recovered from SimActivateService DeviceId/AnonymousDeviceIdUtil. */
internal object XiaomiDeviceIdPolicy {
    private val oaidDeviceWhitelist = setOf(
        "cmi", "umi", "lmi", "picasso", "phoenix", "phoenixin", "vangogh", "monet",
        "toco", "merlin", "curtana", "durandal", "excalibur", "joyeuse", "gram",
    )

    fun isImeiRestricted(apiLevel: Int, restrictImeiProperty: String?, isMiuiTablet: Boolean): Boolean {
        if (apiLevel < 29) return false
        if (restrictImeiProperty == "1") return true
        if (isMiuiTablet && apiLevel >= 30) return true
        return apiLevel >= 31
    }

    fun isOaidPreferredDevice(device: String): Boolean = device.lowercase() in oaidDeviceWhitelist

    /** Candidate order for the activation SysInterface device ID, not Passport pass_o. */
    fun candidateOrder(
        restricted: Boolean,
        device: String,
        hasTelephony: Boolean,
        miuiApiAvailable: Boolean,
    ): List<String> = when {
        restricted -> buildList {
            if (isOaidPreferredDevice(device)) add("oaid")
            add("xiaomi_cloud_id")
            add("android_id_fallback")
        }

        hasTelephony && miuiApiAvailable -> listOf("miui_device_id")

        hasTelephony -> listOf("xiaomi_cloud_id", "android_id_fallback")

        // AOSP-only compatibility path.
        else -> listOf("wifi_mac_fallback")
    }
}

/**
 * Mirrors the original activation DeviceId strategy:
 * - unrestricted MIUI telephony devices use getMiuiDeviceId();
 * - restricted devices use OAID only on Xiaomi's explicit whitelist, then Cloud ID, then Android ID;
 * - non-telephony devices use Wi-Fi MAC.
 *
 * AOSP-only builds have no MIUI TelephonyManager/Cloud ID provider, so API 29/30 additionally
 * fall back to Android ID rather than failing solely because the OEM classes are absent.
 * AccountSDK OAID (pass_o) is resolved independently and never silently substituted as deviceId.
 */
internal object XiaomiDeviceIdProvider {
    fun resolve(context: Context): XiaomiDeviceIdentifiers {
        val appContext = context.applicationContext
        val oaid = resolveOaid(appContext)
        val hasTelephony = appContext.packageManager.hasSystemFeature("android.hardware.telephony")
        val miuiApiAvailable = runCatching { Class.forName("miui.telephony.TelephonyManager") }.isSuccess
        val restricted = XiaomiDeviceIdPolicy.isImeiRestricted(
            apiLevel = Build.VERSION.SDK_INT,
            restrictImeiProperty = readSystemProperty("ro.miui.restrict_imei"),
            isMiuiTablet = isMiuiTablet(),
        )
        val candidates = XiaomiDeviceIdPolicy.candidateOrder(
            restricted = restricted,
            device = Build.DEVICE,
            hasTelephony = hasTelephony,
            miuiApiAvailable = miuiApiAvailable,
        )

        for (source in candidates) {
            val value = runCatching {
                when (source) {
                    "miui_device_id" -> readMiuiDeviceId()

                    "oaid" -> oaid

                    "xiaomi_cloud_id" -> readCloudId(appContext)

                    "android_id_fallback" -> Settings.Secure.getString(
                        appContext.contentResolver,
                        Settings.Secure.ANDROID_ID,
                    )

                    "wifi_mac_fallback" -> readWifiMac(appContext)

                    else -> null
                }
            }.getOrNull()?.trim().orEmpty()
            val valid = value.isNotEmpty() && value != "0" && !value.equals("null", ignoreCase = true)
            if (!valid) continue
            if (source == "miui_device_id" && value.length < 14) {
                throw XiaomiActivationException(
                    "INVALID_MIUI_DEVICE_ID",
                    "MIUI TelephonyManager returned a device ID shorter than the original validity threshold",
                )
            }
            return XiaomiDeviceIdentifiers(hash16(value), source, oaid)
        }
        throw XiaomiActivationException(
            "NO_DEVICE_ID",
            "No device identifier is available through the original Xiaomi fallback policy",
        )
    }

    /** AccountSDK m0.f.b(): OAID is sent as pass_o independently of activation deviceId. */
    fun resolveOaid(context: Context): String? = runCatching { readOaid(context.applicationContext) }.getOrNull()

    private fun hash16(value: String): String = Base64.encodeToString(
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8)),
        Base64.URL_SAFE or Base64.NO_WRAP,
    ).take(16)

    private fun readMiuiDeviceId(): String? {
        val managerClass = Class.forName("miui.telephony.TelephonyManager")
        val manager = managerClass.getMethod("getDefault").invoke(null)
        return managerClass.getMethod("getMiuiDeviceId").invoke(manager) as? String
    }

    private fun readCloudId(context: Context): String? {
        val result = context.contentResolver.call(
            Uri.parse("content://com.xiaomi.cloud.cloudidprovider"),
            "getCloudId",
            null,
            null,
        ) ?: return null
        return result.getString("result_id")
    }

    @Suppress("DEPRECATION")
    private fun readWifiMac(context: Context): String? = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.connectionInfo?.macAddress

    private fun readOaid(context: Context): String? {
        val providerClass = Class.forName("com.android.id.impl.IdProviderImpl")
        val provider = providerClass.getDeclaredConstructor().newInstance()
        return providerClass.getMethod("getOAID", Context::class.java).invoke(provider, context) as? String
    }

    private fun readSystemProperty(name: String): String? = runCatching {
        val type = Class.forName("android.os.SystemProperties")
        type.getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()

    private fun isMiuiTablet(): Boolean = runCatching {
        val type = Class.forName("miui.os.Build")
        type.getField("IS_TABLET").getBoolean(null)
    }.getOrDefault(false)
}
