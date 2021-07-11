package org.openrmf.gatewayapp.rvr.activities

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE
import android.net.Uri.fromParts
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
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
import org.openrmf.gatewayapp.rvr.utils.DriveUtil
import org.openrmf.gatewayapp.rvr.utils.InputHandler
import org.openrmf.gatewayapp.rvr.utils.PrefsManager
import org.openrmf.gatewayapp.rvr.utils.SpheroMotors
import org.openrmf.gatewayapp.rvr.views.BLEScanSnackBarThing
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager


interface LogsCallback {
    // Declaration of the template function for the interface
    fun updateLogs(msg: String)
}

class MainActivity : AppCompatActivity(), LogsCallback, LocationListener {
    // RVR Stuff
    private var previousCommand: Long = System.currentTimeMillis()
    private var timedOut = false
    private var rotTimedOut = false
    private var previousInput = listOf("0", "0")
    private var fidgetingState = false
    private lateinit var inputHandler: InputHandler
    private lateinit var prefsManager: PrefsManager
    private var right = 0.0f
    private var left = 0.0f
    private var viewModelRVR: RVRViewModel? = null
    private var handler : Handler? = null
    private var allowPermissionClickedTime = 0L
    private var bleLayout: BLEScanSnackBarThing? = null

    // MQTT Stuff
    private var timeOfLastMQTTMessage: Long = 0
    private lateinit var mqttClient : MQTTClient
    private val velTopic = "cmd_vel"

    // GPS Stuff
    private val gpsTopic = "gps_location"
    private lateinit var locationManager: LocationManager
    private lateinit var timer: CountDownTimer
    private val locationPermissionCode = 2


