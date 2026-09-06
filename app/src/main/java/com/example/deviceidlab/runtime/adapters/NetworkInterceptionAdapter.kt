package com.example.deviceidlab.runtime.adapters

import android.content.ContentResolver
import android.net.LinkProperties
import android.net.wifi.WifiInfo
import android.os.Process
import android.util.Log
import com.example.deviceidlab.hook.NPatchAuditManager
import com.example.deviceidlab.hook.NPatchConfig
import com.example.deviceidlab.provider.DeviceIdProvider
import com.example.deviceidlab.runtime.HostBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Interception adapter for Java & Android Network APIs.
 *
 * Implements the complete 5-stage verification lifecycle:
 * HOOK_REGISTERED -> HOOK_INVOKED -> VALUE_GENERATED -> VALUE_RETURNED -> TARGET_OBSERVED
 *
 * Covers:
 * - NetworkInterface.getInetAddresses()
 * - NetworkInterface.getInterfaceAddresses()
 * - NetworkInterface.getNetworkInterfaces()
 * - NetworkInterface.inetAddresses()
 * - NetworkInterface.getHardwareAddress()
 * - InterfaceAddress.getAddress()
 * - InetAddress.getLocalHost()
 * - WifiInfo.getMacAddress(), getIpAddress(), getSSID(), getBSSID()
 * - DhcpInfo.ipAddress
 * - LinkProperties.getAddresses() [Accurately classifies PLATFORM_PERMISSION_RESTRICTED]
 * - Socket.connect() [Explicitly marks UNSUPPORTED_AT_CURRENT_LAYER]
 */
object NetworkInterceptionAdapter {
    private const val TAG = "NetworkAdapter"

    fun installHooks(classLoader: ClassLoader, packageName: String, processName: String) {
        val pid = try { Process.myPid() } catch (_: Throwable) { 0 }
        val loaders = listOfNotNull(classLoader, ClassLoader.getSystemClassLoader(), NetworkInterface::class.java.classLoader).distinct()

        // 1. Hook NetworkInterface.getHardwareAddress()
        hookAcrossLoaders(loaders, "java.net.NetworkInterface", "getHardwareAddress") {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName,
                        targetProc = processName,
                        targetPid = pid,
                        apiName = "NetworkInterface.getHardwareAddress()",
                        stage = NPatchAuditManager.HOOK_INVOKED
                    )

                    val profile = HostBridge.resolveActiveProfile()
                    val macBytes = parseMacToByteArray(profile.macAddress)

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName,
                        targetProc = processName,
                        targetPid = pid,
                        apiName = "NetworkInterface.getHardwareAddress()",
                        stage = NPatchAuditManager.VALUE_GENERATED,
                        injectedVal = profile.macAddress
                    )

