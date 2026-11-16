package com.acelik.asistan

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.icu.util.IslamicCalendar
import android.location.Location
import android.location.LocationManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Namazla ilgili ek pencereler: kıble pusulası, 30 günlük çizelge, ince ayarlar, otomatik konum. */
object PrayerExtras {

    // ---------------- hicri tarih ----------------

    private val HIJRI_MONTHS = listOf(
        "Muharrem", "Safer", "Rebiülevvel", "Rebiülahir", "Cemaziyelevvel", "Cemaziyelahir",
        "Recep", "Şaban", "Ramazan", "Şevval", "Zilkade", "Zilhicce"
    )

    /** Yaklaşık hicri tarih (hesaba dayalı; Diyanet takvimiyle 1 gün fark edebilir). */
    fun hijri(time: Long = System.currentTimeMillis()): String = try {
        val cal = IslamicCalendar()
        cal.calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
        cal.timeInMillis = time
        "${cal.get(android.icu.util.Calendar.DAY_OF_MONTH)} ${HIJRI_MONTHS[cal.get(android.icu.util.Calendar.MONTH)]} " +
            "${cal.get(android.icu.util.Calendar.YEAR)}"
    } catch (e: Exception) {
        ""
    }

    // ---------------- kıble pusulası ----------------

    private fun direction(deg: Double): String {
        val names = listOf("kuzey", "kuzeydoğu", "doğu", "güneydoğu", "güney", "güneybatı", "batı", "kuzeybatı")
        return names[((deg + 22.5) / 45).toInt() % 8]
    }

