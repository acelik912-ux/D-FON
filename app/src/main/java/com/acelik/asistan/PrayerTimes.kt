package com.acelik.asistan

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Namaz vakitlerini konumdan, internetsiz hesaplar (Diyanet yöntemi):
 * imsak 18°, yatsı 17°, ikindi gölge 1 boy; Diyanet temkinleri
 * (güneş −7, öğle +5, ikindi +4, akşam +7 dk). Diyanet takvimiyle 1-3 dk fark olabilir.
 */
object PrayerTimes {
    const val IMSAK = 0
    const val GUNES = 1
    const val OGLE = 2
    const val IKINDI = 3
    const val AKSAM = 4
    const val YATSI = 5
    val NAMES = listOf("İmsak", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı")

    private val ADJUST = doubleArrayOf(0.0, -7.0, 5.0, 4.0, 7.0, 0.0)

    /** Verilen günün vakitleri (epoch milisaniye), telefonun saat dilimine göre. */
    fun forDay(day: Calendar, lat: Double, lon: Double): LongArray {
        val y = day.get(Calendar.YEAR)
        val m = day.get(Calendar.MONTH) + 1
        val d = day.get(Calendar.DAY_OF_MONTH)
        val midnight = Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, 0, 0, 0)
        }.timeInMillis
        val tz = TimeZone.getDefault().getOffset(midnight + 12 * 3600_000L) / 3600_000.0
        val mins = minutes(y, m, d, lat, lon, tz)
        return LongArray(6) { midnight + mins[it] * 60_000L }
    }

    /** Gece yarısından itibaren dakika. */
    fun minutes(y: Int, m: Int, d: Int, lat: Double, lon: Double, tz: Double): IntArray {
        val jd = julian(y, m, d) - lon / (15.0 * 24.0)

        fun midDay(t: Double) = fix(12 - sunPosition(jd + t)[1], 24.0)
        fun sunAngleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sunPosition(jd + t)[0]
            val noon = midDay(t)
            val c = (-sind(angle) - sind(decl) * sind(lat)) / (cosd(decl) * cosd(lat))
            val tt = acosd(c.coerceIn(-1.0, 1.0)) / 15.0
            return if (ccw) noon - tt else noon + tt
        }
        fun asr(factor: Double, t: Double): Double {
            val decl = sunPosition(jd + t)[0]
            val angle = -acotd(factor + tand(abs(lat - decl)))
            return sunAngleTime(angle, t, false)
        }

        var t = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0)
        repeat(2) {
            val p = DoubleArray(6) { t[it] / 24.0 }
            t = doubleArrayOf(
                sunAngleTime(18.0, p[0], true),
                sunAngleTime(0.833, p[1], true),
                midDay(p[2]),
                asr(1.0, p[3]),
                sunAngleTime(0.833, p[4], false),
                sunAngleTime(17.0, p[5], false)
            )
        }
        return IntArray(6) { i ->
            val h = t[i] + tz - lon / 15.0 + ADJUST[i] / 60.0
            ((h * 60).roundToInt() % 1440 + 1440) % 1440
        }
    }

    /** Kıble açısı: gerçek kuzeyden saat yönünde derece (Kâbe: 21.4225 K, 39.8262 D). */
    fun qibla(lat: Double, lon: Double): Double {
        val kLat = 21.4225
        val kLon = 39.8262
        val dLon = kLon - lon
        val y = sind(dLon) * cosd(kLat)
        val x = cosd(lat) * sind(kLat) - sind(lat) * cosd(kLat) * cosd(dLon)
        return fix(atan2d(y, x), 360.0)
    }

    /** [eğim (derece), zaman denklemi (saat)] */
    private fun sunPosition(jd: Double): DoubleArray {
        val dd = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * dd, 360.0)
        val q = fix(280.459 + 0.98564736 * dd, 360.0)
        val l = fix(q + 1.915 * sind(g) + 0.020 * sind(2 * g), 360.0)
        val e = 23.439 - 0.00000036 * dd
        val ra = fix(atan2d(cosd(e) * sind(l), cosd(l)) / 15.0, 24.0)
        val eqt = q / 15.0 - ra
        val decl = asind(sind(e) * sind(l))
        return doubleArrayOf(decl, eqt)
    }

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private const val D2R = Math.PI / 180.0
    private fun fix(a: Double, b: Double): Double = a - b * floor(a / b)
    private fun sind(x: Double) = sin(x * D2R)
    private fun cosd(x: Double) = cos(x * D2R)
    private fun tand(x: Double) = tan(x * D2R)
    private fun asind(x: Double) = asin(x) / D2R
    private fun acosd(x: Double) = acos(x) / D2R
    private fun atan2d(y: Double, x: Double) = atan2(y, x) / D2R
    private fun acotd(x: Double) = atan(1 / x) / D2R
}
