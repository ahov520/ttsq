package com.aahl.zaitts

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.net.Inet4Address
import java.net.NetworkInterface

object Prefs {

    private const val FILE = "zaitts"
    private const val KEY_USERID = "userid"
    private const val KEY_TOKEN = "token"
    private const val KEY_VOICE = "voice"
    private const val KEY_PORT = "port"
    private const val KEY_AUTOSTART = "autostart"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): Config {
        val p = sp(context)
        return Config(
            userId = p.getString(KEY_USERID, "") ?: "",
            token = p.getString(KEY_TOKEN, "") ?: "",
            voice = p.getString(KEY_VOICE, "system_002") ?: "system_002",
            port = p.getInt(KEY_PORT, 8823),
            autoStart = p.getBoolean(KEY_AUTOSTART, false),
        )
    }

    fun save(context: Context, config: Config) {
        sp(context).edit()
            .putString(KEY_USERID, config.userId)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_VOICE, config.voice)
            .putInt(KEY_PORT, config.port)
            .putBoolean(KEY_AUTOSTART, config.autoStart)
            .apply()
    }

    data class Config(
        val userId: String,
        val token: String,
        val voice: String,
        val port: Int,
        val autoStart: Boolean,
    )

    fun deviceIp(context: Context): String {
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wm?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                val text = Formatter.formatIpAddress(ip)
                if (text.isNotBlank() && text != "0.0.0.0") return text
            }
        } catch (_: Throwable) {
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return "127.0.0.1"
    }
}
