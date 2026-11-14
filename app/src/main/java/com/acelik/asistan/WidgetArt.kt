package com.acelik.asistan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Namaz widget'ının temaları: altın çerçeve, kandiller, hilal, çini süsleri…
 * Hepsi telefonda çizilir (resim dosyası yok). Ölçüler 440×250'lik tasarım birimindedir;
 * gerçek boyuta göre ölçeklenir, süsler köşelere/kenarlara göre yerleşir.
 */
object WidgetArt {

    class Theme(
        val id: String,
        val name: String,
        val sub: Int,
        val accent: Int,
        val onCellRes: Int,
        val onCell: Int,
        val onFg: Int,
        /** yazı bloğunun üst boşluğu ve başlığın yan boşluğu (tasarım birimi) */
        val padTop: Float,
        val headSide: Float,
    )

    val THEMES = listOf(
        Theme("gece", "Gece Mavisi", 0xFFAFC1E6.toInt(), 0xFFF2D27A.toInt(), R.drawable.widget_cell_on, 0xFFE5BC5C.toInt(), 0xFF1A1405.toInt(), 24f, 70f),
        Theme("zumrut", "Zümrüt", 0xFFA9D6C3.toInt(), 0xFFF2D27A.toInt(), R.drawable.widget_cell_on, 0xFFE5BC5C.toInt(), 0xFF1A1405.toInt(), 64f, 10f),
        Theme("mihrap", "Mihrap", 0xFFD7B9D3.toInt(), 0xFFF2D27A.toInt(), R.drawable.widget_cell_on, 0xFFE5BC5C.toInt(), 0xFF1A1405.toInt(), 62f, 30f),
        Theme("altin", "Altın Hat", 0xFFCBBF9F.toInt(), 0xFFE9C46A.toInt(), R.drawable.widget_cell_on, 0xFFE5BC5C.toInt(), 0xFF1A1405.toInt(), 36f, 26f),
        Theme("safak", "Şafak", 0xFFF1C9C0.toInt(), 0xFFFFD98A.toInt(), R.drawable.widget_cell_on, 0xFFE5BC5C.toInt(), 0xFF1A1405.toInt(), 24f, 48f),
        Theme("cini", "İznik Çini", 0xFFA9D3F0.toInt(), 0xFFFFD166.toInt(), R.drawable.widget_cell_on_turq, 0xFF3CC4C0.toInt(), 0xFF062A3A.toInt(), 30f, 48f),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("dfon", Context.MODE_PRIVATE)
    fun theme(c: Context): Theme = THEMES.firstOrNull { it.id == prefs(c).getString("widget_tema", "gece") } ?: THEMES[0]
    fun setTheme(c: Context, id: String) = prefs(c).edit().putString("widget_tema", id).apply()
    /** Yazı büyüklüğü çarpanı (0.7 – 1.6) */
    fun textScale(c: Context): Float = prefs(c).getFloat("widget_yazi", 1f)
    fun setTextScale(c: Context, s: Float) = prefs(c).edit().putFloat("widget_yazi", s.coerceIn(0.7f, 1.6f)).apply()

    // ---------------- renkler ----------------

    private val GOLD_COLORS = intArrayOf(0xFFF7E2A1.toInt(), 0xFFC9973A.toInt(), 0xFFF2D27A.toInt(), 0xFF8E6620.toInt())
    private val GOLD_STOPS = floatArrayOf(0f, 0.4f, 0.6f, 1f)
    private const val GOLD = 0xFFE2B653.toInt()
    private const val PALE = 0xFFF7E2A1.toInt()

    private fun goldPaint(w: Float, h: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, w, h, GOLD_COLORS, GOLD_STOPS, Shader.TileMode.CLAMP)
    }

    /** SVG'deki gibi kutuya göre (yüzde) eliptik radyal gradyan. */
    private fun radial(w: Float, h: Float, cxP: Float, cyP: Float, rP: Float, colors: IntArray, stops: FloatArray?): Shader {
        val cx = w * cxP
        val cy = h * cyP
        val g = RadialGradient(cx, cy, w * rP, colors, stops, Shader.TileMode.CLAMP)
        g.setLocalMatrix(Matrix().apply { setScale(1f, h / w, cx, cy) })
        return g
    }

