package com.example.deviceidlab.runtime

import com.example.deviceidlab.generator.RandomIdGenerator
import com.example.deviceidlab.hook.NPatchAuditManager
import com.example.deviceidlab.hook.NPatchConfig
import com.example.deviceidlab.hook.NetworkApiCatalog
import com.example.deviceidlab.manager.ProfileJsonSerializer
import com.example.deviceidlab.model.DeviceProfile
import com.example.deviceidlab.model.ProfileState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verification test suite for the host-neutral Identity Runtime, ProfileStore,
 * 5-stage verification lifecycle, and Network API classification.
 */
class IdentityRuntimeVerificationTest {

    @Before
    fun setUp() {
        NPatchAuditManager.clearEvents()
        ProfileStore.resetToDefault()
    }

    @After
    fun tearDown() {
        NPatchAuditManager.clearEvents()
        ProfileStore.resetToDefault()
    }

    @Test
    fun testProfileStoreSingleSourceOfTruth() {
        // Ensure the active profile serves as the single source of truth
        val profile = ProfileStore.getActiveProfile()
        assertNotNull(profile)
        assertNotNull(profile.androidId)
        assertNotNull(profile.imei)
        assertNotNull(profile.macAddress)
        assertNotNull(profile.testIpv4)
        assertNotNull(profile.wifiSsid)
        assertNotNull(profile.bssid)

        // Verify synthetic IP conforms to RFC 5737 (TEST-NET-3: 203.0.113.0/24)
        assertTrue(
            "IP must be in RFC 5737 range: ${profile.testIpv4}",
            profile.testIpv4.startsWith("203.0.113.")
        )
        // Verify MAC formatting
        assertTrue(
            "MAC must be colon-separated: ${profile.macAddress}",
            profile.macAddress.split(":").size == 6
        )
    }

    @Test
    fun testDynamicProfileUpdateWithoutRepatching() {
        // Test that updating the profile dynamically changes the values served to targets
        val initialProfile = ProfileStore.getActiveProfile()

        val updatedProfile = DeviceProfile(
            id = "profile_new_test",
            name = "Dynamically Updated Profile",
            androidId = "NPATCH_DYNAMIC_999",
            imei = "NPATCH_DYNAMIC_IMEI",
            serialNumber = "SERIAL_999",
            macAddress = "02:00:11:22:33:99",
            testIpv4 = "203.0.113.99",
            wifiSsid = "Updated_WiFi",
            bssid = "02:00:11:22:33:99",
            city = "Tokyo",
            country = "JP",
            timezone = "Asia/Tokyo",
            latitude = 35.6762,
            longitude = 139.6503,
            state = ProfileState.ACTIVE
        )

        ProfileStore.setActiveProfile(updatedProfile, System.currentTimeMillis())

        val resolved = HostBridge.resolveActiveProfile()
        assertEquals("NPATCH_DYNAMIC_999", resolved.androidId)
        assertEquals("NPATCH_DYNAMIC_IMEI", resolved.imei)
        assertEquals("203.0.113.99", resolved.testIpv4)
        assertEquals("Tokyo", resolved.city)
        assertEquals("JP", resolved.country)
        assertEquals(35.6762, resolved.latitude, 0.0001)

        // Clean up
        ProfileStore.setActiveProfile(initialProfile)
    }