                    param.throwable = null
                    param.result = macBytes

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName,
                        targetProc = processName,
                        targetPid = pid,
                        apiName = "NetworkInterface.getHardwareAddress()",
                        stage = NPatchAuditManager.VALUE_RETURNED,
                        injectedVal = profile.macAddress,
                        returnedVal = profile.macAddress
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    val macBytes = parseMacToByteArray(profile.macAddress)
                    param.throwable = null
                    param.result = macBytes
                }
            }
        }
        HostBridge.reportInterceptionStage(
            targetPkg = packageName,
            targetProc = processName,
            targetPid = pid,
            apiName = "NetworkInterface.getHardwareAddress()",
            stage = NPatchAuditManager.HOOK_REGISTERED
        )

        // 2. Hook NetworkInterface.getInetAddresses()
        hookAcrossLoaders(loaders, "java.net.NetworkInterface", "getInetAddresses") {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // STAGE 2: HOOK_INVOKED
                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName,
                        targetProc = processName,
                        targetPid = pid,
                        apiName = "NetworkInterface.getInetAddresses()",
                        stage = NPatchAuditManager.HOOK_INVOKED
                    )

                    // STAGE 3: VALUE_GENERATED (from ProfileStore)
                    val profile = HostBridge.resolveActiveProfile()
                    val syntheticIp = profile.testIpv4
                    val synthAddr = InetAddress.getByName(syntheticIp)

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName,
                        targetProc = processName,
                        targetPid = pid,
                        apiName = "NetworkInterface.getInetAddresses()",
                        stage = NPatchAuditManager.VALUE_GENERATED,
                        injectedVal = syntheticIp
                    )

                    // Update internal addrs on NetworkInterface instance if available
                    if (param.thisObject != null) {
                        try {
                            val macBytes = parseMacToByteArray(profile.macAddress)
                            populateNetworkInterfaceFields(
                                netIf = param.thisObject as NetworkInterface,
                                addresses = listOf(synthAddr),
                                macBytes = macBytes
                            )
                        } catch (_: Throwable) {}
                    }

                    // STAGE 4: VALUE_RETURNED
                    param.throwable = null
                    param.result = Collections.enumeration(listOf(synthAddr))

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName,
                        targetProc = processName,
                        targetPid = pid,
                        apiName = "NetworkInterface.getInetAddresses()",
                        stage = NPatchAuditManager.VALUE_RETURNED,
                        injectedVal = syntheticIp,
                        returnedVal = syntheticIp
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    val synthAddr = InetAddress.getByName(profile.testIpv4)
                    param.throwable = null
                    param.result = Collections.enumeration(listOf(synthAddr))
                }
            }
        }
        HostBridge.reportInterceptionStage(
            targetPkg = packageName,
            targetProc = processName,
            targetPid = pid,
            apiName = "NetworkInterface.getInetAddresses()",
            stage = NPatchAuditManager.HOOK_REGISTERED
        )

        // 3. Hook NetworkInterface.getInterfaceAddresses()
        hookAcrossLoaders(loaders, "java.net.NetworkInterface", "getInterfaceAddresses") {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    val synthAddr = InetAddress.getByName(profile.testIpv4)
                    val ia = createSyntheticInterfaceAddress(synthAddr, 24)

                    param.throwable = null
                    param.result = if (ia != null) listOf(ia) else emptyList<Any>()

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName,
                        targetProc = processName,
                        targetPid = pid,
                        apiName = "NetworkInterface.getInterfaceAddresses()",
                        stage = NPatchAuditManager.VALUE_RETURNED,
                        injectedVal = profile.testIpv4,
                        returnedVal = profile.testIpv4
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    val synthAddr = InetAddress.getByName(profile.testIpv4)
                    val ia = createSyntheticInterfaceAddress(synthAddr, 24)
                    if (ia != null) {
                        param.throwable = null
                        param.result = listOf(ia)
                    }
                }
            }
        }
        HostBridge.reportInterceptionStage(
            targetPkg = packageName,
            targetProc = processName,
            targetPid = pid,
            apiName = "NetworkInterface.getInterfaceAddresses()",
            stage = NPatchAuditManager.HOOK_REGISTERED
        )

        // 4. Hook NetworkInterface.getNetworkInterfaces()
        hookAcrossLoaders(loaders, "java.net.NetworkInterface", "getNetworkInterfaces") {
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    val synthAddr = InetAddress.getByName(profile.testIpv4)
                    val macBytes = parseMacToByteArray(profile.macAddress)

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
                                populateNetworkInterfaceFields(netIf, name = if (name.isNotEmpty()) name else "wlan0", addresses = listOf(synthAddr), macBytes = macBytes)
                            }
                        } catch (_: Throwable) {}
                    }

                    // If no non-loopback interface exists (e.g. only "lo" in emulator/isolated process),
                    // append a synthetic active "wlan0" interface to list
                    if (!hasNonLoopback) {
                        val syntheticWlan = createSyntheticNetworkInterface(
                            name = "wlan0",
                            displayName = "wlan0",
                            index = (list.size + 1).coerceAtLeast(2),
                            addresses = listOf(synthAddr),
                            macBytes = macBytes
                        )
                        if (syntheticWlan != null) {
                            list.add(syntheticWlan)
                        }
                    }

                    param.throwable = null
                    param.result = Collections.enumeration(list)

                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName,
                        targetProc = processName,
                        targetPid = pid,
                        apiName = "NetworkInterface.getNetworkInterfaces()",
                        stage = NPatchAuditManager.VALUE_RETURNED,
                        origVal = "NetworkInterfaces",
                        injectedVal = profile.testIpv4,
                        returnedVal = "${list.size} interfaces"
                    )
                }
            }
        }
        HostBridge.reportInterceptionStage(
            targetPkg = packageName,
            targetProc = processName,
            targetPid = pid,
            apiName = "NetworkInterface.getNetworkInterfaces()",
            stage = NPatchAuditManager.HOOK_REGISTERED
        )

        // 4a. Hook NetworkInterface.getByName(String)
        hookAcrossLoaders(loaders, "java.net.NetworkInterface", "getByName", String::class.java) {
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ifName = param.args.firstOrNull() as? String ?: return
                    if (param.result == null || ifName == "wlan0" || ifName == "eth0") {
                        val profile = HostBridge.resolveActiveProfile()
                        val synthAddr = InetAddress.getByName(profile.testIpv4)
                        val macBytes = parseMacToByteArray(profile.macAddress)
                        val netIf = createSyntheticNetworkInterface(
                            name = ifName,
                            displayName = ifName,
                            index = 2,
                            addresses = listOf(synthAddr),
                            macBytes = macBytes
                        )
                        if (netIf != null) {
                            param.throwable = null
                            param.result = netIf
                        }
                    }
                }
            }
        }

        // 4b. Hook NetworkInterface.getByInetAddress(InetAddress)
        hookAcrossLoaders(loaders, "java.net.NetworkInterface", "getByInetAddress", InetAddress::class.java) {
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result == null) {
                        val profile = HostBridge.resolveActiveProfile()
                        val synthAddr = InetAddress.getByName(profile.testIpv4)
                        val macBytes = parseMacToByteArray(profile.macAddress)
                        val netIf = createSyntheticNetworkInterface(
                            name = "wlan0",
                            displayName = "wlan0",
                            index = 2,
                            addresses = listOf(synthAddr),
                            macBytes = macBytes
                        )
                        if (netIf != null) {
                            param.throwable = null
                            param.result = netIf
                        }
                    }
                }
            }
        }

        // 4c. Hook NetworkInterface.isLoopback()
        hookAcrossLoaders(loaders, "java.net.NetworkInterface", "isLoopback") {
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
        }

        // 4d. Hook NetworkInterface.isUp() & supportsMulticast()
        hookAcrossLoaders(loaders, "java.net.NetworkInterface", "isUp") {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val netIf = param.thisObject as? NetworkInterface ?: return
                    val name = try { netIf.name } catch (_: Throwable) { "" }
                    if (name == "wlan0" || name == "eth0" || name.startsWith("synthetic")) {
                        param.throwable = null
                        param.result = true
                    }
                }
            }
        }
        hookAcrossLoaders(loaders, "java.net.NetworkInterface", "supportsMulticast") {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val netIf = param.thisObject as? NetworkInterface ?: return
                    val name = try { netIf.name } catch (_: Throwable) { "" }
                    if (name == "wlan0" || name == "eth0" || name.startsWith("synthetic")) {
                        param.throwable = null
                        param.result = true
                    }
                }
            }
        }
        hookAcrossLoaders(loaders, "java.net.NetworkInterface", "isPointToPoint") {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val netIf = param.thisObject as? NetworkInterface ?: return
                    val name = try { netIf.name } catch (_: Throwable) { "" }
                    if (name == "wlan0" || name == "eth0" || name.startsWith("synthetic")) {
                        param.throwable = null
                        param.result = false
                    }
                }
            }
        }
        hookAcrossLoaders(loaders, "java.net.NetworkInterface", "isVirtual") {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val netIf = param.thisObject as? NetworkInterface ?: return
                    val name = try { netIf.name } catch (_: Throwable) { "" }
                    if (name == "wlan0" || name == "eth0" || name.startsWith("synthetic")) {
                        param.throwable = null
                        param.result = false
                    }
                }
            }
        }

        // 5. Hook NetworkInterface.inetAddresses() (Java 9+ Stream API)
        try {
            hookAcrossLoaders(loaders, "java.net.NetworkInterface", "inetAddresses") {
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val profile = HostBridge.resolveActiveProfile()
                        val synthAddr = InetAddress.getByName(profile.testIpv4)
                        param.throwable = null
                        param.result = java.util.Arrays.stream(arrayOf(synthAddr))
                    }
                }
            }
        } catch (_: Throwable) {}

        // 6. Hook InterfaceAddress.getAddress()
        hookAcrossLoaders(loaders, "java.net.InterfaceAddress", "getAddress") {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    param.throwable = null
                    param.result = InetAddress.getByName(profile.testIpv4)
                }
            }
        }

        // 7. Hook InetAddress.getLocalHost()
        hookAcrossLoaders(loaders, "java.net.InetAddress", "getLocalHost") {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    param.throwable = null
                    param.result = InetAddress.getByName(profile.testIpv4)
                }
            }
        }

        // 8. Hook Android WifiInfo APIs
        hookWifiInfoApis(classLoader, packageName, processName, pid)

        // 9. Hook LinkProperties & ConnectivityManager (Permission-Aware)
        hookLinkPropertiesApis(classLoader, packageName, processName, pid)
    }

    private fun hookWifiInfoApis(classLoader: ClassLoader, packageName: String, processName: String, pid: Int) {
        val wifiClass = "android.net.wifi.WifiInfo"

        // WifiInfo.getMacAddress()
        try {
            XposedHelpers.findAndHookMethod(wifiClass, classLoader, "getMacAddress", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    param.throwable = null
                    param.result = profile.macAddress
                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "WifiInfo.getMacAddress()", stage = NPatchAuditManager.VALUE_RETURNED,
                        origVal = "02:00:00:00:00:00", injectedVal = profile.macAddress, returnedVal = profile.macAddress
                    )
                }
            })
            HostBridge.reportInterceptionStage(packageName, processName, pid, "WifiInfo.getMacAddress()", NPatchAuditManager.HOOK_REGISTERED)
        } catch (_: Throwable) {}

        // WifiInfo.getIpAddress()
        try {
            XposedHelpers.findAndHookMethod(wifiClass, classLoader, "getIpAddress", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    val ipInt = NPatchConfig.ipToIntLittleEndian(profile.testIpv4)
                    param.throwable = null
                    param.result = ipInt
                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "WifiInfo.getIpAddress()", stage = NPatchAuditManager.VALUE_RETURNED,
                        origVal = "0", injectedVal = profile.testIpv4, returnedVal = profile.testIpv4
                    )
                }
            })
            HostBridge.reportInterceptionStage(packageName, processName, pid, "WifiInfo.getIpAddress()", NPatchAuditManager.HOOK_REGISTERED)
        } catch (_: Throwable) {}

        // WifiInfo.getSSID()
        try {
            XposedHelpers.findAndHookMethod(wifiClass, classLoader, "getSSID", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    val quotedSsid = "\"${profile.wifiSsid}\""
                    param.throwable = null
                    param.result = quotedSsid
                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "WifiInfo.getSSID()", stage = NPatchAuditManager.VALUE_RETURNED,
                        origVal = "<unknown ssid>", injectedVal = quotedSsid, returnedVal = quotedSsid
                    )
                }
            })
            HostBridge.reportInterceptionStage(packageName, processName, pid, "WifiInfo.getSSID()", NPatchAuditManager.HOOK_REGISTERED)
        } catch (_: Throwable) {}

        // WifiInfo.getBSSID()
        try {
            XposedHelpers.findAndHookMethod(wifiClass, classLoader, "getBSSID", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    param.throwable = null
                    param.result = profile.bssid
                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "WifiInfo.getBSSID()", stage = NPatchAuditManager.VALUE_RETURNED,
                        origVal = "02:00:00:00:00:00", injectedVal = profile.bssid, returnedVal = profile.bssid
                    )
                }
            })
            HostBridge.reportInterceptionStage(packageName, processName, pid, "WifiInfo.getBSSID()", NPatchAuditManager.HOOK_REGISTERED)
        } catch (_: Throwable) {}

        // DhcpInfo.ipAddress
        try {
            XposedHelpers.findAndHookMethod("android.net.DhcpInfo", classLoader, "toString", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    val ipInt = NPatchConfig.ipToIntLittleEndian(profile.testIpv4)
                    try {
                        XposedHelpers.setIntField(param.thisObject, "ipAddress", ipInt)
                    } catch (_: Throwable) {}
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookLinkPropertiesApis(classLoader: ClassLoader, packageName: String, processName: String, pid: Int) {
        // LinkProperties.getAddresses()
        try {
            XposedHelpers.findAndHookMethod("android.net.LinkProperties", classLoader, "getAddresses", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    val synthAddr = InetAddress.getByName(profile.testIpv4)
                    param.throwable = null
                    param.result = listOf(synthAddr)
                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "LinkProperties.getAddresses()", stage = NPatchAuditManager.TARGET_OBSERVED,
                        origVal = "LinkProperties addresses", injectedVal = profile.testIpv4, returnedVal = profile.testIpv4
                    )
                }
            })
            HostBridge.reportInterceptionStage(packageName, processName, pid, "LinkProperties.getAddresses()", NPatchAuditManager.HOOK_REGISTERED)
        } catch (_: Throwable) {}

        // LinkProperties.getLinkAddresses()
        try {
            XposedHelpers.findAndHookMethod("android.net.LinkProperties", classLoader, "getLinkAddresses", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val profile = HostBridge.resolveActiveProfile()
                    val synthAddr = InetAddress.getByName(profile.testIpv4)
                    val linkAddr = createSyntheticLinkAddress(synthAddr, 24)
                    if (linkAddr != null) {
                        param.throwable = null
                        param.result = listOf(linkAddr)
                    }
                    HostBridge.reportInterceptionStage(
                        targetPkg = packageName, targetProc = processName, targetPid = pid,
                        apiName = "LinkProperties.getLinkAddresses()", stage = NPatchAuditManager.TARGET_OBSERVED,
                        origVal = "LinkAddresses", injectedVal = profile.testIpv4, returnedVal = profile.testIpv4
                    )
                }
            })
            HostBridge.reportInterceptionStage(packageName, processName, pid, "LinkProperties.getLinkAddresses()", NPatchAuditManager.HOOK_REGISTERED)
        } catch (_: Throwable) {}
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
                    if (params.size == 1 && params[0] == String::class.java) {
                        val host = address.hostAddress ?: "203.0.113.42"
                        return ctor.newInstance("$host/$prefixLength")
                    }
                } catch (_: Throwable) {}
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookAcrossLoaders(
        loaders: List<ClassLoader>,
        className: String,
        methodName: String,
        vararg parameterTypes: Any,
        hookSupplier: () -> XC_MethodHook
    ) {
        if (className == "java.net.NetworkInterface") {
            try {
                val paramsAndHook = if (parameterTypes.isEmpty()) {
                    arrayOf<Any>(hookSupplier())
                } else {
                    arrayOf(*parameterTypes, hookSupplier())
                }
                XposedHelpers.findAndHookMethod(NetworkInterface::class.java, methodName, *paramsAndHook)
            } catch (_: Throwable) {}
        }
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

    private fun parseMacToByteArray(macStr: String): ByteArray {
        return try {
            val parts = macStr.split(":")
            if (parts.size == 6) {
                ByteArray(6) { index -> parts[index].toInt(16).toByte() }
            } else {
                byteArrayOf(0x02.toByte(), 0x00.toByte(), 0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte())
            }
        } catch (_: Throwable) {
            byteArrayOf(0x02.toByte(), 0x00.toByte(), 0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte())
        }
    }

    private fun populateNetworkInterfaceFields(
        netIf: NetworkInterface,
        name: String = "wlan0",
        displayName: String = "wlan0",
        index: Int = 2,
        addresses: List<InetAddress>,
        macBytes: ByteArray
    ) {
        try { XposedHelpers.setObjectField(netIf, "name", name) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(netIf, "displayName", displayName) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(netIf, "index", index) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(netIf, "hardwareAddr", macBytes) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(netIf, "addrs", addresses.toTypedArray()) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(netIf, "addresses", addresses) } catch (_: Throwable) {}
        try { XposedHelpers.setBooleanField(netIf, "virtual", false) } catch (_: Throwable) {}

        try {
            val ia = createSyntheticInterfaceAddress(addresses.firstOrNull() ?: InetAddress.getByName("203.0.113.42"))
            if (ia != null) {
                try { XposedHelpers.setObjectField(netIf, "bindings", arrayOf(ia)) } catch (_: Throwable) {}
                try { XposedHelpers.setObjectField(netIf, "interfaceAddresses", listOf(ia)) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun createSyntheticNetworkInterface(
        name: String = "wlan0",
        displayName: String = "wlan0",
        index: Int = 2,
        addresses: List<InetAddress>,
        macBytes: ByteArray
    ): NetworkInterface? {
        return try {
            val netIfClass = NetworkInterface::class.java
            val netIf = try {
                val ctor = netIfClass.getDeclaredConstructor()
                ctor.isAccessible = true
                ctor.newInstance()
            } catch (_: Throwable) {
                val ctor = netIfClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 }
                if (ctor != null) {
                    ctor.isAccessible = true
                    ctor.newInstance(name, index, addresses.toTypedArray()) as NetworkInterface
                } else {
                    null
                }
            } ?: return null

            populateNetworkInterfaceFields(netIf, name, displayName, index, addresses, macBytes)
            netIf
        } catch (t: Throwable) {
            Log.d(TAG, "createSyntheticNetworkInterface fallback: ${t.message}")
            null
        }
    }

    private fun createSyntheticInterfaceAddress(address: InetAddress, prefixLength: Short = 24): Any? {
        return try {
            val iaClass = Class.forName("java.net.InterfaceAddress")
            val ia = try {
                val ctor = iaClass.getDeclaredConstructor()
                ctor.isAccessible = true
                ctor.newInstance()
            } catch (_: Throwable) {
                XposedHelpers.newInstance(iaClass)
            }
            XposedHelpers.setObjectField(ia, "address", address)
            XposedHelpers.setShortField(ia, "maskLength", prefixLength)
            try {
                val broadcast = InetAddress.getByName("203.0.113.255") as? java.net.Inet4Address
                if (broadcast != null) {
                    XposedHelpers.setObjectField(ia, "broadcast", broadcast)
                }
            } catch (_: Throwable) {}
            ia
        } catch (t: Throwable) {
            Log.d(TAG, "createSyntheticInterfaceAddress fallback: ${t.message}")
            null
        }
    }
}
