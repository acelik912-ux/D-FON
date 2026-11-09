package com.acelik.asistan

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.AlarmClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Alarm, zamanlayıcı, saat ve tarih. Telefonun kendi Saat uygulamasını kullanır, internete bağlanmaz. */
class ClockTools(private val activity: Activity) {

    fun setAlarm(hour: Int, minute: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return start(i, "⏰ Alarm kuruldu: %02d:%02d".format(hour, minute))
    }

    fun setTimer(seconds: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return start(i, "⏱ Zamanlayıcı başladı: ${duration(seconds)}")
    }

    fun showAlarms(): String = start(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Alarmlar açıldı.")

    fun showTimers(): String = start(Intent(AlarmClock.ACTION_SHOW_TIMERS), "Zamanlayıcılar açıldı.")

    fun now(): String =
        "🕒 Saat " + SimpleDateFormat("HH:mm", Locale("tr")).format(Date())

    fun today(): String =
        "📅 Bugün " + SimpleDateFormat("d MMMM yyyy, EEEE", Locale("tr")).format(Date())

    private fun start(i: Intent, ok: String): String {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            activity.onMain { activity.startActivity(i) }
            ok
        } catch (e: ActivityNotFoundException) {
            "Telefonda bunu yapabilecek bir Saat uygulaması bulunamadı."
        }
    }

    companion object {
        fun duration(sec: Int): String {
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return listOfNotNull(
                if (h > 0) "$h saat" else null,
                if (m > 0) "$m dakika" else null,
                if (s > 0) "$s saniye" else null
            ).joinToString(" ").ifEmpty { "0 saniye" }
        }
    }
}