    @Test
    fun testFiveStageVerificationLifecycleDistinctness() {
        val api = "NetworkInterface.getInetAddresses()"
        val targetPkg = "com.example.anytargetapp"
        val targetProc = "com.example.anytargetapp:main"
        val expectedIp = "203.0.113.42"

        // Stage 1: Registered
        HostBridge.reportInterceptionStage(
            targetPkg = targetPkg,
            targetProc = targetProc,
            targetPid = 1234,
            apiName = api,
            stage = NPatchAuditManager.HOOK_REGISTERED
        )
        val stage1 = NPatchAuditManager.getStageVerification(api)
        assertTrue(stage1.isRegistered)
        assertFalse(stage1.isInvoked)
        assertFalse(stage1.isGenerated)
        assertFalse(stage1.isReturned)
        assertFalse(stage1.isTargetObserved)

        // Stage 2: Invoked
        HostBridge.reportInterceptionStage(
            targetPkg = targetPkg,
            targetProc = targetProc,
            targetPid = 1234,
            apiName = api,
            stage = NPatchAuditManager.HOOK_INVOKED
        )
        val stage2 = NPatchAuditManager.getStageVerification(api)
        assertTrue(stage2.isInvoked)
        assertFalse(stage2.isGenerated)

        // Stage 3: Generated
        HostBridge.reportInterceptionStage(
            targetPkg = targetPkg,
            targetProc = targetProc,
            targetPid = 1234,
            apiName = api,
            stage = NPatchAuditManager.VALUE_GENERATED,
            injectedVal = expectedIp
        )
        val stage3 = NPatchAuditManager.getStageVerification(api)
        assertTrue(stage3.isGenerated)
        assertEquals(expectedIp, stage3.expectedValue)
        assertFalse(stage3.isReturned)

        // Stage 4: Returned
        HostBridge.reportInterceptionStage(
            targetPkg = targetPkg,
            targetProc = targetProc,
            targetPid = 1234,
            apiName = api,
            stage = NPatchAuditManager.VALUE_RETURNED,
            injectedVal = expectedIp,
            returnedVal = expectedIp
        )
        val stage4 = NPatchAuditManager.getStageVerification(api)
        assertTrue(stage4.isReturned)
        assertEquals(expectedIp, stage4.observedValue)
        assertFalse(stage4.isTargetObserved)

        // Stage 5: Target Observed
        HostBridge.reportInterceptionStage(
            targetPkg = targetPkg,
            targetProc = targetProc,
            targetPid = 1234,
            apiName = api,
            stage = NPatchAuditManager.TARGET_OBSERVED,
            origVal = "127.0.0.1",
            injectedVal = expectedIp,
            returnedVal = expectedIp
        )
        val stage5 = NPatchAuditManager.getStageVerification(api)
        assertTrue(stage5.isTargetObserved)
        assertEquals("PASS", stage5.result)
        assertEquals(expectedIp, stage5.observedValue)

        // Strict assertion: HOOK_REGISTERED != TARGET_OBSERVED
        assertNotEquals(
            "HOOK_REGISTERED must never equal TARGET_OBSERVED",
            NPatchAuditManager.HOOK_REGISTERED,
            NPatchAuditManager.TARGET_OBSERVED
        )
    }

    @Test
    fun testNetworkApiCatalogClassifications() {
        val entries = NetworkApiCatalog.ENTRIES

        // 1. NetworkInterface.getInetAddresses must be HOOKED_AND_PHYSICALLY_VERIFIED
        val netIfEntry = entries.firstOrNull { it.apiName == "NetworkInterface.getInetAddresses" }
        assertNotNull(netIfEntry)
        assertEquals(NetworkApiCatalog.HookStatus.HOOKED_AND_PHYSICALLY_VERIFIED, netIfEntry?.status)
        assertTrue(netIfEntry?.isImplemented == true)
        assertTrue(netIfEntry?.isRegistered == true)
        assertTrue(netIfEntry?.isInvoked == true)
        assertTrue(netIfEntry?.isGenerated == true)
        assertTrue(netIfEntry?.isReturned == true)
        assertTrue(netIfEntry?.isTargetObserved == true)

        // 2. LinkProperties.getAddresses must be PLATFORM_PERMISSION_RESTRICTED
        val linkPropAddresses = entries.firstOrNull { it.apiName == "LinkProperties.getAddresses" }
        assertNotNull(linkPropAddresses)
        assertEquals(NetworkApiCatalog.HookStatus.PLATFORM_PERMISSION_RESTRICTED, linkPropAddresses?.status)
        assertEquals(NetworkApiCatalog.InterceptionLayer.PLATFORM_RESTRICTED, linkPropAddresses?.layer)
        assertFalse("LinkProperties must not report TARGET_OBSERVED when permission restricted", linkPropAddresses?.isTargetObserved == true)

        // 3. Socket.connect must remain UNSUPPORTED_AT_CURRENT_LAYER
        val socketConnect = entries.firstOrNull { it.apiName.startsWith("Socket.connect") }
        assertNotNull(socketConnect)
        assertEquals(NetworkApiCatalog.HookStatus.UNSUPPORTED_AT_CURRENT_LAYER, socketConnect?.status)
        assertEquals(NetworkApiCatalog.InterceptionLayer.NATIVE_SOCKET, socketConnect?.layer)
        assertFalse(socketConnect?.isImplemented == true)
    }

