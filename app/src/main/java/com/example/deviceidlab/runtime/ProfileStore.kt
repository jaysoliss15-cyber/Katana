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

    val DEFAULT_PROFILE: DeviceProfile = DeviceProfile(
        id = "default_npatch_profile",
        name = "Default Active Identity Profile",
        androidId = "NPATCH_ANDROID_001",
        imei = "NPATCH_TELEPHONY_001",
        serialNumber = "NPATCH_SERIAL_001",
        macAddress = NPatchConfig.DEFAULT_MAC,
        buildModel = "Pixel 7",
        buildManufacturer = "Google",
        buildBrand = "google",
        buildProduct = "panther",
        buildDevice = "panther",
        buildFingerprint = "google/panther/panther:13/TQ3A.230901.001/10750709:user/release-keys",
        phoneNumber = "+1 (555) 234-5678",
        batteryHealth = 95,
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
    private var activeProfile: DeviceProfile = DEFAULT_PROFILE

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

    @Synchronized
    fun resetToDefault() {
        activeProfile = DEFAULT_PROFILE
        profileVersion = 1L
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
            putString("build_model", profile.buildModel)
            putString("build_manufacturer", profile.buildManufacturer)
            putString("build_brand", profile.buildBrand)
            putString("build_product", profile.buildProduct)
            putString("build_device", profile.buildDevice)
            putString("build_fingerprint", profile.buildFingerprint)
            putString("phone_number", profile.phoneNumber)
            putInt("battery_health", profile.batteryHealth)
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
        val buildModel = bundle.getString("build_model")
            ?: bundle.getString("buildModel")
            ?: current.buildModel
        val buildManufacturer = bundle.getString("build_manufacturer")
            ?: bundle.getString("buildManufacturer")
            ?: current.buildManufacturer
        val buildBrand = bundle.getString("build_brand")
            ?: bundle.getString("buildBrand")
            ?: current.buildBrand
        val buildProduct = bundle.getString("build_product")
            ?: bundle.getString("buildProduct")
            ?: current.buildProduct
        val buildDevice = bundle.getString("build_device")
            ?: bundle.getString("buildDevice")
            ?: current.buildDevice
        val buildFingerprint = bundle.getString("build_fingerprint")
            ?: bundle.getString("buildFingerprint")
            ?: current.buildFingerprint
        val phoneNumber = bundle.getString("phone_number")
            ?: bundle.getString("phoneNumber")
            ?: current.phoneNumber
        val batteryHealth = if (bundle.containsKey("battery_health")) bundle.getInt("battery_health")
        else if (bundle.containsKey("batteryHealth")) bundle.getInt("batteryHealth")
        else current.batteryHealth
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
            buildModel = buildModel,
            buildManufacturer = buildManufacturer,
            buildBrand = buildBrand,
            buildProduct = buildProduct,
            buildDevice = buildDevice,
            buildFingerprint = buildFingerprint,
            phoneNumber = phoneNumber,
            batteryHealth = batteryHealth,
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
