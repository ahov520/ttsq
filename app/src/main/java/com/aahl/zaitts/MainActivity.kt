package com.aahl.zaitts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aahl.zaitts.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val running = intent.getBooleanExtra(TtsService.EXTRA_RUNNING, false)
            val msg = intent.getStringExtra(TtsService.EXTRA_MESSAGE) ?: ""
            renderState(running, msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureNotificationPermission()
        loadConfigToUi()
        renderState(TtsService.running, TtsService.lastMessage)
        refreshEngineJson()

        binding.btnToggle.setOnClickListener {
            if (TtsService.running) {
                TtsService.stop(this)
            } else {
                saveUiToConfig()
                TtsService.start(this)
            }
        }

        binding.btnCopy.setOnClickListener {
            copyToClipboard("阅读朗读引擎配置", binding.tvEngineJson.text.toString())
        }

        binding.btnCopyUrl.setOnClickListener {
            val port = binding.etPort.text.toString().toIntOrNull() ?: 8823
            copyToClipboard("服务地址", "http://${Prefs.deviceIp(this)}:$port")
        }

        binding.btnFetchVoices.setOnClickListener { fetchVoices() }
        binding.btnTest.setOnClickListener { testSpeak() }

        binding.etPort.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) refreshEngineJson() }
        binding.etVoice.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) refreshEngineJson() }

        // 勾选了"打开 App 时自动启动"且服务未运行 → 自动启动
        if (Prefs.load(this).autoStart && !TtsService.running) {
            saveUiToConfig()
            TtsService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TtsService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        renderState(TtsService.running, TtsService.lastMessage)
    }

    override fun onPause() {
        unregisterReceiver(stateReceiver)
        saveUiToConfig()
        super.onPause()
    }

    private fun loadConfigToUi() {
        val cfg = Prefs.load(this)
        binding.etUserId.setText(cfg.userId)
        binding.etToken.setText(cfg.token)
        binding.etPort.setText(cfg.port.toString())
        binding.etVoice.setText(cfg.voice)
        binding.cbAutoStart.isChecked = cfg.autoStart
    }

    private fun saveUiToConfig() {
        Prefs.save(
            this, Prefs.Config(
                userId = binding.etUserId.text.toString().trim(),
                token = binding.etToken.text.toString().trim(),
                voice = binding.etVoice.text.toString().trim().ifBlank { "system_002" },
                port = binding.etPort.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: 8823,
                autoStart = binding.cbAutoStart.isChecked,
            )
        )
    }

    private fun renderState(running: Boolean, msg: String) {
        binding.tvStatus.text = if (running) "● 服务运行中  $msg" else "● 服务未运行  $msg"
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (running) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
        binding.btnToggle.text = if (running) "停止服务" else "一键启动服务"
        if (msg.isNotBlank()) log(msg)
    }

    /** 生成阅读 App 的 HttpTTS 朗读引擎导入配置 */
    private fun engineJson(): String {
        val port = binding.etPort.text.toString().toIntOrNull() ?: 8823
        val voice = binding.etVoice.text.toString().trim().ifBlank { "system_002" }
        val ip = Prefs.deviceIp(this)
        val url = "http://$ip:$port/v1/audio/speech,{\"method\":\"POST\"," +
            "\"body\":{\"input\":\"{{speakText}}\"," +
            "\"speed\":{{((speakSpeed+5)/10).toFixed(1)}},\"voice\":\"$voice\"}}"
        return "[\n  {\n    \"name\": \"ZaiTTS-智谱\",\n    \"url\": \"$url\",\n" +
            "    \"contentType\": \"audio/wav\",\n    \"concurrentRate\": \"1\"\n  }\n]"
    }

    private fun refreshEngineJson() {
        binding.tvEngineJson.text = engineJson()
    }

    private fun fetchVoices() {
        saveUiToConfig()
        val cfg = Prefs.load(this)
        if (cfg.token.isBlank() || cfg.userId.isBlank()) {
            toast("请先填写 ZAI_USERID 和 ZAI_TOKEN")
            return
        }
        log("正在获取音色列表…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ZaiClient.fetchVoices(cfg.userId, cfg.token) }
            }
            result.onSuccess { voices ->
                if (voices.isEmpty()) {
                    log("未获取到音色,请检查 TOKEN/USERID 是否正确")
                } else {
                    val sb = StringBuilder("可用音色:\n")
                    voices.forEach {
                        sb.append("  ${it.optString("voice_id")}  ${it.optString("voice_name")}\n")
                    }
                    log(sb.toString().trimEnd())
                    toast("获取成功,见日志")
                }
            }.onFailure {
                log("获取音色失败: ${it.message}")
            }
        }
    }

    private fun testSpeak() {
        saveUiToConfig()
        val cfg = Prefs.load(this)
        if (!TtsService.running) {
            toast("请先启动服务")
            return
        }
        log("测试合成中…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val url = "http://127.0.0.1:${cfg.port}/v1/audio/speech"
                    val body = """{"input":"你好,欢迎使用 ZaiTTS","voice":"${cfg.voice}","speed":1}"""
                        .toByteArray()
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 120_000
                    conn.outputStream.use { it.write(body) }
                    val code = conn.responseCode
                    if (code != 200) throw Exception("HTTP $code: ${conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: ""}")
                    val pcm = conn.inputStream.readBytes()
                    pcm
                }
            }
            result.onSuccess { wav ->
                log("合成成功(${wav.size} 字节),播放中…")
                playWav(wav)
            }.onFailure {
                log("合成失败: ${it.message}")
            }
        }
    }

    private var player: MediaPlayer? = null

    private fun playWav(wav: ByteArray) {
        runCatching {
            player?.release()
            val file = java.io.File(cacheDir, "test.wav")
            file.writeBytes(wav)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
        }.onFailure { log("播放失败: ${it.message}") }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        toast("$label 已复制")
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.tvLog.text = "[$time] $msg\n" + binding.tvLog.text.toString()
            .lines().take(30).joinToString("\n")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
