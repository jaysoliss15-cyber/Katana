package com.example.demomodule

import android.app.Activity
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.NetworkInterface

/**
 * Target Demo Activity #1 for module runtime verification.
 * Implements the 5-stage verification model:
 * HOOK_REGISTERED -> HOOK_INVOKED -> VALUE_GENERATED -> VALUE_RETURNED -> TARGET_OBSERVED
 */
class TargetDemoActivity : Activity() {

    data class TestResult(
        val api: String,
        val expected: String,
        val observed: String,
        val hookStatus: String,
        val matchStatus: String,
        val diagnosis: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_target_demo)

        val btnReinvoke = findViewById<Button>(R.id.btn_reinvoke_all)
        btnReinvoke?.setOnClickListener {
            runAuditedVerification()
        }

        runAuditedVerification()
    }

    private fun runAuditedVerification() {
        val resultsContainer = findViewById<LinearLayout>(R.id.layout_test_results) ?: return
        resultsContainer.removeAllViews()

        val providerUri = android.net.Uri.parse("content://com.example.deviceidlab.provider")
        val activeProfileBundle = try {
            contentResolver.call(providerUri, "get_current_test_ids", null, null)
        } catch (_: Throwable) { null }

        val expAndroidId = activeProfileBundle?.getString("android_test_id")
            ?: activeProfileBundle?.getString("test_id") ?: "NPATCH_ANDROID_001"
        val expTelephonyId = activeProfileBundle?.getString("telephony_test_id") ?: "NPATCH_TELEPHONY_001"
        val expIp = activeProfileBundle?.getString("active_synthetic_ip") ?: "203.0.113.42"
        val expMac = activeProfileBundle?.getString("active_mac_address") ?: "02:00:11:22:33:44"
        val expSsid = activeProfileBundle?.getString("active_wifi_ssid")
            ?: activeProfileBundle?.getString("wifi_ssid") ?: "Pixel7_WiFi_A7F2"
        val expBssid = activeProfileBundle?.getString("active_wifi_bssid") ?: expMac
        val expLat = if (activeProfileBundle != null && activeProfileBundle.containsKey("active_latitude")) activeProfileBundle.getDouble("active_latitude") else 37.7749
        val expLng = if (activeProfileBundle != null && activeProfileBundle.containsKey("active_longitude")) activeProfileBundle.getDouble("active_longitude") else -122.4194
        val expLoc = "%.4f, %.4f".format(expLat, expLng)

        fun reportTargetObserved(apiName: String, value: String) {
            try {
                val extras = Bundle().apply {
                    putString("target_package", packageName)
                    putString("target_process", applicationInfo.processName ?: packageName)
                    putInt("target_pid", android.os.Process.myPid())
                    putString("api_name", apiName)
                    putString("stage", "TARGET_OBSERVED")
                    putString("injected_id", value)
                    putString("returned_id", value)
                }
                contentResolver.call(providerUri, "report_interception", null, extras)
            } catch (_: Throwable) {}
        }

        val tests = mutableListOf<TestResult>()

        // 1. Android ID
        val obsAndroidId = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "null"
        } catch (t: Throwable) {
            "Error: ${t.message}"
        }
        val isAndroidMatch = obsAndroidId == expAndroidId
        if (isAndroidMatch) reportTargetObserved("Settings.Secure.getString(android_id)", obsAndroidId)
        tests.add(
            TestResult(
                api = "Settings.Secure.getString(android_id)",
                expected = expAndroidId,
                observed = obsAndroidId,
                hookStatus = if (isAndroidMatch) "TARGET_OBSERVED" else "HOOK_REGISTERED",
                matchStatus = if (isAndroidMatch) "MATCH" else "MISMATCH",
                diagnosis = if (isAndroidMatch) "Target process queries Settings.Secure for active simulated Android ID." else "Observed un-intercepted Android ID."
            )
        )

        // 2. Telephony ID
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val obsTelephony = try {
            @Suppress("DEPRECATION")
            tm?.deviceId ?: tm?.imei ?: "null"
        } catch (_: SecurityException) {
            "Restricted (READ_PRIVILEGED_PHONE_STATE required)"
        } catch (t: Throwable) {
            "Error: ${t.message}"
        }
        val isTeleMatch = obsTelephony == expTelephonyId
        if (isTeleMatch) reportTargetObserved("TelephonyManager.getDeviceId()", obsTelephony)
        tests.add(
            TestResult(
                api = "TelephonyManager.getDeviceId()",
                expected = expTelephonyId,
                observed = obsTelephony,
                hookStatus = when {
                    isTeleMatch -> "TARGET_OBSERVED"
                    obsTelephony.startsWith("Restricted") -> "RESTRICTED"
                    else -> "HOOK_REGISTERED"
                },
                matchStatus = when {
                    isTeleMatch -> "MATCH"
                    obsTelephony.startsWith("Restricted") -> "RESTRICTED"
                    else -> "MISMATCH"
                },
                diagnosis = if (isTeleMatch) "Target queries TelephonyManager for active simulated Device ID." else "Telephony ID not matched or restricted."
            )
        )

        // 3. LocationManager.getLastKnownLocation
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val obsLoc = try {
            val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null) "%.4f, %.4f".format(loc.latitude, loc.longitude) else "null"
        } catch (_: SecurityException) {
            "Location Permission Required"
        } catch (t: Throwable) {
            "Error: ${t.message}"
        }
        val isLocMatch = obsLoc == expLoc
        if (isLocMatch) reportTargetObserved("LocationManager.getLastKnownLocation", obsLoc)
        tests.add(
            TestResult(
                api = "LocationManager.getLastKnownLocation(gps)",
                expected = expLoc,
                observed = obsLoc,
                hookStatus = when {
                    isLocMatch -> "TARGET_OBSERVED"
                    obsLoc.startsWith("Location Permission") -> "RESTRICTED"
                    else -> "HOOK_REGISTERED"
                },
                matchStatus = when {
                    isLocMatch -> "MATCH"
                    obsLoc.startsWith("Location Permission") -> "RESTRICTED"
                    else -> "MISMATCH"
                },
                diagnosis = if (isLocMatch) "Worldwide location coordinates intercepted." else "Location not matched or permission restricted."
            )
        )

        // 4. Location.getLatitude() & getLongitude()
        val obsLat = try {
            val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            loc?.latitude
        } catch (_: Throwable) { null }
        val isLatMatch = obsLat != null && "%.4f".format(obsLat) == "%.4f".format(expLat)
        if (isLatMatch) reportTargetObserved("Location.getLatitude()", obsLat.toString())
        tests.add(
            TestResult(
                api = "Location.getLatitude()",
                expected = "%.4f".format(expLat),
                observed = obsLat?.let { "%.4f".format(it) } ?: "null",
                hookStatus = if (isLatMatch) "TARGET_OBSERVED" else "HOOK_REGISTERED",
                matchStatus = if (isLatMatch) "MATCH" else "MISMATCH",
                diagnosis = if (isLatMatch) "Location.getLatitude() intercepted via framework hook." else "Latitude not intercepted."
            )
        )

        // 5. NetworkInterface Hardware MAC
        val obsMac = try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var mac = "02:00:00:00:00:00"
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val bytes = nif.hardwareAddress
                if (bytes != null && bytes.isNotEmpty() && !nif.isLoopback) {
                    mac = bytes.joinToString(":") { String.format("%02X", it) }
                    break
                }
            }
            mac
        } catch (t: Throwable) {
            "Error: ${t.message}"
        }
        val isMacMatch = obsMac.equals(expMac, ignoreCase = true)
        if (isMacMatch) reportTargetObserved("NetworkInterface.getHardwareAddress()", obsMac)
        tests.add(
            TestResult(
                api = "NetworkInterface.getHardwareAddress()",
                expected = expMac,
                observed = obsMac,
                hookStatus = if (isMacMatch) "TARGET_OBSERVED" else "HOOK_REGISTERED",
                matchStatus = if (isMacMatch) "MATCH" else "MISMATCH",
                diagnosis = if (isMacMatch) "Hardware MAC substitution for privacy and randomization testing." else "MAC address not intercepted."
            )
        )

        // 6. NetworkInterface IP Address (RFC 5737 TEST-NET-3)
        val obsIp = try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var ip = "127.0.0.1"
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    val host = a.hostAddress ?: ""
                    if (!a.isLoopbackAddress && !host.contains(":")) {
                        ip = host
                        break
                    }
                }
            }
            ip
        } catch (t: Throwable) {
            "Error: ${t.message}"
        }
        val isIpMatch = obsIp == expIp
        if (isIpMatch) reportTargetObserved("NetworkInterface.getInetAddresses()", obsIp)
        tests.add(
            TestResult(
                api = "NetworkInterface.getInetAddresses()",
                expected = expIp,
                observed = obsIp,
                hookStatus = if (isIpMatch) "TARGET_OBSERVED" else "HOOK_REGISTERED",
                matchStatus = if (isIpMatch) "MATCH" else "MISMATCH",
                diagnosis = if (isIpMatch) "Synthetic IP range ($expIp) active." else "IP address not intercepted."
            )
        )

        // 7. WifiInfo.getMacAddress()
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val obsWifiMac = try {
            @Suppress("DEPRECATION")
            wm?.connectionInfo?.macAddress ?: "02:00:00:00:00:00"
        } catch (t: Throwable) { "Error: ${t.message}" }
        val isWifiMacMatch = obsWifiMac.equals(expMac, ignoreCase = true)
        if (isWifiMacMatch) reportTargetObserved("WifiInfo.getMacAddress()", obsWifiMac)
        tests.add(
            TestResult(
                api = "WifiInfo.getMacAddress()",
                expected = expMac,
                observed = obsWifiMac,
                hookStatus = if (isWifiMacMatch) "TARGET_OBSERVED" else "HOOK_REGISTERED",
                matchStatus = if (isWifiMacMatch) "MATCH" else "MISMATCH",
                diagnosis = if (isWifiMacMatch) "WifiInfo MAC address intercepted." else "WifiInfo MAC not intercepted."
            )
        )

        // 8. WifiInfo.getIpAddress()
        val obsWifiIp = try {
            @Suppress("DEPRECATION")
            val ipInt = wm?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                "${ipInt and 0xFF}.${(ipInt shr 8) and 0xFF}.${(ipInt shr 16) and 0xFF}.${(ipInt shr 24) and 0xFF}"
            } else "0.0.0.0"
        } catch (t: Throwable) { "Error: ${t.message}" }
        val isWifiIpMatch = obsWifiIp == expIp
        if (isWifiIpMatch) reportTargetObserved("WifiInfo.getIpAddress()", obsWifiIp)
        tests.add(
            TestResult(
                api = "WifiInfo.getIpAddress()",
                expected = expIp,
                observed = obsWifiIp,
                hookStatus = if (isWifiIpMatch) "TARGET_OBSERVED" else "HOOK_REGISTERED",
                matchStatus = if (isWifiIpMatch) "MATCH" else "MISMATCH",
                diagnosis = if (isWifiIpMatch) "WifiInfo IPv4 address unpacked." else "WifiInfo IP not intercepted."
            )
        )

        // 9. WifiInfo.getSSID() & getBSSID()
        val obsSsid = try {
            @Suppress("DEPRECATION")
            wm?.connectionInfo?.ssid ?: "<unknown ssid>"
        } catch (t: Throwable) { "Error: ${t.message}" }
        val isSsidMatch = obsSsid == expSsid || obsSsid == expSsid.trim('"') || obsSsid == "\"$expSsid\""
        if (isSsidMatch) reportTargetObserved("WifiInfo.getSSID()", obsSsid)
        tests.add(
            TestResult(
                api = "WifiInfo.getSSID()",
                expected = expSsid,
                observed = obsSsid,
                hookStatus = if (isSsidMatch) "TARGET_OBSERVED" else "HOOK_REGISTERED",
                matchStatus = if (isSsidMatch) "MATCH" else "MISMATCH",
                diagnosis = if (isSsidMatch) "Connected WiFi SSID substituted." else "SSID not intercepted."
            )
        )

        val obsBssid = try {
            @Suppress("DEPRECATION")
            wm?.connectionInfo?.bssid ?: "02:00:00:00:00:00"
        } catch (t: Throwable) { "Error: ${t.message}" }
        val isBssidMatch = obsBssid.equals(expBssid, ignoreCase = true)
        if (isBssidMatch) reportTargetObserved("WifiInfo.getBSSID()", obsBssid)
        tests.add(
            TestResult(
                api = "WifiInfo.getBSSID()",
                expected = expBssid,
                observed = obsBssid,
                hookStatus = if (isBssidMatch) "TARGET_OBSERVED" else "HOOK_REGISTERED",
                matchStatus = if (isBssidMatch) "MATCH" else "MISMATCH",
                diagnosis = if (isBssidMatch) "Connected WiFi BSSID substituted." else "BSSID not intercepted."
            )
        )

        // 10. LinkProperties.getAddresses()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var isPermissionDenied = false
        val obsLinkProp = try {
            val net = cm?.activeNetwork
            val lp = cm?.getLinkProperties(net)
            lp?.linkAddresses?.firstOrNull()?.address?.hostAddress ?: "None"
        } catch (_: SecurityException) {
            isPermissionDenied = true
            "Permission denied — ACCESS_NETWORK_STATE unavailable"
        } catch (t: Throwable) {
            val msg = t.message ?: ""
            if (msg.contains("ACCESS_NETWORK_STATE", ignoreCase = true) || msg.contains("permission", ignoreCase = true)) {
                isPermissionDenied = true
                "Permission denied — ACCESS_NETWORK_STATE unavailable"
            } else {
                "Error: ${t.message}"
            }
        }
        val isLinkPropMatch = !isPermissionDenied && obsLinkProp == expIp
        if (isLinkPropMatch) reportTargetObserved("LinkProperties.getAddresses()", obsLinkProp)
        tests.add(
            TestResult(
                api = "LinkProperties.getAddresses()",
                expected = expIp,
                observed = obsLinkProp,
                hookStatus = when {
                    isLinkPropMatch -> "TARGET_OBSERVED"
                    isPermissionDenied -> "PLATFORM_RESTRICTED"
                    else -> "HOOK_REGISTERED"
                },
                matchStatus = when {
                    isLinkPropMatch -> "MATCH"
                    isPermissionDenied -> "PERMISSION_RESTRICTED"
                    else -> "MISMATCH"
                },
                diagnosis = when {
                    isLinkPropMatch -> "LinkProperties interface address intercepted."
                    isPermissionDenied -> "Target process lacks ACCESS_NETWORK_STATE, so LinkProperties addresses could not be obtained or verified."
                    else -> "LinkProperties not intercepted."
                }
            )
        )

        // 11. Socket Egress IP (Boundary)
        tests.add(
            TestResult(
                api = "Socket.connect (Public Egress IP)",
                expected = "UNALTERED_PHYSICAL_EGRESS",
                observed = "PHYSICAL_CELLULAR_OR_WIFI_EGRESS",
                hookStatus = "UNSUPPORTED_AT_CURRENT_LAYER",
                matchStatus = "UNSUPPORTED",
                diagnosis = "Application layer hooks do not alter physical carrier NAT or raw TCP egress headers."
            )
        )

        // Render cards
        for (test in tests) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                setBackgroundColor(0xFF1E293B.toInt())
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
                layoutParams = params
            }

            val tvApi = TextView(this).apply {
                text = "${test.api} [${test.matchStatus}]"
                val color = when (test.matchStatus) {
                    "MATCH" -> 0xFF4ADE80.toInt()
                    "PERMISSION_RESTRICTED", "PLATFORM_RESTRICTED", "RESTRICTED" -> 0xFFFBBF24.toInt()
                    "UNSUPPORTED" -> 0xFF94A3B8.toInt()
                    else -> 0xFFFCA5A5.toInt()
                }
                setTextColor(color)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val tvDetails = TextView(this).apply {
                text = "EXPECTED: ${test.expected}\nOBSERVED: ${test.observed}\nSTATUS: ${test.hookStatus}\nDIAGNOSIS: ${test.diagnosis}"
                setTextColor(0xFFCBD5E1.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
            }

            itemLayout.addView(tvApi)
            itemLayout.addView(tvDetails)
            resultsContainer.addView(itemLayout)
        }
    }
}
