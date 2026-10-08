package com.example.aiautoagent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.example.aiautoagent.util.AgentLogger
import kotlinx.coroutines.*

class AIAccessibilityService : AccessibilityService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AgentLogger.log("AIAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        AgentLogger.log("AIAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        serviceJob.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceJob.cancel()
    }

    companion object {
        var instance: AIAccessibilityService? = null
            private set
    }
}
