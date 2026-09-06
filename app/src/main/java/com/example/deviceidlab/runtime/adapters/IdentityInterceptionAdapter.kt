package com.example.deviceidlab.runtime.adapters

import android.content.ContentResolver
import android.os.Process
import android.util.Log
import com.example.deviceidlab.hook.NPatchAuditManager
import com.example.deviceidlab.runtime.HostBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Interception adapter for Android Hardware & Device Identity APIs.
 *
 * Implements the 5-stage verification lifecycle:
 * - Settings.Secure.getString(ANDROID_ID)
 * - TelephonyManager getDeviceId/getImei/getMeid/getSubscriberId/getSimSerialNumber
 * - Build.getSerial()
 */
object IdentityInterceptionAdapter {
    private const val TAG = "IdentityAdapter"

    fun installHooks(classLoader: ClassLoader, packageName: String, processName: String) {
        val pid = try { Process.myPid() } catch (_: Throwable) { 0 }
        val loaders = listOfNotNull(
            classLoader,
            ClassLoader.getSystemClassLoader(),
            Thread.currentThread().contextClassLoader
        ).distinct()

        // 1. Settings.Secure.getString(ContentResolver, String)
        hookAcrossLoaders(loaders, "android.provider.Settings\$Secure", "getString", ContentResolver::class.java, String::class.java) {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val settingName = param.args[1] as? String ?: return
                    if (settingName.equals("android_id", ignoreCase = true)) {
                        HostBridge.reportInterceptionStage(
                            targetPkg = packageName, targetProc = processName, targetPid = pid,
                            apiName = "Settings.Secure.getString(android_id)", stage = NPatchAuditManager.HOOK_INVOKED
                        )
                        val resolver = param.args[0] as? ContentResolver
                        val profile = HostBridge.resolveActiveProfile(resolver)
                        val activeId = profile.androidId

                        HostBridge.reportInterceptionStage(
                            targetPkg = packageName, targetProc = processName, targetPid = pid,
                            apiName = "Settings.Secure.getString(android_id)", stage = NPatchAuditManager.VALUE_GENERATED,
                            injectedVal = activeId
                        )

                        param.throwable = null
                        param.result = activeId

                        HostBridge.reportInterceptionStage(
                            targetPkg = packageName, targetProc = processName, targetPid = pid,
                            apiName = "Settings.Secure.getString(android_id)", stage = NPatchAuditManager.VALUE_RETURNED,
                            injectedVal = activeId, returnedVal = activeId
                        )
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val settingName = param.args[1] as? String ?: return
                    if (settingName.equals("android_id", ignoreCase = true)) {
                        val resolver = param.args[0] as? ContentResolver
                        val profile = HostBridge.resolveActiveProfile(resolver)
                        param.throwable = null
                        param.result = profile.androidId
                    }
                }
            }
        }

