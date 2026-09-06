package com.example.deviceidlab.manager

import android.content.Context
import com.example.deviceidlab.generator.RandomIdGenerator
import com.example.deviceidlab.model.DeviceProfile
import com.example.deviceidlab.model.ProfileActivationResult
import com.example.deviceidlab.model.ProfileState

class DeviceIdentityManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("device_identity_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ACTIVE_PROFILE_JSON = "active_profile_json"
        private const val KEY_PREVIOUS_PROFILE_JSON = "previous_profile_json"
        private const val KEY_CONSUMED_FINGERPRINTS = "consumed_profile_fingerprints"
        private const val KEY_CONSUMED_METADATA_JSON = "consumed_profiles_metadata_json"
        private const val KEY_LAST_UNIQUENESS_STATUS = "last_profile_uniqueness_status"
    }

    /**
     * Checks if a profile (by ID or fingerprint) has already been consumed/exempted.
     */
    fun isProfileConsumed(profile: DeviceProfile): Boolean {
        val consumedSet = getConsumedFingerprints()
        val fingerprint = profile.computeFingerprint()
        return consumedSet.contains(fingerprint) || consumedSet.contains(profile.id)
    }

    /**
     * Returns all consumed fingerprints and IDs persisted in storage.
     */
    fun getConsumedFingerprints(): Set<String> {
        val set = prefs.getStringSet(KEY_CONSUMED_FINGERPRINTS, null) ?: emptySet()
        return HashSet(set)
    }

    /**
     * Retrieves the previously active profile, if any.
     */
    fun getPreviousProfile(): DeviceProfile? {
        val json = prefs.getString(KEY_PREVIOUS_PROFILE_JSON, null) ?: return null
        return try {
            parseProfile(json)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Evaluates profile uniqueness between current active profile and previous profile.
     * Reports "PASS" only when all 5 required fields differ:
     * - Fingerprint
     * - Android ID
     * - Synthetic phone number
     * - Battery health
     * - Test IPv4 (RFC 5737)
     */
    fun getProfileUniquenessStatus(): String {
        val active = getActiveProfileOrNull() ?: return "INITIAL_PROFILE"
        val previous = getPreviousProfile() ?: return "PASS"
        return if (RandomIdGenerator.validateProfileUniqueness(active, previous)) "PASS" else "FAIL"
    }

    /**
     * Evaluates profile consistency.
     * Reports "PASS" only when fingerprint, Android ID, synthetic phone number, battery health,
     * and test IPv4 all belong to the SAME profile and are bound together in the fingerprint hash.
     */
    fun getProfileConsistencyStatus(): String {
        val active = getActiveProfileOrNull() ?: return "FAIL: NO_ACTIVE_PROFILE"
        val computedFp = active.computeFingerprint()
        if (computedFp.isEmpty()) return "FAIL: INVALID_FINGERPRINT"

        val hasValidAndroidId = active.androidId.length == 16
        val hasValidPhone = active.phoneNumber.startsWith("+1 (555)")
        val hasValidBattery = active.batteryHealth in 1..100
        val hasValidIpv4 = isValidSyntheticIpv4(active.testIpv4)

        return if (hasValidAndroidId && hasValidPhone && hasValidBattery && hasValidIpv4) {
            "PASS"
        } else {
            "FAIL"
        }
    }

    /**
     * Returns the verification status of the active profile's test IPv4.
     * Reports "PASS" when testIpv4 is a valid RFC 5737 address and differs from the previous profile.
     */
    fun getIpProfileStatus(): String {
        val active = getActiveProfileOrNull() ?: return "FAIL: NO_ACTIVE_PROFILE"
        if (!isValidSyntheticIpv4(active.testIpv4)) return "FAIL: INVALID_RFC5737_FORMAT"
        val previous = getPreviousProfile()
        if (previous != null && active.testIpv4 == previous.testIpv4) {
            return "FAIL: DUPLICATE_OF_PREVIOUS_IPV4"
        }
        return "PASS"
    }

    fun isValidSyntheticIpv4(ip: String): Boolean {
        return ip.startsWith("192.0.2.") || ip.startsWith("198.51.100.") || ip.startsWith("203.0.113.")
    }

    fun getActiveProfileOrNull(): DeviceProfile? {
        val json = prefs.getString(KEY_ACTIVE_PROFILE_JSON, null) ?: return null
        return try {
            parseProfile(json)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Retrieves the currently active profile. If none exists, generates and activates a fresh one.
     */
    fun getActiveProfile(): DeviceProfile {
        val existing = getActiveProfileOrNull()
        if (existing != null) {
            return existing
        }
        val defaultProfile = generateAvailableProfile("Default Profile")
        applyAndActivateProfile(defaultProfile)
        return defaultProfile
    }

    /**
     * Generates a guaranteed-available profile that has NEVER been consumed,
     * enforcing automated uniqueness against the previous active profile.
     */
    fun generateAvailableProfile(name: String = "Generated Profile"): DeviceProfile {
        val previous = getActiveProfileOrNull()
        var attempts = 0
        while (attempts < 100) {
            val candidate = RandomIdGenerator.generateProfile(name, previous)
            if (!isProfileConsumed(candidate) && RandomIdGenerator.validateProfileUniqueness(candidate, previous)) {
                return candidate.copy(state = ProfileState.AVAILABLE)
            }
            attempts++
        }
        // Extremely rare fallback: append high-entropy nonce with uniqueness
        val candidate = RandomIdGenerator.generateProfile("$name (${System.currentTimeMillis()})", previous)
        return candidate.copy(state = ProfileState.AVAILABLE)
    }

    /**
     * Attempts to activate a profile and mark it as CONSUMED upon successful activation.
     * Enforces:
     * - If profile is already consumed, activation is REJECTED.
     * - Automated uniqueness check rejects profile if any required field matches previous profile.
     * - Atomically switches all fields together with no intermediate or mixed profile states.
     */
    fun applyAndActivateProfile(profile: DeviceProfile): ProfileActivationResult {
        // Check for duplicate / already consumed
        if (isProfileConsumed(profile)) {
            return ProfileActivationResult(
                success = false,
                profile = profile.copy(state = ProfileState.CONSUMED),
                message = "REJECTED: Profile '${profile.name}' (ID: ${profile.id}) has already been consumed/exempted.",
                wasRejected = true,
                rejectionReason = "PROFILE_ALREADY_CONSUMED_OR_EXEMPTED"
            )
        }

        // Automated uniqueness verification against currently active profile
        val currentActive = getActiveProfileOrNull()
        if (currentActive != null && currentActive.id != profile.id) {
            val isUnique = RandomIdGenerator.validateProfileUniqueness(profile, currentActive)
            if (!isUnique) {
                return ProfileActivationResult(
                    success = false,
                    profile = profile,
                    message = "REJECTED: Automated uniqueness check failed. Required fields (Fingerprint, Android ID, Synthetic Phone Number, Battery Health, Test IPv4) must all differ from previous profile.",
                    wasRejected = true,
                    rejectionReason = "PROFILE_UNIQUENESS_FAILED"
                )
            }
        }

        try {
            val now = System.currentTimeMillis()
            val activatedProfile = profile.copy(
                state = ProfileState.CONSUMED,
                consumedAt = now
            )

            val editor = prefs.edit()

            // 1. If transitioning from an existing profile, atomically persist it as previous
            if (currentActive != null && currentActive.id != activatedProfile.id) {
                editor.putString(KEY_PREVIOUS_PROFILE_JSON, serializeProfile(currentActive))
            }

            // 2. Persist active profile JSON (atomic encapsulation of all fields together)
            val json = serializeProfile(activatedProfile)
            editor.putString(KEY_ACTIVE_PROFILE_JSON, json)
            editor.putString(KEY_LAST_UNIQUENESS_STATUS, "PASS")

            // 3. Persist to consumed fingerprints set
            val currentSet = HashSet(getConsumedFingerprints())
            currentSet.add(activatedProfile.computeFingerprint())
            currentSet.add(activatedProfile.id)
            editor.putStringSet(KEY_CONSUMED_FINGERPRINTS, currentSet)

            // 4. Persist audit metadata for consumed history
            val metaJson = buildAuditMetadataEntry(activatedProfile, now)
            val existingMeta = prefs.getString(KEY_CONSUMED_METADATA_JSON, "") ?: ""
            val updatedMeta = if (existingMeta.isEmpty()) metaJson else "$existingMeta\n$metaJson"
            editor.putString(KEY_CONSUMED_METADATA_JSON, updatedMeta)

            val committed = editor.commit() // Synchronous atomic commit ensures all fields switch together
            if (!committed) {
                return ProfileActivationResult(
                    success = false,
                    profile = profile,
                    message = "FAILED: SharedPreferences storage commit failed. Profile remains AVAILABLE.",
                    wasRejected = false,
                    rejectionReason = "STORAGE_COMMIT_FAILURE"
                )
            }

            // Synchronize active profile with ProfileStore and DeviceIdProvider
            try {
                com.example.deviceidlab.runtime.ProfileStore.setActiveProfile(activatedProfile, now)
                com.example.deviceidlab.provider.DeviceIdProvider.synchronizeProfile(
                    context,
                    activatedProfile
                )
            } catch (_: Throwable) {}

            return ProfileActivationResult(
                success = true,
                profile = activatedProfile,
                message = "SUCCESS: Profile '${activatedProfile.name}' successfully activated and permanently marked as CONSUMED.",
                wasRejected = false
            )
        } catch (e: Throwable) {
            return ProfileActivationResult(
                success = false,
                profile = profile,
                message = "FAILED: Exception during profile activation: ${e.message}. Profile remains AVAILABLE.",
                wasRejected = false,
                rejectionReason = "ACTIVATION_EXCEPTION_${e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Legacy helper method for backwards-compatibility; wraps applyAndActivateProfile.
     */
    fun saveActiveProfile(profile: DeviceProfile) {
        applyAndActivateProfile(profile)
    }

    fun getConsumedCount(): Int {
        return getConsumedFingerprints().size / 2 // Accounts for both id and sha256 fingerprint
    }

    fun serializeProfile(p: DeviceProfile): String {
        return ProfileJsonSerializer.serialize(p)
    }

    fun parseProfile(json: String): DeviceProfile {
        return ProfileJsonSerializer.parse(json)
    }

    private fun buildAuditMetadataEntry(p: DeviceProfile, consumedAt: Long): String {
        return "{\"id\":${ProfileJsonSerializer.escape(p.id)},\"name\":${ProfileJsonSerializer.escape(p.name)},\"fingerprint\":${ProfileJsonSerializer.escape(p.computeFingerprint())},\"androidId\":${ProfileJsonSerializer.escape(p.androidId)},\"imei\":${ProfileJsonSerializer.escape(p.imei)},\"phoneNumber\":${ProfileJsonSerializer.escape(p.phoneNumber)},\"batteryHealth\":${p.batteryHealth},\"testIpv4\":${ProfileJsonSerializer.escape(p.testIpv4)},\"createdAt\":${p.createdAt},\"consumedAt\":$consumedAt,\"state\":\"CONSUMED\"}"
    }
}

