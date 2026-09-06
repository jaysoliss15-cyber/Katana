package com.example.deviceidlab.model

import java.io.Serializable
import java.security.MessageDigest

data class DeviceProfile(
    val id: String,
    val name: String,
    val androidId: String,
    val imei: String,
    val serialNumber: String,
    val macAddress: String,
    val buildModel: String = "Pixel 7",
    val buildManufacturer: String = "Google",
    val buildBrand: String = "google",
    val buildProduct: String = "panther",
    val buildDevice: String = "panther",
    val buildFingerprint: String = "google/panther/panther:13/TQ3A.230901.001/10750709:user/release-keys",
    val phoneNumber: String = "+1 (555) 234-5678",
    val batteryHealth: Int = 95,
    val testIpv4: String = "192.0.2.101",
    val wifiSsid: String = "Pixel7_WiFi_A7F2",
    val bssid: String = "02:00:11:22:33:44",
    val latitude: Double = 37.7749,
    val longitude: Double = -122.4194,
    val city: String = "San Francisco",
    val country: String = "US",
    val timezone: String = "America/Los_Angeles",
    val carrierName: String = "Android Carrier",
    val simOperator: String = "310260",
    val createdAt: Long = System.currentTimeMillis(),
    val state: ProfileState = ProfileState.AVAILABLE,
    val consumedAt: Long? = null
) : Serializable {

    fun computeFingerprint(): String {
        val raw = "$id:$androidId:$imei:$serialNumber:$macAddress:$buildFingerprint:$phoneNumber:$batteryHealth:$testIpv4:$wifiSsid"
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

