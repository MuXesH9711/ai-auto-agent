package com.example.aiautoagent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object ActionExecutor {

    suspend fun execute(action: AgentAction, service: AIAccessibilityService): Boolean {
        return when (action) {
            is AgentAction.Click -> click(service, action.x.toFloat(), action.y.toFloat())
            is AgentAction.Swipe -> swipe(service, action.startX.toFloat(), action.startY.toFloat(), action.endX.toFloat(), action.endY.toFloat(), action.duration)
            is AgentAction.Back -> back(service)
            is AgentAction.Home -> home(service)
            is AgentAction.Wait -> {
                kotlinx.coroutines.delay(action.ms)
                true
            }
        }
    }

    private suspend fun click(service: AIAccessibilityService, x: Float, y: Float): Boolean {
        return suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
        }
    }

    private suspend fun swipe(service: AIAccessibilityService, sx: Float, sy: Float, ex: Float, ey: Float, duration: Long): Boolean {
        return suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(sx, sy)
                lineTo(ex, ey)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(100)))
                .build()
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
        }
    }

    private fun back(service: AIAccessibilityService): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun home(service: AIAccessibilityService): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }
}
