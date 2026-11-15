package com.acelik.asistan

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Ezan vakitlerinde telefonu belirli bir süre sessize alıp sonra eski haline döndürür.
 * Her şey telefonda hesaplanır; internet gerekmez.
 */
object Prayer {

    /** Sessize alınabilecek vakitler (ad, PrayerTimes indeksi). */
    val SLOTS = listOf(
        "Sabah" to PrayerTimes.IMSAK,
        "Öğle" to PrayerTimes.OGLE,
        "İkindi" to PrayerTimes.IKINDI,
        "Akşam" to PrayerTimes.AKSAM,
        "Yatsı" to PrayerTimes.YATSI,
    )
    val DURATIONS = listOf(15, 20, 30, 45, 60)

    private const val ACTION_SILENCE = "com.acelik.asistan.NAMAZ_SESSIZ"
    private const val ACTION_RESTORE = "com.acelik.asistan.NAMAZ_SESLI"
    private const val NOTIF_ID = 7001
    private const val NOTIF_CD = 7002
    private const val CHANNEL_CD = "sayac"
    private const val ACTION_TICK = "com.acelik.asistan.NAMAZ_SAYAC"
    private const val CHANNEL = "namaz"

    private fun prefs(c: Context) = c.getSharedPreferences("namaz", Context.MODE_PRIVATE)
    private val hm = SimpleDateFormat("HH:mm", Locale("tr"))
    fun fmt(t: Long): String = hm.format(Date(t))

    // ---------------- ayarlar ----------------

