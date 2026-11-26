package com.acelik.asistan

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ana ekran namaz vakitleri kutucuğu: sıradaki vakit, canlı geri sayım ve günün 6 vakti. */
class PrayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, manager, intArrayOf(id))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) updateAll(context)
    }

    override fun onDisabled(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(refreshIntent(context))
    }

    companion object {
        private const val ACTION_REFRESH = "com.acelik.asistan.WIDGET_YENILE"
        private val NAME_IDS = intArrayOf(R.id.w_n0, R.id.w_n1, R.id.w_n2, R.id.w_n3, R.id.w_n4, R.id.w_n5)
        private val TIME_IDS = intArrayOf(R.id.w_t0, R.id.w_t1, R.id.w_t2, R.id.w_t3, R.id.w_t4, R.id.w_t5)
        private val CELL_IDS = intArrayOf(R.id.w_c0, R.id.w_c1, R.id.w_c2, R.id.w_c3, R.id.w_c4, R.id.w_c5)
        private val WHITE = 0xFFFFFFFF.toInt()

        private fun refreshIntent(c: Context) = PendingIntent.getBroadcast(
            c, 21, Intent(c, PrayerWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun updateAll(c: Context) {
            val m = AppWidgetManager.getInstance(c) ?: return
            val ids = m.getAppWidgetIds(ComponentName(c, PrayerWidget::class.java))
            if (ids.isNotEmpty()) update(c, m, ids)
        }

        fun update(c: Context, m: AppWidgetManager, ids: IntArray) {
            for (id in ids) updateOne(c, m, id)
        }

        private fun updateOne(c: Context, m: AppWidgetManager, id: Int) {
            val v = RemoteViews(c.packageName, R.layout.widget_prayer)
            val theme = WidgetArt.theme(c)
            val scale = WidgetArt.textScale(c)

            // Widget'ın gerçek boyutu (dp) → süslü arka planı o boyutta çiz
            val o = m.getAppWidgetOptions(id)
            val density = c.resources.displayMetrics.density
            val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
            val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 180
            var bw = (wDp * density).toInt()
            var bh = (hDp * density).toInt()
            val maxPx = 900_000f
            if (bw * bh > maxPx) {
                val k = Math.sqrt((maxPx / (bw * bh)).toDouble()).toFloat()
                bw = (bw * k).toInt(); bh = (bh * k).toInt()
            }
            v.setImageViewBitmap(R.id.w_bg, WidgetArt.draw(theme, bw, bh))
            v.setInt(R.id.w_root, "setBackgroundResource", 0)

            // Süslerle çakışmasın diye yazı boşlukları (tasarım birimi → px)
            val u = minOf(wDp / 440f, hDp / 250f) * density
            v.setViewPadding(R.id.w_content, (20 * u).toInt(), (theme.padTop * u).toInt(), (20 * u).toInt(), (16 * u).toInt())
            v.setViewPadding(R.id.w_head, (theme.headSide * u).toInt(), 0, (theme.headSide * u).toInt(), 0)

            fun size(viewId: Int, sp: Float) = v.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, sp * scale)
            size(R.id.w_date, 12f); size(R.id.w_next, 22f); size(R.id.w_next_time, 15f)
            size(R.id.w_count, 26f); size(R.id.w_left, 11f)
            for (i in 0 until 6) { size(NAME_IDS[i], 11f); size(TIME_IDS[i], 14f) }
            v.setTextColor(R.id.w_date, theme.sub)
            v.setTextColor(R.id.w_left, theme.sub)
            v.setTextColor(R.id.w_next_time, theme.accent)
            v.setTextColor(R.id.w_count, theme.accent)
            val open = PendingIntent.getActivity(
                c, 20, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            val now = System.currentTimeMillis()
            v.setTextViewText(R.id.w_date, SimpleDateFormat("d MMMM EEEE", Locale("tr")).format(Date(now)))

            val today = Prayer.timesFor(c, 0)
            if (today == null) {
                v.setTextViewText(R.id.w_next, "Konum yok")
                v.setTextViewText(R.id.w_next_time, "")
                v.setTextViewText(R.id.w_left, "D-FON'da 📍 Konumu bul")
                v.setViewVisibility(R.id.w_count, View.GONE)
                for (i in 0 until 6) {
                    v.setTextViewText(NAME_IDS[i], PrayerTimes.NAMES[i])
                    v.setTextViewText(TIME_IDS[i], "--:--")
                }
                m.updateAppWidget(id, v)
                return
            }

            val idx = today.indexOfFirst { it > now }
            val (name, at) = if (idx >= 0) PrayerTimes.NAMES[idx] to today[idx]
            else PrayerTimes.NAMES[0] to (Prayer.timesFor(c, 1)?.get(0) ?: (now + 3600_000L))
            v.setTextViewText(R.id.w_next, name)
            v.setTextViewText(R.id.w_next_time, Prayer.fmt(at))
            v.setTextViewText(R.id.w_left, "kaldı")
            v.setViewVisibility(R.id.w_count, View.VISIBLE)
            v.setChronometer(R.id.w_count, SystemClock.elapsedRealtime() + (at - now), null, true)
            v.setChronometerCountDown(R.id.w_count, true)

            for (i in 0 until 6) {
                val on = i == idx
                v.setTextViewText(NAME_IDS[i], PrayerTimes.NAMES[i])
                v.setTextViewText(TIME_IDS[i], Prayer.fmt(today[i]))
                v.setInt(CELL_IDS[i], "setBackgroundResource", if (on) theme.onCellRes else R.drawable.widget_cell_off)
                v.setTextColor(NAME_IDS[i], if (on) theme.onFg else theme.sub)
                v.setTextColor(TIME_IDS[i], if (on) theme.onFg else WHITE)
            }
            m.updateAppWidget(id, v)

            // Vakit girince (ve gece yarısı) kendini yenile
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val p = refreshIntent(c)
            val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            // Ayasofya sahnesi gün içinde değiştiği için ara geçişlerde de yenile
            var next = at + 1000
            if (theme.id == "foto_ayasofya") {
                listOf(today[1] + 45 * 60_000L, today[4] - 60 * 60_000L).filter { it > now + 1000 }.minOrNull()
                    ?.let { if (it < next) next = it + 1000 }
            }
            if (exactOk) am.setExact(AlarmManager.RTC, next, p) else am.set(AlarmManager.RTC, next, p)
        }
    }
}
