package com.example.aiautoagent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import com.example.aiautoagent.util.AgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object ActionExecutor {

    suspend fun execute(action: AgentAction): Boolean = withContext(Dispatchers.Main) {
        val service = AIAccessibilityService.instance
        if (service == null) {
            AgentLogger.log("ActionExecutor: AccessibilityService not connected")
            return@withContext false
        }

        when (action) {
            is AgentAction.Click -> {
                return@withContext performClick(service, action.x, action.y)
            }
            is AgentAction.Swipe -> {
                return@withContext performSwipe(service, action.startX, action.startY, action.endX, action.endY, action.durationMs)
            }
            is AgentAction.Back -> {
                return@withContext service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            is AgentAction.Home -> {
                return@withContext service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
            is AgentAction.Wait -> {
                delay(action.ms)
                return@withContext true
            }
            is AgentAction.Stop -> {
                AgentLogger.log("ActionExecutor: STOP action executed")
                return@withContext true
            }
        }
    }

    private suspend fun performClick(service: AIAccessibilityService, x: Float, y: Float): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()

            val success = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)

            if (!success && continuation.isActive) {
                continuation.resume(false)
            }
        }
    }

    private suspend fun performSwipe(
        service: AIAccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100)))
                .build()

            val success = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)

            if (!success && continuation.isActive) {
                continuation.resume(false)
            }
        }
    }
}
