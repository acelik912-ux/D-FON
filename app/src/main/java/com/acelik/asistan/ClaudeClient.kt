package com.acelik.asistan

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Claude Messages API'ye basit bir istemci (ek kütüphane gerektirmez). */
class ClaudeClient(private val apiKey: String, private val model: String) {

    fun send(system: String, messages: JSONArray, tools: JSONArray): JSONObject {
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")

            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 1024)
                .put("system", system)
                .put("messages", messages)
                .put("tools", tools)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException(explain(code, text))
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun explain(code: Int, text: String): String {
        val msg = try {
            JSONObject(text).optJSONObject("error")?.optString("message") ?: text
        } catch (ignored: Exception) {
            text
        }
        return when (code) {
            401 -> "API anahtarı geçersiz. ⚙ menüsünden anahtarı kontrol et."
            402, 403 -> "Hesapta kredi yok ya da erişim reddedildi: $msg"
            404 -> "Model bulunamadı ($model). ⚙ menüsünden model adını kontrol et."
            429 -> "Çok fazla istek; biraz bekleyip tekrar dene."
            529, 503 -> "Sunucu şu an yoğun; birazdan tekrar dene."
            else -> "API hatası $code: $msg"
        }
    }
}
