package org.openrmf.gatewayapp.rvr.activities

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE
import android.net.Uri.fromParts
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.view.*
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import org.eclipse.paho.client.mqttv3.*
import org.openrmf.gatewayapp.rvr.R
import org.openrmf.gatewayapp.rvr.RVRViewModel
import org.openrmf.gatewayapp.rvr.utils.*
import org.openrmf.gatewayapp.rvr.views.BLEScanSnackBarThing
import kotlin.math.roundToInt


interface LogsCallback {
    // Declaration of the template function for the interface
    fun updateLogs(msg: String)
}

class MainActivity : AppCompatActivity(), LogsCallback {
    // RVR Stuff
    private var previousCommand: Long = System.currentTimeMillis()
    private var timedOut = false
    private lateinit var inputHandler: InputHandler
    private lateinit var prefsManager: PrefsManager
    private var right = 0.0f
    private var left = 0.0f
    private var viewModelRVR: RVRViewModel? = null
    private var handler : Handler? = null
    private var allowPermissionClickedTime = 0L
    private var bleLayout: BLEScanSnackBarThing? = null
    private var timeOfLastMQTTMessage: Long = 0

    // MQTT Stuff
    private lateinit var mqttClient : MQTTClient
    private val topic = "cmd_vel"

    private fun simulateJoystickInput(action: Int, d_x: Float, d_y:Float) {
        // Convention: origin is top left, |---> x
        //                                 |
        //                                 v
        //                                 y

        var d_x_t = d_x; var d_y_t = d_y;
        if (d_x > 1.0f) {d_x_t = 1.0f}; if (d_y > 1.0f) {d_y_t = 1.0f}
        if (d_x < -1.0f) {d_x_t = -1.0f}; if (d_y < -1.0f) {d_y_t = -1.0f}

        // Input simulator
        val joystickAxes = joystickSurfaceView.origins

        val x = joystickAxes[0] + (d_x_t * joystickSurfaceView.range)
        val y = joystickAxes[1] + (d_y_t * joystickSurfaceView.range)
        updateLogs("${d_x_t}, ${d_y_t}")
        updateLogs("JoySim: Sending input $action action to ($x, $y)")

        for ( i in 1..2 ) {
            joystickSurfaceView.dispatchTouchEvent(
                MotionEvent.obtain(
                    android.os.SystemClock.uptimeMillis(),
                    android.os.SystemClock.uptimeMillis(),
                    action,
                    x,
                    y,
                    0
                )
            )
        }
    }

