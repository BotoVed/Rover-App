# Automatic Channel Priority — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically select best transport channel (WiFi > 4G > BLE > LoRa) with reactive + periodic probe

**Architecture:** New `ChannelController` class owns interface lifecycle. On startup, probes TCP first. On WiFi/mobile data change detected via `ConnectivityManager.NetworkCallback`, re-evaluates priority. Periodic probe every 60s checks if higher-priority channel reappeared. Monitors TCP interface online state every 5s for fast fallback. RnsManager delegates all TCP management to controller and provides BLE create/destroy via lambdas.

**Tech Stack:** Kotlin (Android), Reticulum-KT v0.0.21, LXMF-KT v0.0.13

---

### Task 1: Create ChannelController class

**File:**
- Create: `app/src/main/java/dev/botoved/rover/service/ChannelController.kt`

- [ ] **Step 1: Write the file shell — Channel sealed class + class skeleton**

```kotlin
package dev.botoved.rover.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.reticulum.Reticulum
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import java.net.Socket
import java.net.SocketTimeoutException

sealed class Channel(val label: String) {
    data object WiFi : Channel("WiFi")
    data object Mobile4G : Channel("4G")
    data object BLE : Channel("BLE")
    data object LoRa : Channel("LoRa")
}

class ChannelController(
    private val scope: CoroutineScope,
    context: Context,
    private val host: String,
    private val port: Int,
    private val onBleNeeded: suspend () -> Unit,
    private val onBleDetach: suspend () -> Unit,
    private val onChannelChanged: (Channel) -> Unit,
) {
    companion object {
        private const val TAG = "Rover"
        private const val PROBE_TIMEOUT_MS = 5_000L
        private const val PERIODIC_INTERVAL_MS = 60_000L
        private const val ONLINE_MONITOR_INTERVAL_MS = 5_000L
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    var currentChannel: Channel = Channel.LoRa
        private set
    private var tcpIface: TCPClientInterface? = null
    private var onlineMonitorJob: Job? = null
    private var periodicJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
}
```

- [ ] **Step 2: Add `probeTcp()`**

```kotlin
    fun probeTcp(): Boolean {
        return try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), PROBE_TIMEOUT_MS.toInt())
            socket.close()
            Log.i(TAG, "TCP probe OK: $host:$port")
            true
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "TCP probe timeout: $host:$port")
            false
        } catch (e: Exception) {
            Log.w(TAG, "TCP probe failed: ${e.message}")
            false
        }
    }
```

- [ ] **Step 3: Add `determineBestTcpChannel()`**

```kotlin
    private fun determineBestTcpChannel(): Channel {
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        return if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            Channel.WiFi
        } else {
            Channel.Mobile4G
        }
    }
```

- [ ] **Step 4: Add `detachAllInterfaces()`**

```kotlin
    private fun detachAllInterfaces() {
        Transport.interfaces.toList().forEach { ifaceRef ->
            Transport.deregisterInterface(ifaceRef)
        }
        Log.i(TAG, "All interfaces detached from Transport")
    }
```

- [ ] **Step 5: Add `switchTo()` + TCP online monitor**

```kotlin
    private fun startTcpOnlineMonitor() {
        onlineMonitorJob?.cancel()
        onlineMonitorJob = scope.launch {
            while (isActive) {
                delay(ONLINE_MONITOR_INTERVAL_MS)
                val online = tcpIface?.online?.value == true
                if (!online && (currentChannel is Channel.WiFi || currentChannel is Channel.Mobile4G)) {
                    Log.w(TAG, "TCP interface offline, switching to BLE")
                    switchTo(Channel.BLE)
                }
            }
        }
    }

    fun switchTo(new: Channel) {
        if (new == currentChannel) return
        Log.i(TAG, "Switching: ${currentChannel.label} → ${new.label}")
        detachAllInterfaces()
        tcpIface = null
        onlineMonitorJob?.cancel()
        when (new) {
            is Channel.WiFi, is Channel.Mobile4G -> {
                val iface = TCPClientInterface("rover-tcp", host, port)
                tcpIface = iface
                iface.start()
                Reticulum.getSharedInstance()?.addInterface(iface)
                Transport.registerInterface(iface.toRef())
                scope.launch { onBleDetach() }
                startTcpOnlineMonitor()
            }
            is Channel.BLE -> {
                scope.launch { onBleNeeded() }
            }
            is Channel.LoRa -> {
                scope.launch { onBleDetach() }
            }
        }
        currentChannel = new
        onChannelChanged(new)
    }
```

- [ ] **Step 6: Add ConnectivityManager listener**

```kotlin
    fun startNetworkListener() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                        Log.i(TAG, "WiFi available")
                        if (currentChannel !is Channel.WiFi) {
                            scope.launch {
                                if (probeTcp()) {
                                    switchTo(determineBestTcpChannel())
                                }
                            }
                        }
                    }
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                        Log.i(TAG, "Cellular available")
                        if (currentChannel !is Channel.WiFi && currentChannel !is Channel.Mobile4G) {
                            scope.launch {
                                if (probeTcp()) {
                                    switchTo(determineBestTcpChannel())
                                }
                            }
                        }
                    }
                }
            }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        networkCallback = callback
    }

    fun stopNetworkListener() {
        networkCallback?.let { cm.unregisterNetworkCallback(it) }
        networkCallback = null
    }
```

