package com.acelik.asistan

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Reminder(val id: Int, val text: String, val time: Long)

/**
 * Saatli hatırlatıcılar: "yarın 10'da faturayı hatırlat", "5 dakika sonra çayı hatırlat".
 * Zamanı gelince bildirim gösterir (telefon sesliyse sesli de söyler). Telefonda saklanır.
 */
object Reminders {

    private const val ACTION = "com.acelik.asistan.HATIRLAT"
    private const val CHANNEL = "hatirlatici"

    private fun prefs(c: Context) = c.getSharedPreferences("dfon", Context.MODE_PRIVATE)

    fun all(c: Context): List<Reminder> {
        val raw = prefs(c).getString("hatirlaticilar", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Reminder(o.getInt("id"), o.getString("m"), o.getLong("z")) } }
                .sortedBy { it.time }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(c: Context, list: List<Reminder>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("m", it.text).put("z", it.time)) }
        prefs(c).edit().putString("hatirlaticilar", a.toString()).apply()
    }

    fun add(c: Context, text: String, time: Long): Reminder {
        val list = all(c)
        val r = Reminder((list.maxOfOrNull { it.id } ?: 0) + 1, text, time)
        save(c, list + r)
        setAlarm(c, r)
        Diag.log(c, "hatirlat", "Kuruldu: ${whenText(time)} — $text")
        return r
    }

    fun remove(c: Context, id: Int): Reminder? {
        val list = all(c)
        val r = list.firstOrNull { it.id == id } ?: return null
        save(c, list - r)
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(c, id))
        return r
    }

    private fun pi(c: Context, id: Int) = PendingIntent.getBroadcast(
        c, 800 + id, Intent(c, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun setAlarm(c: Context, r: Reminder) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.time, pi(c, r.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.time, pi(c, r.id))
    }

    /** Açılışta / yeniden başlatmada: alarmları yeniden kur, zamanı geçmiş olanları hemen göster. */
    fun schedule(c: Context) {
        val now = System.currentTimeMillis()
        for (r in all(c)) if (r.time <= now) fire(c, r.id) else setAlarm(c, r)
    }

    internal fun fire(c: Context, id: Int) {
        val r = remove(c, id) ?: return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hatırlatıcılar", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
        )
        val open = PendingIntent.getActivity(c, 801, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("⏰ Hatırlatma")
            .setContentText(r.text)
            .setStyle(Notification.BigTextStyle().bigText(r.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(8000 + id, n) } catch (ignored: SecurityException) {}
        // Telefon sesliyse sesli de söyle (sessizde/titreşimde sadece bildirim)
        val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            try { Speaker.speak(c.applicationContext, "Hatırlatma: ${r.text}") } catch (ignored: Exception) {}
        }
        Diag.log(c, "hatirlat", "Gösterildi: ${r.text}")
    }

    fun whenText(time: Long): String {
        val t = Calendar.getInstance().apply { timeInMillis = time }
        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val hm = SimpleDateFormat("HH:mm", Locale("tr")).format(Date(time))
        return when {
            sameDay(t, today) -> "bugün $hm"
            sameDay(t, tomorrow) -> "yarın $hm"
            else -> SimpleDateFormat("d MMMM EEEE", Locale("tr")).format(Date(time)) + " $hm"
        }
    }

    fun listText(c: Context): String {
        val l = all(c)
        if (l.isEmpty()) return "⏰ Kurulu hatırlatıcı yok. Örn: \"yarın 10'da faturayı hatırlat\", \"5 dakika sonra çayı hatırlat\"."
        return "⏰ Hatırlatıcıların:\n" + l.mapIndexed { i, r -> "${i + 1}) ${whenText(r.time)} — ${r.text}" }.joinToString("\n")
    }

    /** Liste penceresi: dokununca silinir. */
    fun show(a: Activity, done: (String) -> Unit) {
        val l = all(a)
        if (l.isEmpty()) { done(listText(a)); return }
        AlertDialog.Builder(a)
            .setTitle("⏰ Hatırlatıcılar (silmek için dokun)")
            .setItems(l.map { "${whenText(it.time)} — ${it.text}" }.toTypedArray()) { _, w ->
                remove(a, l[w].id)
                done("🗑 Hatırlatıcı silindi: ${l[w].text}")
            }
            .setNegativeButton("Kapat", null)
            .show()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.fire(context, intent.getIntExtra("id", -1))
    }
}
