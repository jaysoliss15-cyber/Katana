package com.example.deviceidlab.runtime

import android.os.Bundle
import com.example.deviceidlab.hook.NPatchConfig
import com.example.deviceidlab.model.DeviceProfile
import com.example.deviceidlab.model.ProfileState

/**
 * Single Authoritative Source of Truth for runtime device identity profiles.
 *
 * Guarantees that ANDROID_ID, DEVICE_ID, IMEI, MAC, BSSID, SSID, IP,
 * LATITUDE, and LONGITUDE are strictly bound to the active profile schema
 * and resolve consistently across all supported intercepted APIs.
 */
object ProfileStore {
    const val CURRENT_SCHEMA_VERSION = 2

    @Volatile
    private var activeProfile: DeviceProfile = DeviceProfile(
        id = "default_npatch_profile",
        name = "Default Active Identity Profile",
        androidId = "NPATCH_ANDROID_001",
        imei = "NPATCH_TELEPHONY_001",
        serialNumber = "NPATCH_SERIAL_001",
        macAddress = NPatchConfig.DEFAULT_MAC,
        testIpv4 = NPatchConfig.DEFAULT_SYNTHETIC_IP,
        wifiSsid = NPatchConfig.DEFAULT_SSID,
        bssid = NPatchConfig.DEFAULT_BSSID,
        city = "San Francisco",
        country = "US",
        timezone = "America/Los_Angeles",
        latitude = 37.7749,
        longitude = -122.4194,
        carrierName = "Android Carrier",
        simOperator = "310260",
        state = ProfileState.ACTIVE
    )

    @Volatile
    private var profileVersion: Long = 1L

    @Synchronized
    fun getActiveProfile(): DeviceProfile = activeProfile

    @Synchronized
    fun getProfileVersion(): Long = profileVersion

    @Synchronized
    fun setActiveProfile(profile: DeviceProfile, version: Long = System.currentTimeMillis()) {
        activeProfile = profile
        profileVersion = version
    }

    /**
     * Serializes the current active profile to an IPC-safe Bundle.
     */
    fun toBundle(profile: DeviceProfile = getActiveProfile()): Bundle {
        return Bundle().apply {
            putInt("schema_version", CURRENT_SCHEMA_VERSION)
            putLong("profile_version", profileVersion)
            putString("profile_id", profile.id)
            putString("profile_name", profile.name)
            putString("android_id", profile.androidId)
            putString("device_id", profile.imei)
            putString("imei", profile.imei)
            putString("serial", profile.serialNumber)
            putString("mac_address", profile.macAddress)
            putString("synthetic_ip", profile.testIpv4)
            putString("wifi_ssid", profile.wifiSsid)
            putString("wifi_bssid", profile.bssid)
            putDouble("latitude", profile.latitude)
            putDouble("longitude", profile.longitude)
            putString("city", profile.city)
            putString("country", profile.country)
            putString("timezone", profile.timezone)
            putString("carrier_name", profile.carrierName)
            putString("sim_operator", profile.simOperator)
            putString("state", profile.state.name)
        }
    }

    /**
     * Deserializes a profile from an IPC Bundle received from DeviceIdProvider.
     */
    fun fromBundle(bundle: Bundle): DeviceProfile {
        val current = activeProfile
        val androidId = bundle.getString("android_id")
            ?: bundle.getString("active_android_id")
            ?: bundle.getString("test_id")
            ?: current.androidId
        val imei = bundle.getString("imei")
            ?: bundle.getString("device_id")
            ?: bundle.getString("active_telephony_id")
            ?: bundle.getString("telephony_test_id")
            ?: current.imei
        val mac = bundle.getString("mac_address")
            ?: bundle.getString(NPatchConfig.KEY_ACTIVE_MAC_ADDRESS)
            ?: current.macAddress
        val ip = bundle.getString("synthetic_ip")
            ?: bundle.getString(NPatchConfig.KEY_ACTIVE_SYNTHETIC_IP)
            ?: current.testIpv4
        val ssid = bundle.getString("wifi_ssid")
            ?: bundle.getString(NPatchConfig.KEY_ACTIVE_WIFI_SSID)
            ?: current.wifiSsid
        val bssid = bundle.getString("wifi_bssid")
            ?: bundle.getString(NPatchConfig.KEY_ACTIVE_WIFI_BSSID)
            ?: current.bssid
        val lat = if (bundle.containsKey("latitude")) bundle.getDouble("latitude")
        else if (bundle.containsKey(NPatchConfig.KEY_ACTIVE_LATITUDE)) bundle.getDouble(NPatchConfig.KEY_ACTIVE_LATITUDE)
        else current.latitude
        val lng = if (bundle.containsKey("longitude")) bundle.getDouble("longitude")
        else if (bundle.containsKey(NPatchConfig.KEY_ACTIVE_LONGITUDE)) bundle.getDouble(NPatchConfig.KEY_ACTIVE_LONGITUDE)
        else current.longitude
        val city = bundle.getString("city")
            ?: bundle.getString(NPatchConfig.KEY_ACTIVE_CITY)
            ?: current.city
        val country = bundle.getString("country")
            ?: bundle.getString(NPatchConfig.KEY_ACTIVE_COUNTRY)
            ?: current.country
        val timezone = bundle.getString("timezone")
            ?: bundle.getString(NPatchConfig.KEY_ACTIVE_TIMEZONE)
            ?: current.timezone

        return DeviceProfile(
            id = bundle.getString("profile_id") ?: current.id,
            name = bundle.getString("profile_name") ?: current.name,
            androidId = androidId,
            imei = imei,
            serialNumber = bundle.getString("serial") ?: current.serialNumber,
            macAddress = mac,
            testIpv4 = ip,
            wifiSsid = ssid,
            bssid = bssid,
            city = city,
            country = country,
            timezone = timezone,
            latitude = lat,
            longitude = lng,
            carrierName = bundle.getString("carrier_name") ?: current.carrierName,
            simOperator = bundle.getString("sim_operator") ?: current.simOperator,
            state = ProfileState.ACTIVE
        )
    }
}