    private fun getLocation() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if ((ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), locationPermissionCode)
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000, 5f, this)
    }
    override fun onLocationChanged(location: Location) {
        updateGPSLogs("Latitude: " + location.latitude + " , Longitude: " + location.longitude)
        if (mqttClient.isConnected()) {
            mqttClient.publish(gpsTopic, "${location.latitude},${location.longitude}")
        }
    }

    override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) {
    }

    override fun onProviderEnabled(p0: String?) {
    }

    override fun onProviderDisabled(p0: String?) {
    }

    private fun simulateJoystickInput(action: Int, d_x: Float, d_y:Float) {
        // Convention: origin is top left, |---> x
        //                                 |
        //                                 v
        //                                 y

        var dXT = d_x; var dYT = d_y
        if (d_x > 1.0f) {dXT = 1.0f}; if (d_y > 1.0f) {dYT = 1.0f}
        if (d_x < -1.0f) {dXT = -1.0f}; if (d_y < -1.0f) {dYT = -1.0f}

        // Input simulator
        val joystickAxes = joystickSurfaceView.origins

        val x = joystickAxes[0] + (dXT * joystickSurfaceView.range)
        val y = joystickAxes[1] + (dYT * joystickSurfaceView.range)

        for (i in 1..2) {
            joystickSurfaceView.dispatchTouchEvent(
                MotionEvent.obtain(
                    android.os.SystemClock.uptimeMillis(),
                    android.os.SystemClock.uptimeMillis() + 10,
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
        // Prevent lag
        if (logsTextView.lineCount > 300) {
           logsTextView.text = ""
        }

        logsTextView.append("$msg\n")
        val scrollAmount = (logsTextView.lineCount * logsTextView.lineHeight) - (logsTextView.bottom - logsTextView.top)
        logsTextView.scrollTo(0, scrollAmount)
    }

    private fun updateMotorLogs(msg: String) {
        motorLogsTextView.text = ""
        motorLogsTextView.append("$msg\n")
    }

    private fun updateGPSLogs(msg: String) {
        gpsLogsTextView.text = ""
        gpsLogsTextView.append("$msg\n")
    }

    private fun isZero(value: Float, threshold: Float): Boolean {
        return value >= -threshold && value <= threshold
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // RVR STUFF
        prefsManager = PrefsManager(this)
        prefsManager.maxSpeed = 1.0f
        prefsManager.maxTurnSpeed = 1.15f
        inputHandler = InputHandler(this, prefsManager, this::onInputUpdated)
        if(!packageManager.hasSystemFeature(FEATURE_BLUETOOTH_LE)){
            connectionStatusView.text = "Device does not support required bluetooth mode"
            return
        }
        handler = Handler()
        viewModelRVR = ViewModelProviders.of(this)[RVRViewModel::class.java]
        viewModelRVR!!.connected.observe(this, Observer<Boolean> {
            connectionStatusView.text = if(it) "connected" else "disconnected"
            mainCoordinatorLayout.keepScreenOn = if(it) prefsManager.keepScreenAwake else false
        })

        joystickSurfaceView.setListener(inputHandler)

        connectionStatusView.setOnClickListener {
            disconnectFromDevice()
        }
        fab.setOnClickListener {
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
        logsTextView.movementMethod = ScrollingMovementMethod()

        // MQTT STUFF
        mqttSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                updateLogs("MQTT: Activating MQTT.")
                timeOfLastMQTTMessage = System.currentTimeMillis()
                mqttClient = MQTTClient(this.applicationContext,mqttUrlTextEdit.text.toString(), "")
                mqttClient.connect(
                    cbConnect = object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            // cmd stuff
                            updateLogs("MQTT: Connection success")
                            mqttClient.subscribe(velTopic,
                                1,
                                object : IMqttActionListener {
                                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                                        updateLogs("MQTT: Subscribed to: $velTopic")
                                    }

                                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                        updateLogs("MQTT: Failed to subscribe: $velTopic")
                                    }
                                })

                            getLocation()
                        }


                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            updateLogs("MQTT: Connection failure: ${exception.toString()}")
                        }
                    },
                    cbClient = object : MqttCallback {
                        override fun messageArrived(topic: String?, message: MqttMessage?) {
                            updateLogs("MQTT: Receive message: ${message.toString()} from topic: $topic")
                            val xy = message.toString().split(',')

                            // Update memory of previous input if not "0.0,0.0"
                            if (!isZero(xy[0].toFloat(), 0.1f) || !isZero(xy[1].toFloat(), 0.1f)) {

                                if (fidgetingState) {
                                    if (!isZero(xy[1].toFloat(), 0.1f)) {
                                        fidgetingState = false
                                    }
                                } else {
                                    // Decide if user is fidgeting the robot ( alternating between opposite actions )
                                    val xFlip = !isZero(xy[0].toFloat(), 0.1f) && isZero(xy[0].toFloat() + previousInput[0].toFloat(), 0.01f)
                                    val yFlip = !isZero(xy[1].toFloat(), 0.1f) && isZero(xy[1].toFloat() + previousInput[1].toFloat(), 0.01f)

                                    fidgetingState = xFlip || yFlip
                                }

                                previousInput = xy
                            }
                            simulateJoystickInput(MotionEvent.ACTION_MOVE, xy[0].toFloat(), xy[1].toFloat())

                            if (fidgetingState) {
                                prefsManager.timeoutMs = prefsManager.defaultTimeoutMs / 4
                                prefsManager.rotTimeoutMs = prefsManager.defaultRotTimeoutMs / 4
                            } else {
                                prefsManager.timeoutMs = prefsManager.defaultTimeoutMs
                                prefsManager.rotTimeoutMs = prefsManager.defaultRotTimeoutMs
                            }
                        }

                        override fun connectionLost(cause: Throwable?) {
                            updateLogs("MQTT: Connection lost")
                        }

                        override fun deliveryComplete(token: IMqttDeliveryToken?) {
//                            updateLogs("MQTT: Delivery complete")
                        }
                    })
                // GPS
                getLocation()
                timer = object: CountDownTimer(100000, 5000) {
                    @SuppressLint("MissingPermission")
                    override fun onTick(millisUntilFinished: Long) {
                        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        if (mqttClient.isConnected()) {
                            mqttClient.publish(gpsTopic, "${location?.latitude},${location?.longitude}")
                        }
                    }
                    override fun onFinish() {
                        timer.start()
                    }
                }
                timer.start()
            } else {
                updateLogs("MQTT: Deactivating MQTT.")
                try {
                    mqttClient.unsubscribe(velTopic,
                        object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                updateLogs("MQTT: Unsubscribed to: $velTopic")
                            }

                            override fun onFailure(
                                asyncActionToken: IMqttToken?,
                                exception: Throwable?
                            ) {
                                updateLogs("MQTT: Failed to unsubscribe: $velTopic")
                            }
                        })
                } catch (e: Exception) {
                }

                mqttClient.disconnect(object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        updateLogs("MQTT: Disconnected")
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
        return super.onGenericMotionEvent(event)
    }

    private fun onInputUpdated(left : Float, right: Float){
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
        return if (Build.VERSION.SDK_INT >= 23) {
            (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED)
        } else true
    }

    private fun requestPerms(){
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            PERM_REQUEST_LOCATION
        )
    }

    private fun showScanLayout() {
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

    private fun hideScanLayout(){
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

                // Check if timeout
                if(lastInputCommand > prefsManager.timeoutMs){
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

                        var left = it.first
                        var right = it.second

                        // Rotation specific logic
                        if ( kotlin.math.abs(left) > kotlin.math.abs(left + right)) {

                            // Check if rotation timeout
                            if(lastInputCommand > prefsManager.rotTimeoutMs){
                                rotTimedOut = true
                                simulateJoystickInput(MotionEvent.ACTION_UP, 0.0f, 0.0f)
                                left = 0f
                                right = 0f
                            }

                            // left and right motor values have opposite signs  => rotation
                            if (left > 0) { // Clockwise
                                if (left < prefsManager.maxTurnSpeed / 2) { left = prefsManager.maxTurnSpeed / 2; right = -prefsManager.maxTurnSpeed / 2 }
                            }
                            if (left < 0) { // C-Clockwise
                                if (left > -prefsManager.maxTurnSpeed / 2) { left = -prefsManager.maxTurnSpeed / 2; right = prefsManager.maxTurnSpeed / 2 }
                            }
                        } else {
                            if (left > 0) {
                                if (left < prefsManager.maxSpeed / 4) { left = prefsManager.maxSpeed / 4; right = prefsManager.maxSpeed / 4 }
                            }
                            if (left < 0) {
                                if (left > -prefsManager.maxSpeed / 4) { left = -prefsManager.maxSpeed / 4; right = -prefsManager.maxSpeed / 4 }
                            }
                        }

                        updateMotorLogs("Left: $left Right: $right Fidget: $fidgetingState")
                        command = SpheroMotors.drive(left, right)
                    }
                } else{
                    updateMotorLogs("Left: $left Right: $right Fidget: $fidgetingState" )
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
