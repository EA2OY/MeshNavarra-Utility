/* Copyright (c) 2026 Tai Soluciones - taisoluciones@gmail.com */
package com.meshkachoutility

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
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
    private var connectCompleted = false

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
     *
     * Idempotent: onServicesDiscovered and onMtuChanged can both fire for the
     * same session; only the first call notifies the listener (a second
     * onConnected() would schedule a duplicate sendWantConfig -> duplicate
     * want_config + reset of the in-flight download counters).
     */
    private fun completeConnect() {
        isConnected = true
        if (connectCompleted) return
        connectCompleted = true
        listener.onConnected()
        triggerDrain()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != this@BleConnectionManager.gatt) {
                // Stale callback from an old/closed session: ignore and close it
                try { g.close() } catch (_: Exception) {}
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("BLE connection status: $status")
            }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    onLog("BLE GATT connected. Discovering services...")
                    g.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    onLog("BLE GATT disconnected (status=$status)")
                    closeInternal()
                    listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g != this@BleConnectionManager.gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("BLE service discovery failed: $status")
                listener.onError(IllegalStateException("BLE service discovery failed ($status)"))
                return
            }
            val service = g.getService(MESH_SERVICE_UUID)
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
            g.setCharacteristicNotification(fromNumChar, true)
            val cccd = fromNumChar?.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            } else {
                onLog("FROMNUM has no CCCD descriptor")
            }

            // Negotiate a larger MTU as a best-effort improvement (fewer round-trips
            // per FROMRADIO chunk). We mark the transport connected immediately so a
            // reconnect that skips the MTU callback (same-device re-bond) never ends
            // up half-connected; onMtuChanged just updates the negotiated size.
            completeConnect()
            if (!mtuNegotiated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mtuNegotiated = true
                try {
                    g.requestMtu(512)
                } catch (e: Exception) {
                    onLog("BLE requestMtu failed: ${e.message}")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g != this@BleConnectionManager.gatt) return
            onLog("BLE MTU negotiated: $mtu (status=$status)")
            completeConnect()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (g != this@BleConnectionManager.gatt) return
            onLog("BLE FROMNUM notify descriptor write: ${if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "FAILED($status)"}")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (g != this@BleConnectionManager.gatt) return
            if (characteristic.uuid == FROMNUM_UUID) {
                onLog("BLE FROMNUM notify received")
                triggerDrain()
            }
        }

        // API 33+ callback overload
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (g != this@BleConnectionManager.gatt) return
            if (characteristic.uuid == FROMNUM_UUID) {
                onLog("BLE FROMNUM notify received")
                triggerDrain()
            }
        }

        // Legacy onCharacteristicRead (API < 33)
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (g != this@BleConnectionManager.gatt) return
            processCharacteristicRead(characteristic, characteristic.value, status)
        }

        // API 33+ onCharacteristicRead
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (g != this@BleConnectionManager.gatt) return
            processCharacteristicRead(characteristic, value, status)
        }

        private fun processCharacteristicRead(
            characteristic: BluetoothGattCharacteristic,
            data: ByteArray?,
            status: Int
        ) {
            pendingRead = false
            if (characteristic.uuid != FROMRADIO_UUID) return
            lastDataTimestamp = System.currentTimeMillis()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("BLE FROMRADIO read failed: $status")
                handler.postDelayed({ triggerDrain() }, TRANSIENT_RETRY_MS)
                return
            }
            if (data != null && data.isNotEmpty()) {
                onLog("BLE FROMRADIO read: ${data.size} bytes")
                listener.onDataReceived(data)
                triggerDrain() // keep draining queued packets (like the official client)
            } else {
                if (configDraining) {
                    handler.postDelayed({ triggerDrain() }, EMPTY_RETRY_MS)
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (g != this@BleConnectionManager.gatt) return
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
     * Returns bonded devices (Meshtastic nodes are paired in system settings).
     * Deduplicates by MAC address without restrictive type filters that could
     * exclude valid nodes on custom OEM Bluetooth stacks.
     */
    fun scan(): List<BluetoothDevice> {
        val seen = HashSet<String>()
        return bluetoothAdapter?.bondedDevices
            ?.filter { seen.add(it.address) }
            ?.toList() ?: emptyList()
    }

    /**
     * Starts a continuous LE scan. Results are delivered via callback.
     */
    fun startScan(onResult: (BluetoothDevice) -> Unit): android.bluetooth.le.ScanCallback? {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return null
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                onResult(result.device)
            }
        }
        return try {
            scanner.startScan(callback)
            callback
        } catch (e: Exception) {
            Log.w(TAG, "BLE scan start failed: ${e.message}")
            null
        }
    }

    fun stopScan(callback: android.bluetooth.le.ScanCallback?) {
        if (callback == null) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "BLE scan stop failed: ${e.message}")
        }
    }

    /**
     * Requests pairing with a discovered device.
     */
    fun createBond(device: BluetoothDevice): Boolean {
        return try {
            if (device.bondState != BluetoothDevice.BOND_BONDED) device.createBond() else true
        } catch (e: Exception) {
            Log.w(TAG, "BLE bond request failed: ${e.message}")
            false
        }
    }

    /**
     * Connects to a Meshtastic node via Bluetooth LE using explicit TRANSPORT_LE.
     * Tears down any previous GATT session cleanly without race conditions.
     */
    fun connect(device: BluetoothDevice): Boolean {
        return try {
            bondRequested = false
            connectCompleted = false
            lastDevice = device
            mtuNegotiated = false
            stallHandler.removeCallbacksAndMessages(null)
            
            // Clean up old session synchronously
            val oldGatt = gatt
            gatt = null
            isConnected = false
            pendingRead = false
            toRadioChar = null
            fromRadioChar = null
            fromNumChar = null
            
            if (oldGatt != null) {
                try { oldGatt.disconnect() } catch (e: Exception) { Log.w(TAG, "Error disconnecting old GATT", e) }
                try { oldGatt.close() } catch (e: Exception) { Log.w(TAG, "Error closing old GATT", e) }
            }

            onLog("BLE connectGatt initiating (${device.address}, TRANSPORT_LE)...")
            val newGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
            gatt = newGatt
            if (newGatt == null) {
                onLog("BLE connectGatt returned null")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            onLog("BLE connect failed: ${e.message}")
            listener.onError(e)
            false
        }
    }

    private fun tearDownGattSilently() {
        val wasConnected = isConnected
        isConnected = false
        connectCompleted = false
        pendingRead = false
        val oldGatt = gatt
        gatt = null
        toRadioChar = null
        fromRadioChar = null
        fromNumChar = null
        if (oldGatt != null) {
            try { oldGatt.disconnect() } catch (e: Exception) { Log.w(TAG, "Error disconnecting GATT", e) }
            try { oldGatt.close() } catch (e: Exception) { Log.w(TAG, "Error closing GATT", e) }
        }
        if (wasConnected) {
            listener.onDisconnected()
        }
    }

    /**
     * Arms a stall watchdog while the config/NodeDB handshake runs: if no
     * FROMRADIO bytes arrive within [STALL_TIMEOUT_MS], the drain is re-kicked.
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
            onLog("BLE stall detected (no data for $elapsed ms) - resetting drain and re-kicking")
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
     * Writes a raw (unframed) ToRadio protobuf packet. Uses write-with-response (reliable).
     */
    fun write(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = toRadioChar ?: return false
        if (!isConnected) return false
        return try {
            synchronized(writeLock) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val status = g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    status == BluetoothStatusCodes.SUCCESS
                } else {
                    ch.value = data
                    g.writeCharacteristic(ch)
                }
            }
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
