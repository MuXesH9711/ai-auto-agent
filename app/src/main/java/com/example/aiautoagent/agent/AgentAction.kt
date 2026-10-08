package com.example.aiautoagent.agent

import org.json.JSONObject

enum class ActionType { TAP, SWIPE, SCROLL, TYPE, BACK, HOME, WAIT, STOP }
data class Point(val x: Int, val y: Int)

data class AgentAction(
    val type: ActionType,
    val point: Point? = null,
    val point2: Point? = null,
    val inputText: String? = null,
    val durationMs: Long = 500,
    val targetLabel: String = "",
    val isSafe: Boolean = true,
    val summary: String = ""
) {
    companion object {
        fun parse(raw: String): AgentAction {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            require(start >= 0 && end > start) { "No JSON object in vision response" }
            val o = JSONObject(raw.substring(start, end + 1))
            val type = ActionType.valueOf(o.getString("action").trim().uppercase())
            fun point(name: String): Point? {
                if (!o.has(name) || o.isNull(name)) return null
                val p = o.getJSONObject(name)
                return Point(p.getInt("x").coerceIn(0, 1000), p.getInt("y").coerceIn(0, 1000))
            }
            return AgentAction(
                type = type,
                point = point("target_coords"),
                point2 = point("target_coords_2"),
                inputText = if (o.has("input_text") && !o.isNull("input_text")) o.getString("input_text") else null,
                durationMs = o.optLong("duration_ms", 100).coerceIn(50, 3000),
                targetLabel = o.optString("target_label", "").take(160),
                isSafe = o.optBoolean("is_safe", true),
                summary = o.optString("action_summary", o.optString("summary", "")).take(160)
            )
        }
    }
}
