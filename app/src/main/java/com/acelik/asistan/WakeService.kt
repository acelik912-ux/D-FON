package com.acelik.asistan

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

/**
 * Sesle uyandırma: ekran açık ve kilit açıkken "bir iki üç fon" sözünü dinler.
 * Duyunca D-FON'u açar; sözün ardından bir komut söylendiyse ("bir iki üç fon, YouTube'u aç") onu hemen yapar,
 * söylenmediyse D-FON açılıp komutu dinlemeye başlar.
 * Duyulan sesler hiçbir yere kaydedilmez ve gönderilmez. Ekran kapalıyken ya da kilitliyken mikrofon kullanılmaz.
 */
class WakeService : Service(), RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var idle = false      // ekran kapalı / kilitli
    private var paused = false    // D-FON ekranı açık (kendi mikrofonu var)
    private var errors = 0

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { idle = true; stopListening() }
                Intent.ACTION_USER_PRESENT -> { idle = false; errors = 0; schedule(500) }
                Intent.ACTION_SCREEN_ON -> if (!locked()) { idle = false; schedule(500) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = notification()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Android, mikrofonlu servisin arka plandan (sistem yeniden başlatınca) açılmasına izin vermez.
            Diag.log(this, "uyandirma", "Arka planda başlatılamadı: ${e.javaClass.simpleName}")
            notifyStopped(this, "Sesle uyandırma durdu")
            failed = true
            stopSelf()
            return
        }
        instance = this
        Diag.log(this, "uyandirma", "Dinleme başladı")
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screen, f)
        }
        idle = !screenOn() || locked()
        paused = appVisible
        schedule(300)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private var failed = false

    override fun onDestroy() {
        if (failed) { super.onDestroy(); return }
        instance = null
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screen) } catch (_: Exception) {}
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    fun pause() {
        paused = true
        stopListening()
    }

    fun resume() {
        paused = false
        errors = 0
        schedule(800)
    }

    // ---------------- dinleme döngüsü ----------------

    private fun schedule(ms: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ listen() }, ms)
    }

    private fun listen() {
        if (paused || idle || listening) return
        if (!screenOn() || locked()) { idle = true; return }
        try {
            recognizer?.destroy()
            recognizer = Voice.create(this).also {
                it.setRecognitionListener(this)
                it.startListening(Voice.intent(false))
            }
            listening = true
        } catch (e: Exception) {
            listening = false
            schedule(5000)
        }
    }

    private fun stopListening() {
        handler.removeCallbacksAndMessages(null)
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    override fun onResults(results: Bundle?) {
        listening = false
        errors = 0
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList<String>()
        for (said in list) {
            val cmd = Voice.wakeCommand(said) ?: continue
            handleCommand(cmd)
            return
        }
        // Az önce bir komut verildiyse bir süre uyandırma sözü olmadan da dinle
        if (System.currentTimeMillis() < followUntil) {
            for (said in list) {
                val t = FileTools.norm(said)
                if (t in END_WORDS) {
                    followUntil = 0
                    android.widget.Toast.makeText(this, "Tamam, beklemedeyim.", android.widget.Toast.LENGTH_SHORT).show()
                    schedule(300)
                    return
                }
                if (tryHere(said, false)) {
                    followUntil = System.currentTimeMillis() + FOLLOW_MS
                    schedule(400)
                    return
                }
            }
            // Ekranda yapılamayan ama D-FON'un anlayacağı bir komutsa ("feneri kapat") D-FON'a ilet
            val first = list.firstOrNull().orEmpty()
            val tk = FileTools.norm(first).split(" ")
            if (Nlu.hasVerb(tk, FOLLOW_VERBS)) {
                val tapLike = Nlu.hasVerb(tk, listOf("dokun", "bas", "tikla", "gir"))
                if (tapLike) {
                    tryHere(first, true) // bulunamazsa kısa bir uyarı gösterir
                    schedule(400)
                    return
                }
                wake(first)
                return
            }
        }
        schedule(150)
    }

    private fun handleCommand(cmd: String) {
        followUntil = System.currentTimeMillis() + FOLLOW_MS
        if (cmd.isNotBlank() && tryHere(cmd, false)) {
            schedule(400)
            return
        }
        wake(cmd)
    }

    /** Ekranda yapılabilecek komutları D-FON'u öne getirmeden yapar. */
    private fun tryHere(cmd: String, reportMissing: Boolean): Boolean =
        ScreenControl.openApp(this, cmd, false) ||
            ScreenControl.handle(this, cmd, false) ||
            ScreenControl.openApp(this, cmd, true) ||
            (reportMissing && ScreenControl.handle(this, cmd, true))

    override fun onError(error: Int) {
        listening = false
        if (Voice.languageError(error)) { schedule(300); return }
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> schedule(150)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { setEnabled(this, false); stopSelf() }
            else -> {
                errors++
                schedule(minOf(30_000L, 1000L * errors * errors))
            }
        }
    }

    private fun wake(cmd: String) {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(EXTRA_WAKE, true)
            .putExtra(EXTRA_COMMAND, cmd)
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {}
        }
        // "Üstte göster" izni yoksa Android arka plandan açmaya izin vermez; dokunulacak bir bildirim gösteririz.
        val pi = PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("D-FON seni duydu")
            .setContentText(if (cmd.isBlank()) "Açmak için dokun" else "\"$cmd\" için dokun")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_WAKE, n)
        schedule(1500)
    }

    // ---------------- yardımcılar ----------------

    private fun screenOn() = getSystemService(PowerManager::class.java).isInteractive

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sesle uyandırma", NotificationManager.IMPORTANCE_LOW).apply {
                description = "\"Bir iki üç fon\" sözünü dinlerken gösterilir"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("D-FON dinliyor")
            .setContentText("\"Bir iki üç fon\" deyince açılırım")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Kapat", stop).build())
            .build()
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    companion object {
        const val EXTRA_WAKE = "uyandirma"
        const val EXTRA_COMMAND = "komut"
        private const val ACTION_STOP = "com.acelik.asistan.UYANDIRMA_KAPAT"
        private const val CHANNEL = "uyandirma"
        private const val NOTIF_ID = 7
        private const val NOTIF_WAKE = 8
        private const val NOTIF_STOPPED = 9

        @Volatile
        var instance: WakeService? = null
            private set

        /** D-FON ekranı şu an açık mı (açıksa uyandırma dinlemesi bekler). */
        @Volatile
        var appVisible = false

        /** Bu zamana kadar "bir iki üç fon" demeden de komut dinlenir. */
        @Volatile
        var followUntil = 0L
        const val FOLLOW_MS = 25_000L
        private val FOLLOW_VERBS = listOf("dokun", "bas", "tikla", "gir", "kapat", "artir", "azalt", "yap", "kur", "cevir", "ara")
        private val END_WORDS = setOf("tamam", "bitti", "tesekkurler", "tesekkur ederim", "sagol", "kapat dinlemeyi")

        fun isEnabled(c: Context) =
            c.getSharedPreferences("dfon", Context.MODE_PRIVATE).getBoolean("uyandirma", false)

        fun setEnabled(c: Context, on: Boolean) {
            c.getSharedPreferences("dfon", Context.MODE_PRIVATE).edit().putBoolean("uyandirma", on).apply()
        }

        /** Sadece D-FON ekranı açıkken çağrılmalı (Android mikrofonlu servisi arka plandan başlatmaya izin vermez). */
        fun start(c: Context) {
            if (instance == null) c.startForegroundService(Intent(c, WakeService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, WakeService::class.java))
        }

        /** Tanılama için: hangi dinleme motoru kullanılıyor. */
        fun engineName(c: Context): String = "telefonun ses tanıması"

        /** Dinleme durduysa (sistem kapattı / telefon yeniden başladı) dokununca geri açan bildirim. */
        fun notifyStopped(c: Context, title: String) {
            try {
                val nm = c.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Sesle uyandırma", NotificationManager.IMPORTANCE_LOW)
                )
                val open = PendingIntent.getActivity(
                    c, 5, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE
                )
                val n = Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle(title)
                    .setContentText("Yeniden başlatmak için dokun (D-FON açılınca dinleme geri gelir)")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                nm.notify(NOTIF_STOPPED, n)
            } catch (ignored: Exception) {
            }
        }
    }
}