    fun isEnabled(c: Context) = prefs(c).getBoolean("acik", false)
    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("acik", on).apply()
        if (on) schedule(c) else { cancelSilenceAlarm(c); restore(c) }
    }

    fun hasLocation(c: Context) = prefs(c).contains("lat")
    fun lat(c: Context) = prefs(c).getFloat("lat", 0f).toDouble()
    fun lon(c: Context) = prefs(c).getFloat("lon", 0f).toDouble()
    fun setLocation(c: Context, lat: Double, lon: Double) {
        prefs(c).edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply()
        schedule(c)
        refreshExtras(c)
    }
    fun locationText(c: Context) =
        if (!hasLocation(c)) "Konum yok" else "%.3f, %.3f".format(Locale.US, lat(c), lon(c))

    fun duration(c: Context) = prefs(c).getInt("sure", 30)
    fun setDuration(c: Context, min: Int) = prefs(c).edit().putInt("sure", min).apply()

    /** "sessiz" ya da "titresim" */
    fun mode(c: Context) = prefs(c).getString("mod", "sessiz") ?: "sessiz"
    fun setMode(c: Context, m: String) = prefs(c).edit().putString("mod", m).apply()

    /** Sabah ezanı imsaktan kaç dakika sonra okunuyor (bölgeye göre değişir). */
    fun sabahOffset(c: Context) = prefs(c).getInt("sabah_fark", 0)
    fun setSabahOffset(c: Context, m: Int) { prefs(c).edit().putInt("sabah_fark", m).apply(); schedule(c) }

    fun selected(c: Context): Set<String> =
        prefs(c).getStringSet("vakitler", null) ?: SLOTS.map { it.first }.toSet()
    fun setSelected(c: Context, s: Set<String>) {
        prefs(c).edit().putStringSet("vakitler", HashSet(s)).apply()
        schedule(c)
    }

    // ---------------- vakitler ----------------

    fun timesFor(c: Context, dayOffset: Int): LongArray? {
        if (!hasLocation(c)) return null
        val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        return PrayerTimes.forDay(day, lat(c), lon(c))
    }

    /** Sessize alma anları (seçili vakitler), bugünden itibaren. */
    private fun ezans(c: Context, dayOffset: Int): List<Pair<String, Long>> {
        val t = timesFor(c, dayOffset) ?: return emptyList()
        val sel = selected(c)
        return SLOTS.filter { it.first in sel }.map { (name, idx) ->
            val extra = if (idx == PrayerTimes.IMSAK) sabahOffset(c) * 60_000L else 0L
            name to t[idx] + extra
        }
    }

    fun nextEzan(c: Context): Pair<String, Long>? {
        val now = System.currentTimeMillis()
        for (d in 0..2) ezans(c, d).firstOrNull { it.second > now }?.let { return it }
        return null
    }

    /** Bugünün vakitleri, okunabilir metin. */
    fun todayText(c: Context): String {
        val t = timesFor(c, 0) ?: return "Konum belirlenmedi. \"Konumu bul\" düğmesine bas."
        val sb = StringBuilder("🕌 Bugünün vakitleri:\n")
        PrayerTimes.NAMES.forEachIndexed { i, n -> sb.append("$n ${fmt(t[i])}\n") }
        nextEzan(c)?.let { sb.append("\nSıradaki: ${it.first} ${fmt(it.second)}") }
        sb.append(
            if (isEnabled(c)) "\n🔕 Ezanda sessize alma açık (${duration(c)} dk)."
            else "\nEzanda sessize alma kapalı."
        )
        return sb.toString()
    }

    // ---------------- üst çubukta geri sayım ----------------

    fun countdownEnabled(c: Context) = prefs(c).getBoolean("sayac", true)
    fun setCountdownEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("sayac", on).apply()
        updateCountdown(c)
    }

    /** Altı vakit içinden sıradaki (ad, zaman). */
    fun nextTime(c: Context): Pair<String, Long>? {
        val now = System.currentTimeMillis()
        for (d in 0..1) {
            val t = timesFor(c, d) ?: return null
            for (i in 0 until 6) if (t[i] > now) return PrayerTimes.NAMES[i] to t[i]
        }
        return null
    }

    fun durationText(mins: Int): String =
        if (mins >= 60) "${mins / 60} sa ${mins % 60} dk" else "$mins dk"

    /** Widget ve üst çubuk sayacını tazeler. */
    fun refreshExtras(c: Context) {
        updateCountdown(c)
        PrayerWidget.updateAll(c)
    }

    private fun tickIntent(c: Context) = PendingIntent.getBroadcast(
        c, 9, Intent(c, PrayerReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun updateCountdown(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val tick = tickIntent(c)
        am.cancel(tick)
        val next = if (countdownEnabled(c) && hasLocation(c)) nextTime(c) else null
        if (next == null) {
            nm.cancel(NOTIF_CD)
            return
        }
        val now = System.currentTimeMillis()
        val mins = Math.ceil((next.second - now) / 60_000.0).toInt().coerceAtLeast(0)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CD, "Sıradaki vakte kalan süre", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        // Üst satır sayı, alt satır birim: "47 / dk" ya da "2:15 / saat"
        val (top, unit) = if (mins < 60) "$mins" to "dk" else "${mins / 60}:${"%02d".format(mins % 60)}" to "saat"
        val t = timesFor(c, 0)
        val all = if (t == null) "" else PrayerTimes.NAMES.indices.joinToString("  ·  ") { "${PrayerTimes.NAMES[it]} ${fmt(t[it])}" }
        val open = PendingIntent.getActivity(c, 4, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CHANNEL_CD)
            .setSmallIcon(Icon.createWithBitmap(numberBitmap(top, unit)))
            .setContentTitle("${next.first} vaktine ${durationText(mins)} kaldı (${fmt(next.second)})")
            .setContentText(all)
            .setStyle(Notification.BigTextStyle().bigText(all.replace("  ·  ", "\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .build()
        try { nm.notify(NOTIF_CD, n) } catch (ignored: SecurityException) {}

        // Sayı değişeceği an tekrar güncelle. RTC (uyandırmayan) alarm: telefon uykudayken pil harcamaz.
        val at = when {
            mins == 0 -> next.second + 1000
            else -> next.second - (mins - 1) * 60_000L
        }.coerceAtLeast(now + 1000)
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) am.setExact(AlarmManager.RTC, at, tick) else am.set(AlarmManager.RTC, at, tick)
    }

    /** Durum çubuğunda görünecek, içinde sayı yazan küçük simge. */
    private fun numberBitmap(text: String, unit: String): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 64f
        }
        while (big.measureText(text) > size - 2 && big.textSize > 20f) big.textSize -= 2f
        val small = Paint(big).apply { textSize = 34f }
        while (small.measureText(unit) > size - 2 && small.textSize > 14f) small.textSize -= 2f
        // iki satırı dikeyde ortala
        val bigH = big.descent() - big.ascent()
        val smallH = small.descent() - small.ascent()
        val gap = -6f
        val total = bigH + gap + smallH
        var y = (size - total) / 2f - big.ascent()
        cv.drawText(text, size / 2f, y, big)
        y += big.descent() + gap - small.ascent()
        cv.drawText(unit, size / 2f, y, small)
        return bmp
    }

    // ---------------- zamanlama ----------------

    private fun pi(c: Context, action: String, code: Int, name: String? = null): PendingIntent {
        val i = Intent(c, PrayerReceiver::class.java).setAction(action)
        if (name != null) i.putExtra("ad", name)
        return PendingIntent.getBroadcast(
            c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setAlarm(c: Context, at: Long, p: PendingIntent) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
    }

    private fun cancelSilenceAlarm(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(c, ACTION_SILENCE, 1))
    }

    /** Bir sonraki ezan için alarm kurar. */
    fun schedule(c: Context) {
        cancelSilenceAlarm(c)
        if (!isEnabled(c)) return
        val next = nextEzan(c) ?: return
        setAlarm(c, next.second, pi(c, ACTION_SILENCE, 1, next.first))
    }

    // ---------------- sessize alma / geri alma ----------------

    /** @return yapılanı anlatan kısa metin */
    fun silenceNow(c: Context, name: String, minutesOverride: Int? = null): String {
        val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val p = prefs(c)
        val dur = minutesOverride ?: duration(c)
        // Kullanıcı zaten sessiz/titreşimdeyse dokunma
        if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            var target = if (mode(c) == "titresim") AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_SILENT
            try {
                audio.ringerMode = target
            } catch (e: SecurityException) {
                // Rahatsız Etmeyin izni yoksa en azından titreşime al
                target = AudioManager.RINGER_MODE_VIBRATE
                try { audio.ringerMode = target } catch (ignored: Exception) { return "Sessize alınamadı: izin yok." }
            }
            val until = System.currentTimeMillis() + dur * 60_000L
            p.edit()
                .putBoolean("biz_aldik", true)
                .putInt("bizim_mod", audio.ringerMode)
                .putLong("geri_al", until)
                .apply()
            setAlarm(c, until, pi(c, ACTION_RESTORE, 2))
            notify(
                c,
                "🕌 $name vakti",
                "Telefon ${if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) "sessizde" else "titreşimde"}; " +
                    "${fmt(until)}'de tekrar sesli olacak."
            )
            val how = if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) "sessize" else "titreşime"
            return "Telefon $how alındı; ${fmt(until)}'de tekrar sesli olacak."
        }
        return "Telefon zaten sessiz ya da titreşimde olduğu için dokunmadım. Önce sesli yapıp tekrar dene."
    }

    fun restore(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("biz_aldik", false)) return
        val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Kullanıcı bu arada modu kendisi değiştirdiyse ona dokunma
        if (audio.ringerMode == p.getInt("bizim_mod", -1)) {
            try { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (ignored: Exception) {}
        }
        p.edit().remove("biz_aldik").remove("bizim_mod").remove("geri_al").apply()
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(c, ACTION_RESTORE, 2))
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
    }

    /** Açılışta/yeniden başlatmada: geri alma zamanı geçtiyse geri al, değilse alarmı yeniden kur. */
    fun recover(c: Context) {
        val p = prefs(c)
        if (p.getBoolean("biz_aldik", false)) {
            val at = p.getLong("geri_al", 0)
            if (at <= System.currentTimeMillis()) restore(c) else setAlarm(c, at, pi(c, ACTION_RESTORE, 2))
        }
        schedule(c)
    }

    private fun notify(c: Context, title: String, text: String) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Namaz vakti sessiz", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            c, 3, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, "Şimdi sesli yap", pi(c, ACTION_RESTORE, 2)).build()
            )
            .setOngoing(true)
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (ignored: SecurityException) {}
    }

    internal fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ACTION_SILENCE -> {
                if (isEnabled(c)) silenceNow(c, i.getStringExtra("ad") ?: "Namaz")
                // Alarm biraz erken geldiyse aynı vakti tekrar kurmasın
                val now = System.currentTimeMillis()
                cancelSilenceAlarm(c)
                if (isEnabled(c)) {
                    val next = (0..2).asSequence().flatMap { ezans(c, it).asSequence() }
                        .firstOrNull { it.second > now + 60_000L }
                    if (next != null) setAlarm(c, next.second, pi(c, ACTION_SILENCE, 1, next.first))
                }
            }
            ACTION_RESTORE -> {
                restore(c)
            }
            ACTION_TICK -> updateCountdown(c)
            else -> { // açılış, saat/dilim değişimi, güncelleme
                recover(c)
                refreshExtras(c)
                Routines.schedule(c)
            }
        }
    }
}

class PrayerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Prayer.onReceive(context, intent)
}
