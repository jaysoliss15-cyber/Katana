package com.example.deviceidlab

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager

data class DeviceIdentity(
    val androidId: String,
    val imei: String,
    val serial: String,
    val model: String,
    val manufacturer: String
)

object DeviceIdReader {

    @SuppressLint("HardwareIds")
    fun readCurrentIdentity(context: Context): DeviceIdentity {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN"

        var imei = "UNKNOWN"
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                imei = tm.deviceId ?: tm.imei ?: "UNKNOWN"
            }
        } catch (_: Exception) {}

        val serial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        } catch (_: Exception) {
            "RESTRICTED"
        }

        return DeviceIdentity(
            androidId = androidId,
            imei = imei,
            serial = serial,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER
        )
    }

    /**
     * Canary method intercepted by NPatch runtime hook to confirm active status.
     */
    fun isNpatchHookActive(): Boolean = false
}
