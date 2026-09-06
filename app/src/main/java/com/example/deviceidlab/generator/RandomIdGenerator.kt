package com.example.deviceidlab.generator

import com.example.deviceidlab.model.DeviceProfile
import java.security.SecureRandom
import java.util.UUID

object RandomIdGenerator {
    private val random = SecureRandom()
    private val hexChars = "0123456789abcdef".toCharArray()

    fun generateRandomHex(length: Int): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(hexChars[random.nextInt(hexChars.size)])
        }
        return sb.toString()
    }

    fun generateRandomImei(): String {
        val sb = StringBuilder(14)
        for (i in 0 until 14) {
            sb.append(random.nextInt(10))
        }
        val sum = calculateLuhnSum(sb.toString())
        val checkDigit = (10 - (sum % 10)) % 10
        sb.append(checkDigit)
        return sb.toString()
    }

    private fun calculateLuhnSum(digits: String): Int {
        var sum = 0
        var alternate = true
        for (i in digits.length - 1 downTo 0) {
            var n = Character.getNumericValue(digits[i])
            if (alternate) {
                n *= 2
                if (n > 9) n = (n % 10) + 1
            }
            sum += n
            alternate = !alternate
        }
        return sum
    }

    fun generateRandomMac(): String {
        val mac = ByteArray(6)
        random.nextBytes(mac)
        mac[0] = (mac[0].toInt() and 0xFE.toByte().toInt()).toByte()
        mac[0] = (mac[0].toInt() or 0x02).toByte()
        return mac.joinToString(":") { String.format("%02X", it) }
    }

    fun generateSyntheticPhoneNumber(previousPhone: String? = null): String {
        var phone: String
        do {
            val prefix = 100 + random.nextInt(900)
            val lineNum = 1000 + random.nextInt(9000)
            phone = "+1 (555) $prefix-$lineNum"
        } while (previousPhone != null && phone == previousPhone)
        return phone
    }

    fun generateBatteryHealth(previousHealth: Int? = null): Int {
        var health: Int
        do {
            // Realistic battery health percentages: 60% to 100%
            health = 60 + random.nextInt(41)
        } while (previousHealth != null && health == previousHealth)
        return health
    }

    fun generateSyntheticWifiSsid(model: String = "Pixel 7", previousSsid: String? = null): String {
        val cleanModel = model.replace(Regex("[^a-zA-Z0-9]"), "")
        var ssid: String
        do {
            val suffix = generateRandomHex(4).uppercase()
            ssid = "${cleanModel}_WiFi_$suffix"
        } while (previousSsid != null && (ssid == previousSsid || ssid == previousSsid.trim('"')))
        return ssid
    }

    /**
     * Generates a synthetic documentation/test IPv4 address using RFC 5737 TEST-NET ranges:
     * - TEST-NET-1: 192.0.2.0/24 (192.0.2.1 .. 192.0.2.254)
     * - TEST-NET-2: 198.51.100.0/24 (198.51.100.1 .. 198.51.100.254)
     * - TEST-NET-3: 203.0.113.0/24 (203.0.113.1 .. 203.0.113.254)
     * Strictly avoids pretending these are real device network/Wi-Fi addresses.
     * Guarantees that the generated address differs from the previous profile.
     */
    fun generateSyntheticTestIpv4(previousIpv4: String? = null): String {
        val prefixes = listOf("192.0.2", "198.51.100", "203.0.113")
        var ip: String
        do {
            val prefix = prefixes[random.nextInt(prefixes.size)]
            val host = 1 + random.nextInt(254) // 1..254
            ip = "$prefix.$host"
        } while (previousIpv4 != null && ip == previousIpv4)
        return ip
    }

    /**
     * Validates that the candidate profile strictly differs from the previous profile in:
     * 1. Fingerprint
     * 2. Android ID
     * 3. Synthetic phone number
     * 4. Battery health
     * 5. Test IPv4 (RFC 5737)
     * Returns true ONLY when ALL 5 required fields are different.
     */
    fun validateProfileUniqueness(candidate: DeviceProfile, previous: DeviceProfile?): Boolean {
        if (previous == null) return true
        if (candidate.computeFingerprint() == previous.computeFingerprint()) return false
        if (candidate.androidId.equals(previous.androidId, ignoreCase = true)) return false
        if (candidate.phoneNumber == previous.phoneNumber) return false
        if (candidate.batteryHealth == previous.batteryHealth) return false
        if (candidate.testIpv4 == previous.testIpv4) return false
        if (candidate.wifiSsid.equals(previous.wifiSsid, ignoreCase = true)) return false
        return true
    }

    fun generateProfile(
        name: String = "Random Profile",
        previousProfile: DeviceProfile? = null
    ): DeviceProfile {
        val models = listOf(
            Triple("Pixel 7", "Google", "google"),
            Triple("Galaxy S23", "Samsung", "samsung"),
            Triple("Xiaomi 13", "Xiaomi", "xiaomi"),
            Triple("OnePlus 11", "OnePlus", "oneplus")
        )

        var attempts = 0
        while (attempts < 50) {
            val selected = models[random.nextInt(models.size)]
            val id = UUID.randomUUID().toString()
            val androidId = generateRandomHex(16)
            val imei = generateRandomImei()
            val serialNumber = generateRandomHex(12).uppercase()
            val mac = generateRandomMac()
            val model = selected.first
            val manufacturer = selected.second
            val brand = selected.third
            val product = model.lowercase().replace(" ", "_")
            val device = product
            val fingerprint = "$brand/$product/$device:13/TQ3A.230901.001/$androidId:user/release-keys"
            val phoneNumber = generateSyntheticPhoneNumber(previousProfile?.phoneNumber)
            val batteryHealth = generateBatteryHealth(previousProfile?.batteryHealth)
            val testIpv4 = generateSyntheticTestIpv4(previousProfile?.testIpv4)
            val wifiSsid = generateSyntheticWifiSsid(model, previousProfile?.wifiSsid)

            val candidate = DeviceProfile(
                id = id,
                name = name,
                androidId = androidId,
                imei = imei,
                serialNumber = serialNumber,
                macAddress = mac,
                buildModel = model,
                buildManufacturer = manufacturer,
                buildBrand = brand,
                buildProduct = product,
                buildDevice = device,
                buildFingerprint = fingerprint,
                phoneNumber = phoneNumber,
                batteryHealth = batteryHealth,
                testIpv4 = testIpv4,
                wifiSsid = wifiSsid
            )

            // Automated uniqueness check rejects profile if any required field is identical
            if (validateProfileUniqueness(candidate, previousProfile)) {
                return candidate
            }
            attempts++
        }

        // Fallback with guaranteed distinct offsets
        val id = UUID.randomUUID().toString()
        val androidId = generateRandomHex(16)
        val imei = generateRandomImei()
        val prevPhone = previousProfile?.phoneNumber ?: "+1 (555) 000-0000"
        val prevHealth = previousProfile?.batteryHealth ?: 100
        val nextHealth = if (prevHealth <= 70) 95 else prevHealth - 5
        val nextPhone = "+1 (555) ${random.nextInt(899) + 100}-${random.nextInt(8999) + 1000}"
        val prevIpv4 = previousProfile?.testIpv4 ?: "192.0.2.1"
        val nextIpv4 = if (prevIpv4 == "192.0.2.101") "192.0.2.187" else "192.0.2.101"
        val prevSsid = previousProfile?.wifiSsid ?: "Pixel7_WiFi_A7F2"
        val nextSsid = if (prevSsid == "Pixel7_WiFi_A7F2") "GalaxyS23_WiFi_91C4" else "Pixel7_WiFi_A7F2"

        return DeviceProfile(
            id = id,
            name = name,
            androidId = androidId,
            imei = imei,
            serialNumber = generateRandomHex(12).uppercase(),
            macAddress = generateRandomMac(),
            buildModel = "Pixel 7",
            buildManufacturer = "Google",
            buildBrand = "google",
            buildProduct = "panther",
            buildDevice = "panther",
            buildFingerprint = "google/panther/panther:13/TQ3A.230901.001/$androidId:user/release-keys",
            phoneNumber = if (nextPhone == prevPhone) "+1 (555) 999-9999" else nextPhone,
            batteryHealth = nextHealth,
            testIpv4 = nextIpv4,
            wifiSsid = nextSsid
        )
    }
}