    private fun background(t: Theme, w: Float, h: Float): Shader = when (t.id) {
        "zumrut" -> radial(w, h, 0.2f, 0f, 1.2f, intArrayOf(0xFF13684F.toInt(), 0xFF0B4436.toInt(), 0xFF072A22.toInt()), floatArrayOf(0f, 0.5f, 1f))
        "mihrap" -> LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF3A1236.toInt(), 0xFF23102B.toInt(), 0xFF160A1C.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        "altin" -> radial(w, h, 0.5f, 0.3f, 0.9f, intArrayOf(0xFF211E18.toInt(), 0xFF0D0C0A.toInt()), null)
        "safak" -> LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF2B1B4A.toInt(), 0xFF6B2F5C.toInt(), 0xFFB5583A.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        "cini" -> radial(w, h, 0.5f, 0f, 1.1f, intArrayOf(0xFF1A4C8C.toInt(), 0xFF0B2550.toInt()), null)
        else -> radial(w, h, 0.5f, 0f, 1.1f, intArrayOf(0xFF1F3C78.toInt(), 0xFF0F2148.toInt(), 0xFF0A1633.toInt()), floatArrayOf(0f, 0.55f, 1f))
    }

    // ---------------- çizim ----------------

    /** Arka plan + süsler (yazısız). */
    fun draw(t: Theme, wPx: Int, hPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(wPx.coerceAtLeast(40), hPx.coerceAtLeast(30), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val u = minOf(w / 440f, h / 250f)
        val gold = goldPaint(w, h)

        // çerçeve
        c.drawRoundRect(RectF(0f, 0f, w, h), 28 * u, 28 * u, gold)
        val inner = RectF(5 * u, 5 * u, w - 5 * u, h - 5 * u)
        c.drawRoundRect(inner, 23 * u, 23 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = background(t, w, h) })
        if (t.id != "mihrap") {
            c.drawRoundRect(RectF(9 * u, 9 * u, w - 9 * u, h - 9 * u), 19 * u, 19 * u, stroke(GOLD, maxOf(1f, u), 140))
        }

        val clip = Path().apply { addRoundRect(inner, 23 * u, 23 * u, Path.Direction.CW) }
        when (t.id) {
            "gece" -> {
                listOf(16f to 16f, w / u - 16f to 16f, 16f to h / u - 16f, w / u - 16f to h / u - 16f).forEach { (x, y) ->
                    star8(c, x * u, y * u, 14 * u)
                }
                tinyStars(c, u, w, listOf(Triple(-70f, 14f, 7f), Triple(20f, 26f, 5f), Triple(70f, 12f, 6f), Triple(-24f, 34f, 4f)))
                crescent(c, w - 110 * u, 16 * u, 22 * u, 0xFFF2D27A.toInt())
                lantern(c, 32 * u, 0f, 24 * u, 8f)
                lantern(c, 58 * u, 0f, 16 * u, 2f)
                lantern(c, w - 32 * u, 0f, 24 * u, 8f)
                lantern(c, w - 58 * u, 0f, 16 * u, 2f)
            }
            "zumrut" -> {
                // sağ altta silik geometrik desen
                val gx = w - 75 * u
                val gy = h - 95 * u
                val p = stroke(0xFFF2D27A.toInt(), u, 31)
                c.save(); c.rotate(0f, gx, gy); c.drawRect(gx - 35 * u, gy - 35 * u, gx + 35 * u, gy + 35 * u, p); c.restore()
                c.save(); c.rotate(45f, gx, gy); c.drawRect(gx - 35 * u, gy - 35 * u, gx + 35 * u, gy + 35 * u, p); c.restore()
                c.drawCircle(gx, gy, 26 * u, p)
                // kandil dizisi
                val x0 = 30 * u
                val x1 = w - 30 * u
                val cy = 48 * u
                val y0 = 9 * u
                val path = Path().apply { moveTo(x0, y0); quadTo(w / 2, cy, x1, y0) }
                c.drawPath(path, stroke(GOLD, 1.2f * u, 255).apply { pathEffect = DashPathEffect(floatArrayOf(3 * u, 2 * u), 0f) })
                listOf(40f / 380f, 115f / 380f, 0.5f, 265f / 380f, 340f / 380f).forEach { tt ->
                    val x = (1 - tt) * (1 - tt) * x0 + 2 * (1 - tt) * tt * (w / 2) + tt * tt * x1
                    val y = (1 - tt) * (1 - tt) * y0 + 2 * (1 - tt) * tt * cy + tt * tt * y0
                    lantern(c, x, y - u, 14 * u, 0f)
                }
            }
            "mihrap" -> {
                val L = 18 * u
                val R = w - 18 * u
                val B = h - 5 * u
                val m = w / 2
                val arch = Path().apply {
                    moveTo(L, B); lineTo(L, 96 * u); quadTo(L, 66 * u, L + 44 * u, 56 * u)
                    quadTo(m - 68 * u, 40 * u, m, 14 * u); quadTo(m + 68 * u, 40 * u, R - 44 * u, 56 * u)
                    quadTo(R, 66 * u, R, 96 * u); lineTo(R, B)
                }
                c.drawPath(arch, stroke(GOLD, 2 * u, 255))
                val L2 = 26 * u
                val R2 = w - 26 * u
                val arch2 = Path().apply {
                    moveTo(L2, B); lineTo(L2, 99 * u); quadTo(L2, 74 * u, L2 + 40 * u, 64 * u)
                    quadTo(m - 66 * u, 48 * u, m, 24 * u); quadTo(m + 66 * u, 48 * u, R2 - 40 * u, 64 * u)
                    quadTo(R2, 74 * u, R2, 99 * u); lineTo(R2, B)
                }
                c.drawPath(arch2, stroke(GOLD, u, 102))
                c.save(); c.clipPath(clip)
                mosque(c, m, h - 5 * u, u, 60f, 0xFF0B0610.toInt(), 204)
                c.restore()
                tinyStars(c, u, w, listOf(Triple(-146f, 74f, 6f), Triple(136f, 70f, 7f), Triple(116f, 100f, 4f), Triple(-124f, 104f, 4f)))
                lantern(c, m, 0f, 20 * u, 6f)
            }
            "altin" -> {
                corner(c, 14 * u, 14 * u, u, u)
                corner(c, w - 14 * u, 14 * u, -u, u)
                corner(c, 14 * u, h - 14 * u, u, -u)
                corner(c, w - 14 * u, h - 14 * u, -u, -u)
                val lp = stroke(GOLD, u, 178)
                c.drawLine(w / 2 - 70 * u, 22 * u, w / 2 - 15 * u, 22 * u, lp)
                c.drawLine(w / 2 + 15 * u, 22 * u, w / 2 + 70 * u, 22 * u, lp)
                crescent(c, w / 2 - 11 * u, 10 * u, 22 * u, 0xFFE9C46A.toInt())
            }
            "safak" -> {
                c.save(); c.clipPath(clip)
                c.drawCircle(w / 2, h, 120 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(w / 2, h, 120 * u, intArrayOf(0xBFFFD98A.toInt(), 0x00FFD98A), null, Shader.TileMode.CLAMP)
                })
                mosque(c, w / 2, h, u * 0.75f, 60f, 0xFF1E0F2B.toInt(), 89)
                c.restore()
                tinyStars(c, u, w, listOf(Triple(-100f, 20f, 6f), Triple(90f, 16f, 5f), Triple(40f, 34f, 4f), Triple(-50f, 40f, 4f)))
                lantern(c, 34 * u, 0f, 16 * u, 6f)
                lantern(c, w - 34 * u, 0f, 16 * u, 6f)
            }
            "cini" -> {
                c.save()
                c.clipPath(Path().apply { addRoundRect(RectF(9 * u, 9 * u, w - 9 * u, h - 9 * u), 19 * u, 19 * u, Path.Direction.CW) })
                val turq = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD93CC4C0.toInt() }
                val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                var x = 30 * u
                while (x < w - 16 * u) {
                    c.drawArc(RectF(x - 7 * u, 5 * u, x + 7 * u, 19 * u), 0f, 180f, true, turq)
                    c.drawCircle(x, 14 * u, 1.6f * u, white)
                    x += 14 * u
                }
                c.restore()
                tulip(c, 34 * u, 62 * u, u)
                tulip(c, w - 34 * u, 62 * u, -u)
            }
        }
        return bmp
    }

    /** Uygulama içi önizleme: arka plan + örnek yazılar (yazı boyutunu görmek için). */
    fun preview(t: Theme, wPx: Int, hPx: Int, scale: Float, density: Float): Bitmap {
        val bmp = draw(t, wPx, hPx)
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val u = minOf(w / 440f, h / 250f)
        // Widget gerçekte ~ (440u px) = ekranda kaç dp ise, yazılar sp→px ile o ölçekte çizilir
        val sp = u * 440f / 360f * scale // 360dp genişliğindeki widget varsayımı
        @Suppress("UNUSED_VARIABLE") val d = density
        val left = (20 + t.headSide) * u
        val right = w - (20 + t.headSide) * u
        var y = t.padTop * u
        fun p(color: Int, size: Float, bold: Boolean = false, serif: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size * sp
            typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val date = p(t.sub, 12f)
        y += -date.ascent()
        c.drawText("15 Kasım Pazar", left, y, date)
        val name = p(Color.WHITE, 22f, true, true)
        val ny = y + date.descent() - name.ascent()
        c.drawText("Öğle", left, ny, name)
        c.drawText("12:52", left + name.measureText("Öğle") + 8 * sp, ny, p(t.accent, 15f))
        val cnt = p(t.accent, 26f, true).apply { textAlign = Paint.Align.RIGHT }
        c.drawText("02:14:37", right, ny, cnt)
        c.drawText("kaldı", right, ny + 14 * sp, p(t.sub, 11f).apply { textAlign = Paint.Align.RIGHT })

        val names = PrayerTimes.NAMES
        val times = listOf("06:14", "07:42", "12:52", "15:28", "17:51", "19:14")
        val cx0 = 20 * u
        val cw = (w - 40 * u - 5 * 5 * u) / 6
        val ch = 30 * sp + 12 * u
        val cy = h - 16 * u - ch
        for (i in 0 until 6) {
            val x = cx0 + i * (cw + 5 * u)
            val on = i == 2
            val r = RectF(x, cy, x + cw, cy + ch)
            c.drawRoundRect(r, 10 * u, 10 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (on) t.onCell else 0x26FFFFFF })
            val n = p(if (on) t.onFg else t.sub, 11f).apply { textAlign = Paint.Align.CENTER }
            val tm = p(if (on) t.onFg else Color.WHITE, 14f, true).apply { textAlign = Paint.Align.CENTER }
            c.drawText(names[i], r.centerX(), cy + 6 * u - n.ascent(), n)
            c.drawText(times[i], r.centerX(), cy + ch - 6 * u - tm.descent(), tm)
        }
        return bmp
    }

    // ---------------- parçalar ----------------

    private fun stroke(color: Int, width: Float, alpha: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        this.alpha = alpha
        strokeWidth = width
    }

    private fun star8(c: Canvas, cx: Float, cy: Float, size: Float) {
        val half = size * 7f / 24f
        val p = stroke(GOLD, 1.3f * size / 24f, 255)
        c.drawRect(cx - half, cy - half, cx + half, cy + half, p)
        c.save(); c.rotate(45f, cx, cy); c.drawRect(cx - half, cy - half, cx + half, cy + half, p); c.restore()
        c.drawCircle(cx, cy, 2.2f * size / 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD })
    }

    /** dx: merkezden yatay uzaklık (tasarım birimi), y: üstten */
    private fun tinyStars(c: Canvas, u: Float, w: Float, list: List<Triple<Float, Float, Float>>) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PALE; alpha = 220 }
        for ((dx, y, s) in list) {
            val x = w / 2 + dx * u
            val k = s * u / 10f
            val path = Path().apply {
                moveTo(5f, 0f); lineTo(6f, 4f); lineTo(10f, 5f); lineTo(6f, 6f)
                lineTo(5f, 10f); lineTo(4f, 6f); lineTo(0f, 5f); lineTo(4f, 4f); close()
            }
            c.save(); c.translate(x, y * u); c.scale(k, k); c.drawPath(path, p); c.restore()
        }
    }

    private fun crescent(c: Canvas, x: Float, y: Float, size: Float, color: Int) {
        val k = size / 40f
        val layer = c.saveLayer(x, y, x + size, y + size, null)
        c.translate(x, y)
        c.scale(k, k)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawCircle(19f, 20f, 15f, p)
        c.drawCircle(26f, 15f, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        val star = Path().apply {
            moveTo(31f, 25f); lineTo(32.2f, 27.6f); lineTo(35f, 27.9f); lineTo(32.9f, 29.8f); lineTo(33.5f, 32.6f)
            lineTo(31f, 31.2f); lineTo(28.5f, 32.6f); lineTo(29.1f, 29.8f); lineTo(27f, 27.9f); lineTo(29.8f, 27.6f); close()
        }
        c.drawPath(star, p)
        c.restoreToCount(layer)
    }

    /** Kandil: zincir + kubbe + parlayan cam gövde + püskül. cx: orta, top: zincirin başladığı yer, w: genişlik. */
    private fun lantern(c: Canvas, cx: Float, top: Float, w: Float, chain: Float) {
        val k = w / 40f
        val h = chain + 86f
        c.save()
        c.translate(cx - w / 2, top)
        c.scale(k, k)
        val g = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 40f, h, intArrayOf(0xFFF7E2A1.toInt(), GOLD, 0xFF9C7424.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        val gs = Paint(g).apply { style = Paint.Style.STROKE }
        val ch = chain
        // hale
        c.drawCircle(20f, ch + 42f, 34f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(20f, ch + 42f, 34f, intArrayOf(0x8CFFD98A.toInt(), 0x00FFD98A), null, Shader.TileMode.CLAMP)
        })
        // zincir
        if (ch > 0f) {
            c.drawLine(20f, 0f, 20f, ch, Paint(gs).apply { strokeWidth = 1.2f; pathEffect = DashPathEffect(floatArrayOf(2.2f, 1.6f), 0f) })
        }
        c.drawCircle(20f, ch + 1f, 2.4f, Paint(gs).apply { strokeWidth = 1.2f })
        // kubbe
        c.drawPath(Path().apply {
            moveTo(20f, ch + 3f); cubicTo(12f, ch + 9f, 9f, ch + 14f, 9f, ch + 18f)
            lineTo(31f, ch + 18f); cubicTo(31f, ch + 14f, 28f, ch + 9f, 20f, ch + 3f); close()
        }, g)
        c.drawRoundRect(RectF(7f, ch + 18f, 33f, ch + 21f), 1.2f, 1.2f, g)
        // cam gövde
        val body = Path().apply {
            moveTo(9f, ch + 21f); lineTo(31f, ch + 21f); lineTo(34f, ch + 40f)
            lineTo(28f, ch + 60f); lineTo(12f, ch + 60f); lineTo(6f, ch + 40f); close()
        }
        c.drawPath(body, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(20f, ch + 43f, 18f, intArrayOf(0xFFFFF6D6.toInt(), 0xFFFFD98A.toInt(), 0xD9E08A2E.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawPath(body, Paint(gs).apply { strokeWidth = 1.6f })
        c.drawPath(Path().apply {
            moveTo(20f, ch + 21f); lineTo(20f, ch + 60f)
            moveTo(13f, ch + 21f); lineTo(10f, ch + 40f); lineTo(14f, ch + 60f)
            moveTo(27f, ch + 21f); lineTo(30f, ch + 40f); lineTo(26f, ch + 60f)
        }, Paint(gs).apply { strokeWidth = 0.8f; alpha = 204 })
        // alt kısım ve püskül
        c.drawRoundRect(RectF(10f, ch + 60f, 30f, ch + 63f), 1.2f, 1.2f, g)
        c.drawPath(Path().apply { moveTo(14f, ch + 63f); lineTo(26f, ch + 63f); lineTo(20f, ch + 74f); close() }, g)
        c.drawCircle(20f, ch + 78f, 2.6f, g)
        c.drawLine(20f, ch + 80f, 20f, ch + 86f, Paint(gs).apply { strokeWidth = 1.2f })
        c.restore()
    }

    /** Cami silüeti: kubbe + iki minare. cx: orta, base: taban çizgisi, half: kubbenin yarı genişliği (birim). */
    private fun mosque(c: Canvas, cx: Float, base: Float, u: Float, half: Float, color: Int, alpha: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.alpha = alpha }
        val hw = half * u
        c.drawPath(Path().apply {
            moveTo(cx - hw, base); lineTo(cx - hw, base - 23 * u)
            quadTo(cx - hw, base - 43 * u, cx - 28 * u, base - 49 * u)
            quadTo(cx, base - 63 * u, cx + 28 * u, base - 49 * u)
            quadTo(cx + hw, base - 43 * u, cx + hw, base - 23 * u)
            lineTo(cx + hw, base); close()
        }, p)
        for (s in listOf(-1f, 1f)) {
            val mx = cx + s * (hw + 18.5f * u)
            c.drawRect(mx - 3.5f * u, base - 53 * u, mx + 3.5f * u, base, p)
            c.drawPath(Path().apply {
                moveTo(mx - 5.5f * u, base - 53 * u); lineTo(mx, base - 70 * u); lineTo(mx + 5.5f * u, base - 53 * u); close()
            }, p)
        }
    }

    /** Altın Hat köşe süsü. sx/sy: köşeye göre yön ve ölçek. */
    private fun corner(c: Canvas, x: Float, y: Float, sx: Float, sy: Float) {
        c.save()
        c.translate(x, y)
        c.scale(sx, sy)
        val p = stroke(GOLD, 1.3f, 255)
        c.drawPath(Path().apply { moveTo(0f, 34f); quadTo(0f, 0f, 34f, 0f) }, p)
        c.drawPath(Path().apply { moveTo(8f, 34f); quadTo(8f, 8f, 34f, 8f) }, stroke(GOLD, 1.3f, 153))
        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD }
        c.drawPath(Path().apply { moveTo(10f, 14f); lineTo(14f, 10f); lineTo(18f, 14f); lineTo(14f, 18f); close() }, f)
        c.drawCircle(40f, 0f, 1.8f, f)
        c.drawCircle(0f, 40f, 1.8f, f)
        c.restore()
    }

    /** İznik lalesi. */
    private fun tulip(c: Canvas, x: Float, y: Float, s: Float) {
        c.save()
        c.translate(x, y)
        c.scale(s, Math.abs(s))
        val turq = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3CC4C0.toInt() }
        c.drawLine(0f, 0f, 0f, -18f, stroke(0xFF3CC4C0.toInt(), 1.6f, 255))
        c.drawPath(Path().apply { moveTo(0f, -6f); quadTo(-9f, -10f, -11f, -18f); quadTo(-3f, -15f, 0f, -9f); close() }, turq)
        c.drawPath(Path().apply { moveTo(0f, -6f); quadTo(9f, -10f, 11f, -18f); quadTo(3f, -15f, 0f, -9f); close() }, turq)
        val flower = Path().apply {
            moveTo(0f, -18f); quadTo(-8f, -22f, -6f, -32f); quadTo(-3f, -27f, 0f, -30f)
            quadTo(3f, -27f, 6f, -32f); quadTo(8f, -22f, 0f, -18f); close()
        }
        c.drawPath(flower, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE4553F.toInt() })
        c.drawPath(flower, stroke(Color.WHITE, 0.8f, 255))
        c.restore()
    }
}
