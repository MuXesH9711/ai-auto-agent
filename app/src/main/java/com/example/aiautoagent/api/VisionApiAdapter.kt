package com.example.aiautoagent.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface VisionApiAdapter { suspend fun decide(goal: String, history: List<String>, jpeg: ByteArray, gameMode: Boolean): String }

data class VisionConfig(val provider: String, val endpoint: String, val model: String, val apiKey: String)

class OpenAICompatibleVisionAdapter(private val cfg: VisionConfig) : VisionApiAdapter {
    private val client = OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()

    override suspend fun decide(goal: String, history: List<String>, jpeg: ByteArray, gameMode: Boolean): String {
        val prompt = buildPrompt(goal, history, gameMode)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
                .put("detail", "low")))
        val schema = JSONObject().put("type", "object").put("additionalProperties", false).put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("tap","swipe","scroll","type","back","home","wait","stop"))))
             .put("target_coords", coordSchema()).put("target_coords_2", coordSchema())
            .put("input_text", JSONObject().put("type", listOf("string","null")))
            .put("target_label", JSONObject().put("type", "string"))
            .put("is_safe", JSONObject().put("type", "boolean"))
            .put("action_summary", JSONObject().put("type", "string"))
            .put("duration_ms", JSONObject().put("type", "integer")).put("summary", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action","target_coords","target_coords_2","input_text","duration_ms","target_label","is_safe","action_summary")))
        val body = JSONObject().put("model", cfg.model).put("temperature", 0)
            .put("messages", JSONArray().put(JSONObject().put("role","system").put("content", "You are a UI action planner. Return only JSON matching the requested schema. Never reveal hidden chain-of-thought. Use a short summary."))
                .put(JSONObject().put("role","user").put("content", content)))
            .put("response_format", JSONObject().put("type","json_schema").put("json_schema", JSONObject().put("name","agent_action").put("strict",true).put("schema",schema)))
        val request = Request.Builder().url(cfg.endpoint).addHeader("Authorization", "Bearer ${cfg.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return executeCancellable(request) { r ->
            val root = JSONObject(r)
            cleanJson(root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        }
    }
    private fun coordSchema() = JSONObject().put("type", listOf("object","null")).put("additionalProperties", false).put("properties", JSONObject()
        .put("x", JSONObject().put("type","integer").put("minimum",0).put("maximum",1000))
        .put("y", JSONObject().put("type","integer").put("minimum",0).put("maximum",1000)))
        .put("required", JSONArray(listOf("x","y")))
    private fun buildPrompt(goal:String, history:List<String>, gameMode:Boolean) = buildString {
        append("Goal: ").append(goal).append("\n\nPrevious Actions:\n")
        if (history.isEmpty()) append("(none)\n") else history.forEachIndexed { i,s -> append(i+1).append(". ").append(s).append('\n') }
        append("\nCurrent Screen: [Base64 Image Attached]\n")
        if (gameMode) append("You are an automated game UI navigator. Prioritize Claim, Collect, Receive, OK, Continue, Next, and close X controls. Avoid gameplay controls, shops, purchases, premium currency, advertisements, and unlocks.\n")
        append("Choose exactly one next action. DO NOT repeat the last action if the screen has updated. Coordinates are normalized 0-1000. target_label must be a short visual label. Set is_safe=false if the target is a purchase/store/currency/unlock action. Return only JSON.")
    }
    private suspend fun <T> executeCancellable(req: Request, parse:(String)->T):T = suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object: okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { if (!it.isSuccessful) { if (cont.isActive) cont.resumeWithException(java.io.IOException("HTTP ${it.code}: ${it.body?.string()}")); return } ; val text=it.body?.string() ?: ""; if (cont.isActive) cont.resume(parse(text)) }
            }
        })
    }
}

private fun cleanJson(raw: String): String {
    val stripped = raw.replace(Regex("^\\s*```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*```\\s*$"), "")
        .trim()
    val start = stripped.indexOf('{')
    val end = stripped.lastIndexOf('}')
    require(start >= 0 && end > start) { "Vision response did not contain a JSON object" }
    return stripped.substring(start, end + 1)
}

class GeminiNativeVisionAdapter(private val cfg: VisionConfig) : VisionApiAdapter {
    private val client = OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()
    override suspend fun decide(goal:String, history:List<String>, jpeg:ByteArray, gameMode:Boolean):String {
        val prompt = "Goal: $goal\nPrevious Actions:\n" + history.mapIndexed { i,s -> "${i+1}. $s" }.joinToString("\n") +
                "\nCurrent Screen attached. " + if (gameMode) "You are an automated game UI navigator. Prioritize Claim, Collect, Receive, OK, Continue, Next and close X; avoid gameplay, store, purchases, premium currency, ads and unlocks. " else "" + "Return ONLY JSON with action, target_coords, target_coords_2, input_text, duration_ms, target_label, is_safe, action_summary. Coordinates normalized 0-1000."
        val partImage = JSONObject().put("inline_data", JSONObject().put("mime_type","image/jpeg").put("data", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text",prompt)).put(partImage))))
            .put("generationConfig", JSONObject().put("temperature",0).put("responseMimeType","application/json"))
        val url = cfg.endpoint.trimEnd('/') + "/v1beta/models/" + cfg.model + ":generateContent?key=" + java.net.URLEncoder.encode(cfg.apiKey,"UTF-8")
        val req = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return execute(req) { r -> cleanJson(JSONObject(r).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")) }
    }
    private suspend fun <T> execute(req:Request, parse:(String)->T):T = suspendCancellableCoroutine { cont ->
        val call=client.newCall(req); cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object:okhttp3.Callback{
            override fun onFailure(call:Call,e:java.io.IOException){if(cont.isActive)cont.resumeWithException(e)}
            override fun onResponse(call:Call,response:okhttp3.Response){response.use{if(!it.isSuccessful){if(cont.isActive)cont.resumeWithException(java.io.IOException("HTTP ${it.code}"));return};val s=it.body?.string() ?: "";if(cont.isActive)cont.resume(parse(s))}}
        })
    }
}
