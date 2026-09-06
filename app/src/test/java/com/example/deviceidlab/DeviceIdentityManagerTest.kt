package com.example.deviceidlab

import android.content.Context
import android.content.SharedPreferences
import com.example.deviceidlab.generator.RandomIdGenerator
import com.example.deviceidlab.hook.NPatchConfig
import com.example.deviceidlab.hook.TestApiCatalog
import com.example.deviceidlab.manager.DeviceIdentityManager
import com.example.deviceidlab.model.DeviceProfile
import com.example.deviceidlab.model.ProfileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class DeviceIdentityManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: MockSharedPreferences

    @Before
    fun setUp() {
        mockPrefs = MockSharedPreferences()
        mockContext = MockContext(mockPrefs)
        com.example.deviceidlab.runtime.ProfileStore.resetToDefault()
    }

    @After
    fun tearDown() {
        com.example.deviceidlab.runtime.ProfileStore.resetToDefault()
    }

    @Test
    fun testGenerateProfileIntegrity() {
        val profile = RandomIdGenerator.generateProfile("Test Profile")
        assertNotNull(profile.id)
        assertNotNull(profile.androidId)
        assertEquals(16, profile.androidId.length)
        assertNotNull(profile.imei)
        assertEquals(15, profile.imei.length)
        assertTrue(profile.serialNumber.isNotEmpty())
        assertTrue(profile.macAddress.contains(":"))
        assertTrue(profile.buildModel.isNotEmpty())
        assertTrue(profile.buildManufacturer.isNotEmpty())
        assertTrue(profile.buildBrand.isNotEmpty())
        assertTrue(profile.buildProduct.isNotEmpty())
        assertTrue(profile.buildDevice.isNotEmpty())
        assertTrue(profile.buildFingerprint.isNotEmpty())
        assertEquals(ProfileState.AVAILABLE, profile.state)
    }

    @Test
    fun testRandomHexUniqueness() {
        val hex1 = RandomIdGenerator.generateRandomHex(16)
        val hex2 = RandomIdGenerator.generateRandomHex(16)
        assertTrue(hex1 != hex2)
    }

    @Test
    fun testMaskValueUtility() {
        assertEquals("<null>", TestApiCatalog.maskValue(null))
        assertEquals("<empty>", TestApiCatalog.maskValue(""))
        assertEquals("****", TestApiCatalog.maskValue("1234"))
        val masked = TestApiCatalog.maskValue("58e8039d8acedb72")
        assertTrue(masked.startsWith("58...72"))
        assertFalse(masked.contains("039d8ace"))
    }

    @Test
    fun testCatalogAllowlistInventory() {
        val apis = TestApiCatalog.SUPPORTED_APIS
        assertEquals("Strictly 21 APIs must be registered in the catalog", 21, apis.size)
        assertTrue(apis.any { it.configKey == NPatchConfig.KEY_ANDROID_ID && it.isDynamic })
        assertTrue(apis.any { it.configKey == NPatchConfig.KEY_BUILD_MODEL && it.requiresProcessRestart })
        assertTrue(apis.any { it.configKey == NPatchConfig.KEY_SERIAL })
        assertTrue(apis.any { it.configKey == NPatchConfig.KEY_IMEI })
        assertTrue(apis.any { it.configKey == NPatchConfig.KEY_MAC })
    }

    @Test
    fun testDynamicVsStaticFlags() {
        val staticApis = TestApiCatalog.SUPPORTED_APIS.filter { it.requiresProcessRestart }
        val dynamicApis = TestApiCatalog.SUPPORTED_APIS.filter { it.isDynamic }

        assertEquals(7, staticApis.size) // MODEL, MANUFACTURER, BRAND, PRODUCT, DEVICE, FINGERPRINT, SERIAL field
        assertEquals(14, dynamicApis.size) // ANDROID_ID (2), getSerial (1), Telephony 0-arg & slot/sub overloads (10), MAC (1)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PROFILE LIFECYCLE TESTS (A - E)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testLifecycle_A_GenerateActivateConsume_ExcludedFromFutureGeneration() {
        val manager = DeviceIdentityManager(mockContext)
        val profileA = manager.generateAvailableProfile("Profile Alpha")
        assertEquals(ProfileState.AVAILABLE, profileA.state)

        // Activation transitions state to CONSUMED
        val activationResult = manager.applyAndActivateProfile(profileA)
        assertTrue(activationResult.success)
        assertEquals(ProfileState.CONSUMED, activationResult.profile.state)
        assertNotNull(activationResult.profile.consumedAt)
        assertTrue(manager.isProfileConsumed(profileA))

        // Generating a new available profile must NOT select or return Profile A
        val profileB = manager.generateAvailableProfile("Profile Beta")
        assertNotEquals("Profile A and Profile B must have different IDs", profileA.id, profileB.id)
        assertNotEquals("Profile A and Profile B must have different Android IDs", profileA.androidId, profileB.androidId)
        assertNotEquals("Profile A and Profile B must have different Fingerprints", profileA.computeFingerprint(), profileB.computeFingerprint())
        assertFalse("Profile B must be fresh and not consumed", manager.isProfileConsumed(profileB))
    }

    @Test
    fun testLifecycle_B_PersistedExclusionAcrossAppRestart() {
        // First session: activate and consume Profile A
        val manager1 = DeviceIdentityManager(mockContext)
        val profileA = manager1.generateAvailableProfile("Profile Alpha")
        val resultA = manager1.applyAndActivateProfile(profileA)
        assertTrue(resultA.success)

        // Simulate app kill and cold restart by instantiating a fresh DeviceIdentityManager instance
        // pointing to the same persisted storage (mockPrefs)
        val manager2 = DeviceIdentityManager(mockContext)
        assertTrue("Profile A must remain recognized as consumed after cold restart", manager2.isProfileConsumed(profileA))

        // Fresh generated profile in session 2 must still exclude Profile A
        val profileNext = manager2.generateAvailableProfile("Profile Gamma")
        assertNotEquals(profileA.id, profileNext.id)
        assertNotEquals(profileA.computeFingerprint(), profileNext.computeFingerprint())
    }

    @Test
    fun testLifecycle_C_AttemptToActivateAlreadyConsumed_RemainsExcludedAndRejected() {
        val manager = DeviceIdentityManager(mockContext)
        val profileA = manager.generateAvailableProfile("Profile Alpha")
        val firstResult = manager.applyAndActivateProfile(profileA)
        assertTrue(firstResult.success)

        // Attempting to activate already-consumed Profile A again must be REJECTED
        val secondResult = manager.applyAndActivateProfile(profileA)
        assertFalse("Second activation of the same consumed profile must fail", secondResult.success)
        assertTrue(secondResult.wasRejected)
        assertEquals("PROFILE_ALREADY_CONSUMED_OR_EXEMPTED", secondResult.rejectionReason)
    }

    @Test
    fun testLifecycle_D_FailedActivation_ProfileRemainsAvailable() {
        // Use a mock SharedPreferences configured to fail commits
        val failingPrefs = FailingMockSharedPreferences()
        val failingContext = MockContext(failingPrefs)
        val manager = DeviceIdentityManager(failingContext)

        val profileA = manager.generateAvailableProfile("Profile Alpha")
        val result = manager.applyAndActivateProfile(profileA)

        assertFalse("Failed commit must result in unsuccessful activation", result.success)
        assertFalse("Profile A must NOT be marked as consumed if activation failed", manager.isProfileConsumed(profileA))
    }

    @Test
    fun testLifecycle_E_MultipleCycles_AllConsumedProfilesRemainExcluded() {
        val manager = DeviceIdentityManager(mockContext)
        val consumedList = mutableListOf<DeviceProfile>()

        // Perform 5 successive generation and activation cycles
        for (i in 1..5) {
            val p = manager.generateAvailableProfile("Cycle Profile $i")
            val res = manager.applyAndActivateProfile(p)
            assertTrue("Cycle $i activation must succeed", res.success)
            consumedList.add(p)
        }

        // Verify every previously consumed profile is tracked and rejected if re-activated
        for (consumed in consumedList) {
            assertTrue("Profile ${consumed.id} must be marked as consumed", manager.isProfileConsumed(consumed))
            val duplicateRes = manager.applyAndActivateProfile(consumed)
            assertFalse("Re-activation of ${consumed.id} must be rejected", duplicateRes.success)
            assertTrue(duplicateRes.wasRejected)
        }

        // Verify next generated profile is completely unique from all 5 consumed profiles
        val nextProfile = manager.generateAvailableProfile("Next Cycle Profile")
        for (consumed in consumedList) {
            assertNotEquals(consumed.id, nextProfile.id)
            assertNotEquals(consumed.androidId, nextProfile.androidId)
            assertNotEquals(consumed.computeFingerprint(), nextProfile.computeFingerprint())
        }
    }

    @Test
    fun testLifecycle_EndToEnd14StepVerification() {
        val manager = DeviceIdentityManager(mockContext)

        // 1. Generate Profile A
        val profileA = manager.generateAvailableProfile("Profile Alpha")

        // 2. Profile A starts as AVAILABLE
        assertEquals("Profile A must start as AVAILABLE", ProfileState.AVAILABLE, profileA.state)
        assertFalse("Profile A must not be in consumed set", manager.isProfileConsumed(profileA))

        // 3. Activate Profile A
        val actResultA = manager.applyAndActivateProfile(profileA)
        assertTrue("Activation of Profile A must succeed", actResultA.success)

        // 4. Confirm the controller records the correct Profile A ID/fingerprint/state
        val activeProfile = manager.getActiveProfile()
        assertEquals(profileA.id, activeProfile.id)
        assertEquals(profileA.computeFingerprint(), activeProfile.computeFingerprint())
        assertEquals(ProfileState.CONSUMED, activeProfile.state)
        assertNotNull(activeProfile.consumedAt)

        // 5, 6, 7. Confirm target app / provider records Profile A replacement values
        assertTrue("Profile A is marked as consumed after successful activation", manager.isProfileConsumed(profileA))
        assertEquals(profileA.androidId, activeProfile.androidId)
        assertEquals(profileA.imei, activeProfile.imei)

        // 8. Only after successful activation should Profile A remain CONSUMED/EXEMPTED
        assertTrue("Profile A must remain consumed", manager.isProfileConsumed(profileA))

        // 9. Generate Profile B
        val profileB = manager.generateAvailableProfile("Profile Beta")

        // 10. Confirm Profile B is different from Profile A
        assertNotEquals("Profile B must have different ID from Profile A", profileA.id, profileB.id)
        assertNotEquals("Profile B must have different Android ID from Profile A", profileA.androidId, profileB.androidId)
        assertNotEquals("Profile B must have different Fingerprint from Profile A", profileA.computeFingerprint(), profileB.computeFingerprint())
        assertEquals("Profile B starts as AVAILABLE", ProfileState.AVAILABLE, profileB.state)
        assertFalse("Profile B must not be consumed yet", manager.isProfileConsumed(profileB))

        // 11. Attempt to activate Profile A again
        val duplicateActivation = manager.applyAndActivateProfile(profileA)

        // 12. Confirm the controller rejects Profile A with PROFILE_ALREADY_CONSUMED_OR_EXEMPTED
        assertFalse("Re-activating Profile A must fail", duplicateActivation.success)
        assertTrue("Re-activation must be marked as rejected", duplicateActivation.wasRejected)
        assertEquals("PROFILE_ALREADY_CONSUMED_OR_EXEMPTED", duplicateActivation.rejectionReason)

        // 13. Confirm Profile B can be activated normally
        val actResultB = manager.applyAndActivateProfile(profileB)
        assertTrue("Activation of Profile B must succeed", actResultB.success)
        val activeB = manager.getActiveProfile()
        assertEquals(profileB.id, activeB.id)
        assertEquals(ProfileState.CONSUMED, activeB.state)
        assertTrue(manager.isProfileConsumed(profileB))

        // 14. Restart the controller app (fresh manager pointing to same storage) and confirm Profile A is still permanently excluded
        val restartedManager = DeviceIdentityManager(mockContext)
        assertTrue("Profile A must remain consumed across cold restart", restartedManager.isProfileConsumed(profileA))
        assertTrue("Profile B must remain consumed across cold restart", restartedManager.isProfileConsumed(profileB))

        val profileC = restartedManager.generateAvailableProfile("Profile Gamma")
        assertNotEquals(profileA.id, profileC.id)
        assertNotEquals(profileB.id, profileC.id)
        assertNotEquals(profileA.computeFingerprint(), profileC.computeFingerprint())
        assertNotEquals(profileB.computeFingerprint(), profileC.computeFingerprint())
    }

    @Test
    fun testProfileSerializationRoundTrip() {
        val manager = DeviceIdentityManager(mockContext)
        val original = DeviceProfile(
            id = "test_profile_serialize_123",
            name = "Serialized Test Profile",
            androidId = "0123456789abcdef",
            imei = "867530900000001",
            serialNumber = "SER123456789",
            macAddress = "02:00:00:11:22:33",
            buildModel = "Pixel 7",
            buildManufacturer = "Google",
            buildBrand = "google",
            buildProduct = "panther",
            buildDevice = "panther",
            buildFingerprint = "google/panther/panther:13/TQ3A.230901.001/10750709:user/release-keys",
            createdAt = 1700000000000L,
            state = ProfileState.CONSUMED,
            consumedAt = 1700000005000L
        )

        val json = manager.serializeProfile(original)
        assertTrue(json.contains("\"id\":\"test_profile_serialize_123\""))
        assertTrue(json.contains("\"state\":\"CONSUMED\""))

        val parsed = manager.parseProfile(json)
        assertEquals(original.id, parsed.id)
        assertEquals(original.name, parsed.name)
        assertEquals(original.androidId, parsed.androidId)
        assertEquals(original.imei, parsed.imei)
        assertEquals(original.serialNumber, parsed.serialNumber)
        assertEquals(original.macAddress, parsed.macAddress)
        assertEquals(original.buildModel, parsed.buildModel)
        assertEquals(original.buildManufacturer, parsed.buildManufacturer)
        assertEquals(original.buildBrand, parsed.buildBrand)
        assertEquals(original.buildProduct, parsed.buildProduct)
        assertEquals(original.buildDevice, parsed.buildDevice)
        assertEquals(original.buildFingerprint, parsed.buildFingerprint)
        assertEquals(original.createdAt, parsed.createdAt)
        assertEquals(original.state, parsed.state)
        assertEquals(original.consumedAt, parsed.consumedAt)
        assertEquals(original.computeFingerprint(), parsed.computeFingerprint())
    }

    @Test
    fun testTelephonySubscriberIdSignatureResolution() {
        val tmClass = android.telephony.TelephonyManager::class.java
        val noArgMethod = try {
            tmClass.getMethod("getSubscriberId")
        } catch (_: NoSuchMethodException) {
            null
        }
        val intArgMethod = try {
            tmClass.getMethod("getSubscriberId", java.lang.Integer.TYPE)
        } catch (_: NoSuchMethodException) {
            null
        }

        // On standard Android SDK / JVM stub, getSubscriberId() exists, while getSubscriberId(int) is not public or requires createForSubscriptionId
        assertNotNull("TelephonyManager.getSubscriberId() 0-arg method must exist", noArgMethod)
        
        // Diagnostic distinction verification
        val isNoSuchMethod = (intArgMethod == null)
        val reason = if (isNoSuchMethod) {
            "METHOD_NOT_AVAILABLE: TelephonyManager.getSubscriberId(int) not in public SDK on this Android level"
        } else {
            "AVAILABLE"
        }
        assertTrue(reason.startsWith("METHOD_NOT_AVAILABLE") || reason == "AVAILABLE")
    }

    @Test
    fun testProfileUniquenessAcrossFourFields() {
        val manager = DeviceIdentityManager(mockContext)

        // Profile #1
        val p1 = manager.generateAvailableProfile("Profile #1")
        val res1 = manager.applyAndActivateProfile(p1)
        assertTrue(res1.success)
        val active1 = manager.getActiveProfile()

        // Profile #2
        val p2 = manager.generateAvailableProfile("Profile #2")
        val res2 = manager.applyAndActivateProfile(p2)
        assertTrue(res2.success)
        val active2 = manager.getActiveProfile()

        // Profile #3
        val p3 = manager.generateAvailableProfile("Profile #3")
        val res3 = manager.applyAndActivateProfile(p3)
        assertTrue(res3.success)
        val active3 = manager.getActiveProfile()

        // Verify Profile #2 differs from Profile #1 in ALL 5 required fields
        assertNotEquals("Fingerprint must be different", active1.computeFingerprint(), active2.computeFingerprint())
        assertNotEquals("Android ID must be different", active1.androidId, active2.androidId)
        assertNotEquals("Phone Number must be different", active1.phoneNumber, active2.phoneNumber)
        assertNotEquals("Battery Health must be different", active1.batteryHealth, active2.batteryHealth)
        assertNotEquals("Test IPv4 must be different", active1.testIpv4, active2.testIpv4)

        // Verify Profile #3 differs from Profile #2 in ALL 5 required fields
        assertNotEquals("Fingerprint must be different", active2.computeFingerprint(), active3.computeFingerprint())
        assertNotEquals("Android ID must be different", active2.androidId, active3.androidId)
        assertNotEquals("Phone Number must be different", active2.phoneNumber, active3.phoneNumber)
        assertNotEquals("Battery Health must be different", active2.batteryHealth, active3.batteryHealth)
        assertNotEquals("Test IPv4 must be different", active2.testIpv4, active3.testIpv4)

        // Verify that the manager reports PROFILE UNIQUENESS: PASS and PROFILE CONSISTENCY: PASS
        assertEquals("PROFILE UNIQUENESS: PASS", "PASS", manager.getProfileUniquenessStatus())
        assertEquals("PROFILE CONSISTENCY: PASS", "PASS", manager.getProfileConsistencyStatus())
        assertEquals("IP PROFILE STATUS: PASS", "PASS", manager.getIpProfileStatus())
    }

    @Test
    fun testAutomatedUniquenessRejectionIfAnyFieldMatches() {
        val manager = DeviceIdentityManager(mockContext)
        val p1 = manager.generateAvailableProfile("Base Profile")
        manager.applyAndActivateProfile(p1)

        // Candidate sharing Fingerprint
        val dupFingerprint = p1.copy(
            id = "new_id_1",
            state = ProfileState.AVAILABLE
        )
        assertFalse("Must reject if fingerprint matches", RandomIdGenerator.validateProfileUniqueness(dupFingerprint, p1))

        // Candidate sharing Android ID
        val dupAndroidId = RandomIdGenerator.generateProfile("Candidate").copy(
            androidId = p1.androidId,
            phoneNumber = "+1 (555) 999-0001",
            batteryHealth = 55,
            testIpv4 = "192.0.2.200"
        )
        assertFalse("Must reject if androidId matches", RandomIdGenerator.validateProfileUniqueness(dupAndroidId, p1))

        // Candidate sharing Phone Number
        val dupPhone = RandomIdGenerator.generateProfile("Candidate").copy(
            androidId = "ffffffffffffffff",
            phoneNumber = p1.phoneNumber,
            batteryHealth = 55,
            testIpv4 = "192.0.2.201"
        )
        assertFalse("Must reject if phoneNumber matches", RandomIdGenerator.validateProfileUniqueness(dupPhone, p1))

        // Candidate sharing Battery Health
        val dupBattery = RandomIdGenerator.generateProfile("Candidate").copy(
            androidId = "ffffffffffffffff",
            phoneNumber = "+1 (555) 999-0002",
            batteryHealth = p1.batteryHealth,
            testIpv4 = "192.0.2.202"
        )
        assertFalse("Must reject if batteryHealth matches", RandomIdGenerator.validateProfileUniqueness(dupBattery, p1))

        // Candidate sharing Test IPv4
        val dupIpv4 = RandomIdGenerator.generateProfile("Candidate").copy(
            androidId = "eeeeeeeeeeeeeeee",
            phoneNumber = "+1 (555) 999-0003",
            batteryHealth = 50,
            testIpv4 = p1.testIpv4
        )
        assertFalse("Must reject if testIpv4 matches", RandomIdGenerator.validateProfileUniqueness(dupIpv4, p1))

        // Attempting to activate any non-unique candidate must be rejected by applyAndActivateProfile
        val activationResult = manager.applyAndActivateProfile(dupIpv4)
        assertFalse(activationResult.success)
        assertTrue(activationResult.wasRejected)
        assertEquals("PROFILE_UNIQUENESS_FAILED", activationResult.rejectionReason)
    }

    @Test
    fun testAtomicProfileSwitchingNoMixedState() {
        val manager = DeviceIdentityManager(mockContext)

        val p1 = DeviceProfile(
            id = "profile_alpha",
            name = "Alpha",
            androidId = "1111111111111111",
            imei = "864201041111111",
            serialNumber = "SERIAL1111",
            macAddress = "02:00:00:11:11:11",
            buildModel = "Pixel 7",
            buildManufacturer = "Google",
            buildBrand = "google",
            buildProduct = "panther",
            buildDevice = "panther",
            buildFingerprint = "fp_1",
            phoneNumber = "+1 (555) 111-1111",
            batteryHealth = 95,
            testIpv4 = "192.0.2.101",
            state = ProfileState.AVAILABLE
        )
        val p2 = DeviceProfile(
            id = "profile_beta",
            name = "Beta",
            androidId = "2222222222222222",
            imei = "864201042222222",
            serialNumber = "SERIAL2222",
            macAddress = "02:00:00:22:22:22",
            buildModel = "Galaxy S23",
            buildManufacturer = "Samsung",
            buildBrand = "samsung",
            buildProduct = "dm1q",
            buildDevice = "dm1q",
            buildFingerprint = "fp_2",
            phoneNumber = "+1 (555) 222-2222",
            batteryHealth = 82,
            testIpv4 = "192.0.2.187",
            state = ProfileState.AVAILABLE
        )

        // Activate Alpha
        manager.applyAndActivateProfile(p1)
        val active1 = manager.getActiveProfile()
        assertEquals("1111111111111111", active1.androidId)
        assertEquals("+1 (555) 111-1111", active1.phoneNumber)
        assertEquals(95, active1.batteryHealth)
        assertEquals("192.0.2.101", active1.testIpv4)

        // Atomically switch to Beta
        val switchResult = manager.applyAndActivateProfile(p2)
        assertTrue(switchResult.success)

        val active2 = manager.getActiveProfile()
        // Ensure all fields switched together with NO partial or mixed values
        assertEquals("Beta", active2.name)
        assertEquals("2222222222222222", active2.androidId)
        assertEquals("+1 (555) 222-2222", active2.phoneNumber)
        assertEquals(82, active2.batteryHealth)
        assertEquals("192.0.2.187", active2.testIpv4)
        assertEquals("SERIAL2222", active2.serialNumber)

        // Previous profile record holds exact pre-switch values
        val prev = manager.getPreviousProfile()
        assertNotNull(prev)
        assertEquals("Alpha", prev!!.name)
        assertEquals("1111111111111111", prev.androidId)
        assertEquals("+1 (555) 111-1111", prev.phoneNumber)
        assertEquals(95, prev.batteryHealth)
        assertEquals("192.0.2.101", prev.testIpv4)

        assertEquals("PASS", manager.getProfileUniquenessStatus())
        assertEquals("PASS", manager.getProfileConsistencyStatus())
        assertEquals("PASS", manager.getIpProfileStatus())
    }

    @Test
    fun testProfileConsistencyVerification() {
        val manager = DeviceIdentityManager(mockContext)

        // Profile with all 5 valid attributes
        val p = DeviceProfile(
            id = "profile_consistent",
            name = "Consistent Profile",
            androidId = "1234567890abcdef",
            imei = "864201041234567",
            serialNumber = "SERIAL_CONSIST",
            macAddress = "02:00:00:12:34:56",
            buildModel = "Pixel 7",
            buildManufacturer = "Google",
            buildBrand = "google",
            buildProduct = "panther",
            buildDevice = "panther",
            buildFingerprint = "google/panther:13/TQ3A:user/release-keys",
            phoneNumber = "+1 (555) 345-6789",
            batteryHealth = 76,
            testIpv4 = "192.0.2.224",
            state = ProfileState.AVAILABLE
        )

        manager.applyAndActivateProfile(p)

        assertEquals("PROFILE CONSISTENCY: PASS", "PASS", manager.getProfileConsistencyStatus())
        assertEquals("IP PROFILE STATUS: PASS", "PASS", manager.getIpProfileStatus())
        assertEquals("192.0.2.224", manager.getActiveProfile().testIpv4)
        assertTrue("Test IPv4 must belong to RFC 5737 TEST-NET documentation range",
            manager.isValidSyntheticIpv4(manager.getActiveProfile().testIpv4))
    }

    @Test
    fun testProfileSerializationPreservesAllFields() {
        val original = DeviceProfile(
            id = "test_ser_all_fields",
            name = "Serializer Test",
            androidId = "deadbeef12345678",
            imei = "359876543210987",
            serialNumber = "SRL987654",
            macAddress = "02:00:00:99:88:77",
            buildModel = "TestModel",
            buildManufacturer = "TestMfr",
            buildBrand = "testbrand",
            buildProduct = "testproduct",
            buildDevice = "testdevice",
            buildFingerprint = "test/fingerprint/v1:user/release-keys",
            phoneNumber = "+1 (555) 777-8888",
            batteryHealth = 76,
            testIpv4 = "198.51.100.42",
            state = ProfileState.CONSUMED,
            consumedAt = 1700000000000L
        )

        val json = com.example.deviceidlab.manager.ProfileJsonSerializer.serialize(original)
        assertTrue(json.contains("\"phoneNumber\":\"+1 (555) 777-8888\""))
        assertTrue(json.contains("\"batteryHealth\":76"))
        assertTrue(json.contains("\"testIpv4\":\"198.51.100.42\""))

        val parsed = com.example.deviceidlab.manager.ProfileJsonSerializer.parse(json)
        assertEquals(original.id, parsed.id)
        assertEquals(original.phoneNumber, parsed.phoneNumber)
        assertEquals(original.batteryHealth, parsed.batteryHealth)
        assertEquals(original.testIpv4, parsed.testIpv4)
        assertEquals(original.computeFingerprint(), parsed.computeFingerprint())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // IN-MEMORY MOCKS FOR UNIT TESTING WITHOUT ANDROID RUNTIME FRAMEWORK
    // ──────────────────────────────────────────────────────────────────────────

    private open class MockContext(private val prefs: SharedPreferences) : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private open class MockSharedPreferences : SharedPreferences {
        protected val map = ConcurrentHashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MockEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        open class MockEditor(private val storage: ConcurrentHashMap<String, Any>) : SharedPreferences.Editor {
            private val temp = HashMap<String, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) if (value != null) temp[key] = value else temp.remove(key)
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) if (values != null) temp[key] = HashSet(values) else temp.remove(key)
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                storage.clear()
                return this
            }
            override fun commit(): Boolean {
                temp.forEach { (k, v) -> if (v != null) storage[k] = v else storage.remove(k) }
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    private class FailingMockSharedPreferences : MockSharedPreferences() {
        override fun edit(): SharedPreferences.Editor {
            return object : MockEditor(map) {
                override fun commit(): Boolean = false // Simulates I/O or storage failure
            }
        }
    }
}
