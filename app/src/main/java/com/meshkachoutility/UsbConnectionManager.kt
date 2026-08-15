/* Copyright (c) 2026 Tai Soluciones - taisoluciones@gmail.com */
package com.meshkachoutility

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

class UsbConnectionManager(
    private val context: Context,
    private val listener: ConnectionListener
) {

    interface ConnectionListener {
        fun onDeviceAttached(device: UsbDevice)
        fun onDeviceDetached(device: UsbDevice)
        fun onPermissionGranted(device: UsbDevice)
        fun onPermissionDenied(device: UsbDevice)
        fun onConnected()
        fun onDisconnected()
        fun onDataReceived(data: ByteArray)
        fun onError(exception: Exception)
    }

    companion object {
        private const val TAG = "UsbConnectionManager"
        private const val ACTION_USB_PERMISSION = "com.meshkachoutility.USB_PERMISSION"
        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 2000
        private const val MAX_CONSECUTIVE_READ_ERRORS = 5
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var serialPort: UsbSerialPort? = null
    private var isConnected = false
    private var lastDevice: UsbDevice? = null

    /**
     * Whether a serial connection to a node is currently open.
     */
    fun isConnected(): Boolean = isConnected

    private val writeLock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        if (permissionGranted) {
                            Log.d(TAG, "USB Permission granted for device: ${device.deviceName}")
                            listener.onPermissionGranted(device)
                        } else {
                            Log.d(TAG, "USB Permission denied for device: ${device.deviceName}")
                            listener.onPermissionDenied(device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        Log.d(TAG, "USB Device attached: ${device.deviceName}")
                        listener.onDeviceAttached(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        Log.d(TAG, "USB Device detached: ${device.deviceName}")
                        listener.onDeviceDetached(device)
                        if (serialPort?.device == device) {
                            disconnect()
                        }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        context.registerReceiver(usbReceiver, filter, flags)
    }

    /**
     * Unregisters the internal receiver and cleans up connections.
     */
    fun destroy() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered or already unregistered", e)
        }
        disconnect()
    }

    /**
     * Discovers all connected USB serial devices matching supported drivers.
     */
    fun discoverDevices(): List<UsbDevice> {
        return try {
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).map { it.device }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering USB devices", e)
            listener.onError(e)
            emptyList()
        }
    }

    /**
     * Checks if permission is granted for the given USB device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * Requests runtime USB permission for the given device.
     *
     * The PendingIntent must use FLAG_MUTABLE (the system injects the device and
     * the granted result as extras) but the wrapped Intent must be EXPLICIT: since
     * Android 14, targeting SDK 34+ disallows FLAG_MUTABLE with an implicit Intent
     * and throws SecurityException. Setting the package makes the Intent explicit
     * while keeping it deliverable to the dynamic receiver.
     */
    fun requestPermission(device: UsbDevice) {
        try {
            val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flag
            )
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request USB permission", e)
            listener.onError(e)
        }
    }

    /**
     * Opens the USB serial connection for the given device.
     *
     * This method deliberately catches every exception (not just IOException):
     * Android 14+ (and 16) can throw SecurityException / IllegalStateException
     * from `openDevice`, `claimInterface` or `setParameters` when USB permission
     * has been revoked or the port is in an unexpected state. Any such failure is
     * reported through [ConnectionListener.onError] instead of crashing the app.
     */
    fun connect(device: UsbDevice): Boolean {
        try {
            if (isConnected) {
                disconnect()
            }

            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = availableDrivers.firstOrNull { it.device == device }
            if (driver == null) {
                Log.w(TAG, "Cannot connect: No driver found for ${device.deviceName}")
                listener.onError(IOException("No USB driver found for ${device.deviceName}"))
                return false
            }

            lastDevice = device
            if (!usbManager.hasPermission(device)) {
                Log.w(TAG, "Cannot connect: Permission not granted")
                listener.onError(IOException("USB permission not granted for ${device.deviceName}"))
                return false
            }

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                Log.e(TAG, "Failed to open USB connection")
                listener.onError(IOException("Failed to open USB connection to ${device.deviceName}"))
                return false
            }

            usbConnection = connection
            val port = driver.ports.firstOrNull()
            if (port == null) {
                Log.e(TAG, "No serial port available on driver")
                try {
                    connection.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing USB connection after port lookup failure", e)
                }
                usbConnection = null
                listener.onError(IOException("No serial port available on ${device.deviceName}"))
                return false
            }
            serialPort = port

            try {
                port.open(connection)
                // Configure parameters: 115200 baud rate, 8 data bits, 1 stop bit, no parity
                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                // Meshtastic firmware on native USB CDC (nRF52840, ESP32-S3, ...) only
                // activates its API client once the host asserts the serial control
                // lines. Without DTR/RTS the node silently ignores everything we send
                // (the meshtastic CLI does this via pyserial, which asserts DTR/RTS on
                // open). Assert both, then send the same wake/resync bytes the CLI uses.
                port.setDTR(true)
                port.setRTS(true)
                try {
                    port.write(ByteArray(32) { 0xC3.toByte() }, WRITE_TIMEOUT_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Wake bytes write failed (ignored)", e)
                }

                isConnected = true
                listener.onConnected()
                startReading()
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up serial port connection", e)
                disconnect()
                listener.onError(e)
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during connect", e)
            disconnect()
            listener.onError(e)
            return false
        }
    }

    /**
     * Reconnects to the last used USB serial device (e.g. after a node reboot
     * or a transient detach/re-attach). Returns false when no device is known.
     */
    fun reconnect(): Boolean {
        val device = lastDevice ?: return false
        return connect(device)
    }

    /**
     * Disconnects the active USB serial connection.
     */
    fun disconnect() {
        val wasConnected = isConnected
        isConnected = false
        stopReading()

        synchronized(writeLock) {
            try {
                serialPort?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing serial port", e)
            } finally {
                serialPort = null
            }

            try {
                usbConnection?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing USB connection", e)
            } finally {
                usbConnection = null
            }
        }

        if (wasConnected) {
            listener.onDisconnected()
        }
    }

    /**
     * Writes raw bytes to the USB serial connection in a thread-safe manner.
     */
    fun write(data: ByteArray): Boolean {
        val port = serialPort ?: return false
        if (!isConnected) return false

        return try {
            synchronized(writeLock) {
                port.write(data, WRITE_TIMEOUT_MS)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing data to serial port", e)
            listener.onError(e)
            disconnect()
            false
        }
    }

    /**
     * Starts the asynchronous loop to poll and read data from the USB port.
     *
     * Transient read failures (e.g. "USB get_status request failed" that some
     * CDC-ACM devices such as the nRF52840 report when the link hiccups, or the
     * race between a read error and the detach broadcast when the user unplugs
     * the device) are tolerated: the link is only declared dead after several
     * consecutive errors, so a healthy connection is not dropped by noise.
     */
    private fun startReading() {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ByteArray(2048)
            var consecutiveErrors = 0
            while (isActive && isConnected) {
                try {
                    val port = serialPort
                    if (port == null) {
                        delay(10)
                        continue
                    }
                    val numBytes = port.read(buffer, READ_TIMEOUT_MS)
                    consecutiveErrors = 0
                    if (numBytes > 0) {
                        val dataChunk = buffer.copyOfRange(0, numBytes)
                        listener.onDataReceived(dataChunk)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Read error", e)
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_READ_ERRORS && isConnected) {
                        Log.e(TAG, "Persistent read errors, dropping connection", e)
                        listener.onError(e)
                        disconnect()
                        break
                    }
                    delay(100)
                }
            }
        }
    }

    /**
     * Cancels the active read job.
     */
    private fun stopReading() {
        readJob?.cancel()
        readJob = null
    }
}
