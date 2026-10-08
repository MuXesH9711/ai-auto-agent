package com.example.aiautoagent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.*
import com.example.aiautoagent.agent.AIAccessibilityService
import com.example.aiautoagent.capture.ScreenCaptureService

class MainActivity : Activity() {
    private val captureCode = 9001
    private lateinit var goal: EditText
    private lateinit var gamePackage: EditText
    private lateinit var endpoint: EditText
    private lateinit var model: EditText
    private lateinit var key: EditText
    private lateinit var provider: Spinner
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var overlayCheck: TextView
    private lateinit var accessibilityCheck: TextView
    private lateinit var captureCheck: TextView
    private var isMediaProjectionOk = false
    private val prefs by lazy { getSharedPreferences("agent", MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        goal = findViewById(R.id.goal); gamePackage = findViewById(R.id.gamePackage); endpoint = findViewById(R.id.endpoint)
        model = findViewById(R.id.model); key = findViewById(R.id.apiKey)
        provider = findViewById(R.id.provider); status = findViewById(R.id.status)
        start = findViewById(R.id.start)
        overlayCheck = findViewById(R.id.overlayCheck)
        accessibilityCheck = findViewById(R.id.accessibilityCheck)
        captureCheck = findViewById(R.id.captureCheck)

        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("OpenAI-compatible", "Gemini Native"))
        val savedProvider = prefs.getString("provider", "OpenAI-compatible")
        provider.setSelection(if (savedProvider == "Gemini Native") 1 else 0)
        endpoint.setText(prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions"))
        model.setText(prefs.getString("model", "")); key.setText(prefs.getString("apiKey", ""))
        goal.setText(prefs.getString("goal", "Claim daily rewards and dismiss popups"))
        gamePackage.setText(prefs.getString("gamePackage", ""))

        findViewById<Button>(R.id.accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.overlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else status.text = "Status: overlay permission already enabled"
        }
        findViewById<Button>(R.id.capture).setOnClickListener {
            val m = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(m.createScreenCaptureIntent(), captureCode)
        }
        start.setOnClickListener {
            if (!refreshPreflight()) return@setOnClickListener
            save()
            val s = AIAccessibilityService.instance
            if (s == null) { status.text = "Status: Accessibility service is not connected" }
            else { s.startAgent(goal.text.toString()); status.text = "Status: agent running" }
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            AIAccessibilityService.instance?.stopAgent(); status.text = "Status: stopped"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPreflight()
    }

    private fun refreshPreflight(): Boolean {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = isAccessibilityEnabled()
        val captureOk = isMediaProjectionOk || ScreenCaptureService.isActive()
        isMediaProjectionOk = captureOk
        setIndicator(overlayCheck, overlayOk, "Overlay permission")
        setIndicator(accessibilityCheck, accessibilityOk, "Accessibility service")
        setIndicator(captureCheck, captureOk, "Screen capture permission")
        start.isEnabled = overlayOk && accessibilityOk && captureOk
        if (!start.isEnabled) status.text = "Status: complete all preflight checks"
        return start.isEnabled
    }

    private fun setIndicator(view: TextView, ok: Boolean, label: String) {
        view.text = if (ok) "✓ $label" else "✗ $label"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun save() {
        prefs.edit().putString("provider", provider.selectedItem.toString())
            .putString("endpoint", endpoint.text.toString().trim())
            .putString("model", model.text.toString().trim())
            .putString("apiKey", key.text.toString())
            .putString("goal", goal.text.toString())
            .putString("gamePackage", gamePackage.text.toString().trim()).apply()
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == captureCode && c == RESULT_OK && d != null) {
            isMediaProjectionOk = true
            val i = Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, c)
                .putExtra(ScreenCaptureService.EXTRA_DATA, d)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            refreshPreflight()
            status.text = "Status: screen capture active"
        }
    }
}
