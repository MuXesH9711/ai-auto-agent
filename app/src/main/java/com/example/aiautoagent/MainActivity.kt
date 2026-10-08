package com.example.aiautoagent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.*
import com.example.aiautoagent.agent.AIAccessibilityService
import com.example.aiautoagent.capture.ScreenCaptureService

class MainActivity : Activity() {

    private val captureCode = 9001

    private lateinit var status: TextView
    private lateinit var start: Button

    private var isMediaProjectionOk = false

    private val prefs by lazy {
        getSharedPreferences("agent", MODE_PRIVATE)
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: TextView? = null
    private var commandCard: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        start = findViewById(R.id.start)

        start.setOnClickListener {
            startAI()
        }

        findViewById<TextView>(R.id.settings).setOnClickListener {
            showSettings()
        }

        refreshPreflight()
    }

    override fun onResume() {
        super.onResume()
        refreshPreflight()
    }

    private fun startAI() {
        if (!refreshPreflight()) {
            showSetupRequired()
            return
        }

        showFloatingBubble()
        status.text = "● AI ready"
        status.setTextColor(Color.rgb(87, 242, 135))
        Toast.makeText(this, "AI ready — floating button se command do", Toast.LENGTH_SHORT).show()
    }

    private fun showFloatingBubble() {
        if (bubbleView != null) return

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Floating overlay permission required", Toast.LENGTH_SHORT).show()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val bubble = TextView(this)
        bubble.text = "◉"
        bubble.textSize = 28f
        bubble.gravity = Gravity.CENTER
        bubble.setTextColor(Color.WHITE)
        bubble.background = roundedBackground(Color.rgb(88, 101, 242), 100f)
        bubble.elevation = 12f

        val size = dp(62)
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 24
        params.y = 220

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        bubble.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        moved = true
                    }
                    params.x = startX + dx
                    params.y = startY + dy
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        showCommandCard()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(bubble, params)
            bubbleView = bubble
        } catch (e: Exception) {
            Toast.makeText(this, "Floating bubble failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCommandCard() {
        if (commandCard != null) return
        val wm = windowManager ?: return

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(20), dp(18), dp(20), dp(18))
        card.background = roundedBackground(Color.rgb(30, 31, 34), 24f)

        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(this)
        title.text = "AI Agent"
        title.textSize = 18f
        title.setTextColor(Color.WHITE)
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        titleRow.addView(title, LinearLayout.LayoutParams(0, dp(45), 1f))

        val close = TextView(this)
        close.text = "✕"
        close.textSize = 20f
        close.gravity = Gravity.CENTER
        close.setTextColor(Color.rgb(174, 180, 192))
        titleRow.addView(close, LinearLayout.LayoutParams(dp(45), dp(45)))
        card.addView(titleRow)

        val input = EditText(this)
        input.hint = "Mujhe kya karwana hai?"
        input.setHintTextColor(Color.rgb(130, 135, 145))
        input.setTextColor(Color.WHITE)
        input.textSize = 15f
        input.minLines = 3
        input.gravity = Gravity.TOP
        input.setPadding(dp(14), dp(12), dp(14), dp(12))
        input.background = roundedBackground(Color.rgb(18, 19, 22), 16f)
        card.addView(input, LinearLayout.LayoutParams(-1, dp(100)))

        val buttons = LinearLayout(this)
        buttons.gravity = Gravity.CENTER_VERTICAL
        buttons.setPadding(0, dp(12), 0, 0)

        val stop = Button(this)
        stop.text = "STOP"
        stop.textSize = 12f
        stop.setTextColor(Color.WHITE)
        stop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(220, 70, 70))
        buttons.addView(stop, LinearLayout.LayoutParams(0, dp(48), 1f))

        val go = Button(this)
        go.text = "GO"
        go.textSize = 14f
        go.setTextColor(Color.WHITE)
        go.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(88, 101, 242))

        val goParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        goParams.marginStart = dp(10)
        buttons.addView(go, goParams)
        card.addView(buttons)

        val params = WindowManager.LayoutParams(
            dp(330),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        close.setOnClickListener { removeCommandCard() }

        stop.setOnClickListener {
            AIAccessibilityService.instance?.stopAgent()
            status.text = "● Stopped"
            status.setTextColor(Color.rgb(255, 100, 100))
            removeCommandCard()
        }

        go.setOnClickListener {
            val command = input.text.toString().trim()
            if (command.isEmpty()) {
                input.error = "Command likho"
                return@setOnClickListener
            }

            saveLastCommand(command)
            val service = AIAccessibilityService.instance
            if (service == null) {
                input.error = "Accessibility service connected nahi hai"
                return@setOnClickListener
            }

            removeCommandCard()
            status.text = "● AI working..."
            status.setTextColor(Color.rgb(255, 200, 80))
            service.startAgent(command)
            Toast.makeText(this, "AI task started", Toast.LENGTH_SHORT).show()
        }

        try {
            wm.addView(card, params)
            commandCard = card
            input.requestFocus()
        } catch (e: Exception) {
            Toast.makeText(this, "Command window failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeCommandCard() {
        commandCard?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        commandCard = null
    }

    private fun showSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = dp(20)
        box.setPadding(pad, 0, pad, 0)

        val provider = Spinner(this)
        provider.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("OpenAI-compatible", "Gemini Native")
        )
        val savedProvider = prefs.getString("provider", "OpenAI-compatible")
        provider.setSelection(if (savedProvider == "Gemini Native") 1 else 0)
        box.addView(provider)

        val endpoint = settingsEdit("Endpoint", prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions"))
        box.addView(endpoint)

        val model = settingsEdit("Model", prefs.getString("model", ""))
        box.addView(model)

        val apiKey = settingsEdit("API Key", prefs.getString("apiKey", ""))
        apiKey.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        box.addView(apiKey)

        val gamePackage = settingsEdit("Game package (optional)", prefs.getString("gamePackage", ""))
        box.addView(gamePackage)

        val setup = TextView(this)
        setup.text = "\nPermissions\n\nAccessibility Service\nScreen Capture\nFloating Overlay"
        setup.setTextColor(Color.rgb(174, 180, 192))
        setup.textSize = 14f
        box.addView(setup)

        val accessibility = Button(this)
        accessibility.text = "Open Accessibility Settings"
        box.addView(accessibility)

        val capture = Button(this)
        capture.text = "Enable Screen Capture"
        box.addView(capture)

        val overlay = Button(this)
        overlay.text = "Allow Floating Overlay"
        box.addView(overlay)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setNegativeButton("Close", null)
            .setPositiveButton("SAVE", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                prefs.edit()
                    .putString("provider", provider.selectedItem.toString())
                    .putString("endpoint", endpoint.text.toString().trim())
                    .putString("model", model.text.toString().trim())
                    .putString("apiKey", apiKey.text.toString())
                    .putString("gamePackage", gamePackage.text.toString().trim())
                    .apply()

                dialog.dismiss()
                refreshPreflight()
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
        }

        accessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        capture.setOnClickListener {
            val manager = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(manager.createScreenCaptureIntent(), captureCode)
        }

        overlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Overlay already enabled", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun settingsEdit(hint: String, value: String?): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value ?: "")
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(Color.rgb(130, 135, 145))
        e.setPadding(dp(12), dp(8), dp(12), dp(8))
        return e
    }

    private fun refreshPreflight(): Boolean {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = isAccessibilityEnabled()
        val captureOk = isMediaProjectionOk || ScreenCaptureService.isActive()
        isMediaProjectionOk = captureOk

        val ready = overlayOk && accessibilityOk && captureOk
        start.isEnabled = ready

        status.text = if (ready) "● Ready" else "● Setup required"
        status.setTextColor(if (ready) Color.rgb(87, 242, 135) else Color.rgb(174, 180, 192))
        return ready
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureCode && resultCode == RESULT_OK && data != null) {
            isMediaProjectionOk = true
            val intent = Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_DATA, data)

            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            refreshPreflight()
        }
    }

    private fun saveLastCommand(command: String) {
        prefs.edit().putString("lastCommand", command).apply()
    }

    private fun showSetupRequired() {
        Toast.makeText(this, "Settings mein required permissions complete karo", Toast.LENGTH_LONG).show()
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius.toInt()).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        removeCommandCard()
        bubbleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        bubbleView = null
        super.onDestroy()
    }
}
