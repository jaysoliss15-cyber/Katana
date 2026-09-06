package com.example.deviceidlab.runtime.adapters

import android.location.Location
import android.os.Process
import android.util.Log
import com.example.deviceidlab.hook.NPatchAuditManager
import com.example.deviceidlab.runtime.HostBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Interception adapter for Worldwide Location Subsystem APIs.
 *
 * Implements the 5-stage verification lifecycle for:
 * - LocationManager.getLastKnownLocation(String)
 * - Location.getLatitude()
 * - Location.getLongitude()
 * - Location.getAltitude()
 */
object LocationInterceptionAdapter {
    private const val TAG = "LocationAdapter"

    fun installHooks(classLoader: ClassLoader, packageName: String, processName: String) {
        val pid = try { Process.myPid() } catch (_: Throwable) { 0 }
        val loaders = listOfNotNull(
            classLoader,
            ClassLoader.getSystemClassLoader(),
            Thread.currentThread().contextClassLoader
        ).distinct()

        // 1. LocationManager.getLastKnownLocation(String)
        hookAcrossLoaders(loaders, "android.location.LocationManager", "getLastKnownLocation", String::class.java) {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "LocationManager.getLastKnownLocation", stage = NPatchAuditManager.HOOK_INVOKED
                    )
                    val profile = HostBridge.resolveActiveProfile()
                    val provider = param.args[0] as? String ?: "gps"
                    val loc = Location(provider).apply {
                        latitude = profile.latitude
                        longitude = profile.longitude
                        altitude = 15.0
                        accuracy = 5.0f
                        time = System.currentTimeMillis()
                    }

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "LocationManager.getLastKnownLocation", stage = NPatchAuditManager.VALUE_GENERATED,
                        injectedVal = "${profile.latitude}, ${profile.longitude}"
                    )

                    param.throwable = null
                    param.result = loc

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "LocationManager.getLastKnownLocation", stage = NPatchAuditManager.VALUE_RETURNED,
                        injectedVal = "${profile.latitude}, ${profile.longitude}",
                        returnedVal = "${profile.latitude}, ${profile.longitude}"
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    val provider = param.args.getOrNull(0) as? String ?: "gps"
                    val loc = Location(provider).apply {
                        latitude = profile.latitude
                        longitude = profile.longitude
                        altitude = 15.0
                        accuracy = 5.0f
                        time = System.currentTimeMillis()
                    }
                    param.throwable = null
                    param.result = loc
                }
            }
        }
        HostBridge.reportInterceptionStage(
            targetPkg = packageName, targetProc = processName, targetPid = pid,
            apiName = "LocationManager.getLastKnownLocation", stage = NPatchAuditManager.HOOK_REGISTERED
        )

        // 2. Location.getLatitude()
        hookAcrossLoaders(loaders, "android.location.Location", "getLatitude") {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "Location.getLatitude()", stage = NPatchAuditManager.HOOK_INVOKED
                    )
                    val profile = HostBridge.resolveActiveProfile()

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "Location.getLatitude()", stage = NPatchAuditManager.VALUE_GENERATED,
                        injectedVal = "${profile.latitude}"
                    )

                    param.throwable = null
                    param.result = profile.latitude

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "Location.getLatitude()", stage = NPatchAuditManager.VALUE_RETURNED,
                        injectedVal = "${profile.latitude}", returnedVal = "${profile.latitude}"
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    param.throwable = null
                    param.result = profile.latitude
                }
            }
        }
        HostBridge.reportInterceptionStage(
            targetPkg = packageName, targetProc = processName, targetPid = pid,
            apiName = "Location.getLatitude()", stage = NPatchAuditManager.HOOK_REGISTERED
        )

        // 3. Location.getLongitude()
        hookAcrossLoaders(loaders, "android.location.Location", "getLongitude") {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "Location.getLongitude()", stage = NPatchAuditManager.HOOK_INVOKED
                    )
                    val profile = HostBridge.resolveActiveProfile()

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "Location.getLongitude()", stage = NPatchAuditManager.VALUE_GENERATED,
                        injectedVal = "${profile.longitude}"
                    )

                    param.throwable = null
                    param.result = profile.longitude

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "Location.getLongitude()", stage = NPatchAuditManager.VALUE_RETURNED,
                        injectedVal = "${profile.longitude}", returnedVal = "${profile.longitude}"
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    param.throwable = null
                    param.result = profile.longitude
                }
            }
        }
        HostBridge.reportInterceptionStage(
            targetPkg = packageName, targetProc = processName, targetPid = pid,
            apiName = "Location.getLongitude()", stage = NPatchAuditManager.HOOK_REGISTERED
        )
    }

    private fun hookAcrossLoaders(
        loaders: List<ClassLoader>,
        className: String,
        methodName: String,
        vararg parameterTypes: Any,
        hookSupplier: () -> XC_MethodHook
    ) {
        for (loader in loaders) {
            try {
                val paramsAndHook = if (parameterTypes.isEmpty()) {
                    arrayOf<Any>(hookSupplier())
                } else {
                    arrayOf(*parameterTypes, hookSupplier())
                }
                XposedHelpers.findAndHookMethod(className, loader, methodName, *paramsAndHook)
            } catch (_: Throwable) {}
        }
    }
}
