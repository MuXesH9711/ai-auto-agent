package com.example.aiautoagent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.Toast
import com.example.aiautoagent.api.*
import com.example.aiautoagent.capture.ScreenCaptureService
import com.example.aiautoagent.util.AgentLogger
import kotlinx.coroutines.*
import kotlin.random.Random

class AIAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: AIAccessibilityService? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var agentJob: Job? = null
    private var overlay: View? = null
    private val history = ArrayDeque<String>()
    private val blocked = setOf(
        "com.phonepe.app",
        "com.google.android.apps.nbu.paisa.user",
        "com.google.android.apps.walletnfcrel",
        "net.one97.paytm",
        "com.android.settings"
    )
    private val purchaseRegex = Regex("(?i)(\\bbuy\\b|\\bpurchase\\b|\\bstore\\b|\\bgem\\b|\\bpack\\b|\\bunlock\\b|₹|\\$)")

    override fun onServiceConnected() {
        instance = this
        showOverlay()
        AgentLogger.info("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = stopAgent()

    fun startAgent(goal: String) {
        stopAgent()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_LONG).show()
            return
        }
        if (!ScreenCaptureService.isActive() || ScreenCaptureService.latestJpeg() == null) {
            Toast.makeText(this, "Start screen capture first", Toast.LENGTH_LONG).show()
            return
        }
        agentJob = scope.launch { runLoop(goal) }
    }

    fun stopAgent() {
        agentJob?.cancel()
        agentJob = null
        history.clear()
        AgentLogger.info("Agent stopped/cancelled")
    }

    private suspend fun runLoop(goal: String) {
        val prefs = getSharedPreferences("agent", MODE_PRIVATE)
        val provider = prefs.getString("provider", "OpenAI-compatible") ?: "OpenAI-compatible"
        val endpoint = prefs.getString("endpoint", "") ?: ""
        val model = prefs.getString("model", "") ?: ""
        val key = prefs.getString("apiKey", "") ?: ""
        val gamePackage = prefs.getString("gamePackage", "")?.trim().orEmpty()
        if (endpoint.isBlank() || model.isBlank() || key.isBlank()) {
            Toast.makeText(this, "Endpoint, model and API key required", Toast.LENGTH_LONG).show()
            return
        }

        val cfg = VisionConfig(provider, endpoint, model, key)
        val api: VisionApiAdapter = if (provider == "Gemini Native") GeminiNativeVisionAdapter(cfg) else OpenAICompatibleVisionAdapter(cfg)
        val executor = ActionExecutor(this)
        val maxSteps = if (gamePackage.isNotBlank()) 10 else 15

        try {
            repeat(maxSteps) { step ->
                ensureActive()
                val pkg = rootInActiveWindow?.packageName?.toString()
                if (pkg != null && pkg in blocked) {
                    AgentLogger.warn("Safety stop: blocked package $pkg")
                    return
                }
                val pureVision = gamePackage.isNotBlank() && pkg == gamePackage
                AgentLogger.info("Step ${step + 1}/$maxSteps | mode=${if (pureVision) "GAME_VISION" else "HYBRID"} | package=${pkg ?: "unknown"}")

                if (!pureVision) {
                    val direct = tryLocalTree(goal)
                    if (direct != null) {
                        record(direct)
                        delay(1000)
                        return@repeat
                    }
                }

                val jpeg = ScreenCaptureService.latestJpeg() ?: run {
                    AgentLogger.warn("No valid screenshot available; stopping")
                    return
                }
                val raw = withTimeoutOrNull(12_000) { api.decide(goal, history.toList(), jpeg, pureVision) }
                    ?: run {
                        AgentLogger.warn("Vision request timeout/cancelled; stopping")
                        return
                    }
                val action = try {
                    AgentAction.parse(raw)
                } catch (t: Throwable) {
                    AgentLogger.error("Invalid vision JSON; stopping", t)
                    return
                }

                if (!validateSafety(action, pkg)) {
                    AgentLogger.warn("Action rejected by safety guard | target=${action.targetLabel}")
                    history.addLast("Action rejected by safety guard")
                    while (history.size > 3) history.removeFirst()
                    return
                }
                if (action.type == ActionType.STOP) {
                    AgentLogger.step(step + 1, maxSteps, "STOP", action.targetLabel.ifBlank { action.summary })
                    return
                }

                AgentLogger.step(step + 1, maxSteps, action.type.name, actionLogDetails(action))
                val ok = executor.execute(action)
                if (!ok) {
                    AgentLogger.warn("Action execution failed: ${action.type}")
                    return
                }
                record(action)
                delay(if (pureVision && action.type in setOf(ActionType.TAP, ActionType.SWIPE, ActionType.SCROLL)) Random.nextLong(1500, 2001) else 1000)
            }
        } catch (e: CancellationException) {
            AgentLogger.info("Agent coroutine cancelled")
            throw e
        } catch (t: Throwable) {
            AgentLogger.error("Agent loop stopped by exception", t)
        }
    }

    private fun validateSafety(action: AgentAction, pkg: String?): Boolean {
        if (pkg != null && pkg in blocked) return false
        if (!action.isSafe) return false
        val label = "${action.targetLabel} ${action.summary} ${action.inputText.orEmpty()}"
        if (purchaseRegex.containsMatchIn(label)) return false
        if (action.type == ActionType.TAP && action.point == null) return false
        if (action.type == ActionType.SWIPE && (action.point == null || action.point2 == null)) return false
        if (action.type == ActionType.TYPE && (action.inputText?.length ?: 0) > 500) return false
        return true
    }

    private fun actionLogDetails(a: AgentAction): String = when (a.type) {
        ActionType.TAP -> "Coords: (${a.point?.x ?: 0}, ${a.point?.y ?: 0}) | Target: \"${a.targetLabel.ifBlank { a.summary }}\""
        ActionType.SWIPE, ActionType.SCROLL -> "Coords: (${a.point?.x ?: 0}, ${a.point?.y ?: 0}) -> (${a.point2?.x ?: 0}, ${a.point2?.y ?: 0}) | Target: \"${a.targetLabel.ifBlank { a.summary }}\""
        ActionType.TYPE -> "Value: \"${(a.inputText ?: "").take(80)}\" | Target: \"${a.targetLabel.ifBlank { a.summary }}\""
        else -> "Target: \"${a.targetLabel.ifBlank { a.summary }}\""
    }

    private fun record(a: AgentAction) {
        val s = "${a.type.name} ${a.targetLabel.ifBlank { a.summary }}".trim()
        history.addLast(s)
        while (history.size > 3) history.removeFirst()
    }

    private fun tryLocalTree(goal: String): AgentAction? {
        val lower = goal.lowercase()
        val root = rootInActiveWindow ?: return null
        val wanted = when {
            "allow" in lower -> listOf("allow", "while using the app", "only this time")
            "continue" in lower -> listOf("continue")
            "next" in lower -> listOf("next")
            "start" in lower -> listOf("start")
            else -> emptyList()
        }
        if (wanted.isEmpty()) return null
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (n == null) return null
            val text = (n.text?.toString() ?: "").lowercase()
            val desc = (n.contentDescription?.toString() ?: "").lowercase()
            if (n.isClickable && wanted.any { text.contains(it) || desc.contains(it) }) return n
            for (i in 0 until n.childCount) walk(n.getChild(i))?.let { return it }
            return null
        }
        val node = walk(root) ?: return null
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        AgentLogger.step(1, 15, "TAP", "Accessibility tree direct target")
        return AgentAction(ActionType.WAIT, durationMs = 900, targetLabel = "Accessibility direct click")
    }

    private fun showOverlay() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) {
            AgentLogger.warn("Overlay permission missing; floating STOP not created")
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val button = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                stopAgent()
                Toast.makeText(context, "Agent stopped", Toast.LENGTH_SHORT).show()
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            150, 70, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 180 }
        button.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; return true }
                    MotionEvent.ACTION_MOVE -> { if (kotlin.math.abs(e.rawX - sx) > 8 || kotlin.math.abs(e.rawY - sy) > 8) moved = true; lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(v, lp); return true }
                    MotionEvent.ACTION_UP -> { if (!moved) v.performClick(); return true }
                }
                return true
            }
        })
        try {
            if (!Settings.canDrawOverlays(this)) return
            wm.addView(button, lp)
            overlay = button
        } catch (e: WindowManager.BadTokenException) {
            AgentLogger.error("Overlay addView rejected; permission may have changed", e)
        } catch (e: SecurityException) {
            AgentLogger.error("Overlay permission denied", e)
        }
    }

    override fun onDestroy() {
        stopAgent()
        overlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        overlay = null
        instance = null
        scope.cancel()
        super.onDestroy()
    }
}
