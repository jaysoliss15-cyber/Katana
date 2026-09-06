package com.example.deviceidlab.runtime

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.example.deviceidlab.hook.NPatchAuditManager
import com.example.deviceidlab.hook.NPatchConfig
import com.example.deviceidlab.model.DeviceProfile
import com.example.deviceidlab.provider.DeviceIdProvider
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge

/**
 * HostBridge abstracts host/injection-specific communication.
 *
 * It is completely host-neutral:
 * - Operates entirely based on the injected target process/package context.
 * - Does not require hardcoding of specific demo/target package names.
 * - Resolves active profile values through multi-tier fallback transport:
 *     1. System ContentResolver IPC to DeviceIdProvider
 *     2. XSharedPreferences (if available)
 *     3. In-memory versioned ProfileStore
 * - Relays the 5-stage verification events back to the audit manager & controller.
 */
object HostBridge {
    private const val TAG = "HostBridge"

    val PROVIDER_URIS = listOf(
        Uri.parse("content://${DeviceIdProvider.AUTHORITY}"),
        Uri.parse("content://com.example.deviceidlab.provider.deviceid")
    )

    private var xSharedPreferences: XSharedPreferences? = null
    private var lastProfileFetchTime: Long = 0L
    private const val CACHE_EXPIRY_MS = 500L // Fast refresh for dynamic profile switching

    @Synchronized
    fun getOrInitXPrefs(): XSharedPreferences? {
        if (xSharedPreferences == null) {
            try {
                xSharedPreferences = XSharedPreferences("com.example.deviceidlab", NPatchConfig.PREF_FILE).apply {
                    makeWorldReadable()
                    reload()
                }
            } catch (t: Throwable) {
                Log.d(TAG, "[$TAG] XSharedPreferences init fallback: ${t.message}")
            }
        }
        return xSharedPreferences
    }

    @Volatile
    private var cachedContext: Context? = null

    fun setContext(context: Context) {
        cachedContext = context
    }

    /**
     * Resolves the current Application context reflectively from ActivityThread or cached context.
     */
    fun resolveCurrentApplication(): Context? {
        cachedContext?.let { return it }
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getMethod("currentApplication")
            val app = currentAppMethod.invoke(null) as? Context
            if (app != null) cachedContext = app
            app
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolves the current process name dynamically.
     */
    fun resolveCurrentProcessName(): String? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentProcessNameMethod = activityThreadClass.getMethod("currentProcessName")
            currentProcessNameMethod.invoke(null) as? String
        } catch (_: Throwable) {
            try {
                val appClass = Class.forName("android.app.Application")
                val getProcessNameMethod = appClass.getMethod("getProcessName")
                getProcessNameMethod.invoke(null) as? String
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * Resolves the current package name dynamically.
     */
    fun resolveCurrentPackageName(): String? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentPackageNameMethod = activityThreadClass.getMethod("currentPackageName")
            currentPackageNameMethod.invoke(null) as? String
        } catch (_: Throwable) {
            resolveCurrentApplication()?.packageName
        }
    }

    /**
     * Resolves a ContentResolver instance from the current process.
     */
    fun resolveContentResolver(context: Context? = null): ContentResolver? {
        if (context != null) return context.contentResolver
        val app = resolveCurrentApplication()
        return app?.contentResolver
    }