- [ ] **Step 7: Add periodic probe**

```kotlin
    fun startPeriodicProbe() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (isActive) {
                delay(PERIODIC_INTERVAL_MS)
                if (currentChannel is Channel.BLE || currentChannel is Channel.LoRa) {
                    if (probeTcp()) {
                        switchTo(determineBestTcpChannel())
                    }
                }
            }
        }
    }

    fun stopPeriodicProbe() {
        periodicJob?.cancel()
        periodicJob = null
    }
```

- [ ] **Step 8: Add `start()` / `stop()` lifecycle**

```kotlin
    fun start() {
        Log.i(TAG, "ChannelController starting")
        startNetworkListener()
        startPeriodicProbe()
        if (probeTcp()) {
            switchTo(determineBestTcpChannel())
        } else {
            Log.i(TAG, "Initial TCP probe failed, staying on BLE")
        }
    }

    fun stop() {
        Log.i(TAG, "ChannelController stopping")
        stopPeriodicProbe()
        stopNetworkListener()
        onlineMonitorJob?.cancel()
        detachAllInterfaces()
        tcpIface = null
        currentChannel = Channel.LoRa
    }
```

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/dev/botoved/rover/service/ChannelController.kt
git commit -m "feat: add ChannelController for automatic channel priority (WiFi > 4G > BLE > LoRa)"
```

---

### Task 2: Integrate ChannelController into RnsManager

**File:**
- Modify: `app/src/main/java/dev/botoved/rover/service/RnsManager.kt`

- [ ] **Step 1: Add `channelController` field + `startChannelController()` method**

Add to class body (after existing fields):

```kotlin
    private var channelController: ChannelController? = null

    fun startChannelController(host: String, port: Int) {
        val controller = ChannelController(
            scope = scope,
            context = context,
            host = host,
            port = port,
            onBleNeeded = {
                recreateBleInterface()
            },
            onBleDetach = {
                destroyBleInterface()
            },
            onChannelChanged = { channel ->
                Log.i(TAG, "Channel changed: ${channel.label}")
                val intent = android.content.Intent("dev.botoved.rover.ACTION_PONG")
                intent.putExtra("channel", channel.label)
                androidx.localbroadcastmanager.content.LocalBroadcastManager
                    .getInstance(context).sendBroadcast(intent)
            }
        )
        channelController = controller
        controller.start()
    }

    private suspend fun recreateBleInterface() {
        bleDriver?.let { driver ->
            Transport.interfaces.toList().forEach { Transport.deregisterInterface(it) }
            driver.shutdown()
        }
        val btManager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        val driver = AndroidBLEDriver(context, btManager, scope)
        bleDriver = driver
        val iface = BLEInterface("rover-ble", driver, transportIdentity = byteArrayOf())
        bleInterface = iface
        iface.start()
        Reticulum.getSharedInstance()?.addInterface(iface)
        Transport.registerInterface(iface.toRef())
        Log.i(TAG, "BLE interface recreated")
    }

    private suspend fun destroyBleInterface() {
        bleInterface?.let { iface ->
            Transport.interfaces.toList().forEach { Transport.deregisterInterface(it) }
            iface.detach()
            bleDriver?.shutdown()
            bleInterface = null
            bleDriver = null
            Log.i(TAG, "BLE interface destroyed")
        }
    }
```

- [ ] **Step 2: Modify `getActiveChannel()` to delegate to controller**

```kotlin
    fun getActiveChannel(): String {
        return channelController?.currentChannel?.label ?: "LoRa"
    }
```

- [ ] **Step 3: Remove `addTcpInterfaceAndWait()` and `isTcpAdded` field**

Delete the entire `addTcpInterfaceAndWait()` method and `isTcpAdded` property. TCP lifecycle is now managed by ChannelController. If `tcpInterface` field is only used by `addTcpInterfaceAndWait`, remove it too.

Note: this will cause a compile error if `RoverService.kt` still references `isTcpAdded`. Task 3 removes those references, so do Tasks 3.1–3.3 before committing this step. Or commit everything together.

- [ ] **Step 4: Commit** (after verifying RoverService compiles)

```bash
git add app/src/main/java/dev/botoved/rover/service/RnsManager.kt
git commit -m "refactor: integrate ChannelController into RnsManager, remove addTcpInterfaceAndWait"
```

---

### Task 3: Update RoverService for new channel lifecycle

**File:**
- Modify: `app/src/main/java/dev/botoved/rover/service/RoverService.kt`

- [ ] **Step 1: Import ChannelController at top**

Verify the import `import dev.botoved.rover.service.ChannelController` is present (or auto-imported by IDE).

- [ ] **Step 2: Call `startChannelController()` after RnsManager starts in `onStartCommand`**

After `manager.start(identity)` and `rnsManagerReady.complete(manager)`, add:

```kotlin
                // Start channel controller for automatic priority switching
                val tcpPref = prefs.serverTcp.first()
                if (tcpPref != null) {
                    val parts = tcpPref.split(":")
                    if (parts.size == 2) {
                        val tcpHost = parts[0]
                        val tcpPort = parts[1].toIntOrNull() ?: 4242
                        manager.startChannelController(tcpHost, tcpPort)
                    }
                }
