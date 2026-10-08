package com.example.aiautoagent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.random.Random

class ActionExecutor(private val service: AccessibilityService) {
    suspend fun execute(a: AgentAction): Boolean = when (a.type) {
        ActionType.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        ActionType.HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        ActionType.TYPE -> {
            val root = service.rootInActiveWindow ?: return false
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, (a.inputText ?: "").take(500))
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                delay(300)
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            ok
        }
        ActionType.WAIT -> { delay(a.durationMs); true }
        ActionType.STOP -> false
        ActionType.TAP -> gesture(a.point ?: return false, null, a.durationMs)
        ActionType.SWIPE -> gesture(a.point ?: return false, a.point2 ?: return false, a.durationMs)
        ActionType.SCROLL -> {
            val p = a.point ?: return false
            val q = Point(p.x, (p.y - 350).coerceIn(0, 1000))
            gesture(p, q, a.durationMs.coerceAtLeast(350))
        }
    }

    private fun displaySize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            service.resources.displayMetrics.widthPixels to service.resources.displayMetrics.heightPixels
        }
    }

    private suspend fun gesture(p: Point, q: Point?, requestedDuration: Long): Boolean = suspendCancellableCoroutine { cont ->
        val (width, height) = displaySize()
        fun px(v: Int, max: Int) = ((v.coerceIn(0, 1000) / 1000f) * max).coerceIn(0f, max.toFloat())

        // Small bounded input variation for robustness against integer rounding.
        // This is not intended to evade game anti-cheat systems.
        val jitterX = Random.nextInt(-2, 3)
        val jitterY = Random.nextInt(-2, 3)
        val x = px(p.x + jitterX, width)
        val y = px(p.y + jitterY, height)
        val duration = if (q == null) Random.nextLong(80, 151) else requestedDuration.coerceIn(80, 3000)
        val path = Path().apply {
            moveTo(x, y)
            if (q != null) lineTo(px(q.x, width), px(q.y, height))
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(g: GestureDescription) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }
}
