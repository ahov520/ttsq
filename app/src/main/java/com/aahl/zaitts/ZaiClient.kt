package com.aahl.zaitts

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 智谱 audio.z.ai TTS 客户端
 * Kotlin 移植自 https://github.com/aahl/zai-tts 的 zai_tts/client.py
 */
object ZaiClient {

    private const val TAG = "ZaiClient"
    const val BASE_URL = "https://audio.z.ai"
    private const val UA =
        "Mozilla/5.0 AppleWebKit/537.36 Chrome/143 Safari/537"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // 合成大段文本时服务端可能几十秒才推流,读超时放宽到 5 分钟
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    fun systemVoices(): List<JSONObject> = listOf(
        voiceJson("system_001", "活泼女声"),
        voiceJson("system_002", "温柔女声"),
        voiceJson("system_003", "通用男声"),
    )

    private fun voiceJson(id: String, name: String) = JSONObject()
        .put("voice_id", id)
        .put("voice_name", name)

    /**
     * 拉取音色列表(系统音色 + 用户克隆音色)。
     * 与上游一致:两个接口都请求,克隆音色接口失败时仅记日志。
     */
    fun fetchVoices(userId: String, token: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val headers = okhttp3.Headers.Builder()
            .add("Authorization", "Bearer $token")
            .add("User-Agent", UA)
            .add("Referer", "$BASE_URL/")
            .add("Origin", BASE_URL)
            .build()

        val url = okhttp3.HttpUrl.get("$BASE_URL/api/v1/z-audio/voices/list_system")
            .newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("page_size", "200")
            .addQueryParameter("user_id", userId)
            .build()

        client.newCall(Request.Builder().url(url).headers(headers).get().build())
            .execute().use { res ->
                if (res.isSuccessful) {
                    val data = JSONObject(res.body!!.string()).optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) out += data.getJSONObject(i)
                    }
                } else {
                    Log.w(TAG, "list_system voices failed: ${res.code}")
                }
            }

        val url2 = url.newBuilder().encodedPath("/api/v1/z-audio/voices/list").build()
        try {
            client.newCall(Request.Builder().url(url2).headers(headers).get().build())
                .execute().use { res ->
                    if (res.isSuccessful) {
                        val data = JSONObject(res.body!!.string()).optJSONArray("data")
                        if (data != null) {
                            for (i in 0 until data.length()) out += data.getJSONObject(i)
                        }
                    } else {
                        Log.w(TAG, "list cloned voices failed: ${res.code}")
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "list cloned voices error", e)
        }

        if (out.isEmpty()) out += systemVoices()
        return out
    }

    /** 音色名缓存,避免每次合成前都拉取列表 */
    @Volatile
    private var voiceNameCache: Map<String, String>? = null

    fun voiceNameFor(voiceId: String, userId: String, token: String): String {
        var cache = voiceNameCache
        if (cache == null) {
            cache = try {
                fetchVoices(userId, token).associate {
                    it.optString("voice_id") to it.optString("voice_name")
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchVoices error, fallback to id", e)
                emptyMap()
            }
            voiceNameCache = cache
        }
        return cache[voiceId] ?: voiceId
    }

    /**
     * 发起合成请求,返回 SSE 响应体输入流(调用方负责关闭)。
     * 返回 null 表示请求失败,error 中包含错误信息。
     */
    class SpeechStream(val input: java.io.InputStream, val close: () -> Unit)

    @Throws(SpeechException::class)
    fun openSpeechStream(
        text: String,
        voiceId: String,
        speed: Float,
        userId: String,
        token: String,
    ): SpeechStream {
        val voiceName = voiceNameFor(voiceId, userId, token)
        val bodyJson = JSONObject()
            .put("voice_name", voiceName)
            .put("voice_id", voiceId)
            .put("user_id", userId)
            .put("input_text", text)
            .put("speed", (speed * 10).toInt() / 10.0)
            .put("volume", 1)

        val req = Request.Builder()
            .url("$BASE_URL/api/v1/z-audio/tts/create")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("User-Agent", UA)
            .addHeader("Referer", "$BASE_URL/")
            .addHeader("Origin", BASE_URL)
            .addHeader("Accept", "text/event-stream")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(
                "application/json; charset=utf-8".toMediaTypeOrNullCompat()))
            .build()

        val call = client.newCall(req)
        val res = call.execute()
        if (!res.isSuccessful) {
            val errBody = runCatching { res.body?.string() }.getOrNull()
            res.close()
            throw SpeechException("HTTP ${res.code}: ${errBody ?: "no body"}")
        }
        val stream = res.body!!.byteStream()
        return SpeechStream(stream) { runCatching { res.close() } }
    }

    class SpeechException(message: String) : Exception(message)

    /**
     * 解析 SSE 流,把每段音频回调给 consumer。
     * 与上游一致:首块 WAV 去掉原始头,换成长度未知的流式 WAV 头,后续块直接透传。
     */
    fun pumpSseToWav(stream: java.io.InputStream, onChunk: (ByteArray) -> Unit) {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        var wavHeaderSent = false
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload == "[DONE]") break
            val obj = try {
                JSONObject(payload)
            } catch (e: Exception) {
                Log.w(TAG, "not json: ${payload.take(100)}")
                continue
            }
            val b64 = obj.optString("audio")
            if (b64.isNullOrEmpty()) {
                Log.w(TAG, "no audio: $payload")
                continue
            }
            var bytes = Base64.decode(b64, Base64.DEFAULT)
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
            ) {
                val pcm = Wav.extractPcm(bytes)
                if (!wavHeaderSent) {
                    onChunk(Wav.streamingHeader(pcm.sampleRate, pcm.channels, pcm.bitsPerSample))
                    wavHeaderSent = true
                }
                onChunk(pcm.data)
            } else {
                onChunk(bytes)
            }
        }
    }

    private fun String.toMediaTypeOrNullCompat(): okhttp3.MediaType? =
        okhttp3.MediaType.parse(this)
}

