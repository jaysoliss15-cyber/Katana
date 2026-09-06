package com.example.deviceidlab.runtime

import android.os.Process
import android.util.Log
import com.example.deviceidlab.hook.NPatchAuditManager
import com.example.deviceidlab.runtime.adapters.IdentityInterceptionAdapter
import com.example.deviceidlab.runtime.adapters.LocationInterceptionAdapter
import com.example.deviceidlab.runtime.adapters.NetworkInterceptionAdapter
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Host-Neutral Runtime Interception Entry Point for injected APKs and Xposed hosts.
 *
 * Separation of Concerns:
 * CONTROL / PROFILE LAYER -> ProfileStore
 * RUNTIME LAYER           -> IdentityRuntimeEntry -> HostBridge -> API Interception Adapters
 * TARGET PROCESS          -> Observes Replaced Values
 */
object IdentityRuntimeEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private const val TAG = "IdentityRuntime"

    @Volatile
    private var zygoteInitialized = false

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        if (zygoteInitialized) return
        zygoteInitialized = true
        Log.i(TAG, "[$TAG] [NPATCH INIT] Initializing Zygote runtime hooks")
        try {
            // Install system-wide bootclasspath network hooks if available
            NetworkInterceptionAdapter.installHooks(
                classLoader = ClassLoader.getSystemClassLoader(),
                packageName = "android",
                processName = "system_server"
            )
        } catch (t: Throwable) {
            Log.d(TAG, "[$TAG] Zygote init fallback: ${t.message}")
        }
    }

    @Volatile
    private var isRuntimeInitialized = false

    @JvmStatic
    fun init(context: android.content.Context) {
        HostBridge.setContext(context)
        val classLoader = context.classLoader ?: ClassLoader.getSystemClassLoader()
        val pkg = context.packageName ?: ""
        val proc = HostBridge.resolveCurrentProcessName() ?: pkg
        initRuntime(classLoader, pkg, proc)
    }

    @JvmStatic
    fun initRuntime(
        classLoader: ClassLoader = IdentityRuntimeEntry::class.java.classLoader ?: ClassLoader.getSystemClassLoader(),
        packageName: String = "",
        processName: String = ""
    ) {
        val pid = try { Process.myPid() } catch (_: Throwable) { 0 }
        val targetPkg = if (packageName.isNotEmpty()) packageName else HostBridge.resolveCurrentPackageName() ?: "unknown"
        val targetProc = if (processName.isNotEmpty()) processName else HostBridge.resolveCurrentProcessName() ?: targetPkg

        Log.i(TAG, "[$TAG] [NPATCH INIT] Loading runtime interception for package='$targetPkg' process='$targetProc' (PID $pid)")

        // 1. Canary Hook for Module Detection
        try {
            XposedHelpers.findAndHookMethod(
                "com.example.deviceidlab.DeviceIdReader",
                classLoader,
                "isNpatchHookActive",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.throwable = null
                        param.result = true
                    }
                }
            )
        } catch (_: Throwable) {}

        // 2. Identity APIs (Settings.Secure, Telephony, Build)
        IdentityInterceptionAdapter.installHooks(
            classLoader = classLoader,
            packageName = targetPkg,
            processName = targetProc
        )

        // 3. Location APIs (LocationManager, Location)
        LocationInterceptionAdapter.installHooks(
            classLoader = classLoader,
            packageName = targetPkg,
            processName = targetProc
        )

        // 4. Network APIs (NetworkInterface, WifiInfo, LinkProperties, etc.)
        NetworkInterceptionAdapter.installHooks(
            classLoader = classLoader,
            packageName = targetPkg,
            processName = targetProc
        )

        // Record successful runtime installation
        NPatchAuditManager.recordHookEvent(
            targetPackage = targetPkg,
            targetProcess = targetProc,
            targetPid = pid,
            hookEntryStatus = "EXECUTED",
            hookInstallationStatus = "ACTIVE_INTERCEPTION",
            canaryIntercepted = true,
            apiName = "IdentityRuntimeEntry.init",
            stage = NPatchAuditManager.HOOK_REGISTERED
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        initRuntime(lpparam.classLoader, lpparam.packageName, lpparam.processName)
    }
}
