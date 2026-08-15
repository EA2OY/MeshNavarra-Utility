/* Copyright (c) 2026 Tai Soluciones - taisoluciones@gmail.com */
package com.meshkachoutility

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Bluetooth Low Energy transport for Meshtastic nodes.
 *
 * Protocol (from the Meshtastic firmware):
 * - Service: 6ba1b218-15a8-461f-9fa8-5dcae273eafd
 * - TORADIO   (write)  f75c76d2-129e-4dad-a1dd-7866124401e7
 * - FROMRADIO (read)   2c55e69e-4993-11ed-b878-0242ac120002
 * - FROMNUM   (notify) ed9da18c-a800-4f66-a670-aa7547e34453
 *
 * Unlike USB, BLE carries RAW protobuf (no 0x94 0xC3 framing). The radio
 * notifies via FROMNUM when a FromRadio packet is queued; the client reads
 * FROMRADIO (blocking read that returns empty when there is nothing left).
 */
class BleConnectionManager(
    private val context: Context,
    private val listener: UsbConnectionManager.ConnectionListener,
    private val onLog: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "BleConnectionManager"
        val MESH_SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TRANSIENT_RETRY_MS = 500L
        private const val EMPTY_RETRY_MS = 250L
        private const val STALL_TIMEOUT_MS = 25000L
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var lastDevice: BluetoothDevice? = null
    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null
    private var fromNumChar: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private val writeLock = Any()
    private var pendingRead = false
    private var bondRequested = false
    @Volatile private var configDraining = false
    private val handler = Handler(Looper.getMainLooper())
    private val stallHandler = Handler(Looper.getMainLooper())
    @Volatile private var stallWatchdogArmed = false
    private var lastDataTimestamp = 0L
    private var mtuNegotiated = false

    /**
     * Reconnects to the last used BLE device (e.g. after a node reboot).
     */
    fun reconnect(): Boolean {
        val device = lastDevice ?: return false
        return connect(device)
    }

    /**
     * Enables aggressive proactive draining during the config handshake (FROMNUM
     * is gated behind STATE_SEND_PACKETS). When false, we only read after a
     * FROMNUM notify, so writes are never starved by blocking reads.
     */
    fun setConfigDraining(active: Boolean) {
        configDraining = active
        if (active) {
            handler.post { triggerDrain() }
        } else {
            // Handshake finished (ConfigComplete or safety timeout): the stall
            // watchdog must never survive a completed download, or it would force
            // spurious reconnects during the post-download silence.
            disarmStallWatchdog()
        }
    }

    /**
     * Called once the GATT session is fully ready (services + MTU negotiated):
     * marks the transport connected and seeds the proactive drain so the
     * firmware's config burst starts flowing immediately.
     */
    private fun completeConnect() {
        isConnected = true
        listener.onConnected()
        triggerDrain()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("BLE connection error: $status")
            }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    onLog("BLE GATT connected. Discovering services...")
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    onLog("BLE GATT disconnected")
                    closeInternal()
                    listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("BLE service discovery failed: $status")
                listener.onError(IllegalStateException("BLE service discovery failed ($status)"))
                return
            }
            val service = gatt.getService(MESH_SERVICE_UUID)
            if (service == null) {
                onLog("Meshtastic BLE service not found")
                listener.onError(IllegalStateException("Meshtastic BLE service not found"))
                return
            }
            toRadioChar = service.getCharacteristic(TORADIO_UUID)
            fromRadioChar = service.getCharacteristic(FROMRADIO_UUID)
            fromNumChar = service.getCharacteristic(FROMNUM_UUID)
            onLog("BLE chars found: toRadio=${toRadioChar != null} fromRadio=${fromRadioChar != null} fromNum=${fromNumChar != null}")

            if (toRadioChar == null || fromRadioChar == null || fromNumChar == null) {
                listener.onError(IllegalStateException("Meshtastic BLE characteristics missing"))
                return
            }

            // Enable notifications on FROMNUM before any handshake traffic.
            gatt.setCharacteristicNotification(fromNumChar, true)
            val cccd = fromNumChar?.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            } else {
                onLog("FROMNUM has no CCCD descriptor")
            }

            // Negotiate a larger MTU as a best-effort improvement (fewer round-trips
            // per FROMRADIO chunk). We mark the transport connected immediately so a
            // reconnect that skips the MTU callback (same-device re-bond) never ends
            // up half-connected; onMtuChanged just updates the negotiated size.
            completeConnect()
            if (!mtuNegotiated && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                mtuNegotiated = true
                try {
                    gatt.requestMtu(512)
                } catch (e: Exception) {
                    onLog("BLE requestMtu failed: ${e.message}")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            onLog("BLE MTU negotiated: $mtu (status=$status)")
            completeConnect()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            onLog("BLE FROMNUM notify descriptor write: ${if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "FAILED($status)"}")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == FROMNUM_UUID) {
                onLog("BLE FROMNUM notify received")
                triggerDrain()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            pendingRead = false
            if (characteristic.uuid != FROMRADIO_UUID) return
            // Any GATT read activity means the session is alive — refresh the stall
            // heartbeat so the watchdog only trips on a truly frozen link.
            lastDataTimestamp = System.currentTimeMillis()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("BLE FROMRADIO read failed: $status")
                // transient — retry shortly
                handler.postDelayed({ triggerDrain() }, TRANSIENT_RETRY_MS)
                return
            }
            val data = characteristic.value
            if (data != null && data.isNotEmpty()) {
                onLog("BLE FROMRADIO read: ${data.size} bytes")
                listener.onDataReceived(data)
                triggerDrain() // keep draining queued packets (like the official client)
            } else {
                // Empty read: during the config handshake the firmware gates FROMNUM
                // notifications, so keep draining proactively. In steady state, stop
                // and wait for the next FROMNUM notify so writes are not starved.
                if (configDraining) {
                    handler.postDelayed({ triggerDrain() }, EMPTY_RETRY_MS)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == TORADIO_UUID) {
                onLog("BLE TORADIO write result: ${if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "FAILED($status)"}")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    triggerDrain() // after any write, poll for the response
                }
            }
        }
    }

    /**
     * Starts (or continues) a FROMRADIO drain. The firmware's read blocks until a
     * packet is ready and returns empty when the queue is exhausted, so reading in
     * a loop until empty mirrors the official client. Guards against stacking reads.
     */
    private fun triggerDrain() {
        val g = gatt ?: return
        val ch = fromRadioChar ?: return
        if (!isConnected || pendingRead) return
        pendingRead = true
        val ok = try {
            g.readCharacteristic(ch)
        } catch (e: Exception) {
            onLog("BLE read error: ${e.message}")
            pendingRead = false
            false
        }
        if (!ok) {
            onLog("BLE readCharacteristic rejected")
            pendingRead = false
            handler.postDelayed({ triggerDrain() }, TRANSIENT_RETRY_MS)
        }
    }

    /**
     * Returns bonded LE devices (Meshtastic nodes are paired in system settings).
     * Some stacks (MIUI/HyperOS, Samsung) report the peer type as UNKNOWN or
     * DUAL instead of LE — filtering strictly on DEVICE_TYPE_LE would hide valid
     * nodes on those phones. Deduplicates by MAC address.
     */
    fun scan(): List<BluetoothDevice> {
        val seen = HashSet<String>()
        return bluetoothAdapter?.bondedDevices
            ?.filter {
                it.type == BluetoothDevice.DEVICE_TYPE_LE ||
                    it.type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                    it.type == BluetoothDevice.DEVICE_TYPE_UNKNOWN
            }
            ?.filter { seen.add(it.address) }
            ?.toList() ?: emptyList()
    }

    /**
     * Connects to a bonded Meshtastic node. Tears down any previous GATT session
     * and forces a service-cache refresh before reconnecting, so a stale link from
     * a previous process (e.g. right after `adb install -r`) never hangs.
     *
     * Vendor-safety: the refresh + re-connect is deferred ~150 ms after the
     * previous GATT is closed (some stacks release the session asynchronously
     * and break an immediate reconnect), and the service-cache refresh only runs
     * on reconnects to the same device — a fresh connect does not refresh.
     */
    private var sessionCount = 0

    fun connect(device: BluetoothDevice): Boolean {
        return try {
            bondRequested = false
            val doRefresh = lastDevice?.address == device.address && sessionCount > 0
            lastDevice = device
            sessionCount++
            mtuNegotiated = false
            stallHandler.removeCallbacksAndMessages(null)
            if (doRefresh) refreshGattCache()
            tearDownGattSilently()
            handler.postDelayed({ connectGattNow(device) }, 150)
            true
        } catch (e: Exception) {
            onLog("BLE connect failed: ${e.message}")
            listener.onError(e)
            false
        }
    }

    private fun connectGattNow(device: BluetoothDevice) {
        try {
            gatt = device.connectGatt(context, false, gattCallback)
            if (gatt == null) onLog("BLE connectGatt returned null")
        } catch (e: Exception) {
            onLog("BLE connectGatt failed: ${e.message}")
            listener.onError(e)
        }
    }

    /**
     * Invokes the hidden `BluetoothGatt.refresh()` via reflection to drop a stale
     * service cache (the official client does the same after firmware updates).
     */
    private fun refreshGattCache() {
        try {
            val old = gatt ?: return
            val m = BluetoothGatt::class.java.getMethod("refresh")
            m.isAccessible = true
            m.invoke(old)
        } catch (e: Exception) {
            Log.w(TAG, "BLE gatt refresh unavailable: ${e.message}")
        }
    }

    private fun tearDownGattSilently() {
        val wasConnected = isConnected
        isConnected = false
        pendingRead = false
        handler.removeCallbacksAndMessages(null)
        try {
            gatt?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting GATT", e)
        }
        try {
            gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT", e)
        }
        gatt = null
        toRadioChar = null
        fromRadioChar = null
        fromNumChar = null
        if (wasConnected) {
            listener.onDisconnected()
        }
    }

    /**
     * Arms a stall watchdog while the config/NodeDB handshake runs: if no
     * FROMRADIO bytes arrive within [STALL_TIMEOUT_MS], the session is considered
     * hung and we force a teardown + reconnect (instead of waiting forever).
     */
    fun armStallWatchdog() {
        stallHandler.removeCallbacksAndMessages(null)
        lastDataTimestamp = System.currentTimeMillis()
        stallWatchdogArmed = true
        stallHandler.postDelayed({ checkStall() }, STALL_TIMEOUT_MS)
    }

    fun disarmStallWatchdog() {
        stallWatchdogArmed = false
        stallHandler.removeCallbacksAndMessages(null)
    }

    private fun checkStall() {
        if (!stallWatchdogArmed || !isConnected) return
        val elapsed = System.currentTimeMillis() - lastDataTimestamp
        if (elapsed >= STALL_TIMEOUT_MS) {
            // The link may be alive but a single GATT read got stuck (pendingRead
            // never cleared). Reset the read latch and re-kick the drain instead of
            // tearing down — tearing down caused the endless reconnect/download loop.
            onLog("BLE stall detected (no data for $elapsed ms) — resetting drain and re-kicking")
            pendingRead = false
            triggerDrain()
            lastDataTimestamp = System.currentTimeMillis()
            stallHandler.postDelayed({ checkStall() }, STALL_TIMEOUT_MS)
        } else {
            stallHandler.postDelayed({ checkStall() }, STALL_TIMEOUT_MS - elapsed)
        }
    }

    /**
     * Requests high connection priority during the config burst (faster
     * notifications/reads). Downgrade to balanced once the handshake is done.
     */
    fun setHighConnectionPriority(high: Boolean) {
        val g = gatt ?: return
        try {
            g.requestConnectionPriority(
                if (high) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            )
        } catch (e: Exception) {
            Log.w(TAG, "BLE setConnectionPriority failed: ${e.message}")
        }
    }

    /**
     * Writes a raw (unframed) ToRadio protobuf packet. Uses write-with-response
     * (reliable): a fire-and-forget write can be silently dropped on a freshly
     * reconnected session, which would leave the handshake permanently stalled.
     */
    fun write(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = toRadioChar ?: return false
        if (!isConnected) return false
        return try {
            synchronized(writeLock) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    // API 33+: overload without shared mutable state (avoids the
                    // classic .value race when writes are issued back to back).
                    g.writeCharacteristic(ch, data, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    ch.value = data
                    g.writeCharacteristic(ch)
                }
            }
            true
        } catch (e: Exception) {
            onLog("BLE write error: ${e.message}")
            listener.onError(e)
            false
        }
    }

    fun isConnected(): Boolean = isConnected

    fun disconnect() = closeInternal()

    fun destroy() = closeInternal()

    private fun closeInternal() {
        stallHandler.removeCallbacksAndMessages(null)
        tearDownGattSilently()
    }
}
