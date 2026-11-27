package com.acelik.asistan

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ana ekran hava durumu widget'ı. Veri: Open-Meteo (ücretsiz, hesap/anahtar istemez).
 * Konum, namaz vakitleri için kaydedilen konumdur. Arka plan resmi havaya göre değişir,
 * hava durumu ayrıca simgeyle ve yazıyla gösterilir.
 */
class WeatherWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context, false)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) =
        refresh(context, false)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) refresh(context, true) else super.onReceive(context, intent)
    }

    private fun refresh(c: Context, force: Boolean) {
        val pr = goAsync()
        Thread {
            try { updateAll(c, force) } catch (e: Exception) { Diag.log(c, "hava", "widget hatası: ${e.message}") }
            finally { pr.finish() }
        }.start()
    }

    data class Kind(val text: String, val icon: String, val day: Int, val night: Int)

    companion object {
        private const val ACTION_REFRESH = "com.acelik.asistan.HAVA_YENILE"
        private val CELL = intArrayOf(R.id.h_c0, R.id.h_c1, R.id.h_c2, R.id.h_c3)
        private val DAY = intArrayOf(R.id.h_d0, R.id.h_d1, R.id.h_d2, R.id.h_d3)
        private val ICON = intArrayOf(R.id.h_i0, R.id.h_i1, R.id.h_i2, R.id.h_i3)
        private val TEMP = intArrayOf(R.id.h_t0, R.id.h_t1, R.id.h_t2, R.id.h_t3)

        private fun prefs(c: Context) = c.getSharedPreferences("dfon", Context.MODE_PRIVATE)

        /** WMO hava kodu → Türkçe ad, simge, gündüz ve gece resmi */
        fun kind(code: Int, windKmh: Double = 0.0): Kind = when (code) {
            0 -> if (windKmh >= 35) Kind("Açık, rüzgârlı", "💨", R.drawable.hava_ruzgar, R.drawable.hava_gece)
                 else Kind("Açık", "☀️", R.drawable.hava_gunes, R.drawable.hava_gece)
            1 -> if (windKmh >= 35) Kind("Az bulutlu, rüzgârlı", "💨", R.drawable.hava_ruzgar, R.drawable.hava_gece)
                 else Kind("Az bulutlu", "🌤️", R.drawable.hava_parcali, R.drawable.hava_gece)
            2 -> if (windKmh >= 35) Kind("Parçalı bulutlu, rüzgârlı", "💨", R.drawable.hava_ruzgar, R.drawable.hava_gece)
                 else Kind("Parçalı bulutlu", "⛅", R.drawable.hava_parcali, R.drawable.hava_gece)
            3 -> Kind(if (windKmh >= 35) "Kapalı, rüzgârlı" else "Kapalı", "☁️", R.drawable.hava_kapali, R.drawable.hava_gece)
            45, 48 -> Kind("Sisli", "🌫️", R.drawable.hava_sis, R.drawable.hava_sis)
            51, 53, 55, 56, 57 -> Kind("Çisenti", "🌦️", R.drawable.hava_yagmur, R.drawable.hava_gece_yagmur)
            61, 63, 80, 81 -> Kind("Yağmurlu", "🌧️", R.drawable.hava_yagmur, R.drawable.hava_gece_yagmur)
            65, 82 -> Kind("Kuvvetli yağmur", "🌧️", R.drawable.hava_yagmur, R.drawable.hava_gece_yagmur)
            66, 67 -> Kind("Dondurucu yağmur", "🌧️", R.drawable.hava_yagmur, R.drawable.hava_gece_yagmur)
            71, 73, 75, 77, 85, 86 -> Kind("Karlı", "❄️", R.drawable.hava_kar, R.drawable.hava_kar)
            95, 96, 99 -> Kind("Gök gürültülü sağanak", "⛈️", R.drawable.hava_firtina, R.drawable.hava_firtina)
            else -> Kind("Bulutlu", "☁️", R.drawable.hava_kapali, R.drawable.hava_gece)
        }

        private fun nightIcon(k: Kind): String = when (k.icon) { "☀️", "🌤️" -> "🌙"; "⛅" -> "☁️"; else -> k.icon }

        private fun fetch(c: Context): JSONObject? {
            if (!Prayer.hasLocation(c)) return null
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(Locale.US, Prayer.lat(c), Prayer.lon(c)) +
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=5"
            )
            val con = url.openConnection() as HttpURLConnection
            con.connectTimeout = 10_000; con.readTimeout = 10_000
            return try {
                if (con.responseCode != 200) null
                else JSONObject(con.inputStream.bufferedReader().readText())
            } finally { con.disconnect() }
        }

        /** Önbellek 25 dk'dan eskiyse (ya da istenirse) yeniden çeker, sonra tüm widget'ları çizer. */
        fun updateAll(c: Context, force: Boolean) {
            val m = AppWidgetManager.getInstance(c) ?: return
            val ids = m.getAppWidgetIds(ComponentName(c, WeatherWidget::class.java))
            if (ids.isEmpty()) return
            val p = prefs(c)
            val age = System.currentTimeMillis() - p.getLong("hava_zaman", 0)
            var err: String? = null
            if (force || age > 25 * 60_000L) {
                try {
                    val j = fetch(c)
                    if (j != null) p.edit().putString("hava_json", j.toString()).putLong("hava_zaman", System.currentTimeMillis()).apply()
                    else if (!Prayer.hasLocation(c)) err = "Konum yok · D-FON'da 📍 Konumu bul"
                } catch (e: Exception) { err = "İnternet yok · dokun, yenile" }
            }
            val json = p.getString("hava_json", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }
            for (id in ids) draw(c, m, id, json, err)
        }

        private fun draw(c: Context, m: AppWidgetManager, id: Int, j: JSONObject?, err: String?) {
            val v = RemoteViews(c.packageName, R.layout.widget_weather)
            val tap = PendingIntent.getBroadcast(c, 31, Intent(c, WeatherWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.h_root, tap)

            val o = m.getAppWidgetOptions(id)
            val d = c.resources.displayMetrics.density
            val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
            val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 180
            var bw = (wDp * d).toInt(); var bh = (hDp * d).toInt()
            if (bw * bh > 900_000) { val k = Math.sqrt(900_000.0 / (bw * bh)); bw = (bw * k).toInt(); bh = (bh * k).toInt() }

            if (j == null) {
                v.setImageViewBitmap(R.id.h_bg, WidgetArt.drawPhoto(c, R.drawable.hava_parcali, bw, bh))
                v.setTextViewText(R.id.h_temp, "--°")
                v.setTextViewText(R.id.h_desc, "Hava bilgisi yok")
                v.setTextViewText(R.id.h_sub, err ?: "Dokun, yenile")
                v.setTextViewText(R.id.h_icon, "🌡️")
                for (i in 0 until 4) v.setViewVisibility(CELL[i], View.INVISIBLE)
                m.updateAppWidget(id, v); return
            }
            val cur = j.getJSONObject("current")
            val code = cur.optInt("weather_code", 3)
            val wind = cur.optDouble("wind_speed_10m", 0.0)
            val isDay = cur.optInt("is_day", 1) == 1
            val k = kind(code, wind)
            v.setImageViewBitmap(R.id.h_bg, WidgetArt.drawPhoto(c, if (isDay) k.day else k.night, bw, bh))
            v.setTextViewText(R.id.h_temp, "${cur.optDouble("temperature_2m").roundToInt()}°")
            v.setTextViewText(R.id.h_desc, k.text)
            v.setTextViewText(R.id.h_icon, if (isDay) k.icon else nightIcon(k))

            val daily = j.getJSONObject("daily")
            val codes = daily.getJSONArray("weather_code")
            val max = daily.getJSONArray("temperature_2m_max")
            val min = daily.getJSONArray("temperature_2m_min")
            val dates = daily.getJSONArray("time")
            val place = Prayer.placeName(c)?.substringBefore(",")?.trim()
            val feels = cur.optDouble("apparent_temperature").roundToInt()
            val hi = max.optDouble(0).roundToInt(); val lo = min.optDouble(0).roundToInt()
            val upd = SimpleDateFormat("HH:mm", Locale("tr")).format(java.util.Date(prefs(c).getLong("hava_zaman", 0)))
            v.setTextViewText(R.id.h_sub, listOfNotNull(place, "↑$hi° ↓$lo°", "hissedilen $feels°").joinToString(" · ") +
                (if (err != null) "  ($upd)" else ""))

            val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val dayFmt = SimpleDateFormat("EEE", Locale("tr"))
            for (i in 0 until 4) {
                val n = i + 1
                if (n >= dates.length()) { v.setViewVisibility(CELL[i], View.INVISIBLE); continue }
                v.setViewVisibility(CELL[i], View.VISIBLE)
                val name = if (n == 1) "Yarın" else try { dayFmt.format(inFmt.parse(dates.getString(n))!!) } catch (e: Exception) { "" }
                v.setTextViewText(DAY[i], name)
                v.setTextViewText(ICON[i], kind(codes.optInt(n, 3)).icon)
                v.setTextViewText(TEMP[i], "${max.optDouble(n).roundToInt()}° / ${min.optDouble(n).roundToInt()}°")
            }
            m.updateAppWidget(id, v)
        }
    }
}
