package com.acelik.asistan

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Application
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uygulama açılırken hata yakalayıcıyı kurar. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.install(this)
    }
}

/**
 * Tanılama: sürüm, izinler, servisler, son olaylar ve son çökme tek ekranda.
 * Her şey telefonda kalır; kullanıcı isterse kopyalayıp paylaşır.
 */
object Diag {

    private val stamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
    private fun logFile(c: Context) = File(c.filesDir, "gunluk.txt")
    private fun crashFile(c: Context) = File(c.filesDir, "cokme.txt")

    fun version(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    fun install(c: Context) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                crashFile(app).writeText(
                    "${stamp.format(Date())}  v${version(app)}  (${thread.name})\n" + sw.toString().take(6000)
                )
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Önemli olayları kısa bir günlüğe yazar (en fazla ~30 KB tutulur). */
    @Synchronized
    fun log(c: Context, tag: String, msg: String) {
        try {
            val f = logFile(c.applicationContext)
            if (f.length() > 30_000) f.writeText(f.readText().takeLast(15_000).substringAfter('\n'))
            f.appendText("${stamp.format(Date())} [$tag] $msg\n")
        } catch (ignored: Throwable) {
        }
    }

    private fun yn(b: Boolean) = if (b) "✅" else "❌"

    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun report(c: Context): String {
        val sb = StringBuilder()
        fun line(s: String) { sb.append(s).append('\n') }
        line("D-FON v${version(c)}")
        line("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("")
        line("— İzinler —")
        line("${yn(Settings.System.canWrite(c))} Sistem ayarlarını değiştirme")
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        line("${yn(nm.isNotificationPolicyAccessGranted)} Rahatsız Etmeyin erişimi")
        line("${yn(nm.areNotificationsEnabled())} Bildirim gösterme")
        line("${yn(granted(c, Manifest.permission.RECORD_AUDIO))} Mikrofon")
        line("${yn(Settings.canDrawOverlays(c))} Üstte gösterme")
        line("${yn(granted(c, Manifest.permission.ACCESS_COARSE_LOCATION))} Konum")
        line("${yn(granted(c, Manifest.permission.READ_CONTACTS))} Rehber   ${yn(granted(c, Manifest.permission.CALL_PHONE))} Arama")
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        line("${yn(Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())} Tam zamanlı alarm")
        line("${yn(!Prayer.batteryRestricted(c))} Pil kısıtlaması yok")
        line("")
        line("— Servisler —")
        line("${yn(AppLock.serviceEnabled(c))} Kilit + sesli kontrol servisi (çalışıyor: ${yn(AppLockService.instance != null)})")
        line("Uyandırma: ayar ${yn(WakeService.isEnabled(c))} · çalışıyor ${yn(WakeService.instance != null)} · motor: ${WakeService.engineName(c)}")
        line("")
        line("— Namaz —")
        line("Ezanda sessiz: ${yn(Prayer.isEnabled(c))} · ${Prayer.duration(c)} dk · ${Prayer.mode(c)}")
        line("Konum: ${Prayer.locationText(c)}")
        Prayer.nextEzan(c)?.let { line("Sıradaki sessiz: ${it.first} ${Prayer.fmt(it.second)}") }
        Prayer.lastEvent(c)?.let { line("Son olay: $it") }
        line("")
        line("— Diğer —")
        line("Kilitli uygulama: ${AppLock.locked(c).size} · şifre: ${yn(Guard.hasPin(c))}")
        line("Rutin: ${Routines.all(c).size} (saatli: ${Routines.all(c).count { it.time != null }}) · Not: ${Notes.all(c).size}")
        line("Sesli okuma (Türkçe): ${when (Speaker.turkishAvailable()) { true -> "✅"; false -> "❌ paket yok"; null -> "henüz denenmedi" }}")
        for (extra in extras) try { line(extra(c)) } catch (ignored: Throwable) {}
        val crash = try { crashFile(c).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }
        line("")
        line("— Son çökme —")
        line(crash?.lines()?.take(14)?.joinToString("\n") ?: "Kayıt yok 👍")
        line("")
        line("— Son olaylar —")
        val log = try { logFile(c).takeIf { it.exists() }?.readLines()?.takeLast(25)?.joinToString("\n") } catch (e: Exception) { null }
        line(log ?: "Kayıt yok")
        return sb.toString()
    }

    /** Sonradan eklenen modüller (ör. yapay zeka) tanılama satırı ekleyebilir. */
    val extras = mutableListOf<(Context) -> String>()

    fun show(a: Activity) {
        val text = report(a)
        val tv = TextView(a).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(a)
            .setTitle("🩺 Tanılama")
            .setView(ScrollView(a).apply { addView(tv) })
            .setPositiveButton("Kopyala") { _, _ ->
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("D-FON tanılama", text))
                Toast.makeText(a, "Kopyalandı; sohbete yapıştırabilirsin.", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Paylaş") { _, _ ->
                a.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Tanılama raporu"
                    )
                )
            }
            .setNegativeButton("Kapat", null)
            .show()
    }
}