```

- [ ] **Step 3: Remove manual `addTcpInterfaceAndWait` calls**

In the auto-reconnect block and watchdog, remove `addTcpInterfaceAndWait()` calls. The watchdog should only send PING/REQ, not manage interfaces.

Replace the watchdog loop (currently starting around line 263) with:

```kotlin
                serviceScope.launch {
                    while (isActive) {
                        delay(30_000)
                        val elapsed = System.currentTimeMillis() - lastPongTime
                        if (elapsed > 30_000 && isServerOnline) {
                            isServerOnline = false
                            Log.w(TAG, "Watchdog: no PONG for ${elapsed}ms, marking offline")
                        }
                        if (!isServerOnline || !repository.isConfigReceived) {
                            val wPrefs = ServerPreferences(applicationContext)
                            val wDst = wPrefs.serverDestHash.first() ?: continue
                            val wPk = wPrefs.serverPk.first() ?: continue
                            if (!repository.isConfigReceived) {
                                Log.i(TAG, "Watchdog: no config, sending REQ for all sections")
                                for (section in listOf("m", "a", "d")) {
                                    manager.sendReq(wDst, wPk, section)
                                    delay(200)
                                }
                            } else {
                                Log.i(TAG, "Watchdog: offline, sending PING")
                                manager.sendPing(wDst, wPk, repository.getSectionHashes())
                            }
                        }
                    }
                }
```

- [ ] **Step 4: Simplify auto-reconnect block**

In the auto-reconnect block (lines 230-258), keep the identity/registration check but remove TCP add. ChannelController handles TCP automatically:

```kotlin
                if (reg == "approved") {
                    val dst = prefs.serverDestHash.first()
                    val pk = prefs.serverPk.first()
                    Log.i(TAG, "Auto-reconnect: dst=$dst")
                    if (dst != null && pk != null) {
                        repository.resetConfigReceived()
                        repository.clearAll()
                        Log.i(TAG, "Auto-reconnect: DB cleared, channel controller handles transport")
                        Log.i(TAG, "Auto-reconnect: sending REQ for all sections")
                        for (section in listOf("m", "a", "d")) {
                            manager.sendReq(dst, pk, section)
                            delay(200)
                        }
                        updateNotification("Подключено")
                    }
                }
```

- [ ] **Step 5: Simplify REGISTER broadcast receiver**

Replace `registerReceiver` with version that doesn't call `addTcpInterfaceAndWait`:

```kotlin
    private val registerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val dst = intent?.getStringExtra("dst") ?: return
            val pk = intent.getStringExtra("pk") ?: return
            val uid = intent.getStringExtra("uid") ?: ""
            serviceScope.launch {
                val manager = rnsManagerReady.await()
                manager.sendRegister(dst, pk, uid)
            }
        }
    }
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/botoved/rover/service/RoverService.kt
git commit -m "refactor: RoverService delegates interface management to ChannelController"
```

---

### Task 4: Verify build and test

**No test files exist in project — manual verification only.**

- [ ] **Step 1: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Install on device**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: Success

- [ ] **Step 3: Verify channel switching in logcat**

Run: `adb logcat -c && adb shell am force-stop dev.botoved.rover && sleep 1 && adb shell monkey -p dev.botoved.rover 1 >/dev/null 2>&1 && sleep 15 && adb logcat -d -s Rover`

Check logcat for:
```
ChannelController starting
TCP probe OK: 192.168.1.114:4242
Switching: LoRa → WiFi
Channel changed: WiFi
BLE interface destroyed
```

- [ ] **Step 4: Test WiFi → BLE fallback**

1. Turn off WiFi on phone while app is running
2. Within 5-10 seconds, logcat should show:
```
TCP interface offline, switching to BLE
Switching: WiFi → BLE
BLE interface recreated
Channel changed: BLE
```
3. UI shows "BLE" channel indicator

- [ ] **Step 5: Test BLE → WiFi recovery**

1. While app shows "BLE" channel, re-enable WiFi
2. Check logcat — should see either immediate (ConnectivityManager) or within 60s (periodic probe):
```
WiFi available → TCP probe OK → Switching: BLE → WiFi
```
or
```
TCP probe OK → Switching: BLE → WiFi
```
3. UI shows "WiFi" channel indicator

- [ ] **Step 6: Test 4G fallback**

1. Turn off WiFi but keep mobile data on
2. App should show "4G" channel (TCP over mobile data)

- [ ] **Step 7: Commit final**

```bash
git add -A
git commit -m "chore: verify automatic channel priority works end-to-end"
```
