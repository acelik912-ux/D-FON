package com.acelik.asistan

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Bir rutin: adı, sırayla yapılacak komutlar, isteğe bağlı her gün saati ("HH:mm"). */
data class Routine(val name: String, val commands: List<String>, val time: String?)

/**
 * Rutinler: tek komutla ya da tek düğmeyle birden fazla işi sırayla yapar.
 * Saat verilirse her gün o saatte kendiliğinden çalışır. Her şey telefonda kalır.
 */
object Routines {

    const val EXTRA_ROUTINE = "rutin"
    const val EXTRA_BACKGROUND = "rutin_arka"
    private const val ACTION_RUN = "com.acelik.asistan.RUTIN"
    private const val CHANNEL = "rutin"
    private const val MAX_ALARMS = 40

    private fun prefs(c: Context) = c.getSharedPreferences("dfon", Context.MODE_PRIVATE)

    fun all(c: Context): List<Routine> {
        val raw = prefs(c).getString("rutinler", null) ?: return defaults()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val cmds = o.getJSONArray("k")
                Routine(
                    o.getString("ad"),
                    (0 until cmds.length()).map { cmds.getString(it) },
                    o.optString("saat").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            defaults()
        }
    }

    private fun defaults() = listOf(
        Routine("Uyku modu", listOf("Titreşime al", "Parlaklık 10", "Rahatsız etmeyi aç"), null),
        Routine("Sabah modu", listOf("Sessizden çık", "Rahatsız etmeyi kapat", "Otomatik parlaklığı aç", "Durum"), null),
    )

    fun save(c: Context, list: List<Routine>) {
        val a = JSONArray()
        list.forEach { r ->
            a.put(JSONObject().put("ad", r.name).put("k", JSONArray(r.commands)).put("saat", r.time ?: ""))
        }
        prefs(c).edit().putString("rutinler", a.toString()).apply()
        schedule(c)
    }

    /** "15:30", "7.05", "7" → "HH:mm"; geçersizse null. */
    fun parseTime(s: String): String? {
        val m = Regex("^\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*$").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h > 23 || min > 59) return null
        return "%02d:%02d".format(h, min)
    }

    /** Metnin tamamı (ya da "rutin X", "X'i çalıştır") bir rutinin adıysa onu döner. */
    fun match(c: Context, t: String): Routine? {
        val text = t.removePrefix("rutin ").trim()
        return all(c).sortedByDescending { it.name.length }.firstOrNull { r ->
            val n = FileTools.norm(r.name)
            n.isNotEmpty() && text.startsWith(n) && text.removePrefix(n).trim().split(" ").filter { it.isNotEmpty() }.size <= 2
        }
    }

    // ---------------- zamanlama ----------------

    private fun pi(c: Context, idx: Int, name: String?): PendingIntent {
        val i = Intent(c, RoutineReceiver::class.java).setAction(ACTION_RUN)
        if (name != null) i.putExtra(EXTRA_ROUTINE, name)
        return PendingIntent.getBroadcast(c, 500 + idx, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until MAX_ALARMS) am.cancel(pi(c, i, null))
        all(c).take(MAX_ALARMS).forEachIndexed { i, r ->
            val t = r.time ?: return@forEachIndexed
            val (h, m) = t.split(":").map { it.toInt() }
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis() + 1000) add(Calendar.DAY_OF_YEAR, 1)
            }
            val p = pi(c, i, r.name)
            val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, p)
        }
    }

    /** Saati gelen rutini çalıştırır: D-FON arka planda açılıp komutları yapar ve geri çekilir. */
    internal fun fire(c: Context, name: String) {
        val i = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ROUTINE, name)
            .putExtra(EXTRA_BACKGROUND, true)
        val svc = AppLockService.instance
        try {
            when {
                svc != null -> { svc.startActivity(i); return }
                Settings.canDrawOverlays(c) -> { c.startActivity(i); return }
            }
        } catch (ignored: Exception) {
        }
        // Android arka plandan açmaya izin vermediyse: dokunulacak bildirim
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rutinler", NotificationManager.IMPORTANCE_DEFAULT))
        val p = PendingIntent.getActivity(c, 600, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("⏰ $name zamanı")
            .setContentText("Çalıştırmak için dokun")
            .setContentIntent(p)
            .setAutoCancel(true)
            .build()
        try { nm.notify(name.hashCode(), n) } catch (ignored: SecurityException) {}
    }
}

class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(Routines.EXTRA_ROUTINE)?.let { Routines.fire(context, it) }
        Routines.schedule(context) // yarın için yeniden kur
    }
}
