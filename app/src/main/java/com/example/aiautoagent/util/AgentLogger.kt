package com.example.aiautoagent.util

import android.util.Log

object AgentLogger {
    private const val TAG = "AI_AGENT"
    fun info(message: String) = Log.i(TAG, message)
    fun warn(message: String) = Log.w(TAG, message)
    fun error(message: String, t: Throwable? = null) = Log.e(TAG, message, t)
    fun step(step: Int, max: Int, action: String, details: String) =
        Log.i(TAG, "Step: $step/$max | Action: $action | $details")
}
