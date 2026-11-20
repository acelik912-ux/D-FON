package com.acelik.asistan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Ezan sonrası sesliye alma işini kısa bir ön plan servisinde yapar.
 * Yeni Android/One UI sürümleri arka plandaki uygulamanın telefonu titreşimden
 * sesliye almasına izin vermeyebiliyor; ön plan servisi bu kısıtı aşar.
 */
class RestoreService : Service() {
    private val h = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH, "Namaz: sesliye alma", NotificationManager.IMPORTANCE_LOW))
            val n = Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("D-FON")
                .setContentText("Telefon tekrar sesli yapılıyor…")
                .build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(ID, n)
        } catch (e: Exception) {
            Diag.log(this, "namaz", "Ön plan servisi başlatılamadı: ${e.message}")
        }
        Prayer.restore(this)
        // Yeniden denemeler (birkaç sn arayla) bitene kadar ön planda kal
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ stopSelf() }, 40_000L)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) { stopSelf() }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
        private const val CH = "namaz_geri"
        private const val ID = 7731

        /** Ön plan servisiyle dener; başlatılamazsa doğrudan geri alır. */
        fun start(c: Context) {
            try {
                c.startForegroundService(Intent(c, RestoreService::class.java))
            } catch (e: Exception) {
                Diag.log(c, "namaz", "Servis açılamadı (${e.javaClass.simpleName}); doğrudan deniyorum.")
                Prayer.restore(c)
            }
        }
    }
}
