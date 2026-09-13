package gay.protopanda.controller

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class BlePeripheralService : Service() {

    companion object {
        private const val TAG = "BlePeripheral"
        private const val CHANNEL_ID = "ble_peripheral_channel"
        private const val NOTIF_ID = 1

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val PACKET_BYTES = 23
        const val ACTION_RELOAD_IDENTITY = "gay.protopanda.controller.RELOAD_IDENTITY"
    }

    interface StatusListener {
        fun onStatus(connected: Boolean)
        fun onId(id: Int)
        fun onAdvertising(active: Boolean, error: String = "")
        fun onBluetoothStateChanged(enabled: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun getService(): BlePeripheralService = this@BlePeripheralService
    }

    private val binder = LocalBinder()
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var notificationsEnabled = false
    private lateinit var identity: BleIdentity

    private val mainHandler = Handler(Looper.getMainLooper())

    private var serviceReady = false
    private var gattService: BluetoothGattService? = null
    private var isBluetoothEnabled = false
    private var isRecovering = false

    @Volatile var statusListener: StatusListener? = null

    @Volatile var myId: Int = -1
    @Volatile var isAdvertising: Boolean = false
    @Volatile var connectedDevice: BluetoothDevice? = null
    val buttonStates = IntArray(8) { 0 }

    @Volatile var imuAccX: Short = 0
    @Volatile var imuAccY: Short = 0
    @Volatile var imuAccZ: Short = 0
    @Volatile var imuGyroX: Short = 0
    @Volatile var imuGyroY: Short = 0
    @Volatile var imuGyroZ: Short = 0

    private var notifyThread: Thread? = null
    private var running = false

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "✅ Bluetooth turned ON")
                        isBluetoothEnabled = true
                        statusListener?.onBluetoothStateChanged(true)
                        recoverFromBluetoothOff()
                    }
                    BluetoothAdapter.STATE_TURNING_ON -> {
                        Log.i(TAG, "Bluetooth turning ON...")
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.i(TAG, "❌ Bluetooth turned OFF")
                        isBluetoothEnabled = false
                        connectedDevice = null
                        notificationsEnabled = false
                        myId = -1
                        isAdvertising = false
                        serviceReady = false
                        stopSelf()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                    BluetoothAdapter.STATE_TURNING_OFF -> {
                        Log.i(TAG, "Bluetooth turning OFF...")
                    }
                }
            }
        }
    }


    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasAdvertisePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        identity = BleIdentity.load(this)

        isBluetoothEnabled = bluetoothAdapter?.isEnabled == true

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothStateReceiver, filter)
        }

        if (isBluetoothEnabled) {
            startGattServer()
            startNotifyLoop()
        } else {
            updateNotification("Bluetooth is off")
        }
    }

    @Volatile private var initialized = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD_IDENTITY) {
            reloadIdentity()
            return START_STICKY
        }
        if (!initialized && isBluetoothEnabled) {
            initialized = true
            startGattServer()
            startNotifyLoop()
        }
        return START_STICKY
    }

    private fun reloadIdentity() {
        identity = BleIdentity.load(this)
        if (isBluetoothEnabled) {
            stopAdvertising()
            forceCloseConnection()
            startGattServer()
        }
    }

    private fun recoverFromBluetoothOff() {
        if (isRecovering) return
        isRecovering = true

        mainHandler.postDelayed({
            try {
                Log.i(TAG, "Starting recovery from Bluetooth toggle...")

                if (hasConnectPermission()) {
                    try {
                        gattServer?.close()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Error closing old GATT server", e)
                    }
                }
                gattServer = null
                serviceReady = false
                gattService = null
                connectedDevice = null
                notificationsEnabled = false
                myId = -1
                isAdvertising = false

                bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                bluetoothAdapter = bluetoothManager?.adapter

                if (bluetoothAdapter?.isEnabled == true) {
                    isBluetoothEnabled = true
                    startGattServer()
                    if (notifyThread == null || !notifyThread!!.isAlive) {
                        startNotifyLoop()
                    }
                    updateNotification("Recovered — starting service")
                } else {
                    Log.w(TAG, "Bluetooth is still off during recovery")
                    updateNotification("Bluetooth is off")
                }
            } finally {
                isRecovering = false
            }
        }, 1000)
    }

    private fun startGattServer() {
        if (!isBluetoothEnabled) {
            Log.w(TAG, "Cannot start GATT server - Bluetooth is off")
            return
        }

        if (hasConnectPermission()) {
            try {
                gattServer?.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "close denied", e)
            }
        }

        serviceReady = false
        gattService = null

        val gattCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                Log.i(TAG, "onConnectionStateChange: device=${device.address}, status=$status, newState=$newState")

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "✅ Client connected: ${device.address}")
                    if (connectedDevice != null && connectedDevice != device) {
                        if (hasConnectPermission()) {
                            try {
                                gattServer?.cancelConnection(device)
                            } catch (e: SecurityException) {
                                Log.e(TAG, "cancelConnection denied", e)
                            }
                        }
                        return
                    }
                    connectedDevice = device
                    myId = -1
                    stopAdvertising()
                    notifyStatus(true)
                    updateNotification("Connected — waiting for ID")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Client disconnected")
                    if (connectedDevice == device) {
                        connectedDevice = null
                        notificationsEnabled = false
                        myId = -1
                        notifyStatus(false)
                        mainHandler.postDelayed({
                            if (isBluetoothEnabled) {
                                startAdvertising()
                            }
                        }, 500)
                    }
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice, requestId: Int, offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (!hasConnectPermission()) return
                try {
                    when (characteristic.uuid) {
                        identity.readWriteUuid -> {
                            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                            buf.putInt(myId)
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, buf.array())
                        }
                        identity.notifyUuid -> {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, buildPacket())
                        }
                        else -> gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "sendResponse denied", e)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (characteristic.uuid == identity.readWriteUuid && value.size == 4) {
                    val id = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).int
                    myId = id
                    Log.i(TAG, "Assigned controller ID: $id")
                    notifyId(id)
                    updateNotification("Connected — ID $id")
                }
                if (responseNeeded && hasConnectPermission()) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "sendResponse denied", e)
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (descriptor.uuid == CCCD_UUID) {
                    notificationsEnabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    Log.i(TAG, "Notifications enabled: $notificationsEnabled")
                }
                if (responseNeeded && hasConnectPermission()) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "sendResponse denied", e)
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                Log.i(TAG, "onServiceAdded called with status: $status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "✅ GATT service registered successfully")
                    serviceReady = true
                    gattService = service
                    updateNotification("Service ready — advertising")
                    if (isBluetoothEnabled) {
                        startAdvertising()
                    }
                } else {
                    Log.e(TAG, "❌ GATT service registration FAILED with status: $status")
                    serviceReady = false
                    notifyAdvertising(false, "Service registration failed (status $status)")
                    updateNotification("Service registration failed")
                }
            }
        }

        gattServer = if (hasConnectPermission()) {
            try {
                bluetoothManager?.openGattServer(this, gattCallback)
            } catch (e: SecurityException) {
                Log.e(TAG, "openGattServer denied", e)
                null
            }
        } else {
            Log.e(TAG, "Cannot open GATT server — BLUETOOTH_CONNECT not granted")
            null
        }

        if (gattServer == null || !hasConnectPermission()) {
            Log.e(TAG, "❌ GATT server is null")
            notifyAdvertising(false, "missing Bluetooth connect permission")
            return
        }

        val service = BluetoothGattService(identity.serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val rwChar = BluetoothGattCharacteristic(
            identity.readWriteUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(rwChar)

        val notifChar = BluetoothGattCharacteristic(
            identity.notifyUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        notifChar.addDescriptor(cccd)
        service.addCharacteristic(notifChar)
        this.notifyChar = notifChar

        try {
            val result = gattServer?.addService(service)
            Log.i(TAG, "addService returned: $result, waiting for onServiceAdded callback...")
            updateNotification("Starting GATT service...")

            mainHandler.postDelayed({
                if (!serviceReady && isBluetoothEnabled) {
                    Log.w(TAG, "⚠️ onServiceAdded timeout - forcing advertising start")
                    serviceReady = true
                    startAdvertising()
                }
            }, 2000)

        } catch (e: SecurityException) {
            Log.e(TAG, "addService denied", e)
            notifyAdvertising(false, "GATT permission denied")
            updateNotification("Service start failed: permission denied")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "✅ Advertising started successfully")
            isAdvertising = true
            notifyAdvertising(true)
            updateNotification("Advertising — waiting for connection")
        }

        override fun onStartFailure(errorCode: Int) {
            val reason = when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "unsupported"
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                else -> "code $errorCode"
            }
            Log.e(TAG, "❌ Advertising FAILED: $reason")
            isAdvertising = false
            notifyAdvertising(false, reason)
            updateNotification("Advertising failed: $reason")
        }
    }

    fun startAdvertising() {
        Log.i(TAG, "startAdvertising called, serviceReady=$serviceReady, connectedDevice=$connectedDevice, isBluetoothEnabled=$isBluetoothEnabled")

        if (!isBluetoothEnabled) {
            Log.w(TAG, "Bluetooth is off, cannot advertise")
            updateNotification("Bluetooth is off")
            return
        }

        if (connectedDevice != null) {
            Log.i(TAG, "Already connected, not advertising")
            return
        }

        if (!serviceReady) {
            Log.w(TAG, "Service not ready yet - will try again in 500ms")
            mainHandler.postDelayed({
                startAdvertising()
            }, 500)
            return
        }

        if (!hasAdvertisePermission()) {
            Log.e(TAG, "No advertise permission")
            notifyAdvertising(false, "advertise permission missing")
            return
        }

        val adv = bluetoothAdapter?.bluetoothLeAdvertiser
        if (adv == null) {
            Log.e(TAG, "Advertiser is null")
            notifyAdvertising(false, "advertiser unavailable")
            return
        }

        if (isAdvertising) {
            stopAdvertising()
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(android.os.ParcelUuid(identity.serviceUuid))
            .build()

        try {
            Log.i(TAG, "Calling startAdvertising...")
            adv.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "startAdvertising denied", e)
            notifyAdvertising(false, "advertise permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "startAdvertising exception", e)
            notifyAdvertising(false, "advertise exception: ${e.message}")
        }
    }

    fun validateConnectionState() {
        if (!isBluetoothEnabled) {
            Log.w(TAG, "Bluetooth is off, cannot validate connection")
            return
        }

        val device = connectedDevice ?: return
        if (!hasConnectPermission()) return
        val state = try {
            bluetoothManager?.getConnectionState(device, BluetoothProfile.GATT_SERVER)
        } catch (e: SecurityException) {
            Log.e(TAG, "getConnectionState denied", e)
            return
        }
        if (state != BluetoothProfile.STATE_CONNECTED) {
            Log.w(TAG, "Stale connectedDevice detected (real state=$state) — clearing")
            connectedDevice = null
            notificationsEnabled = false
            myId = -1
            notifyStatus(false)
            startAdvertising()
        }
    }

    fun disconnectAndStopAdvertising() {
        stopAdvertising()
        forceCloseConnection()
        if (isBluetoothEnabled) {
            startGattServer()
        }
    }

    private fun forceCloseConnection() {
        if (hasConnectPermission()) {
            try {
                connectedDevice?.let { device -> gattServer?.cancelConnection(device) }
                gattServer?.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "cancelConnection/close denied", e)
            }
        }
        connectedDevice = null
        notificationsEnabled = false
        myId = -1
        notifyStatus(false)
        serviceReady = false
    }

    fun stopAdvertising() {
        if (hasAdvertisePermission()) {
            try {
                bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            } catch (e: SecurityException) {
                Log.e(TAG, "stopAdvertising denied", e)
            }
        }
        isAdvertising = false
        notifyAdvertising(false)
    }

    fun ensureAdvertising() {
        if (!isBluetoothEnabled) {
            Log.w(TAG, "Cannot ensure advertising - Bluetooth is off")
            return
        }
        mainHandler.postDelayed({
            if (connectedDevice != null) return@postDelayed
            startAdvertising()
        }, 800)
    }

    private fun startNotifyLoop() {
        running = true
        notifyThread = Thread {
            while (running) {
                val device = connectedDevice
                val chr = notifyChar
                if (device != null && chr != null && notificationsEnabled && myId != -1 &&
                    hasConnectPermission() && isBluetoothEnabled) {
                    val packet = buildPacket()
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gattServer?.notifyCharacteristicChanged(device, chr, false, packet)
                        } else {
                            @Suppress("DEPRECATION")
                            chr.value = packet
                            @Suppress("DEPRECATION")
                            gattServer?.notifyCharacteristicChanged(device, chr, false)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "notifyCharacteristicChanged denied", e)
                    }
                }
                Thread.sleep(50)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun buildPacket(): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(imuAccZ)
        buf.putShort(imuAccX)
        buf.putShort(imuAccY)
        buf.putShort(imuGyroZ)
        buf.putShort(imuGyroX)
        buf.putShort(imuGyroY)
        buf.putShort(0)
        buf.put(myId.toByte())
        for (i in 0 until 8) buf.put(buttonStates[i].toByte())
        return buf.array()
    }

    private fun notifyStatus(connected: Boolean) {
        mainHandler.post { statusListener?.onStatus(connected) }
    }

    private fun notifyId(id: Int) {
        mainHandler.post { statusListener?.onId(id) }
    }

    private fun notifyAdvertising(active: Boolean, error: String = "") {
        mainHandler.post { statusListener?.onAdvertising(active, error) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "BLE Peripheral", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Protopanda Controller")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .build()
    }

    @SuppressLint("MissingPermission") // Permission is checked immediately below.
    private fun updateNotification(text: String) {
        if (!hasNotificationPermission()) return
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (e: SecurityException) {
            Log.e(TAG, "notify denied", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {
        }
        running = false
        stopAdvertising()
        forceCloseConnection()
        super.onDestroy()
    }
}
