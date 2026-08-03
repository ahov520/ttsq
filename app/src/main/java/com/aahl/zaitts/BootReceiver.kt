package com.aahl.zaitts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自动启动 TTS 服务(需在 App 内开启"自动启动") */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.load(context).autoStart) {
            TtsService.start(context)
        }
    }
}
