package com.mobipc.connect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Foreground service that keeps screen hosting alive. The notification has a Stop button. */
class HostService : Service() {
    companion object {
        const val CH = "mobi_host"
        const val CH_REQ = "mobi_req"
        @Volatile var engine: HostEngine? = null

        fun start(ctx: Context, data: Intent) {
            ctx.startForegroundService(Intent(ctx, HostService::class.java).putExtra("data", data))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Screen hosting", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_REQ, "Connection requests", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        channels()
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HostService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("MobiPC is hosting this phone")
            .setContentText("Trusted devices can connect. Tap Stop to end.")
            .setContentIntent(open)
            .addAction(0, "Stop hosting", stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        engine?.stop()
        val e = HostEngine(this, data) { stopSelf() }
        engine = e
        e.start()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        engine?.updateSize()
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        AppState.hosting = false
        AppState.peerId = ""
        AppState.peerName = ""
        AppState.refresh()
        super.onDestroy()
    }
}
