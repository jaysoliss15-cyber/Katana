package com.example.deviceidlab

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.deviceidlab.hook.TestApiCatalog
import com.example.deviceidlab.manager.DeviceIdentityManager
import com.example.deviceidlab.model.DeviceProfile
import com.example.deviceidlab.model.ProfileState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var identityManager: DeviceIdentityManager
    private lateinit var tvAndroidId: TextView
    private lateinit var tvImei: TextView
    private lateinit var tvSerial: TextView
    private lateinit var tvMac: TextView
    private lateinit var tvModel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvProfileLifecycle: TextView
    private lateinit var tvCatalogList: TextView
    private lateinit var btnGenerateProfile1: Button
    private lateinit var btnGenerateProfile2: Button
    private lateinit var btnGenerateRandom: Button

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        identityManager = DeviceIdentityManager(this)

        tvAndroidId = findViewById(R.id.tvAndroidId)
        tvImei = findViewById(R.id.tvImei)
        tvSerial = findViewById(R.id.tvSerial)
        tvMac = findViewById(R.id.tvMac)
        tvModel = findViewById(R.id.tvModel)
        tvStatus = findViewById(R.id.tvStatus)
        tvProfileLifecycle = findViewById(R.id.tvProfileLifecycle)
        tvCatalogList = findViewById(R.id.tvCatalogList)

        btnGenerateProfile1 = findViewById(R.id.btnGenerateProfile1)
        btnGenerateProfile2 = findViewById(R.id.btnGenerateProfile2)
        btnGenerateRandom = findViewById(R.id.btnGenerateRandom)

        renderCurrentIdentity()
        renderCatalog()

        btnGenerateProfile1.setOnClickListener {
            val p1 = DeviceProfile(
                id = "profile_pixel_7",
                name = "Google Pixel 7 (Profile #1)",
                androidId = "a1b2c3d4e5f60718",
                imei = "864201041234567",
                serialNumber = "27161FDH200001",
                macAddress = "02:00:00:11:22:33",
                buildModel = "Pixel 7",
                buildManufacturer = "Google",
                buildBrand = "google",
                buildProduct = "panther",
                buildDevice = "panther",
                buildFingerprint = "google/panther/panther:13/TQ3A.230901.001/10750709:user/release-keys",
                phoneNumber = "+1 (555) 234-5678",
                batteryHealth = 95,
                testIpv4 = "192.0.2.101",
                wifiSsid = "Pixel7_WiFi_A7F2",
                state = ProfileState.AVAILABLE
            )
            val result = identityManager.applyAndActivateProfile(p1)
            handleActivationResult(result)
        }

        btnGenerateProfile2.setOnClickListener {
            val p2 = DeviceProfile(
                id = "profile_s23",
                name = "Samsung Galaxy S23 (Profile #2)",
                androidId = "f9e8d7c6b5a41320",
                imei = "359876543210987",
                serialNumber = "R58M30ABCDE",
                macAddress = "02:00:00:AA:BB:CC",
                buildModel = "SM-S911B",
                buildManufacturer = "samsung",
                buildBrand = "samsung",
                buildProduct = "dm1qxxx",
                buildDevice = "dm1q",
                buildFingerprint = "samsung/dm1qxxx/dm1q:14/UP1A.231005.007/S911BXXU3BWJM:user/release-keys",
                phoneNumber = "+1 (555) 876-5432",
                batteryHealth = 82,
                testIpv4 = "192.0.2.187",
                wifiSsid = "GalaxyS23_WiFi_91C4",
                state = ProfileState.AVAILABLE
            )
            val result = identityManager.applyAndActivateProfile(p2)
            handleActivationResult(result)
        }

        btnGenerateRandom.setOnClickListener {
            val freshProfile = identityManager.generateAvailableProfile("Randomized Profile")
            val result = identityManager.applyAndActivateProfile(freshProfile)
            handleActivationResult(result)
        }
    }

    private fun handleActivationResult(result: com.example.deviceidlab.model.ProfileActivationResult) {
        renderCurrentIdentity()
        if (result.success) {
            tvStatus.text = "${result.message}\n(Total consumed/exempted: ${identityManager.getConsumedCount()})"
        } else {
            tvStatus.text = "ACTIVATION REJECTED: ${result.message}\nReason: ${result.rejectionReason ?: "UNKNOWN"}"
        }
    }

    private fun renderCurrentIdentity() {
        val profile = identityManager.getActiveProfile()
        val createdStr = dateFormat.format(Date(profile.createdAt))
        val consumedStr = if (profile.consumedAt != null) dateFormat.format(Date(profile.consumedAt)) else "NOT_CONSUMED"
        val fingerprint = profile.computeFingerprint().take(12) + "..."
        val uniquenessStatus = identityManager.getProfileUniquenessStatus()
        val consistencyStatus = identityManager.getProfileConsistencyStatus()
        val ipStatus = identityManager.getIpProfileStatus()

        tvProfileLifecycle.text = StringBuilder()
            .append("Profile ID: ${profile.id}\n")
            .append("Profile State: ${profile.state}\n")
            .append("PROFILE UNIQUENESS: $uniquenessStatus\n")
            .append("PROFILE CONSISTENCY: $consistencyStatus\n")
            .append("IP PROFILE VALUE: ${profile.testIpv4}\n")
            .append("IP PROFILE STATUS: $ipStatus\n")
            .append("ATOMIC INTEGRITY: ALL_FIELDS_BOUND_TO_SAME_PROFILE\n")
            .append("Fingerprint: $fingerprint\n")
            .append("Created: $createdStr\n")
            .append("Activated/Consumed: $consumedStr\n")
            .append("[Notice: Test-profile IPv4 is synthetic (RFC 5737); does not modify physical Wi-Fi/cellular IP]")
            .toString()

        tvAndroidId.text = "Android ID: ${profile.androidId} [Masked: ${TestApiCatalog.maskValue(profile.androidId)}]"
        tvImei.text = "IMEI: ${profile.imei} [Masked: ${TestApiCatalog.maskValue(profile.imei)}]"
        tvSerial.text = "Serial: ${profile.serialNumber} [Masked: ${TestApiCatalog.maskValue(profile.serialNumber)}]"
        tvMac.text = "MAC: ${profile.macAddress} [Masked: ${TestApiCatalog.maskValue(profile.macAddress)}]\nPhone: ${profile.phoneNumber} | Battery: ${profile.batteryHealth}% | IPv4: ${profile.testIpv4}"
        tvModel.text = "Model: ${profile.buildModel} (${profile.buildManufacturer}) [Product: ${profile.buildProduct}]"
    }

    private fun renderCatalog() {
        val sb = StringBuilder()
        TestApiCatalog.SUPPORTED_APIS.forEachIndexed { index, api ->
            val mode = if (api.isDynamic) "DYNAMIC" else "STATIC (Restart Required)"
            sb.append("${index + 1}. ${api.name}\n   [$mode] -> Key: ${api.configKey}\n")
        }
        tvCatalogList.text = sb.toString()
    }
}
