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
    /** name: listeden seçilen il (GPS'ten gelirse null) */
    fun setLocation(c: Context, lat: Double, lon: Double, name: String? = null) {
        val e = prefs(c).edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat())
        if (name != null) e.putString("konum_adi", name) else e.remove("konum_adi")
        e.apply()
        schedule(c)
        refreshExtras(c)
    }
    fun placeName(c: Context): String? = prefs(c).getString("konum_adi", null)
    fun locationText(c: Context) = when {
        !hasLocation(c) -> "Konum yok"
        placeName(c) != null -> "${placeName(c)} (il merkezi)"
        else -> "%.3f, %.3f".format(Locale.US, lat(c), lon(c))
    }

    // ---------------- son olay ve pil kısıtlaması ----------------

    /** Ne olduğunu kullanıcıya göstermek için son ezan olayını saklar. */
    private fun log(c: Context, text: String) {
        prefs(c).edit().putString("son_olay", text).apply()
        Diag.log(c, "namaz", text)
    }
    fun lastEvent(c: Context): String? = prefs(c).getString("son_olay", null)

    /** Samsung/Android pil tasarrufu uygulamayı uyutursa ezan alarmı gecikebilir. */
    fun batteryRestricted(c: Context): Boolean {
        val pm = c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return !pm.isIgnoringBatteryOptimizations(c.packageName)
    }

    /**
     * Alarm gecikti ya da hiç gelmediyse: şu an bir vaktin sessiz kalma süresi içindeysek
     * ve o vakit işlenmediyse, kalan süre kadar sessize al.
     */
    fun checkMissed(c: Context) {
        if (!isEnabled(c) || !hasLocation(c)) return
        val p = prefs(c)
        val now = System.currentTimeMillis()
        reassert(c)
        for ((name, t) in ezans(c, 0) + ezans(c, -1)) {
            val durMs = durationFor(c, name) * 60_000L
            if (t <= now && now < t + durMs - 60_000L && p.getLong("son_ezan", 0L) != t) {
                p.edit().putLong("son_ezan", t).apply()
                val left = Math.ceil((t + durMs - now) / 60_000.0).toInt()
                val r = silenceNow(c, name, left)
                log(c, "$name ${fmt(t)}: zamanında yakalanamadı, ${fmt(now)}'de yapıldı. $r")
                return
            }
        }
    }

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
        val t = PrayerTimes.forDay(day, lat(c), lon(c))
        for (i in 0 until 6) t[i] += offset(c, i) * 60_000L
        return t
    }

    /** Kullanıcının her vakit için verdiği dakika düzeltmesi (Diyanet takvimine tam uydurmak için). */
    fun offset(c: Context, i: Int) = prefs(c).getInt("duzeltme_$i", 0)
    fun setOffset(c: Context, i: Int, min: Int) = prefs(c).edit().putInt("duzeltme_$i", min.coerceIn(-30, 30)).apply()

    /** Cuma günü öğle vaktinde ayrı sessiz süresi (dk); 0 = diğer vakitlerle aynı. */
    fun cumaDuration(c: Context) = prefs(c).getInt("cuma_sure", 0)
    fun setCumaDuration(c: Context, min: Int) = prefs(c).edit().putInt("cuma_sure", min).apply()
    fun durationFor(c: Context, name: String) =
        if (name == "Cuma" && cumaDuration(c) > 0) cumaDuration(c) else duration(c)

    /** D-FON açıldığında konum belirgin değiştiyse vakitleri kendiliğinden güncelle (sadece GPS konumunda). */
    fun autoLocation(c: Context) = prefs(c).getBoolean("oto_konum", true)
    fun setAutoLocation(c: Context, on: Boolean) = prefs(c).edit().putBoolean("oto_konum", on).apply()

    /** Sessize alma anları (seçili vakitler), bugünden itibaren. */
    private fun ezans(c: Context, dayOffset: Int): List<Pair<String, Long>> {
        val t = timesFor(c, dayOffset) ?: return emptyList()
        val sel = selected(c)
        val friday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
            .get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        return SLOTS.filter { it.first in sel }.map { (name, idx) ->
            val extra = if (idx == PrayerTimes.IMSAK) sabahOffset(c) * 60_000L else 0L
            (if (friday && idx == PrayerTimes.OGLE) "Cuma" else name) to t[idx] + extra
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
        // 1 saatten az: "47" altında "dk"; fazlası sadece "2:15"
        val (top, unit) = if (mins < 60) "$mins" to "dk" else "${mins / 60}:${"%02d".format(mins % 60)}" to ""
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
            .setColor(0xFFE2B653.toInt()) // bildirim panelinde altın renk
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
    /**
     * Durum çubuğu simgesi: yazı karenin tamamını dolduracak kadar büyük çizilir.
     * Dar (condensed) kalın yazı + yatay sıkıştırma ile rakamlar olabildiğince uzun olur.
     */
    private fun numberBitmap(text: String, unit: String): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val face = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val r = android.graphics.Rect()

        /** Yazıyı (w × h) kutusuna sığacak en büyük boyda ayarlar; gerçek çizim sınırlarını döner. */
        fun fit(p: Paint, t: String, w: Int, h: Int): android.graphics.Rect {
            var ts = 160f
            while (ts > 10f) {
                p.textSize = ts
                p.getTextBounds(t, 0, t.length, r)
                if (r.width() <= w && r.height() <= h) break
                ts -= 1f
            }
            return android.graphics.Rect(r)
        }
        fun paint(scaleX: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = face
            textScaleX = scaleX
        }

        if (unit.isEmpty()) {
            // "3:53": yatayda daha çok sıkıştırıp boyu uzatıyoruz, kare yüksekliğini neredeyse dolduruyor
            val p = paint(0.58f)
            val b = fit(p, text, size, size - 2)
            // sınırlara göre tam ortala
            cv.drawText(text, (size - b.width()) / 2f - b.left, (size - b.height()) / 2f - b.top, p)
            return bmp
        }
        val gap = 3
        val big = paint(0.8f)
        val small = paint(0.9f)
        val ub = fit(small, unit, size - 8, 15)
        val nb = fit(big, text, size, size - ub.height() - gap - 2)
        val total = nb.height() + gap + ub.height()
        val top = (size - total) / 2f
        cv.drawText(text, (size - nb.width()) / 2f - nb.left, top - nb.top, big)
        cv.drawText(unit, (size - ub.width()) / 2f - ub.left, top + nb.height() + gap - ub.top, small)
        return bmp
    }

    // ---------------- zamanlama ----------------

    private fun pi(c: Context, action: String, code: Int, name: String? = null, at: Long = 0L): PendingIntent {
        val i = Intent(c, PrayerReceiver::class.java).setAction(action)
        if (name != null) i.putExtra("ad", name)
        if (at > 0) i.putExtra("zaman", at)
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

    /** Geri alma için: setAlarmClock hem tam zamanlıdır hem de Doze/izin durumundan etkilenmez. */
    private fun setRestoreAlarm(c: Context, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(
            c, 3, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(c, ACTION_RESTORE, 2))
        } catch (e: Exception) {
            setAlarm(c, at, pi(c, ACTION_RESTORE, 2))
        }
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
        setAlarm(c, next.second, pi(c, ACTION_SILENCE, 1, next.first, next.second))
    }

    // ---------------- sessize alma / geri alma ----------------

    /** @return yapılanı anlatan kısa metin */
    fun silenceNow(c: Context, name: String, minutesOverride: Int? = null): String {
        val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val p = prefs(c)
        val dur = minutesOverride ?: durationFor(c, name)
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
                .putInt("yeniden", 0)
                .putInt("bizim_mod", target)
                .putLong("geri_al", until)
                .apply()
            setRestoreAlarm(c, until)
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

    /** Sessiz süresi içinde telefon sesliye dönmüşse (başka bir ayar/uygulama çevirdiyse) tekrar al. */
    private fun reassert(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("biz_aldik", false)) return
        val now = System.currentTimeMillis()
        if (now >= p.getLong("geri_al", 0)) { restore(c); return }
        val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val want = p.getInt("bizim_mod", -1)
        val tries = p.getInt("yeniden", 0)
        if (want == -1 || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || tries >= 4) return
        p.edit().putInt("yeniden", tries + 1).apply()
        try {
            audio.ringerMode = want
            log(c, "Süre içinde telefon sesliye dönmüştü; tekrar ${if (want == AudioManager.RINGER_MODE_VIBRATE) "titreşime" else "sessize"} aldım (${tries + 1}).")
        } catch (e: Exception) {
            log(c, "Süre içinde sesliye dönüldü, tekrar alınamadı: ${e.message}")
        }
    }

    fun restore(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("biz_aldik", false)) return
        val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cur = audio.ringerMode
        val ours = p.getInt("bizim_mod", -1)
        var msg: String
        // Kullanıcı bu arada modu kendisi değiştirdiyse ona dokunma
        if (cur == AudioManager.RINGER_MODE_NORMAL) {
            msg = "Telefon zaten sesliydi."
        } else if (cur == ours || ours == -1) {
            try { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (e: Exception) { Diag.log(c, "namaz", "geri alma hatası: ${e.message}") }
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                // Samsung bazen ilk denemeyi yutuyor: sesi açma tuşu gibi dene
                try { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0) } catch (ignored: Exception) {}
                try { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_RAISE, 0) } catch (ignored: Exception) {}
                try { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (ignored: Exception) {}
            }
            msg = if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) "Telefon tekrar sesli yapıldı."
            else "Sesli yapılamadı (mod ${audio.ringerMode})."
        } else {
            msg = "Telefonun modunu sen değiştirmişsin, dokunmadım."
        }
        log(c, "Süre doldu: $msg")
        p.edit().remove("biz_aldik").remove("bizim_mod").remove("geri_al").apply()
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(c, ACTION_RESTORE, 2))
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
    }

    /** Açılışta/yeniden başlatmada: geri alma zamanı geçtiyse geri al, değilse alarmı yeniden kur. */
    fun recover(c: Context) {
        val p = prefs(c)
        if (p.getBoolean("biz_aldik", false)) {
            val at = p.getLong("geri_al", 0)
            if (at <= System.currentTimeMillis()) restore(c) else setRestoreAlarm(c, at)
        }
        checkMissed(c)
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
                val now = System.currentTimeMillis()
                val name = i.getStringExtra("ad") ?: "Namaz"
                val at = i.getLongExtra("zaman", now)
                if (isEnabled(c) && prefs(c).getLong("son_ezan", 0L) != at) {
                    prefs(c).edit().putLong("son_ezan", at).apply()
                    val late = (now - at) / 60_000L
                    val r = silenceNow(c, name)
                    log(c, "$name ${fmt(at)}: " + (if (late >= 2) "alarm $late dk gecikti. " else "") + r)
                }
                // Alarm biraz erken geldiyse aynı vakti tekrar kurmasın
                cancelSilenceAlarm(c)
                if (isEnabled(c)) {
                    val next = (0..2).asSequence().flatMap { ezans(c, it).asSequence() }
                        .firstOrNull { it.second > now + 60_000L }
                    if (next != null) setAlarm(c, next.second, pi(c, ACTION_SILENCE, 1, next.first, next.second))
                }
            }
            ACTION_RESTORE -> {
                restore(c)
            }
            ACTION_TICK -> {
                checkMissed(c)
                updateCountdown(c)
            }
            else -> { // açılış, saat/dilim değişimi, güncelleme
                recover(c)
                refreshExtras(c)
                Routines.schedule(c)
                Reminders.schedule(c)
                if (i.action == Intent.ACTION_BOOT_COMPLETED && WakeService.isEnabled(c)) {
                    WakeService.notifyStopped(c, "Telefon yeniden başladı: sesle uyandırma kapalı")
                }
            }
        }
    }
}

class PrayerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Prayer.onReceive(context, intent)
}
