package com.example.aiautoagent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.aiautoagent.util.AgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ActionExecutor(private val service: AIAccessibilityService) {

    suspend fun execute(action: AgentAction): Boolean = withContext(Dispatchers.Main) {
        when (action.type) {
            ActionType.TAP -> {
                val pt = action.point
                if (pt != null) {
                    performClick(pt.x.toFloat(), pt.y.toFloat())
                } else {
                    false
                }
            }
            ActionType.SWIPE -> {
                val p1 = action.point
                val p2 = action.point2
                if (p1 != null && p2 != null) {
                    performSwipe(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat(), action.durationMs)
                } else {
                    false
                }
            }
            ActionType.SCROLL -> {
                val p1 = action.point ?: Point(500, 800)
                val p2 = action.point2 ?: Point(500, 200)
                performSwipe(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat(), action.durationMs)
            }
            ActionType.TYPE -> {
                val text = action.inputText ?: ""
                val rootNode = service.rootInActiveWindow
                val focused = rootNode?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && text.isNotEmpty()) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } else {
                    false
                }
            }
            ActionType.BACK -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            ActionType.HOME -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
            ActionType.WAIT -> {
                delay(action.durationMs)
                true
            }
            ActionType.STOP -> {
                AgentLogger.info("ActionExecutor: STOP action executed")
                true
            }
        }
    }

    private suspend fun performClick(x: Float, y: Float): Boolean {
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
