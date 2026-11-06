package com.titan.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Gemini {

    private fun content(role: String, text: String) =
        JSONObject().put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    /**
     * history: (kullanıcı, titan) çiftleri.
     * AI Studio anahtarı (AIza...) ve Vertex ekspres anahtarı (AQ....) desteklenir.
     */
    fun chat(
        key: String,
        model: String,
        system: String,
        history: List<Pair<String, String>>,
        user: String,
        imageB64: String?
    ): String {
        val contents = JSONArray()
        for ((u, m) in history) {
            contents.put(content("user", u))
            contents.put(content("model", m))
        }
        val parts = JSONArray().put(JSONObject().put("text", user))
        if (imageB64 != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", imageB64)
                )
            )
        }
        contents.put(JSONObject().put("role", "user").put("parts", parts))

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 2048))
            .toString()

        val vertexModel = if (model.contains("latest")) "gemini-2.5-flash" else model
        val studio = Endpoint(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to key)
        )
        val vertex = Endpoint(
            "https://aiplatform.googleapis.com/v1/publishers/google/models/$vertexModel:generateContent?key=$key",
            emptyMap()
        )
        val order = if (key.startsWith("AQ.")) listOf(vertex, studio) else listOf(studio, vertex)

        var lastError = "Bilinmeyen hata"
        for (ep in order) {
            try {
                return call(ep, body)
            } catch (e: Exception) {
                lastError = e.message ?: "Bilinmeyen hata"
            }
        }
        throw Exception(lastError)
    }

    private class Endpoint(val url: String, val headers: Map<String, String>)

    private fun call(ep: Endpoint, body: String): String {
        val conn = URL(ep.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            for ((k, v) in ep.headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    text.take(200)
                }
                throw Exception("HTTP $code: $msg")
            }
            val cands = JSONObject(text).optJSONArray("candidates")
            val sb = StringBuilder()
            if (cands != null && cands.length() > 0) {
                val ps = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                if (ps != null) {
                    for (i in 0 until ps.length()) sb.append(ps.getJSONObject(i).optString("text", ""))
                }
            }
            if (sb.isBlank()) throw Exception("Boş yanıt geldi")
            return sb.toString().trim()
        } finally {
            conn.disconnect()
        }
    }
}
