package com.example.deviceidlab.manager

import com.example.deviceidlab.model.DeviceProfile
import com.example.deviceidlab.model.ProfileState

object ProfileJsonSerializer {

    fun escape(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder()
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun serialize(p: DeviceProfile): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"id\":").append(escape(p.id)).append(",")
        sb.append("\"name\":").append(escape(p.name)).append(",")
        sb.append("\"androidId\":").append(escape(p.androidId)).append(",")
        sb.append("\"imei\":").append(escape(p.imei)).append(",")
        sb.append("\"serialNumber\":").append(escape(p.serialNumber)).append(",")
        sb.append("\"macAddress\":").append(escape(p.macAddress)).append(",")
        sb.append("\"buildModel\":").append(escape(p.buildModel)).append(",")
        sb.append("\"buildManufacturer\":").append(escape(p.buildManufacturer)).append(",")
        sb.append("\"buildBrand\":").append(escape(p.buildBrand)).append(",")
        sb.append("\"buildProduct\":").append(escape(p.buildProduct)).append(",")
        sb.append("\"buildDevice\":").append(escape(p.buildDevice)).append(",")
        sb.append("\"buildFingerprint\":").append(escape(p.buildFingerprint)).append(",")
        sb.append("\"phoneNumber\":").append(escape(p.phoneNumber)).append(",")
        sb.append("\"batteryHealth\":").append(p.batteryHealth).append(",")
        sb.append("\"testIpv4\":").append(escape(p.testIpv4)).append(",")
        sb.append("\"wifiSsid\":").append(escape(p.wifiSsid)).append(",")
        sb.append("\"bssid\":").append(escape(p.bssid)).append(",")
        sb.append("\"latitude\":").append(p.latitude).append(",")
        sb.append("\"longitude\":").append(p.longitude).append(",")
        sb.append("\"city\":").append(escape(p.city)).append(",")
        sb.append("\"country\":").append(escape(p.country)).append(",")
        sb.append("\"timezone\":").append(escape(p.timezone)).append(",")
        sb.append("\"carrierName\":").append(escape(p.carrierName)).append(",")
        sb.append("\"simOperator\":").append(escape(p.simOperator)).append(",")
        sb.append("\"createdAt\":").append(p.createdAt).append(",")
        sb.append("\"state\":").append(escape(p.state.name)).append(",")
        sb.append("\"consumedAt\":").append(p.consumedAt ?: "null")
        sb.append("}")
        return sb.toString()
    }

    fun parse(json: String): DeviceProfile {
        val map = parseFlatJsonObject(json)
        val stateStr = map["state"] ?: ProfileState.CONSUMED.name
        val state = try {
            ProfileState.valueOf(stateStr)
        } catch (_: Exception) {
            ProfileState.CONSUMED
        }
        val consumedAt = map["consumedAt"]?.toLongOrNull()
        val createdAt = map["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis()
        val phoneNumber = map["phoneNumber"]?.takeIf { it.isNotEmpty() } ?: "+1 (555) 234-5678"
        val batteryHealth = map["batteryHealth"]?.toIntOrNull() ?: 95
        val testIpv4 = map["testIpv4"]?.takeIf { it.isNotEmpty() } ?: "192.0.2.101"
        val rawSsid = map["wifiSsid"]?.takeIf { it.isNotEmpty() && it != "\"LabTest_WiFi\"" && it != "LabTest_WiFi" }
        val wifiSsid = rawSsid ?: run {
            val model = map["buildModel"]?.takeIf { it.isNotEmpty() } ?: "Pixel 7"
            val seed = (map["androidId"] ?: map["id"] ?: "0").hashCode().toLong()
            com.example.deviceidlab.hook.NPatchConfig.deriveWifiSsid(model, seed)
        }
        val bssid = map["bssid"]?.takeIf { it.isNotEmpty() } ?: "02:00:11:22:33:44"
        val latitude = map["latitude"]?.toDoubleOrNull() ?: 37.7749
        val longitude = map["longitude"]?.toDoubleOrNull() ?: -122.4194
        val city = map["city"]?.takeIf { it.isNotEmpty() } ?: "San Francisco"
        val country = map["country"]?.takeIf { it.isNotEmpty() } ?: "US"
        val timezone = map["timezone"]?.takeIf { it.isNotEmpty() } ?: "America/Los_Angeles"
        val carrierName = map["carrierName"]?.takeIf { it.isNotEmpty() } ?: "Android Carrier"
        val simOperator = map["simOperator"]?.takeIf { it.isNotEmpty() } ?: "310260"

        return DeviceProfile(
            id = map["id"] ?: "",
            name = map["name"] ?: "",
            androidId = map["androidId"] ?: "",
            imei = map["imei"] ?: "",
            serialNumber = map["serialNumber"] ?: "",
            macAddress = map["macAddress"] ?: "",
            buildModel = map["buildModel"] ?: "",
            buildManufacturer = map["buildManufacturer"] ?: "",
            buildBrand = map["buildBrand"] ?: "",
            buildProduct = map["buildProduct"] ?: "",
            buildDevice = map["buildDevice"] ?: "",
            buildFingerprint = map["buildFingerprint"] ?: "",
            phoneNumber = phoneNumber,
            batteryHealth = batteryHealth,
            testIpv4 = testIpv4,
            wifiSsid = wifiSsid,
            bssid = bssid,
            latitude = latitude,
            longitude = longitude,
            city = city,
            country = country,
            timezone = timezone,
            carrierName = carrierName,
            simOperator = simOperator,
            createdAt = createdAt,
            state = state,
            consumedAt = consumedAt
        )
    }

    fun parseFlatJsonObject(json: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return result
        val content = trimmed.substring(1, trimmed.length - 1)

        var i = 0
        while (i < content.length) {
            val keyStartQuote = content.indexOf('"', i)
            if (keyStartQuote == -1) break
            val keyEndQuote = findMatchingQuote(content, keyStartQuote + 1)
            if (keyEndQuote == -1) break
            val key = unescape(content.substring(keyStartQuote + 1, keyEndQuote))

            val colon = content.indexOf(':', keyEndQuote + 1)
            if (colon == -1) break

            var valStart = colon + 1
            while (valStart < content.length && content[valStart].isWhitespace()) {
                valStart++
            }
            if (valStart >= content.length) break

            val value: String
            val nextI: Int
            if (content[valStart] == '"') {
                val valEndQuote = findMatchingQuote(content, valStart + 1)
                if (valEndQuote == -1) break
                value = unescape(content.substring(valStart + 1, valEndQuote))
                nextI = valEndQuote + 1
            } else {
                var comma = content.indexOf(',', valStart)
                if (comma == -1) comma = content.length
                val rawVal = content.substring(valStart, comma).trim()
                value = if (rawVal == "null") "" else rawVal
                nextI = comma + 1
            }

            result[key] = value
            i = nextI
        }
        return result
    }

    private fun findMatchingQuote(s: String, startIndex: Int): Int {
        var i = startIndex
        var escaped = false
        while (i < s.length) {
            val c = s[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                return i
            }
            i++
        }
        return -1
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
