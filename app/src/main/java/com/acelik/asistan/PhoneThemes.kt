package com.acelik.asistan

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Telefon temaları: duvar kağıdı + simge/widget çerçevesinin LED rengi + uyumlu namaz widget'ı.
 * Duvar kağıtları uygulamanın içinde; internet gerekmez.
 */
object PhoneThemes {

    class Theme(val id: String, val name: String, val res: Int, val led: String, val widget: String)

    val ALL = listOf(
        Theme("altin_turkuaz_led", "Altın & Turkuaz LED", R.drawable.wp_altin_turkuaz_led, "turkuaz", "led"),
        Theme("turkuaz_led", "Turkuaz LED", R.drawable.wp_turkuaz_led, "turkuaz", "led"),
        Theme("altin_led", "Altın LED", R.drawable.wp_altin_led, "altin", "led_altin"),
        Theme("altin_gece", "Altın Gece", R.drawable.wp_altin_gece, "turkuaz", "altin"),
        Theme("turkuaz_cini", "Turkuaz Çini", R.drawable.wp_turkuaz_cini, "turkuaz", "cini"),
    )

    fun current(a: Activity): String? = a.getSharedPreferences("dfon", Activity.MODE_PRIVATE).getString("telefon_tema", null)

    /** Tema seçme penceresi. done: uygulandıktan sonra (ör. ana ekranı yenilemek için). */
    fun show(a: Activity, done: (() -> Unit)? = null) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var selected = current(a) ?: ALL[0].id
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        fun title(t: String) = TextView(a).apply {
            text = t
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }
        box.addView(title("Duvar kağıdı"))
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val thumbs = ALL.map { t ->
            val iv = ImageView(a).apply {
                val o = BitmapFactory.Options().apply { inSampleSize = 8 }
                setImageBitmap(BitmapFactory.decodeResource(a.resources, t.res, o))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(dp(3), dp(3), dp(3), dp(3))
                contentDescription = t.name
            }
            val cell = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(iv, LinearLayout.LayoutParams(dp(92), dp(200)))
                addView(TextView(a).apply {
                    text = t.name
                    textSize = 11f
                    gravity = Gravity.CENTER
                    width = dp(92)
                })
                setPadding(dp(3), 0, dp(3), 0)
            }
            row.addView(cell)
            Triple(t, iv, cell)
        }
        box.addView(android.widget.HorizontalScrollView(a).apply { addView(row) })

        box.addView(title("Simge ve widget çerçevesi: altın çizginin altındaki LED"))
        val ledGroup = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL }
        val rT = RadioButton(a).apply { text = "Turkuaz"; id = android.view.View.generateViewId() }
        val rA = RadioButton(a).apply { text = "Altın"; id = android.view.View.generateViewId() }
        val rN = RadioButton(a).apply { text = "LED yok"; id = android.view.View.generateViewId() }
        ledGroup.addView(rT); ledGroup.addView(rA); ledGroup.addView(rN)
        box.addView(ledGroup)

        val home = CheckBox(a).apply { text = "Ana ekran duvar kağıdı"; isChecked = true }
        val lock = CheckBox(a).apply { text = "Kilit ekranı duvar kağıdı"; isChecked = true }
        val widget = CheckBox(a).apply { text = "Namaz widget'ını uyumlu yap"; isChecked = true }
        box.addView(title("Nereye uygulansın?"))
        box.addView(home); box.addView(lock); box.addView(widget)

        fun render() {
            thumbs.forEach { (t, iv, _) ->
                iv.setBackgroundColor(if (t.id == selected) 0xFFFFD166.toInt() else Color.TRANSPARENT)
            }
            val led = ALL.first { it.id == selected }.led
            if (!rN.isChecked) (if (led == "altin") rA else rT).isChecked = true
        }
        thumbs.forEach { (t, _, cell) -> cell.setOnClickListener { selected = t.id; render() } }
        when (LedStyle.ledName(a)) { "altin" -> rA.isChecked = true; "yok" -> rN.isChecked = true; else -> rT.isChecked = true }
        render()

        AlertDialog.Builder(a)
            .setTitle("🎨 Telefon teması")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Uygula") { _, _ ->
                val t = ALL.first { it.id == selected }
                val ledName = when { rA.isChecked -> "altin"; rN.isChecked -> "yok"; else -> "turkuaz" }
                apply(a, t, ledName, home.isChecked, lock.isChecked, widget.isChecked, done)
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    fun apply(a: Activity, t: Theme, led: String, home: Boolean, lock: Boolean, widget: Boolean, done: (() -> Unit)?) {
        a.getSharedPreferences("dfon", Activity.MODE_PRIVATE).edit().putString("telefon_tema", t.id).apply()
        LedStyle.setLed(a, led)
        if (widget) {
            WidgetArt.setTheme(a, t.widget)
            PrayerWidget.updateAll(a)
        }
        Toast.makeText(a, "Tema uygulanıyor…", Toast.LENGTH_SHORT).show()
        Thread {
            var flags = 0
            if (home) flags = flags or WallpaperManager.FLAG_SYSTEM
            if (lock) flags = flags or WallpaperManager.FLAG_LOCK
            val ok = try {
                if (flags != 0) WallpaperManager.getInstance(a).setResource(t.res, flags)
                true
            } catch (e: Exception) {
                false
            }
            a.runOnUiThread {
                Toast.makeText(a, if (ok) "🎨 ${t.name} uygulandı" else "Duvar kağıdı ayarlanamadı", Toast.LENGTH_LONG).show()
                done?.invoke()
            }
        }.start()
    }
}