    /**
     * Obtains the authoritative active profile for the calling/target process.
     */
    fun resolveActiveProfile(resolver: ContentResolver? = null): DeviceProfile {
        val now = System.currentTimeMillis()
        if (now - lastProfileFetchTime < CACHE_EXPIRY_MS) {
            return ProfileStore.getActiveProfile()
        }

        // Tier 1: ContentResolver IPC to DeviceIdProvider
        val effectiveResolver = resolver ?: resolveContentResolver()
        if (effectiveResolver != null) {
            for (uri in PROVIDER_URIS) {
                try {
                    val bundle = effectiveResolver.call(
                        uri,
                        DeviceIdProvider.METHOD_GET_CURRENT_TEST_IDS,
                        null,
                        null
                    )
                    if (bundle != null) {
                        val profile = ProfileStore.fromBundle(bundle)
                        ProfileStore.setActiveProfile(profile)
                        lastProfileFetchTime = now
                        return profile
                    }
                } catch (_: Throwable) {}
            }
        }

        // Tier 2: XSharedPreferences (Xposed/LSPosed environment)
        try {
            val prefs = getOrInitXPrefs()
            prefs?.reload()
            if (prefs != null) {
                val androidId = prefs.getString(NPatchConfig.KEY_ACTIVE_ANDROID_ID, null)
                val telephonyId = prefs.getString(NPatchConfig.KEY_ACTIVE_TELEPHONY_ID, null)
                val synthIp = prefs.getString(NPatchConfig.KEY_ACTIVE_SYNTHETIC_IP, null)
                val mac = prefs.getString(NPatchConfig.KEY_ACTIVE_MAC_ADDRESS, null)
                if (!androidId.isNullOrEmpty() || !synthIp.isNullOrEmpty()) {
                    val current = ProfileStore.getActiveProfile()
                    val profile = current.copy(
                        androidId = androidId ?: current.androidId,
                        imei = telephonyId ?: current.imei,
                        testIpv4 = synthIp ?: current.testIpv4,
                        macAddress = mac ?: current.macAddress
                    )
                    ProfileStore.setActiveProfile(profile)
                    lastProfileFetchTime = now
                    return profile
                }
            }
        } catch (_: Throwable) {}

        // Tier 3: Local SharedPreferences of host application (standalone injection mode)
        try {
            val app = resolveCurrentApplication()
            if (app != null) {
                val prefs = app.getSharedPreferences("device_randomizer_profile", Context.MODE_PRIVATE)
                val androidId = prefs.getString("android_id", null)
                val synthIp = prefs.getString("synthetic_ip", null)
                val mac = prefs.getString("mac_address", null)
                val imei = prefs.getString("imei", null)
                if (!androidId.isNullOrEmpty() || !synthIp.isNullOrEmpty()) {
                    val current = ProfileStore.getActiveProfile()
                    val profile = current.copy(
                        androidId = androidId ?: current.androidId,
                        imei = imei ?: current.imei,
                        testIpv4 = synthIp ?: current.testIpv4,
                        macAddress = mac ?: current.macAddress
                    )
                    ProfileStore.setActiveProfile(profile)
                    lastProfileFetchTime = now
                    return profile
                }
            }
        } catch (_: Throwable) {}

        // Tier 4: In-memory ProfileStore
        return ProfileStore.getActiveProfile()
    }

    /**
     * Reports a distinct lifecycle stage for an intercepted API.
     */
    fun reportInterceptionStage(
        targetPkg: String,
        targetProc: String,
        targetPid: Int = try { Process.myPid() } catch (_: Throwable) { 0 },
        apiName: String,
        stage: String,
        origVal: String = "",
        injectedVal: String = "",
        returnedVal: String = "",
        resolver: ContentResolver? = null
    ) {
        // 1. Log to system log & XposedBridge
        val logMsg = "[$TAG] [NPATCH STAGE: $stage] api='$apiName' in pkg='$targetPkg' (PID $targetPid) -> returned='$returnedVal'"
        Log.i(TAG, logMsg)
        try {
            XposedBridge.log(logMsg)
        } catch (_: Throwable) {}

        // 2. Audit Manager in-memory registration
        NPatchAuditManager.recordHookEvent(
            targetPackage = targetPkg,
            targetProcess = targetProc,
            targetPid = targetPid,
            hookEntryStatus = "EXECUTED",
            hookInstallationStatus = "ACTIVE_INTERCEPTION",
            canaryIntercepted = true,
            apiName = apiName,
            originalId = origVal,
            injectedId = injectedVal,
            returnedId = returnedVal,
            stage = stage
        )

        // 3. Relay to DeviceIdProvider via ContentResolver IPC if available
        val effectiveResolver = resolver ?: resolveContentResolver()
        if (effectiveResolver != null) {
            val bundle = Bundle().apply {
                putString(DeviceIdProvider.KEY_TARGET_PACKAGE, targetPkg)
                putString(DeviceIdProvider.KEY_TARGET_PROCESS, targetProc)
                putInt(DeviceIdProvider.KEY_TARGET_PID, targetPid)
                putString(DeviceIdProvider.KEY_API_NAME, apiName)
                putString(DeviceIdProvider.KEY_STAGE, stage)
                putString(DeviceIdProvider.KEY_ORIGINAL_ID, origVal)
                putString(DeviceIdProvider.KEY_INJECTED_ID, injectedVal)
                putString(DeviceIdProvider.KEY_RETURNED_ID, returnedVal)
            }
            for (uri in PROVIDER_URIS) {
                try {
                    effectiveResolver.call(uri, DeviceIdProvider.METHOD_REPORT_INTERCEPTION, null, bundle)
                    break
                } catch (_: Throwable) {}
            }
        }
    }
}
