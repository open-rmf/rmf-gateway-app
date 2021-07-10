package org.openrmf.gatewayapp.rvr.utils

import android.content.Context
import android.content.SharedPreferences

class PrefsManager (
    context: Context,
    private val sharedPrefs : SharedPreferences = context.getSharedPreferences("RVR", Context.MODE_PRIVATE)
){

    /**
     * Handle input timeout
     */
    val defaultTimeoutMs: Long = 500
    val defaultRotTimeoutMs: Long = 500
    var timeoutMs: Long = 500           // These values will shrink as the user fidgets
    var rotTimeoutMs: Long = 500
    var keepScreenAwake : Boolean
        get() {
            return sharedPrefs.getBoolean("keepScreenAwake", false)
        }
        set(value) {
            sharedPrefs.edit().putBoolean("keepScreenAwake", value).apply()
        }

    private var _maxSpeed : Float? = null
    var maxSpeed : Float
        get() {
            _maxSpeed?: run {
                _maxSpeed = sharedPrefs.getFloat("maxSpeed", .7f)
            }
            return _maxSpeed!!
        }
        set(value) {
            _maxSpeed = value
            sharedPrefs.edit().putFloat("maxSpeed", value).apply()
        }

    private var _maxTurnSpeed : Float? = null
    var maxTurnSpeed : Float
        get() {
            _maxTurnSpeed?: run {
                _maxTurnSpeed = sharedPrefs.getFloat("maxTurnSpeed", .8f)
            }
            return _maxTurnSpeed!!
        }
        set(value) {
            _maxTurnSpeed = value
            sharedPrefs.edit().putFloat("maxTurnSpeed", value).apply()
        }
}