        // Settings.Secure.getStringForUser(ContentResolver, String, int)
        hookAcrossLoaders(loaders, "android.provider.Settings\$Secure", "getStringForUser", ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType ?: Int::class.java) {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val settingName = param.args[1] as? String ?: return
                    if (settingName.equals("android_id", ignoreCase = true)) {
                        val resolver = param.args[0] as? ContentResolver
                        val profile = HostBridge.resolveActiveProfile(resolver)
                        param.throwable = null
                        param.result = profile.androidId
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val settingName = param.args[1] as? String ?: return
                    if (settingName.equals("android_id", ignoreCase = true)) {
                        val resolver = param.args[0] as? ContentResolver
                        val profile = HostBridge.resolveActiveProfile(resolver)
                        param.throwable = null
                        param.result = profile.androidId
                    }
                }
            }
        }
        HostBridge.reportInterceptionStage(
            targetPkg = packageName, targetProc = processName, targetPid = pid,
            apiName = "Settings.Secure.getString(android_id)", stage = NPatchAuditManager.HOOK_REGISTERED
        )

        // 2. TelephonyManager methods
        val telephonyClass = "android.telephony.TelephonyManager"
        val stringMethods = listOf(
            "getDeviceId",
            "getImei",
            "getMeid",
            "getSubscriberId",
            "getSimSerialNumber",
            "getLine1Number"
        )
        for (methodName in stringMethods) {
            hookAcrossLoaders(loaders, telephonyClass, methodName) {
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        HostBridge.reportInterceptionStage(
                            targetPkg = packageName, targetProc = processName, targetPid = pid,
                            apiName = "TelephonyManager.$methodName()", stage = NPatchAuditManager.HOOK_INVOKED
                        )
                        val profile = HostBridge.resolveActiveProfile()
                        val telephonyVal = profile.imei

                        HostBridge.reportInterceptionStage(
                            targetPkg = packageName, targetProc = processName, targetPid = pid,
                            apiName = "TelephonyManager.$methodName()", stage = NPatchAuditManager.VALUE_GENERATED,
                            injectedVal = telephonyVal
                        )

                        param.throwable = null
                        param.result = telephonyVal

                        HostBridge.reportInterceptionStage(
                            targetPkg = packageName, targetProc = processName, targetPid = pid,
                            apiName = "TelephonyManager.$methodName()", stage = NPatchAuditManager.VALUE_RETURNED,
                            injectedVal = telephonyVal, returnedVal = telephonyVal
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val profile = HostBridge.resolveActiveProfile()
                        param.throwable = null
                        param.result = profile.imei
                    }
                }
            }
            HostBridge.reportInterceptionStage(
                targetPkg = packageName, targetProc = processName, targetPid = pid,
                apiName = "TelephonyManager.$methodName()", stage = NPatchAuditManager.HOOK_REGISTERED
            )

            // Hook int slot overloads
            hookAcrossLoaders(loaders, telephonyClass, methodName, Int::class.javaPrimitiveType ?: Int::class.java) {
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        HostBridge.reportInterceptionStage(
                            targetPkg = packageName, targetProc = processName, targetPid = pid,
                            apiName = "TelephonyManager.$methodName(int)", stage = NPatchAuditManager.HOOK_INVOKED
                        )
                        val profile = HostBridge.resolveActiveProfile()
                        val telephonyVal = profile.imei

                        HostBridge.reportInterceptionStage(
                            targetPkg = packageName, targetProc = processName, targetPid = pid,
                            apiName = "TelephonyManager.$methodName(int)", stage = NPatchAuditManager.VALUE_GENERATED,
                            injectedVal = telephonyVal
                        )

                        param.throwable = null
                        param.result = telephonyVal

                        HostBridge.reportInterceptionStage(
                            targetPkg = packageName, targetProc = processName, targetPid = pid,
                            apiName = "TelephonyManager.$methodName(int)", stage = NPatchAuditManager.VALUE_RETURNED,
                            injectedVal = telephonyVal, returnedVal = telephonyVal
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val profile = HostBridge.resolveActiveProfile()
                        param.throwable = null
                        param.result = profile.imei
                    }
                }
            }
        }

        // 3. Build.getSerial()
        hookAcrossLoaders(loaders, "android.os.Build", "getSerial") {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "Build.getSerial()", stage = NPatchAuditManager.HOOK_INVOKED
                    )
                    val profile = HostBridge.resolveActiveProfile()

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "Build.getSerial()", stage = NPatchAuditManager.VALUE_GENERATED,
                        injectedVal = profile.serialNumber
                    )

                    param.throwable = null
                    param.result = profile.serialNumber

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "Build.getSerial()", stage = NPatchAuditManager.VALUE_RETURNED,
                        injectedVal = profile.serialNumber, returnedVal = profile.serialNumber
                    )
                }
            }
        }
        HostBridge.reportInterceptionStage(
            targetPkg = packageName, targetProc = processName, targetPid = pid,
            apiName = "Build.getSerial()", stage = NPatchAuditManager.HOOK_REGISTERED
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
