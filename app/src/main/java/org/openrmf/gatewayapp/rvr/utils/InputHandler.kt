package org.openrmf.gatewayapp.rvr.utils

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import org.openrmf.gatewayapp.rvr.ui.JoystickSurfaceView

class InputHandler(
    val context: Context,
    val prefsManager: PrefsManager,
    val onInputUpdate: (left: Float, right: Float)->Unit
) : JoystickSurfaceView.JoystickUpdateListener {
    private var lastGamepadId: Int? = null
    val isInputGamepad : Boolean
        get() {
            return lastGamepadId != null
        }
    var right = 0.0f
    var left = 0.0f
    var lastUpdated = System.currentTimeMillis()

    fun onDestroy(){
    }

    private fun sendInputUpdatedEvent() {
        lastUpdated = System.currentTimeMillis()
        onInputUpdate(left, right)
    }

    fun processMotionEvent(event: MotionEvent) : Boolean{
        // Check that the event came from a game controller
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && event.action == MotionEvent.ACTION_MOVE) {
            processJoystickInput(event, -1)
            return true
        }
        return false
    }

    private fun getCenteredAxis(
        event: MotionEvent,
        device: InputDevice, axis: Int, historyPos: Int
    ): Float {
        val range = device.getMotionRange(axis, event.source)

        // A joystick at rest does not always report an absolute position of
        // (0,0). Use the getFlat() method to determine the range of values
        // bounding the joystick axis center.
        if (range != null) {
            val flat = range.flat
            val value = if (historyPos < 0)
                event.getAxisValue(axis)
            else
                event.getHistoricalAxisValue(axis, historyPos)

            // Ignore axis values that are within the 'flat' region of the
            // joystick axis center.
            if (Math.abs(value) > flat) {
                return value
            }
        }
        return 0f
    }

    private fun processJoystickInput(
        event: MotionEvent,
        historyPos: Int
    ) {

        val mInputDevice = event.device ?: return

        // Calculate the vertical distance to move by
        // using the input value from one of these physical controls:
        // the left control stick, hat switch, or the right control stick.
        val linearSpeed = -getCenteredAxis(
            event, mInputDevice,
            MotionEvent.AXIS_Y, historyPos
        )
        val rotateSpeed = -getCenteredAxis(
            event, mInputDevice,
            MotionEvent.AXIS_Z, historyPos
        )
        DriveUtil.rcDrive(linearSpeed*prefsManager.maxSpeed, rotateSpeed*prefsManager.maxTurnSpeed,true).also {
            left = it.first
            right = it.second
            lastGamepadId = mInputDevice.id
        }
        sendInputUpdatedEvent()
    }


    override fun OnJoystickUpdate(id: Int, joystickAxes: FloatArray?) {
        lastGamepadId = null
        if(joystickAxes == null) return
        val y = joystickAxes[0]
        val x = joystickAxes[1]

        //we will ignore the ID since we are only using one joystick for this
        DriveUtil.rcDrive(-y*prefsManager.maxSpeed, -x*prefsManager.maxTurnSpeed, true).also {
            left = it.first
            right = it.second
        }
        sendInputUpdatedEvent()
    }
}