    fun qibla(a: Activity) {
        if (!Prayer.hasLocation(a)) {
            Toast.makeText(a, "Önce konumunu bul ya da ilini seç.", Toast.LENGTH_LONG).show()
            return
        }
        val bearing = PrayerTimes.qibla(Prayer.lat(a), Prayer.lon(a))
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 20, 40, 10)
        }
        val info = TextView(a).apply {
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val size = (a.resources.displayMetrics.widthPixels * 0.68f).toInt()
        val compass = CompassView(a, bearing.toFloat(), Prayer.lat(a).toFloat(), Prayer.lon(a).toFloat()) { diff ->
            info.text = when {
                diff == null -> "Bu telefonda pusula sensörü bulunamadı.\nKıble: kuzeyden saat yönünde ${bearing.roundToInt()}° (${direction(bearing)})."
                abs(diff) < 4 -> "✅ Kıbleye dönüksün."
                diff > 0 -> "Sağa dön  (${abs(diff).roundToInt()}°)"
                else -> "Sola dön  (${abs(diff).roundToInt()}°)"
            }
        }
        box.addView(compass, LinearLayout.LayoutParams(size, size))
        box.addView(info)
        box.addView(TextView(a).apply {
            text = "Kıble açısı: ${bearing.roundToInt()}° (${direction(bearing)}) · ${Prayer.locationText(a)}\n" +
                "Telefonu yere paralel tut. Ok yukarıyı gösterip halka yeşile dönünce kıbleye dönüksün. " +
                "Pusula şaşıyorsa telefonu havada 8 çizerek birkaç kez döndür; metal eşyalardan uzak tut."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        })
        AlertDialog.Builder(a).setTitle("🧭 Kıble pusulası").setView(box).setPositiveButton("Kapat", null).show()
    }

    /** Pusula: Kâbe yönünü gösteren ok. onDiff: kıbleye göre sapma (derece; + sağa dön), sensör yoksa null. */
    private class CompassView(
        c: Context, private val bearing: Float, lat: Float, lon: Float, private val onDiff: (Float?) -> Unit
    ) : View(c), SensorEventListener {
        private val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        private val declination = try {
            GeomagneticField(lat, lon, 0f, System.currentTimeMillis()).declination
        } catch (e: Exception) {
            0f
        }
        private var heading = 0f          // telefonun üstünün baktığı yön (gerçek kuzeyden)
        private var has = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rot = FloatArray(9)
        private val ori = FloatArray(3)

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) else onDiff(null)
        }

        override fun onDetachedFromWindow() {
            sm.unregisterListener(this)
            super.onDetachedFromWindow()
        }

        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            SensorManager.getOrientation(rot, ori)
            val h = ((Math.toDegrees(ori[0].toDouble()).toFloat() + declination) % 360 + 360) % 360
            // yumuşat (0/360 geçişine dikkat ederek)
            var d = h - heading
            if (d > 180) d -= 360
            if (d < -180) d += 360
            heading = if (has) ((heading + d * 0.2f) % 360 + 360) % 360 else h
            has = true
            var diff = bearing - heading
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            onDiff(diff)
            invalidate()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat()
            val cx = w / 2
            val r = w / 2 - 12
            var diff = bearing - heading
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            val ok = has && abs(diff) < 4
            p.style = Paint.Style.FILL
            p.color = 0xFF10202E.toInt()
            cv.drawCircle(cx, cx, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 8f
            p.color = if (ok) 0xFF2ECC71.toInt() else 0xFFE2B653.toInt()
            cv.drawCircle(cx, cx, r, p)

            // pusula gülü: telefon döndükçe ters yönde döner
            cv.save()
            cv.rotate(-heading, cx, cx)
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = r * 0.14f
            val letters = listOf("K", "D", "G", "B")
            for (i in 0 until 4) {
                cv.save()
                cv.rotate(i * 90f, cx, cx)
                p.color = if (i == 0) 0xFFFF6B6B.toInt() else Color.WHITE
                cv.drawText(letters[i], cx, cx - r + r * 0.2f, p)
                cv.restore()
            }
            p.color = 0x55FFFFFF
            p.strokeWidth = 3f
            for (i in 0 until 24) {
                cv.save()
                cv.rotate(i * 15f, cx, cx)
                cv.drawLine(cx, cx - r + 6, cx, cx - r + 20, p)
                cv.restore()
            }
            // Kâbe oku
            cv.rotate(bearing, cx, cx)
            p.color = if (ok) 0xFF2ECC71.toInt() else 0xFFFFD166.toInt()
            val arrow = Path().apply {
                moveTo(cx, cx - r * 0.72f)
                lineTo(cx - r * 0.13f, cx - r * 0.1f)
                lineTo(cx, cx - r * 0.2f)
                lineTo(cx + r * 0.13f, cx - r * 0.1f)
                close()
            }
            cv.drawPath(arrow, p)
            p.color = Color.BLACK
            cv.drawRect(cx - r * 0.07f, cx - r * 0.62f, cx + r * 0.07f, cx - r * 0.48f, p)
            p.color = 0xFFFFD166.toInt()
            cv.drawRect(cx - r * 0.07f, cx - r * 0.58f, cx + r * 0.07f, cx - r * 0.555f, p)
            cv.restore()

            // telefonun baktığı yön işareti (üstte sabit)
            p.color = Color.WHITE
            val mark = Path().apply {
                moveTo(cx, 2f); lineTo(cx - 14, 26f); lineTo(cx + 14, 26f); close()
            }
            cv.drawPath(mark, p)
        }
    }

    // ---------------- 30 günlük çizelge ----------------

    fun monthly(a: Activity) {
        if (!Prayer.hasLocation(a)) {
            Toast.makeText(a, "Önce konumunu bul ya da ilini seç.", Toast.LENGTH_LONG).show()
            return
        }
        val day = SimpleDateFormat("dd.MM EEE", Locale("tr"))
        val sb = StringBuilder()
        sb.append("${Prayer.locationText(a)}\nHicri (yaklaşık): ${hijri()}\n\n")
        sb.append("Tarih       İmsak Güneş Öğle  İkindi Akşam Yatsı\n")
        for (d in 0 until 30) {
            val t = Prayer.timesFor(a, d) ?: break
            sb.append(day.format(Date(t[2])).padEnd(11))
            for (i in 0 until 6) sb.append(' ').append(Prayer.fmt(t[i]).padEnd(if (i == 3) 6 else 5))
            sb.append('\n')
        }
        sb.append("\nVakitler konumuna göre telefonda hesaplanır; Diyanet takvimiyle 1-3 dakika fark olabilir.")
        val text = sb.toString()
        val tv = TextView(a).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
        }
        AlertDialog.Builder(a)
            .setTitle("🗓 30 günlük vakitler")
            .setView(ScrollView(a).apply { addView(android.widget.HorizontalScrollView(a).apply { addView(tv) }) })
            .setPositiveButton("Kapat", null)
            .setNeutralButton("Paylaş") { _, _ ->
                a.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Vakit çizelgesi"
                    )
                )
            }
            .setNegativeButton("Kopyala") { _, _ ->
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Namaz vakitleri", text))
            }
            .show()
    }

    // ---------------- ince ayar ----------------

    fun advanced(a: Activity, done: () -> Unit) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 16, 50, 0)
        }
        fun label(t: String) = TextView(a).apply {
            text = t
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 24, 0, 6)
        }
        box.addView(label("Dakika düzeltmesi (Diyanet takvimine tam uydurmak için; − erken, + geç)"))
        val today = Prayer.timesFor(a, 0)
        val fields = (0 until 6).map { i ->
            val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
            val shown = today?.let { Prayer.fmt(it[i]) } ?: "--:--"
            row.addView(TextView(a).apply { text = "${PrayerTimes.NAMES[i]}  ($shown)" }, LinearLayout.LayoutParams(0, -2, 1f))
            val f = EditText(a).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(Prayer.offset(a, i).toString())
                gravity = Gravity.CENTER
            }
            row.addView(f, LinearLayout.LayoutParams(160, -2))
            row.addView(TextView(a).apply { text = " dk" })
            box.addView(row)
            f
        }
        box.addView(label("Cuma günü öğle vaktinde sessiz süresi"))
        val cuma = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL }
        val cumaOpts = listOf(0, 45, 60, 75, 90)
        cumaOpts.forEach { m ->
            cuma.addView(RadioButton(a).apply {
                text = if (m == 0) "Aynı" else "$m"
                id = View.generateViewId()
                tag = m
                isChecked = m == Prayer.cumaDuration(a)
            })
        }
        box.addView(cuma)
        val auto = CheckBox(a).apply {
            text = "D-FON'u açtığımda konumum değiştiyse vakitleri kendiliğinden güncelle (il seçiliyken çalışmaz)"
            isChecked = Prayer.autoLocation(a)
        }
        box.addView(auto)
        AlertDialog.Builder(a)
            .setTitle("🛠 Namaz ince ayarları")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Kaydet") { _, _ ->
                fields.forEachIndexed { i, f -> Prayer.setOffset(a, i, f.text.toString().trim().toIntOrNull() ?: 0) }
                val chosen = (0 until cuma.childCount).map { cuma.getChildAt(it) as RadioButton }.firstOrNull { it.isChecked }?.tag as? Int
                Prayer.setCumaDuration(a, chosen ?: 0)
                Prayer.setAutoLocation(a, auto.isChecked)
                Prayer.schedule(a)
                Prayer.refreshExtras(a)
                done()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---------------- otomatik konum ----------------

    /** D-FON açıldığında: telefonun son bildiği konum kayıtlı konumdan 15 km'den uzaksa vakitleri güncelle. */
    fun maybeRefreshLocation(a: Activity): String? {
        try {
            if (!Prayer.hasLocation(a) || Prayer.placeName(a) != null || !Prayer.autoLocation(a)) return null
            if (a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
            val lm = a.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val last = lm.getProviders(true).mapNotNull {
                try { lm.getLastKnownLocation(it) } catch (e: SecurityException) { null }
            }.maxByOrNull { it.time } ?: return null
            if (System.currentTimeMillis() - last.time > 6 * 3600_000L) return null
            val dist = FloatArray(1)
            Location.distanceBetween(Prayer.lat(a), Prayer.lon(a), last.latitude, last.longitude, dist)
            if (dist[0] < 15_000f) return null
            Prayer.setLocation(a, last.latitude, last.longitude)
            Diag.log(a, "namaz", "Konum otomatik güncellendi (${(dist[0] / 1000).roundToInt()} km)")
            return "📍 Konumun ${(dist[0] / 1000).roundToInt()} km değişmiş; namaz vakitleri yeni konuma göre güncellendi."
        } catch (e: Exception) {
            return null
        }
    }
}
