package com.ambiance.recorder

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class WebServerService : Service() {
    private var server: WebServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server != null) return START_NOT_STICKY
        val ip = localIp()
        val url = if (ip != null) "http://$ip:$PORT" else "sin conexión a red local"
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, Notifications.build(this, "Servidor activo: $url"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        try {
            server = WebServer(Sessions.root(this), PORT).apply { start() }
            ServerState.error = null
            ServerState.url = url
        } catch (e: Exception) {
            ServerState.error = "No se pudo abrir el puerto $PORT: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF sigue siendo lo que evita que el wifi se duerma.
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ambiance:server")
            .apply { acquire() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        ServerState.url = null
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /** IPv4 de la wifi (o del punto de acceso, si el móvil hace de router). */
    private fun localIp(): String? {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
            .filter { it.second.isSiteLocalAddress }
        return (candidates.firstOrNull { it.first.startsWith("wlan") || it.first.contains("ap") } ?: candidates.firstOrNull())
            ?.second?.hostAddress
    }

    companion object {
        const val PORT = 8080
        private const val NOTIFICATION_ID = 2
    }
}
