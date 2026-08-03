package com.aahl.zaitts

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * 兼容 OpenAI Speech API 的本地 HTTP 服务。
 * 端口默认 8823,与上游 zai-tts 一致。
 */
class TtsServer(
    port: Int,
    private val configProvider: () -> Prefs.Config,
) : NanoHTTPD(port) {

    private val tag = "TtsServer"

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri ?: "/"
        Log.i(tag, "${session.method} $path")
        return try {
            when {
                path == "/v1/models" -> handleModels(session)
                path == "/v1/audio/speech" -> handleSpeech(session)
                path == "/" || path == "/health" ->
                    newFixedLengthResponse(Response.Status.OK, "application/json",
                        JSONObject().put("status", "ok").put("service", "zai-tts-android").toString())
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                    JSONObject().put("error", "not found: $path").toString())
            }
        } catch (e: Exception) {
            Log.e(tag, "serve error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().put("error", e.message ?: e.toString()).toString())
        }
    }

    private fun handleModels(session: IHTTPSession): Response {
        val cfg = configProvider()
        val token = bearerToken(session) ?: cfg.token
        val userId = session.parms["user_id"] ?: cfg.userId
        val voices = JSONArray()
        try {
            ZaiClient.fetchVoices(userId, token).forEach { voices.put(it) }
        } catch (e: Exception) {
            Log.w(tag, "fetchVoices failed, using builtin", e)
            ZaiClient.systemVoices().forEach { voices.put(it) }
        }
        val data = JSONObject()
            .put("object", "list")
            .put("data", JSONArray().put(JSONObject().put("id", "zai-tts")))
            .put("voices", voices)
        return newFixedLengthResponse(Response.Status.OK, "application/json", data.toString())
    }

    private fun handleSpeech(session: IHTTPSession): Response {
        val cfg = configProvider()

        // 解析参数:优先 JSON body,退回 query 参数(与上游行为一致)
        var input = ""
        var voice = cfg.voice
        var speed = 1.0f
        var userId = cfg.userId

        val bodyText = readBody(session)
        if (bodyText.isNotBlank()) {
            val json = JSONObject(bodyText)
            input = json.optString("input", "")
            voice = json.optString("voice", voice).ifBlank { voice }
            speed = json.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 4f)
            userId = json.optString("user_id", userId).ifBlank { userId }
        } else {
            input = session.parms["input"] ?: session.parms["text"] ?: ""
            session.parms["voice"]?.takeIf { it.isNotBlank() }?.let { voice = it }
            session.parms["speed"]?.toFloatOrNull()?.let { speed = it.coerceIn(0.25f, 4f) }
            session.parms["user_id"]?.takeIf { it.isNotBlank() }?.let { userId = it }
        }

        if (input.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                JSONObject().put("error", "input is empty").toString())
        }
        if (cfg.token.isBlank()) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                JSONObject().put("error", "尚未配置 ZAI_TOKEN,请先在 App 中填写").toString())
        }

        Log.i(tag, "speech: voice=$voice speed=$speed len=${input.length}")

        val speech = try {
            ZaiClient.openSpeechStream(input, voice, speed, userId, bearerToken(session) ?: cfg.token)
        } catch (e: ZaiClient.SpeechException) {
            Log.e(tag, "speech request failed", e)
            return newFixedLengthResponse(Response.Status.BAD_GATEWAY, "application/json",
                JSONObject().put("error", "智谱TTS请求失败: ${e.message}").toString())
        }

        val piped = java.io.PipedInputStream(64 * 1024)
        val pipedOut = java.io.PipedOutputStream(piped)
        Thread {
            var ok = false
            try {
                ZaiClient.pumpSseToWav(speech.input) { chunk ->
                    pipedOut.write(chunk)
                    pipedOut.flush()
                }
                ok = true
            } catch (e: Exception) {
                Log.e(tag, "stream pump error", e)
            } finally {
                runCatching { pipedOut.close() }
                runCatching { speech.close() }
                Log.i(tag, "speech done, ok=$ok")
            }
        }.apply {
            isDaemon = true
            name = "tts-pump"
            start()
        }

        return newChunkedResponse(Response.Status.OK, "audio/wav", piped)
    }

    private fun bearerToken(session: IHTTPSession): String? {
        val h = session.headers.entries.firstOrNull { it.key.equals("authorization", true) }?.value
            ?: return null
        return h.removePrefix("Bearer ").trim().takeIf {
            it.isNotEmpty() && !it.equals("none", true) && !it.equals("null", true)
        }
    }

    private fun readBody(session: IHTTPSession): String {
        if (session.method != Method.POST && session.method != Method.PUT) return ""
        val contentLength = session.headers.entries
            .firstOrNull { it.key.equals("content-length", true) }?.value?.toIntOrNull() ?: 0
        val ctype = session.headers.entries
            .firstOrNull { it.key.equals("content-type", true) }?.value ?: ""
        if (!ctype.contains("application/json")) return ""
        return try {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val r = session.inputStream.read(buf, read, contentLength - read)
                if (r <= 0) break
                read += r
            }
            String(buf, 0, read, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(tag, "readBody error", e)
            ""
        }
    }
}