    @Test
    fun testHostNeutrality() {
        // Verify that HostBridge works for arbitrary package names without hardcoding
        val randomPkg = "com.random.injected.targetapp"
        val randomProc = "com.random.injected.targetapp:worker"
        val pid = 9876

        HostBridge.reportInterceptionStage(
            targetPkg = randomPkg,
            targetProc = randomProc,
            targetPid = pid,
            apiName = "Settings.Secure.getString(android_id)",
            stage = NPatchAuditManager.TARGET_OBSERVED,
            origVal = "original_val",
            injectedVal = "NPATCH_ANDROID_001",
            returnedVal = "NPATCH_ANDROID_001"
        )

        val events = NPatchAuditManager.getAuditEventsForPackage(randomPkg)
        assertEquals(1, events.size)
        assertEquals(randomPkg, events[0].targetPackage)
        assertEquals(randomProc, events[0].targetProcess)
        assertEquals(pid, events[0].targetPid)
        assertEquals("NPATCH_ANDROID_001", events[0].returnedId)
    }

    @Test
    fun testProfileGeneratedWifiSsidUniquenessAndSwitching() {
        // 1. Newly generated profile contains wifiSsid
        val p1 = RandomIdGenerator.generateProfile("Test Profile 1")
        assertNotNull(p1.wifiSsid)
        assertTrue(p1.wifiSsid.isNotBlank())
        assertFalse("wifiSsid must not be default LabTest_WiFi", p1.wifiSsid.contains("LabTest_WiFi"))

        // 2. Two independently generated profiles have different/profile-specific SSIDs
        val p2 = RandomIdGenerator.generateProfile("Test Profile 2", previousProfile = p1)
        assertNotNull(p2.wifiSsid)
        assertTrue(p2.wifiSsid.isNotBlank())
        assertNotEquals("Different profiles must have different SSIDs", p1.wifiSsid, p2.wifiSsid)

        // 6 & 7. Profile switching updates active SSID and HostBridge resolves active ProfileStore profile
        ProfileStore.setActiveProfile(p1)
        assertEquals(p1.wifiSsid, ProfileStore.getActiveProfile().wifiSsid)
        assertEquals(p1.wifiSsid, HostBridge.resolveActiveProfile().wifiSsid)

        ProfileStore.setActiveProfile(p2)
        assertEquals(p2.wifiSsid, ProfileStore.getActiveProfile().wifiSsid)
        assertEquals(p2.wifiSsid, HostBridge.resolveActiveProfile().wifiSsid)
        assertNotEquals(p1.wifiSsid, ProfileStore.getActiveProfile().wifiSsid)

        // 8. Ensure ProfileStore represents Profile B consistently across all fields with no mixed state
        val activeB = ProfileStore.getActiveProfile()
        assertEquals(p2.wifiSsid, activeB.wifiSsid)
        assertEquals(p2.androidId, activeB.androidId)
        assertEquals(p2.imei, activeB.imei)
        assertEquals(p2.macAddress, activeB.macAddress)
        assertEquals(p2.testIpv4, activeB.testIpv4)
        assertNotEquals(p1.androidId, activeB.androidId)
        assertNotEquals(p1.testIpv4, activeB.testIpv4)
    }

