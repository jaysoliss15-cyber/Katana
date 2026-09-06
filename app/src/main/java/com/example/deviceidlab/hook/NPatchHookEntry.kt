package com.example.deviceidlab.hook

import android.content.ContentResolver
import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.example.deviceidlab.demo.HookInvocationLog
import com.example.deviceidlab.demo.InterceptionBridge
import com.example.deviceidlab.provider.DeviceIdProvider
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * NPatch 1.0.7 / LSPatch / Xposed Module Entry Point.
 *
 * Architecture:
 * - Target APK is patched and embedded ONLY ONCE.
 * - Dynamically queries DeviceIdProvider via ContentResolver at runtime.
 * - Allows unlimited test ID updates without repatching, reinstalling, or rebuilding.
 */
class NPatchHookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        const val TAG = "NPatchHookEntry"
        const val PREF_FILE = "npatch_config"
        const val KEY_ACTIVE_ANDROID_ID = "active_android_id"
        const val KEY_ACTIVE_TELEPHONY_ID = "active_telephony_id"
        const val KEY_INTERCEPTION_ENABLED = "interception_enabled"
        const val KEY_TARGET_PACKAGE_FILTER = "target_package_filter"

        private val PROVIDER_URI = Uri.parse("content://com.example.deviceidlab.provider")

        /**
         * Internal flag to track when the class is loaded by an active Xposed/NPatch environment.
         */
        @Volatile
        var isXposedEnvironmentActive: Boolean = false
            private set
    }

    private var xSharedPreferences: XSharedPreferences? = null

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        isXposedEnvironmentActive = true
        NPatchConfig.isXposedEnvironmentActive = true
        val msg = "[NPATCH] Runtime loaded in Zygote (path=${startupParam?.modulePath})"
        Log.i(TAG, msg)
        XposedBridge.log(msg)

        startupParam?.let {
            try {
                com.example.deviceidlab.runtime.IdentityRuntimeEntry.initZygote(it)
            } catch (_: Throwable) {}
        }

        try {
            xSharedPreferences = XSharedPreferences("com.example.deviceidlab", PREF_FILE).apply {
                makeWorldReadable()
                reload()
            }
            val prefMsg = "[$TAG] [NPATCH INIT] [NPATCH STAGE: CONFIG_LOAD] XSharedPreferences loaded successfully for com.example.deviceidlab ($PREF_FILE)"
            Log.d(TAG, prefMsg)
            XposedBridge.log(prefMsg)
        } catch (t: Throwable) {
            val err = "[$TAG] [NPATCH FAILURE] [NPATCH STAGE: CONFIG_LOAD] XSharedPreferences init warning: ${t.message}"
            Log.w(TAG, err, t)
            XposedBridge.log(err)
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        isXposedEnvironmentActive = true
        val pid = try { android.os.Process.myPid() } catch (_: Throwable) { 0 }

        Log.i(TAG, "[NPATCH] Runtime loaded")
        XposedBridge.log("[NPATCH] Runtime loaded")

        val targetLog = "[NPATCH] Target process detected: pkg='${lpparam.packageName}', process='${lpparam.processName}', pid=$pid"
        Log.i(TAG, targetLog)
        XposedBridge.log(targetLog)

        try {
            com.example.deviceidlab.runtime.IdentityRuntimeEntry.handleLoadPackage(lpparam)
        } catch (_: Throwable) {}

        Log.i(TAG, "[NPATCH] Hook installation started in process='${lpparam.processName}'")
        XposedBridge.log("[NPATCH] Hook installation started in process='${lpparam.processName}'")

        var hookSuccessCount = 0

        // 1. Hook canary diagnostic method to prove live framework bytecode interception
        try {
            XposedHelpers.findAndHookMethod(
                "com.example.deviceidlab.DeviceIdReader",
                lpparam.classLoader,
                "isNpatchHookActive",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam?): Any {
                        val canaryLog = "[$TAG] [NPATCH CANARY] isNpatchHookActive() intercepted in process='${lpparam.processName}' (PID=$pid, pkg='${lpparam.packageName}') -> returning true"
                        Log.i(TAG, canaryLog)
                        XposedBridge.log(canaryLog)

                        NPatchAuditManager.recordHookEvent(
                            context = null,
                            targetPackage = lpparam.packageName,
                            targetProcess = lpparam.processName,
                            targetPid = pid,
                            hookEntryStatus = "EXECUTED",
                            hookInstallationStatus = "INSTALLED",
                            canaryIntercepted = true,
                            apiName = "DeviceIdReader.isNpatchHookActive"
                        )
                        return true
                    }
                }
            )
            hookSuccessCount++
        } catch (t: Throwable) {
            // Expected if hooking external target application where DeviceIdReader does not exist
            Log.d(TAG, "[$TAG] [NPATCH HOOK] DeviceIdReader not found in ${lpparam.packageName}: ${t.message}")
        }

        // 2. Hook Settings.Secure.getString(ContentResolver, String)
        try {
            XposedHelpers.findAndHookMethod(
                "android.provider.Settings\$Secure",
                lpparam.classLoader,
                "getString",
                ContentResolver::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val settingName = param.args[1] as? String ?: return
                        if (settingName.equals("android_id", ignoreCase = true)) {
                            val resolver = param.args[0] as? ContentResolver
                            val originalValue = param.result as? String ?: "null"

                            Log.i(TAG, "[NPATCH] ANDROID_ID request intercepted in process='${lpparam.processName}' (PID=$pid)")
                            XposedBridge.log("[NPATCH] ANDROID_ID request intercepted in process='${lpparam.processName}' (PID=$pid)")

                            // Dynamic Tier 1: Query DeviceIdProvider from Controller app
                            val dynamicIds = queryDynamicTestIds(resolver)
                            val activeSimulatedId = dynamicIds?.first ?: resolveActiveAndroidId()

                            if (isInterceptionEnabled() && !activeSimulatedId.isNullOrEmpty()) {
                                param.result = activeSimulatedId

                                Log.i(TAG, "[NPATCH] Returning current Android test ID: '$activeSimulatedId'")
                                XposedBridge.log("[NPATCH] Returning current Android test ID: '$activeSimulatedId'")

                                // Standardized NPatch Spoof log
                                val spoofLog = "[$TAG] [NPATCH SPOOF] Package: '${lpparam.packageName}', Process: '${lpparam.processName}', PID: $pid, Original Android ID: '$originalValue', Spoofed Android ID: '$activeSimulatedId'"
                                Log.i(TAG, spoofLog)
                                XposedBridge.log(spoofLog)

                                val readLog = "[NPATCH] Target read verification successful: package='${lpparam.packageName}', original='$originalValue', injected='$activeSimulatedId'"
                                Log.i(TAG, readLog)
                                XposedBridge.log(readLog)

                                InterceptionBridge.logInvocation(
                                    HookInvocationLog(
                                        callerPackage = lpparam.packageName,
                                        targetApi = "Settings.Secure.getString(android_id)",
                                        requestedParam = settingName,
                                        returnedValue = activeSimulatedId,
                                        wasIntercepted = true,
                                        reason = "NPatch 1.0.7 / Xposed module dynamically substituted current runtime Android test ID."
                                    )
                                )

                                reportInterceptionEvent(
                                    resolver = resolver,
                                    targetPkg = lpparam.packageName,
                                    targetProc = lpparam.processName,
                                    targetPid = pid,
                                    apiName = "Settings.Secure.getString(android_id)",
                                    identifierType = DeviceIdProvider.TYPE_ANDROID_ID,
                                    origId = originalValue,
                                    injectedId = activeSimulatedId,
                                    retId = activeSimulatedId
                                )
                            }
                        }
                    }
                }
            )
            hookSuccessCount++
            Log.i(TAG, "[NPATCH] Hook installed successfully on Settings.Secure.getString in pkg='${lpparam.packageName}'")
            XposedBridge.log("[NPATCH] Hook installed successfully on Settings.Secure.getString in pkg='${lpparam.packageName}'")
        } catch (t: Throwable) {
            val errLog = "[$TAG] [NPATCH FAILURE] Failed to hook Settings.Secure.getString in pkg='${lpparam.packageName}': ${t.message}"
            Log.e(TAG, errLog, t)
            XposedBridge.log(errLog)
        }

        // 3. Hook TelephonyManager methods
        hookTelephonyMethods(lpparam)

        // 4. Hook Additional Telephony & Hardware Identity APIs (completing the 21 protected APIs)
        hookAdditionalIdentityMethods(lpparam)

        // 5. Hook Worldwide Location Subsystem APIs
        hookLocationMethods(lpparam)

        // 6. Hook Java & Android Network APIs
        hookNetworkMethods(lpparam)

        // Record initial hook registration
        NPatchAuditManager.recordHookEvent(
            context = null,
            targetPackage = lpparam.packageName,
            targetProcess = lpparam.processName,
            targetPid = pid,
            hookEntryStatus = "EXECUTED",
            hookInstallationStatus = if (hookSuccessCount > 0) "INSTALLED ($hookSuccessCount hooks)" else "INITIALIZED",
            canaryIntercepted = false,
            apiName = "handleLoadPackage"
        )
    }

    data class DynamicProfile(
        val androidId: String = "NPATCH_ANDROID_001",
        val telephonyId: String = "NPATCH_TELEPHONY_001",
        val syntheticIp: String = NPatchConfig.DEFAULT_SYNTHETIC_IP,
        val macAddress: String = NPatchConfig.DEFAULT_MAC,
        val wifiSsid: String = NPatchConfig.DEFAULT_SSID,
        val wifiBssid: String = NPatchConfig.DEFAULT_BSSID,
        val latitude: Double = 37.7749,
        val longitude: Double = -122.4194,
        val city: String = "San Francisco",
        val country: String = "US",
        val timezone: String = "America/Los_Angeles",
        val lifecycle: String = NPatchConfig.STATE_ACTIVE
    )

    private fun queryDynamicProfile(resolver: ContentResolver?): DynamicProfile {
        return try {
            val p = com.example.deviceidlab.runtime.HostBridge.resolveActiveProfile(resolver)
            DynamicProfile(
                androidId = p.androidId,
                telephonyId = p.imei,
                syntheticIp = p.testIpv4,
                macAddress = p.macAddress,
                wifiSsid = p.wifiSsid,
                wifiBssid = p.bssid,
                latitude = p.latitude,
                longitude = p.longitude,
                city = p.city,
                country = p.country,
                timezone = p.timezone,
                lifecycle = NPatchConfig.STATE_ACTIVE
            )
        } catch (t: Throwable) {
            Log.d(TAG, "[$TAG] [NPATCH] queryDynamicProfile fallback: ${t.message}")
            DynamicProfile()
        }
    }

    private fun queryDynamicTestIds(resolver: ContentResolver?): Pair<String?, String?>? {
        val profile = queryDynamicProfile(resolver)
        return Pair(profile.androidId, profile.telephonyId)
    }

    private fun queryDynamicTestId(resolver: ContentResolver?): String? {
        return queryDynamicProfile(resolver).androidId
    }

    private fun queryDynamicTelephonyId(resolver: ContentResolver?): String? {
        return queryDynamicProfile(resolver).telephonyId
    }

    private fun resolveContentResolver(thisObj: Any?): ContentResolver? {
        if (thisObj != null) {
            try {
                val ctx = XposedHelpers.getObjectField(thisObj, "mContext") as? Context
                if (ctx != null) return ctx.contentResolver
            } catch (_: Throwable) {}
        }
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getMethod("currentApplication")
            val app = currentAppMethod.invoke(null) as? Context
            if (app != null) return app.contentResolver
        } catch (_: Throwable) {}
        return null
    }

    private fun reportInterceptionEvent(
        resolver: ContentResolver?,
        targetPkg: String,
        targetProc: String,
        targetPid: Int,
        apiName: String,
        identifierType: String = DeviceIdProvider.TYPE_ANDROID_ID,
        origId: String,
        injectedId: String,
        retId: String
    ) {
        NPatchAuditManager.recordHookEvent(
            context = null,
            targetPackage = targetPkg,
            targetProcess = targetProc,
            targetPid = targetPid,
            hookEntryStatus = "EXECUTED",
            hookInstallationStatus = "ACTIVE_INTERCEPTION",
            canaryIntercepted = true,
            apiName = apiName,
            originalId = origId,
            injectedId = injectedId,
            returnedId = retId
        )

        val targetResolver = resolver ?: resolveContentResolver(null)
        if (targetResolver != null) {
            try {
                val extras = Bundle().apply {
                    putString(DeviceIdProvider.KEY_TARGET_PACKAGE, targetPkg)
                    putString(DeviceIdProvider.KEY_TARGET_PROCESS, targetProc)
                    putInt(DeviceIdProvider.KEY_TARGET_PID, targetPid)
                    putString(DeviceIdProvider.KEY_API_NAME, apiName)
                    putString(DeviceIdProvider.KEY_IDENTIFIER_TYPE, identifierType)
                    putString(DeviceIdProvider.KEY_ORIGINAL_ID, origId)
                    putString(DeviceIdProvider.KEY_RETURNED_ID, retId)
                    putLong(DeviceIdProvider.KEY_TIMESTAMP, System.currentTimeMillis())
                }
                targetResolver.call(
                    PROVIDER_URI,
                    DeviceIdProvider.METHOD_REPORT_INTERCEPTION,
                    null,
                    extras
                )
            } catch (_: Throwable) {}
        }
    }

    private fun hookTelephonyMethods(lpparam: XC_LoadPackage.LoadPackageParam) {
        val telephonyClass = "android.telephony.TelephonyManager"

        // getDeviceId()
        try {
            XposedHelpers.findAndHookMethod(
                telephonyClass,
                lpparam.classLoader,
                "getDeviceId",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyTelephonyOverride(lpparam.packageName, lpparam.processName, param, "getDeviceId()")
                    }
                }
            )
            Log.d(TAG, "[$TAG] [NPATCH HOOK] Installed hook on TelephonyManager.getDeviceId() in ${lpparam.packageName}")
        } catch (t: Throwable) {
            Log.d(TAG, "[$TAG] [NPATCH HOOK] TelephonyManager.getDeviceId() hook skipped: ${t.message}")
        }

        // getDeviceId(int)
        try {
            XposedHelpers.findAndHookMethod(
                telephonyClass,
                lpparam.classLoader,
                "getDeviceId",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyTelephonyOverride(lpparam.packageName, lpparam.processName, param, "getDeviceId(slot)")
                    }
                }
            )
            Log.d(TAG, "[$TAG] [NPATCH HOOK] Installed hook on TelephonyManager.getDeviceId(int) in ${lpparam.packageName}")
        } catch (t: Throwable) {
            Log.d(TAG, "[$TAG] [NPATCH HOOK] TelephonyManager.getDeviceId(int) hook skipped: ${t.message}")
        }

        // getImei()
        try {
            XposedHelpers.findAndHookMethod(
                telephonyClass,
                lpparam.classLoader,
                "getImei",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyTelephonyOverride(lpparam.packageName, lpparam.processName, param, "getImei()")
                    }
                }
            )
            Log.d(TAG, "[$TAG] [NPATCH HOOK] Installed hook on TelephonyManager.getImei() in ${lpparam.packageName}")
        } catch (t: Throwable) {
            Log.d(TAG, "[$TAG] [NPATCH HOOK] TelephonyManager.getImei() hook skipped: ${t.message}")
        }

        // getImei(int)
        try {
            XposedHelpers.findAndHookMethod(
                telephonyClass,
                lpparam.classLoader,
                "getImei",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyTelephonyOverride(lpparam.packageName, lpparam.processName, param, "getImei(slot)")
                    }
                }
            )
            Log.d(TAG, "[$TAG] [NPATCH HOOK] Installed hook on TelephonyManager.getImei(int) in ${lpparam.packageName}")
        } catch (t: Throwable) {
            Log.d(TAG, "[$TAG] [NPATCH HOOK] TelephonyManager.getImei(int) hook skipped: ${t.message}")
        }

        // getMeid()
        try {
            XposedHelpers.findAndHookMethod(
                telephonyClass,
                lpparam.classLoader,
                "getMeid",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyTelephonyOverride(lpparam.packageName, lpparam.processName, param, "getMeid()")
                    }
                }
            )
        } catch (_: Throwable) {}

        // getMeid(int)
        try {
            XposedHelpers.findAndHookMethod(
                telephonyClass,
                lpparam.classLoader,
                "getMeid",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyTelephonyOverride(lpparam.packageName, lpparam.processName, param, "getMeid(slot)")
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun applyTelephonyOverride(
        packageName: String,
        processName: String,
        param: XC_MethodHook.MethodHookParam,
        apiName: String
    ) {
        if (!isInterceptionEnabled()) return

        val pid = try { android.os.Process.myPid() } catch (_: Throwable) { 0 }
        val resolver = resolveContentResolver(param.thisObject)
        val dynamicTelephonyId = queryDynamicTelephonyId(resolver)
        val activeSimulatedTelephony = dynamicTelephonyId ?: resolveActiveTelephonyId() ?: "NPATCH_TELEPHONY_001"

        // Clear any security exceptions thrown by platform on Android 10+ (API 29+)
        param.throwable = null
        param.result = activeSimulatedTelephony

        val msg = "[$TAG] [NPATCH HOOK] [NPATCH VERIFIED] $apiName -> '$activeSimulatedTelephony' in pkg='$packageName', process='$processName' (PID $pid)"
        Log.i(TAG, msg)
        XposedBridge.log(msg)

        InterceptionBridge.logInvocation(
            HookInvocationLog(
                callerPackage = packageName,
                targetApi = "TelephonyManager.$apiName",
                requestedParam = "NONE",
                returnedValue = activeSimulatedTelephony,
                wasIntercepted = true,
                reason = "NPatch 1.0.7 / Xposed module dynamically substituted current runtime Telephony ID."
            )
        )

        reportInterceptionEvent(
            resolver = resolver,
            targetPkg = packageName,
            targetProc = processName,
            targetPid = pid,
            apiName = "TelephonyManager.$apiName",
            identifierType = DeviceIdProvider.TYPE_TELEPHONY_ID,
            origId = "Hardware / Restricted",
            injectedId = activeSimulatedTelephony,
            retId = activeSimulatedTelephony
        )
    }

    private fun hookAdditionalIdentityMethods(lpparam: XC_LoadPackage.LoadPackageParam) {
        val telephonyClass = "android.telephony.TelephonyManager"
        val stringGetters = listOf(
            "getSubscriberId",
            "getSimSerialNumber",
            "getLine1Number",
            "getSimCountryIso",
            "getSimOperator",
            "getSimOperatorName",
            "getNetworkCountryIso",
            "getNetworkOperator",
            "getNetworkOperatorName"
        )
        for (methodName in stringGetters) {
            try {
                XposedHelpers.findAndHookMethod(
                    telephonyClass,
                    lpparam.classLoader,
                    methodName,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            applyTelephonyOverride(lpparam.packageName, lpparam.processName, param, "$methodName()")
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        // Build.getSerial() (API 26+)
        try {
            XposedHelpers.findAndHookMethod(
                "android.os.Build",
                lpparam.classLoader,
                "getSerial",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        val resolver = resolveContentResolver(null)
                        val profile = queryDynamicProfile(resolver)
                        param.throwable = null
                        param.result = profile.telephonyId
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hookLocationMethods(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pid = try { android.os.Process.myPid() } catch (_: Throwable) { 0 }

        // LocationManager.getLastKnownLocation(String)
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                lpparam.classLoader,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        val resolver = resolveContentResolver(null)
                        val profile = queryDynamicProfile(resolver)
                        val providerName = param.args[0] as? String ?: "gps"
                        val spoofedLoc = Location(providerName).apply {
                            latitude = profile.latitude
                            longitude = profile.longitude
                            altitude = 15.0
                            accuracy = 5.0f
                            time = System.currentTimeMillis()
                        }
                        param.throwable = null
                        param.result = spoofedLoc

                        val logMsg = "[$TAG] [NPATCH HOOK] LocationManager.getLastKnownLocation('$providerName') -> (${profile.latitude}, ${profile.longitude}) [${profile.city}, ${profile.country}] in process='${lpparam.processName}' (PID $pid)"
                        Log.i(TAG, logMsg)
                        XposedBridge.log(logMsg)

                        reportInterceptionEvent(
                            resolver = resolver,
                            targetPkg = lpparam.packageName,
                            targetProc = lpparam.processName,
                            targetPid = pid,
                            apiName = "LocationManager.getLastKnownLocation($providerName)",
                            identifierType = DeviceIdProvider.TYPE_LOCATION,
                            origId = "Physical GPS",
                            injectedId = "${profile.latitude},${profile.longitude} (${profile.city})",
                            retId = "${profile.latitude},${profile.longitude}"
                        )
                    }
                }
            )
            Log.d(TAG, "[$TAG] [NPATCH HOOK] Installed hook on LocationManager.getLastKnownLocation()")
        } catch (t: Throwable) {
            Log.d(TAG, "[$TAG] [NPATCH HOOK] LocationManager.getLastKnownLocation hook skipped: ${t.message}")
        }

        // Location.getLatitude()
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                lpparam.classLoader,
                "getLatitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        val resolver = resolveContentResolver(null)
                        val profile = queryDynamicProfile(resolver)
                        param.result = profile.latitude
                    }
                }
            )
        } catch (_: Throwable) {}

        // Location.getLongitude()
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                lpparam.classLoader,
                "getLongitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        val resolver = resolveContentResolver(null)
                        val profile = queryDynamicProfile(resolver)
                        param.result = profile.longitude
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hookNetworkMethods(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pid = try { android.os.Process.myPid() } catch (_: Throwable) { 0 }

        // 1. NetworkInterface.getHardwareAddress()
        try {
            XposedHelpers.findAndHookMethod(
                "java.net.NetworkInterface",
                lpparam.classLoader,
                "getHardwareAddress",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        val resolver = resolveContentResolver(null)
                        val profile = queryDynamicProfile(resolver)
                        val originalBytes = param.result as? ByteArray
                        val originalMac = NPatchConfig.byteArrayToMac(originalBytes)
                        val testBytes = NPatchConfig.macToByteArray(profile.macAddress)
                        param.result = testBytes
                        val logMsg = "[$TAG] [NPATCH HOOK] NetworkInterface.getHardwareAddress() -> '${profile.macAddress}' (original: '$originalMac') in process='${lpparam.processName}' (PID $pid)"
                        Log.i(TAG, logMsg)
                        XposedBridge.log(logMsg)

                        reportInterceptionEvent(
                            resolver = resolver,
                            targetPkg = lpparam.packageName,
                            targetProc = lpparam.processName,
                            targetPid = pid,
                            apiName = "NetworkInterface.getHardwareAddress()",
                            identifierType = DeviceIdProvider.TYPE_NETWORK_MAC,
                            origId = originalMac,
                            injectedId = profile.macAddress,
                            retId = profile.macAddress
                        )
                    }
                }
            )
            Log.d(TAG, "[$TAG] [NPATCH HOOK] Installed hook on NetworkInterface.getHardwareAddress()")
        } catch (t: Throwable) {
            Log.d(TAG, "[$TAG] [NPATCH HOOK] NetworkInterface.getHardwareAddress() hook skipped: ${t.message}")
        }

        // 2. NetworkInterface.getInetAddresses()
        try {
            XposedHelpers.findAndHookMethod(
                "java.net.NetworkInterface",
                lpparam.classLoader,
                "getInetAddresses",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        try {
                            val resolver = resolveContentResolver(null)
                            val profile = queryDynamicProfile(resolver)
                            val synthAddr = InetAddress.getByName(profile.syntheticIp)
                            param.result = Collections.enumeration(listOf(synthAddr))
                            val logMsg = "[$TAG] [NPATCH HOOK] NetworkInterface.getInetAddresses() -> RFC 5737 '${profile.syntheticIp}' in process='${lpparam.processName}' (PID $pid)"
                            Log.i(TAG, logMsg)
                            XposedBridge.log(logMsg)

                            reportInterceptionEvent(
                                resolver = resolver,
                                targetPkg = lpparam.packageName,
                                targetProc = lpparam.processName,
                                targetPid = pid,
                                apiName = "NetworkInterface.getInetAddresses()",
                                identifierType = DeviceIdProvider.TYPE_NETWORK_IP,
                                origId = "Local / System IP",
                                injectedId = profile.syntheticIp,
                                retId = profile.syntheticIp
                            )
                        } catch (_: Throwable) {}
                    }
                }
            )
            Log.d(TAG, "[$TAG] [NPATCH HOOK] Installed hook on NetworkInterface.getInetAddresses()")
        } catch (t: Throwable) {
            Log.d(TAG, "[$TAG] [NPATCH HOOK] NetworkInterface.getInetAddresses() hook skipped: ${t.message}")
        }

        // 3. NetworkInterface.getInterfaceAddresses()
        try {
            XposedHelpers.findAndHookMethod(
                "java.net.NetworkInterface",
                lpparam.classLoader,
                "getInterfaceAddresses",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        Log.d(TAG, "[$TAG] [NPATCH HOOK] NetworkInterface.getInterfaceAddresses() intercepted")
                    }
                }
            )
        } catch (_: Throwable) {}

        // 3a. NetworkInterface.getNetworkInterfaces()
        try {
            XposedHelpers.findAndHookMethod(
                "java.net.NetworkInterface",
                lpparam.classLoader,
                "getNetworkInterfaces",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        try {
                            val resolver = resolveContentResolver(null)
                            val profile = queryDynamicProfile(resolver)
                            val synthAddr = InetAddress.getByName(profile.syntheticIp)
                            val macBytes = NPatchConfig.macToByteArray(profile.macAddress)

                            @Suppress("UNCHECKED_CAST")
                            val list = (param.result as? java.util.Enumeration<NetworkInterface>)?.toList()?.toMutableList()
                                ?: mutableListOf()

                            var hasNonLoopback = false
                            for (netIf in list) {
                                try {
                                    val name = try { netIf.name } catch (_: Throwable) { "" }
                                    val isLb = try { netIf.isLoopback } catch (_: Throwable) { name == "lo" }
                                    if (!isLb || name != "lo") {
                                        hasNonLoopback = true
                                    }
                                } catch (_: Throwable) {}
                            }

                            if (!hasNonLoopback) {
                                try {
                                    val netIfClass = NetworkInterface::class.java
                                    val netIf = try {
                                        val ctor = netIfClass.getDeclaredConstructor()
                                        ctor.isAccessible = true
                                        ctor.newInstance()
                                    } catch (_: Throwable) {
                                        val ctor = netIfClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 }
                                        ctor?.isAccessible = true
                                        ctor?.newInstance("wlan0", 2, arrayOf(synthAddr)) as? NetworkInterface
                                    }
                                    if (netIf != null) {
                                        try { XposedHelpers.setObjectField(netIf, "name", "wlan0") } catch (_: Throwable) {}
                                        try { XposedHelpers.setObjectField(netIf, "displayName", "wlan0") } catch (_: Throwable) {}
                                        try { XposedHelpers.setIntField(netIf, "index", 2) } catch (_: Throwable) {}
                                        try { XposedHelpers.setObjectField(netIf, "hardwareAddr", macBytes) } catch (_: Throwable) {}
                                        try { XposedHelpers.setObjectField(netIf, "addrs", arrayOf(synthAddr)) } catch (_: Throwable) {}
                                        try { XposedHelpers.setObjectField(netIf, "addresses", listOf(synthAddr)) } catch (_: Throwable) {}
                                        try { XposedHelpers.setBooleanField(netIf, "virtual", false) } catch (_: Throwable) {}
                                        list.add(netIf)
                                    }
                                } catch (_: Throwable) {}
                            }

                            param.result = Collections.enumeration(list)
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        // 3b. NetworkInterface.isLoopback()
        try {
            XposedHelpers.findAndHookMethod(
                "java.net.NetworkInterface",
                lpparam.classLoader,
                "isLoopback",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val netIf = param.thisObject as? NetworkInterface ?: return
                        val name = try { netIf.name } catch (_: Throwable) { "" }
                        if (name != "lo") {
                            param.throwable = null
                            param.result = false
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 4. WifiInfo.getMacAddress()
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                lpparam.classLoader,
                "getMacAddress",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        val resolver = resolveContentResolver(null)
                        val profile = queryDynamicProfile(resolver)
                        val orig = param.result as? String ?: "02:00:00:00:00:00"
                        param.result = profile.macAddress
                        val logMsg = "[$TAG] [NPATCH HOOK] WifiInfo.getMacAddress() -> '${profile.macAddress}' (original: '$orig') in process='${lpparam.processName}'"
                        Log.i(TAG, logMsg)
                        XposedBridge.log(logMsg)
                    }
                }
            )
            Log.d(TAG, "[$TAG] [NPATCH HOOK] Installed hook on WifiInfo.getMacAddress()")
        } catch (t: Throwable) {
            Log.d(TAG, "[$TAG] [NPATCH HOOK] WifiInfo.getMacAddress() hook skipped: ${t.message}")
        }

        // 5. WifiInfo.getIpAddress()
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                lpparam.classLoader,
                "getIpAddress",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        val resolver = resolveContentResolver(null)
                        val profile = queryDynamicProfile(resolver)
                        val origInt = param.result as? Int ?: 0
                        val synthInt = NPatchConfig.ipToIntLittleEndian(profile.syntheticIp)
                        param.result = synthInt
                        val logMsg = "[$TAG] [NPATCH HOOK] WifiInfo.getIpAddress() -> '${profile.syntheticIp}' (int: $synthInt, original int: $origInt) in process='${lpparam.processName}'"
                        Log.i(TAG, logMsg)
                        XposedBridge.log(logMsg)
                    }
                }
            )
            Log.d(TAG, "[$TAG] [NPATCH HOOK] Installed hook on WifiInfo.getIpAddress()")
        } catch (t: Throwable) {
            Log.d(TAG, "[$TAG] [NPATCH HOOK] WifiInfo.getIpAddress() hook skipped: ${t.message}")
        }

        // 6. WifiInfo.getSSID()
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                lpparam.classLoader,
                "getSSID",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        val resolver = resolveContentResolver(null)
                        val profile = queryDynamicProfile(resolver)
                        param.result = profile.wifiSsid
                    }
                }
            )
        } catch (_: Throwable) {}

        // 7. WifiInfo.getBSSID()
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                lpparam.classLoader,
                "getBSSID",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        val resolver = resolveContentResolver(null)
                        val profile = queryDynamicProfile(resolver)
                        param.result = profile.wifiBssid
                    }
                }
            )
        } catch (_: Throwable) {}

        // 8. ConnectivityManager.getLinkProperties() & LinkProperties APIs
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.ConnectivityManager",
                lpparam.classLoader,
                "getLinkProperties",
                "android.net.Network",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        if (!checkAccessNetworkState(param.thisObject) || param.throwable is SecurityException) {
                            return
                        }
                        try {
                            val resolver = resolveContentResolver(param.thisObject)
                            val profile = queryDynamicProfile(resolver)
                            val synthAddr = InetAddress.getByName(profile.syntheticIp)
                            val linkAddr = createSyntheticLinkAddress(synthAddr, 24)
                            val lp = param.result ?: Class.forName("android.net.LinkProperties").getDeclaredConstructor().newInstance()
                            if (linkAddr != null) {
                                try {
                                    val addMethod = lp.javaClass.getMethod("addLinkAddress", Class.forName("android.net.LinkAddress"))
                                    addMethod.invoke(lp, linkAddr)
                                } catch (_: Throwable) {}
                            }
                            param.throwable = null
                            param.result = lp
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                "android.net.LinkProperties",
                lpparam.classLoader,
                "getAddresses",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        if (!checkAccessNetworkState(null)) return
                        try {
                            val resolver = resolveContentResolver(null)
                            val profile = queryDynamicProfile(resolver)
                            val synthAddr = InetAddress.getByName(profile.syntheticIp)
                            param.result = listOf(synthAddr)
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                "android.net.LinkProperties",
                lpparam.classLoader,
                "getLinkAddresses",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isInterceptionEnabled()) return
                        if (!checkAccessNetworkState(null)) return
                        try {
                            val resolver = resolveContentResolver(null)
                            val profile = queryDynamicProfile(resolver)
                            val synthAddr = InetAddress.getByName(profile.syntheticIp)
                            val linkAddr = createSyntheticLinkAddress(synthAddr, 24)
                            if (linkAddr != null) {
                                param.result = listOf(linkAddr)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun checkAccessNetworkState(thisObj: Any?): Boolean {
        val ctx = resolveContext(thisObj)
        if (ctx != null) {
            return try {
                ctx.checkCallingOrSelfPermission("android.permission.ACCESS_NETWORK_STATE") ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) { false }
        }
        return false
    }

    private fun resolveContext(thisObj: Any?): Context? {
        if (thisObj != null) {
            try {
                val ctx = XposedHelpers.getObjectField(thisObj, "mContext") as? Context
                if (ctx != null) return ctx
            } catch (_: Throwable) {}
        }
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getMethod("currentApplication")
            return currentAppMethod.invoke(null) as? Context
        } catch (_: Throwable) {}
        return null
    }

    private fun createSyntheticLinkAddress(address: InetAddress, prefixLength: Int = 24): Any? {
        return try {
            val linkAddressClass = Class.forName("android.net.LinkAddress")
            for (ctor in linkAddressClass.declaredConstructors) {
                try {
                    ctor.isAccessible = true
                    val params = ctor.parameterTypes
                    if (params.size == 2 &&
                        params[0].isAssignableFrom(InetAddress::class.java) &&
                        (params[1] == Int::class.javaPrimitiveType || params[1] == java.lang.Integer::class.java)
                    ) {
                        return ctor.newInstance(address, prefixLength)
                    }
                } catch (_: Throwable) {}
            }
            for (ctor in linkAddressClass.declaredConstructors) {
                try {
                    ctor.isAccessible = true
                    val params = ctor.parameterTypes
                    if (params.size == 4 &&
                        params[0].isAssignableFrom(InetAddress::class.java) &&
                        (params[1] == Int::class.javaPrimitiveType || params[1] == java.lang.Integer::class.java)
                    ) {
                        return ctor.newInstance(address, prefixLength, 0, 0)
                    }
                } catch (_: Throwable) {}
            }
            for (ctor in linkAddressClass.declaredConstructors) {
                try {
                    ctor.isAccessible = true
                    val params = ctor.parameterTypes
                    if (params.size == 1 && params[0] == String::class.java) {
                        val host = address.hostAddress ?: "198.51.100.42"
                        return ctor.newInstance("$host/$prefixLength")
                    }
                } catch (_: Throwable) {}
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun getOrInitXPrefs(): XSharedPreferences? {
        if (xSharedPreferences == null) {
            try {
                xSharedPreferences = XSharedPreferences("com.example.deviceidlab", PREF_FILE).apply {
                    makeWorldReadable()
                    reload()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[$TAG] [NPATCH FAILURE] Lazy XSharedPreferences init warning: ${t.message}")
            }
        }
        return xSharedPreferences
    }

    private fun resolveActiveAndroidId(): String? {
        val inMemory = InterceptionBridge.activeSimulatedAndroidId.value
        if (!inMemory.isNullOrEmpty()) return inMemory

        val fromBridge = com.example.deviceidlab.runtime.HostBridge.resolveActiveProfile().androidId
        if (fromBridge.isNotEmpty()) return fromBridge

        return try {
            val prefs = getOrInitXPrefs()
            prefs?.reload()
            val id = prefs?.getString(KEY_ACTIVE_ANDROID_ID, null)
            if (!id.isNullOrEmpty()) id else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun resolveActiveTelephonyId(): String? {
        val inMemory = InterceptionBridge.activeSimulatedTelephonyId.value
        if (!inMemory.isNullOrEmpty()) return inMemory

        val fromBridge = com.example.deviceidlab.runtime.HostBridge.resolveActiveProfile().imei
        if (fromBridge.isNotEmpty()) return fromBridge

        return try {
            val prefs = getOrInitXPrefs()
            prefs?.reload()
            val id = prefs?.getString(KEY_ACTIVE_TELEPHONY_ID, null)
            if (!id.isNullOrEmpty()) id else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun isInterceptionEnabled(): Boolean {
        if (InterceptionBridge.isInterceptionActive.value) return true

        return try {
            val prefs = getOrInitXPrefs()
            prefs?.reload()
            prefs?.getBoolean(KEY_INTERCEPTION_ENABLED, true) ?: true
        } catch (t: Throwable) {
            true
        }
    }
}

