package com.aahl.zaitts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class TtsService : Service() {

    companion object {
        private const val TAG = "TtsService"
        private const val CHANNEL_ID = "tts_server"
        private const val NOTIF_ID = 1

        const val ACTION_START = "com.aahl.zaitts.START"
        const val ACTION_STOP = "com.aahl.zaitts.STOP"
        const val ACTION_STATE = "com.aahl.zaitts.STATE"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_MESSAGE = "message"

        /** 进程内状态,Activity 直接读取 */
        @Volatile
        var running = false
            private set

        @Volatile
        var lastMessage: String = ""
            private set

        fun start(context: Context) {
            val intent = Intent(context, TtsService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TtsService::class.java).setAction(ACTION_STOP))
        }
    }

    private var server: TtsServer? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (running) {
            broadcastState(true, "服务已在运行")
            return
        }
        val cfg = Prefs.load(this)
        startForeground(NOTIF_ID, buildNotification("启动中…"))
        try {
            val s = TtsServer(cfg.port) { Prefs.load(this) }
            s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            running = true
            acquireWakeLock()
            val ip = Prefs.deviceIp(this)
            val msg = "运行中 http://$ip:${cfg.port}"
            lastMessage = msg
            updateNotification(msg)
            broadcastState(true, msg)
            Log.i(TAG, "server started on port ${cfg.port}")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            running = false
            lastMessage = "启动失败: ${e.message}"
            broadcastState(false, lastMessage)
            stopSelf()
        }
    }

    private fun stopServer() {
        runCatching { server?.stop() }
        server = null
        running = false
        lastMessage = "已停止"
        releaseWakeLock()
        broadcastState(false, lastMessage)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastState(running: Boolean, message: String) {
        sendBroadcast(Intent(ACTION_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_RUNNING, running)
            .putExtra(EXTRA_MESSAGE, message))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "zaitts:server")
            .apply { acquire() }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TTS 服务", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        createChannel()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ZaiTTS 朗读服务")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        releaseWakeLock()
        running = false
        super.onDestroy()
    }
}
