package com.example.deviceidlab.runtime

import com.example.deviceidlab.hook.NPatchAuditManager
import com.example.deviceidlab.hook.NPatchConfig
import com.example.deviceidlab.hook.NetworkApiCatalog
import com.example.deviceidlab.model.DeviceProfile
import com.example.deviceidlab.model.ProfileState
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
}