    // Logging Stuff
    override fun updateLogs(msg: String) {
        logsTextView.append("$msg\n")
        val scrollAmount = (logsTextView.lineCount * logsTextView.lineHeight) - (logsTextView.bottom - logsTextView.top)
        logsTextView.scrollTo(0, scrollAmount)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // RVR STUFF
        prefsManager = PrefsManager(this)
        inputHandler = InputHandler(this, prefsManager, this::onInputUpdated)
        if(!packageManager.hasSystemFeature(FEATURE_BLUETOOTH_LE)){
            connectionStatusView.text = "Device does not support required bluetooth mode"
            return
        }
        linearSpeedMaxValue.progress = (prefsManager.maxSpeed*100.0f).roundToInt()
        rotationSpeedMaxValue.progress = (prefsManager.maxTurnSpeed*100.0f).roundToInt()
        handler = Handler()
        viewModelRVR = ViewModelProviders.of(this)[RVRViewModel::class.java]
        viewModelRVR!!.connected.observe(this, Observer<Boolean> {
            connectionStatusView.text = if(it) "connected" else "disconnected"
            mainCoordinatorLayout.keepScreenOn = if(it) prefsManager.keepScreenAwake else false
        })
        linearSpeedMaxValue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefsManager.maxSpeed = progress/100.0f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        rotationSpeedMaxValue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefsManager.maxTurnSpeed = progress/100.0f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })


        joystickSurfaceView.setListener(inputHandler)

        connectionStatusView.setOnClickListener {
            disconnectFromDevice()
        }
        fab.setOnClickListener { view ->
            if(bleLayout?.isShown != true){
                disconnectFromDevice()
                val ready = checkPerms()
                if(ready)
                    showScanLayout()
                else
                    showPermissionsRationale()
            }
            else{
                hideScanLayout()
            }
        }

        fab.performClick()

        // MQTT STUFF
        mqttSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                updateLogs("MQTT: Activating MQTT.")
                timeOfLastMQTTMessage = System.currentTimeMillis()
                mqttClient = MQTTClient(this.applicationContext,mqttUrlTextEdit.text.toString(), "");
                mqttClient.connect(
                    cbConnect = object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            updateLogs("MQTT: Connection success")
                            mqttClient.subscribe(topic,
                                1,
                                object : IMqttActionListener {
                                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                                        updateLogs("MQTT: Subscribed to: $topic")
                                    }

                                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                        updateLogs("MQTT: Failed to subscribe: $topic")
                                    }
                                })
                        }


                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            updateLogs("MQTT: Connection failure: ${exception.toString()}")
                        }
                    },
                    cbClient = object : MqttCallback {
                        override fun messageArrived(topic: String?, message: MqttMessage?) {
                            updateLogs("MQTT: Receive message: ${message.toString()} from topic: $topic")
                            val xy = message.toString().split(',')
                            timeOfLastMQTTMessage = System.currentTimeMillis()
                            simulateJoystickInput(MotionEvent.ACTION_MOVE, xy[0].toFloat(), xy[1].toFloat())
                        }

                        override fun connectionLost(cause: Throwable?) {
                            updateLogs("MQTT: Connection lost ${cause.toString()}")
                        }

                        override fun deliveryComplete(token: IMqttDeliveryToken?) {
                            updateLogs("MQTT: Delivery complete")
                        }
                    })
            } else {
                updateLogs("MQTT: Deactivating MQTT.")
                try {
                    mqttClient.unsubscribe(topic,
                        object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                updateLogs("MQTT: Unsubscribed to: $topic")
                            }

                            override fun onFailure(
                                asyncActionToken: IMqttToken?,
                                exception: Throwable?
                            ) {
                                updateLogs("MQTT: Failed to unsubscribe: $topic")
                            }
                        })
                } catch (e: Exception) {
                }

                mqttClient.disconnect(object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        updateLogs("MQTT: Disconnected");
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        updateLogs("MQTT: Failed to disconnect")
                    }
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        right = 0.0f
        left = 0.0f
        scheduleNewMotorLooper()
    }

    override fun onPause() {
        super.onPause()
        hideScanLayout()
        handler?.removeCallbacks(motorLooper)
    }

    override fun onDestroy() {
        super.onDestroy()
        inputHandler.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH && resultCode == RESULT_OK) {
            showScanLayout()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERM_REQUEST_LOCATION -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    showScanLayout()
                } else {
                    if(System.currentTimeMillis() - allowPermissionClickedTime < 500){
                        val intent = Intent(ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = fromParts("package", packageName, null)
                        intent.data = uri
                        startActivity(intent)
                        Toast.makeText(this,
                            "Please enable the location permission in Permissions section", Toast.LENGTH_LONG).show()
                    }
                    else{
                        Toast.makeText(this,
                            "Location permission denied! Unable to scan for RVR. " +
                                    "Location permission only is used for bluetooth and data is not shared.", Toast.LENGTH_LONG).show()
                    }
                }
                return
            }

            // Add other 'when' lines to check for other
            // permissions this app might request.
            else -> {
                // Ignore all other requests.
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return inputHandler.processMotionEvent(event) || super.onGenericMotionEvent(event)
    }

    fun onInputUpdated(left : Float, right: Float){
        this.left = left
        this.right = right
        sendMotorCommandFrame()
    }

    private fun showPermissionsRationale() {
        Snackbar.make(mainCoordinatorLayout, R.string.btPermRequestText, Snackbar.LENGTH_INDEFINITE).also {
            it.setAction("Allow"){
                allowPermissionClickedTime = System.currentTimeMillis()
                requestPerms()
            }
        }.show()
    }

    private fun checkPerms() : Boolean{
        if (Build.VERSION.SDK_INT >= 23) {
            return (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED)
        }
        else return true
    }

    fun requestPerms(){
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            PERM_REQUEST_LOCATION
        )
    }

    fun showScanLayout() {
        bleLayout ?: let {
            bleLayout = BLEScanSnackBarThing.make(mainCoordinatorLayout)
        }
        if(bleLayout?.isShown != true) {
            bleLayout?.onItemClickedListener = {
//                log.d { it.toString() }
                hideScanLayout()
                handler?.postDelayed({
                    connectToDevice(it.device)
                }, 2000)
            }
            if(!BluetoothAdapter.getDefaultAdapter().isEnabled) {
                Snackbar.make(mainCoordinatorLayout, "Bluetooth needs to be enabled", Snackbar.LENGTH_INDEFINITE)
                    .setAction("Turn on"){
                        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH)
                    }.show()
                return
            }
            fab.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            if(bleLayout?.isShown != true && !isFinishing)
                bleLayout?.show()
        }
    }

    fun hideScanLayout(){
        fab.setImageResource(android.R.drawable.stat_sys_data_bluetooth)
        if(bleLayout?.isShown == true) {
            bleLayout?.dismiss()
            bleLayout?.onItemClickedListener = null
        }
    }

    private fun disconnectFromDevice(){
        viewModelRVR?.disconnect()
    }

    @SuppressLint("SetTextI18n")
    private fun connectToDevice(device: BluetoothDevice) {
//        log.d { "connectToDevice" }
        disconnectFromDevice()
        connectionStatusView.text = "connecting..."
        viewModelRVR?.connect(device)
    }

    private val motorLooper = Runnable {
        sendMotorCommandFrame()
        scheduleNewMotorLooper()
    }

    private fun sendMotorCommandFrame() {
        viewModelRVR?.let { viewModel->
            if(viewModel.connected.value == true){
                val lastInputCommand = System.currentTimeMillis() - inputHandler.lastUpdated
                // Injection to restart MQTT if too long since last message
                if (mqttSwitch.isChecked) {
                    if (System.currentTimeMillis() - timeOfLastMQTTMessage > 20000) {
                        mqttSwitch.toggle()
                    }
                } else {
                    mqttSwitch.toggle()
                }
                if(lastInputCommand > prefsManager.timeoutMs){
                    if(timedOut && lastInputCommand > prefsManager.timeoutMs + 1000) return //allow it to send the stop command for a second
                    timedOut = true
                    simulateJoystickInput(MotionEvent.ACTION_UP, 0.0f, 0.0f)
                    left = 0f
                    right = 0f
                }
                else{
                    if(timedOut || System.currentTimeMillis() - previousCommand > prefsManager.timeoutMs){ //we hit the timeout at some point, better make sure RVR is awake
                        viewModel.wake()
                    }
                    timedOut = false
                }
                val axes = joystickSurfaceView.joystickAxes
                var command : ByteArray
//                updateLogs("axes: ${axes[0]}, ${axes[1]}")
                if(axes[0] != 0.0f || axes[1] != 0.0f){
                    DriveUtil.rcDrive(-axes[1]*prefsManager.maxSpeed, -axes[0]*prefsManager.maxTurnSpeed, true).also {
                        val left = it.first
                        val right = it.second
                        command = SpheroMotors.drive(left, right)
                    }
                } else{
                    command = SpheroMotors.drive(left, right)
                }
                viewModel.sendCommand(command)
                previousCommand = inputHandler.lastUpdated
            }
        }
    }

    private fun scheduleNewMotorLooper() {
        handler?.postDelayed(motorLooper, 45)
    }

    companion object {
        private const val PERM_REQUEST_LOCATION = 234
        private const val REQUEST_ENABLE_BLUETOOTH = 2221
    }
}
