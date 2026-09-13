package gay.protopanda.controller

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import gay.protopanda.controller.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }

    private lateinit var binding: ActivityMainBinding
    private var bleService: BlePeripheralService? = null
    private var serviceBound = false

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    private val accel = FloatArray(3)
    private val gyro = FloatArray(3)
    private val alpha = 0.8f
    private val ACCEL_SCALE = 32768f / 4f
    private val GYRO_SCALE = 32768f / 2000f

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startBleService()
        } else {
            finishAndRemoveTask()
        }
    }


    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val svc = (service as BlePeripheralService.LocalBinder).getService()
            bleService = svc
            serviceBound = true

            svc.statusListener = object : BlePeripheralService.StatusListener {
                override fun onStatus(connected: Boolean) {
                    updateStateUi(connected = connected, id = svc.myId)
                }
                override fun onId(id: Int) {
                    updateStateUi(connected = true, id = id)
                }
                override fun onAdvertising(active: Boolean, error: String) {
                    updateStateUi(advertising = active, advError = error)
                }
                override fun onBluetoothStateChanged(enabled: Boolean) {
                    if (enabled) {
                        updateStateUi(
                            connected = svc.connectedDevice != null,
                            id = svc.myId,
                            advertising = svc.isAdvertising,
                            advError = ""
                        )
                    } else {
                        updateStateUi(connected = false, id = -1, advertising = false, advError = "Bluetooth is off")
                    }
                }
            }

            svc.validateConnectionState()
            updateStateUi(
                connected = svc.connectedDevice != null,
                id = svc.myId,
                advertising = svc.isAdvertising
            )
            svc.ensureAdvertising()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bleService = null
            serviceBound = false
        }
    }


    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val btGranted = results
            .filterKeys { it != POST_NOTIFICATIONS_PERMISSION }
            .values
            .all { it }
        if (btGranted) checkBluetoothAndStart()
        else { binding.tvStatus.text = "Bluetooth permissions denied"; }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvVersion.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        setupButtons()
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnExit.setOnClickListener { closeApp() }
        requestPermissionsAndStart()
    }

    private var wentToBackground = false

    override fun onStart() {
        super.onStart()
        bleService?.let { svc ->
            svc.validateConnectionState()
            updateStateUi(connected = svc.connectedDevice != null, id = svc.myId, advertising = svc.isAdvertising)
            if (wentToBackground) {
                wentToBackground = false
                svc.ensureAdvertising()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        wentToBackground = true
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)

        bleService?.let { svc ->
            updateStateUi(connected = svc.connectedDevice != null, id = svc.myId, advertising = svc.isAdvertising)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        if (serviceBound) {
            bleService?.statusListener = null
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }


    private fun setupButtons() {
        setupMultiTouchButtons(
            binding.controlsContainer,
            listOf(
                binding.btnUp    to 3,
                binding.btnDown  to 1,
                binding.btnLeft  to 2,
                binding.btnRight to 0,
                binding.btnOk    to 4,
                binding.btnBack  to 5,
                binding.btnL1    to 6,
                binding.btnR1    to 7
            )
        )
    }
    private fun setupMultiTouchButtons(container: ViewGroup, buttons: List<Pair<MaterialButton, Int>>) {
        buttons.forEach { (btn, _) -> btn.isClickable = false; btn.isFocusable = false }

        val pressed = mutableSetOf<MaterialButton>()
        val rect = Rect()

        fun applyPointers(pointers: List<PointF>) {
            val nowPressed = mutableSetOf<MaterialButton>()
            for ((btn, index) in buttons) {
                btn.getHitRect(rect)
                val isOver = pointers.any { rect.contains(it.x.toInt(), it.y.toInt()) }
                if (isOver) {
                    nowPressed += btn
                    if (btn !in pressed) {
                        btn.isSelected = true
                        bleService?.buttonStates?.set(index, 1)
                    }
                }
            }
            for ((btn, index) in buttons) {
                if (btn in pressed && btn !in nowPressed) {
                    btn.isSelected = false
                    bleService?.buttonStates?.set(index, 0)
                }
            }
            pressed.clear()
            pressed += nowPressed
        }

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    val pointers = (0 until event.pointerCount).map {
                        PointF(event.getX(it), event.getY(it))
                    }
                    applyPointers(pointers)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val upIndex = event.actionIndex
                    val pointers = (0 until event.pointerCount)
                        .filter { it != upIndex }
                        .map { PointF(event.getX(it), event.getY(it)) }
                    applyPointers(pointers)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    applyPointers(emptyList())
                }
            }
            true
        }
    }


    override fun onSensorChanged(event: SensorEvent) {
        val svc = bleService ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accel[0] = alpha * accel[0] + (1 - alpha) * event.values[0]
                accel[1] = alpha * accel[1] + (1 - alpha) * event.values[1]
                accel[2] = alpha * accel[2] + (1 - alpha) * event.values[2]
                svc.imuAccX = (accel[0] / 9.81f * ACCEL_SCALE).toInt().coerceIn(-32768, 32767).toShort()
                svc.imuAccY = (accel[1] / 9.81f * ACCEL_SCALE).toInt().coerceIn(-32768, 32767).toShort()
                svc.imuAccZ = (accel[2] / 9.81f * ACCEL_SCALE).toInt().coerceIn(-32768, 32767).toShort()
                runOnUiThread {
                    binding.tvImu.text =
                        "Acc  X:${"%.2f".format(accel[0])}  Y:${"%.2f".format(accel[1])}  Z:${"%.2f".format(accel[2])} m/s²\n" +
                                "Gyro X:${"%.2f".format(gyro[0])}   Y:${"%.2f".format(gyro[1])}   Z:${"%.2f".format(gyro[2])} °/s"
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyro[0] = Math.toDegrees(event.values[0].toDouble()).toFloat()
                gyro[1] = Math.toDegrees(event.values[1].toDouble()).toFloat()
                gyro[2] = Math.toDegrees(event.values[2].toDouble()).toFloat()
                svc.imuGyroX = (gyro[0] * GYRO_SCALE).toInt().coerceIn(-32768, 32767).toShort()
                svc.imuGyroY = (gyro[1] * GYRO_SCALE).toInt().coerceIn(-32768, 32767).toShort()
                svc.imuGyroZ = (gyro[2] * GYRO_SCALE).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}


    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!has(Manifest.permission.BLUETOOTH_ADVERTISE)) needed += Manifest.permission.BLUETOOTH_ADVERTISE
            if (!has(Manifest.permission.BLUETOOTH_CONNECT))   needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (!has(Manifest.permission.BLUETOOTH))               needed += Manifest.permission.BLUETOOTH
            if (!has(Manifest.permission.BLUETOOTH_ADMIN))         needed += Manifest.permission.BLUETOOTH_ADMIN
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION))    needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!has(Manifest.permission.POST_NOTIFICATIONS)) needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) checkBluetoothAndStart()
        else permLauncher.launch(needed.toTypedArray())
    }

    private fun checkBluetoothAndStart() {
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        when {
            bt == null -> {
                binding.tvStatus.text = "No Bluetooth on this device"
            }
            !bt.isEnabled -> {
                binding.tvStatus.text = "Waiting for Bluetooth…"
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            !bt.isMultipleAdvertisementSupported -> {
                binding.tvStatus.text = "BLE peripheral mode not supported"
            }
            else -> startBleService()
        }
    }

    private fun startBleService() {
        if (serviceBound) return
        val intent = Intent(this, BlePeripheralService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun closeApp() {
        stopService(Intent(this, BlePeripheralService::class.java))
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun has(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED


    private var lastConnected = false
    private var lastId = -1
    private var lastAdvertising = false
    private var lastAdvError = ""

    private fun updateStateUi(
        connected: Boolean = lastConnected,
        id: Int = lastId,
        advertising: Boolean = lastAdvertising,
        advError: String = lastAdvError
    ) {
        lastConnected = connected
        lastId = id
        lastAdvertising = advertising
        lastAdvError = advError

        runOnUiThread {
            when {
                connected && id != -1 -> {
                    binding.statusDot.setImageResource(R.drawable.dot_connected)
                    binding.tvStatus.text = "Connected  ·  ID $id"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                }
                connected -> {
                    binding.statusDot.setImageResource(R.drawable.dot_waiting)
                    binding.tvStatus.text = "Connected  ·  awaiting ID…"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                }
                advertising -> {
                    binding.statusDot.setImageResource(R.drawable.dot_waiting)
                    binding.tvStatus.text = "Advertising…"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                }
                advError.isNotEmpty() -> {
                    binding.statusDot.setImageResource(R.drawable.dot_idle)
                    binding.tvStatus.text = "Adv failed: $advError"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.dot_amber))
                }
                else -> {
                    binding.statusDot.setImageResource(R.drawable.dot_idle)
                    binding.tvStatus.text = "Waiting for connection…"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                }
            }
        }
    }
}