    @Test
    fun testWifiSsidSerializationAndLegacyMigration() {
        val testProfile = DeviceProfile(
            id = "test_profile_custom",
            name = "Custom SSID Profile",
            androidId = "custom_android_id",
            imei = "123456789012345",
            serialNumber = "SERIAL_CUSTOM",
            macAddress = "02:00:11:22:33:44",
            wifiSsid = "Custom_SSID_XYZ123",
            state = ProfileState.ACTIVE
        )

        // 3. wifiSsid survives serialization/deserialization
        val json = ProfileJsonSerializer.serialize(testProfile)
        assertTrue(json.contains("\"wifiSsid\":\"Custom_SSID_XYZ123\""))
        val restored = ProfileJsonSerializer.parse(json)
        // 4. Existing wifiSsid survives profile reload
        assertEquals("Custom_SSID_XYZ123", restored.wifiSsid)

        // 5. Legacy profile without wifiSsid receives a valid migrated value
        val legacyJsonWithoutSsid = """
            {
                "id": "legacy_p_1",
                "name": "Legacy Profile",
                "androidId": "legacy_android_id",
                "imei": "987654321098765",
                "serialNumber": "SERIAL_LEGACY",
                "macAddress": "02:00:11:22:33:55",
                "buildModel": "Pixel 7"
            }
        """.trimIndent()
        val migrated = ProfileJsonSerializer.parse(legacyJsonWithoutSsid)
        assertNotNull(migrated.wifiSsid)
        assertTrue(migrated.wifiSsid.isNotBlank())
        assertFalse("Legacy profile must not be migrated to LabTest_WiFi", migrated.wifiSsid.contains("LabTest_WiFi"))
        assertTrue("Migrated SSID should reflect model", migrated.wifiSsid.contains("Pixel7"))

        // Also verify legacy profile with explicit "LabTest_WiFi" gets migrated safely
        val legacyJsonWithLabTest = """
            {
                "id": "legacy_p_2",
                "name": "Legacy Profile With LabTest",
                "androidId": "legacy_android_id_2",
                "imei": "987654321098766",
                "serialNumber": "SERIAL_LEGACY_2",
                "macAddress": "02:00:11:22:33:66",
                "buildModel": "Galaxy S23",
                "wifiSsid": "\"LabTest_WiFi\""
            }
        """.trimIndent()
        val migratedFromLabTest = ProfileJsonSerializer.parse(legacyJsonWithLabTest)
        assertFalse("Must not retain LabTest_WiFi", migratedFromLabTest.wifiSsid.contains("LabTest_WiFi"))
        assertTrue("Migrated SSID should reflect model", migratedFromLabTest.wifiSsid.contains("GalaxyS23"))
    }

    @Test
    fun testLinkPropertiesPermissionClassificationHonesty() {
        // 9. LinkProperties permission failure is classified as PERMISSION_RESTRICTED or PLATFORM_RESTRICTED, NOT MISMATCH
        val expIp = "198.51.100.189"
        val permissionDeniedMessage = "Permission denied — ACCESS_NETWORK_STATE unavailable"

        var isPermissionDenied = false
        val obsLinkProp = try {
            throw SecurityException("Neither user 10864 nor current process has android.permission.ACCESS_NETWORK_STATE.")
        } catch (_: SecurityException) {
            isPermissionDenied = true
            permissionDeniedMessage
        }

        assertTrue(isPermissionDenied)
        val isLinkPropMatch = !isPermissionDenied && obsLinkProp == expIp
        assertFalse("Permission restricted state must not be treated as a value MATCH", isLinkPropMatch)

        val hookStatus = when {
            isLinkPropMatch -> "TARGET_OBSERVED"
            isPermissionDenied -> "PLATFORM_RESTRICTED"
            else -> "HOOK_REGISTERED"
        }
        val matchStatus = when {
            isLinkPropMatch -> "MATCH"
            isPermissionDenied -> "PERMISSION_RESTRICTED"
            else -> "MISMATCH"
        }
        val diagnosis = when {
            isLinkPropMatch -> "LinkProperties interface address intercepted."
            isPermissionDenied -> "Target process lacks ACCESS_NETWORK_STATE, so LinkProperties addresses could not be obtained or verified."
            else -> "LinkProperties not intercepted."
        }

        assertEquals("PLATFORM_RESTRICTED", hookStatus)
        assertEquals("PERMISSION_RESTRICTED", matchStatus)
        assertNotEquals("MISMATCH", matchStatus)
        assertNotEquals("TARGET_OBSERVED", hookStatus)
        assertTrue(diagnosis.contains("lacks ACCESS_NETWORK_STATE"))

        // Verify catalog classification for LinkProperties
        val entries = NetworkApiCatalog.ENTRIES
        val linkAddresses = entries.firstOrNull { it.apiName == "LinkProperties.getAddresses" }
        assertNotNull(linkAddresses)
        assertEquals(NetworkApiCatalog.HookStatus.PLATFORM_PERMISSION_RESTRICTED, linkAddresses?.status)
        assertEquals(NetworkApiCatalog.InterceptionLayer.PLATFORM_RESTRICTED, linkAddresses?.layer)
        assertFalse("TARGET_OBSERVED must not be claimed when permission restricted", linkAddresses?.isTargetObserved == true)
    }
}