/** WAV 头解析/流式头生成,与上游 client.py 的 wave 处理逻辑一致 */
object Wav {

    class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int, val bitsPerSample: Int)

    /** 从完整 WAV 字节中提取 PCM 数据与格式参数(按 RIFF chunk 遍历,不假设头为 44 字节) */
    fun extractPcm(wav: ByteArray): Pcm {
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var sampleRate = 24000
        var channels = 1
        var bits = 16
        // 跳过 RIFF + size + WAVE
        buf.position(12)
        while (buf.remaining() >= 8) {
            val id = IntArray(4) { buf.get().toInt() and 0xFF }
                .joinToString("") { it.toChar().toString() }
            val size = buf.int
            if (size < 0 || buf.remaining() < size) break
            when (id) {
                "fmt " -> {
                    if (size >= 8) {
                        val start = buf.position()
                        channels = buf.getShort(start + 2).toInt() and 0xFFFF
                        sampleRate = buf.getInt(start + 4)
                        bits = buf.getShort(start + 14).toInt() and 0xFFFF
                    }
                }
                "data" -> {
                    val data = ByteArray(size)
                    buf.get(data)
                    return Pcm(data, sampleRate, channels, bits)
                }
            }
            buf.position(buf.position() + size + (size and 1))
        }
        // 找不到 data chunk 时退回固定 44 字节头
        val data = wav.copyOfRange(44.coerceAtMost(wav.size), wav.size)
        return Pcm(data, sampleRate, channels, bits)
    }

    /** 生成长度字段为 0xFFFFFFFF 的流式 WAV 头,与上游行为一致 */
    fun streamingHeader(sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        buf.put("RIFF".toByteArray())
        buf.putInt(-1)              // 文件长度未知
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)              // PCM fmt 块大小
        buf.putShort(1)             // PCM 格式
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray())
        buf.putInt(-1)              // 数据长度未知
        return buf.array()
    }
}
