package com.acelik.asistan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * D-FON teması: altın çizgi, hemen altında (içinde) parlayan LED.
 * Simgelerin ve widget'ların çerçevesi buradan çizilir. Bulanık parıltı sadece yazılımla çizilen
 * bitmap'te çalıştığı için her şey önce bitmap'e çizilir.
 */
object LedStyle {

    private val GOLDS = intArrayOf(0xFFFBEBB5.toInt(), 0xFFD4A23C.toInt(), 0xFFF2D27A.toInt(), 0xFF8E6620.toInt())
    private val GOLD_STOPS = floatArrayOf(0f, 0.45f, 0.7f, 1f)
    const val TURKUAZ = 0xFF3CF2E6.toInt()
    const val ALTIN_LED = 0xFFFFCF5A.toInt()

    private fun prefs(c: Context) = c.getSharedPreferences("dfon", Context.MODE_PRIVATE)

    /** "turkuaz" | "altin" | "yok" */
    fun ledName(c: Context) = prefs(c).getString("led_renk", "turkuaz") ?: "turkuaz"
    fun setLed(c: Context, v: String) {
        prefs(c).edit().putString("led_renk", v).apply()
        iconCache.evictAll()
    }
    fun ledColor(c: Context): Int = when (ledName(c)) {
        "altin" -> ALTIN_LED
        "yok" -> 0
        else -> TURKUAZ
    }

    private fun gold(r: RectF) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        shader = LinearGradient(r.left, r.top, r.right, r.bottom, GOLDS, GOLD_STOPS, Shader.TileMode.CLAMP)
    }

    /**
     * Dışta altın çizgi; LED ışığı alttan gelir, alt kenarda en parlak, yanlardan yukarı doğru söner.
     * Alt kenarın hemen altında da hafif bir yansıma vardır. dp: 1 dp'nin piksel karşılığı.
     */
    fun drawFrame(c: Canvas, r: RectF, radius: Float, dp: Float, led: Int) {
        if (led != 0) underGlow(c, r, radius, dp, led)
        val g = RectF(r.left + 1.2f * dp, r.top + 1.2f * dp, r.right - 1.2f * dp, r.bottom - 1.2f * dp)
        c.drawRoundRect(g, radius, radius, gold(g).apply { strokeWidth = 2.4f * dp })
    }

    private fun clear(color: Int) = color and 0x00FFFFFF

    /** Alttan yanlara yayılan LED ışığı (çerçevenin içinde, altın çizginin hemen altında). */
    private fun underGlow(c: Canvas, r: RectF, radius: Float, dp: Float, led: Int) {
        val h = r.height()
        // alttaki yansıma: alt kenar boyunca yayvan bir ışık
        c.drawOval(
            RectF(r.left + r.width() * 0.08f, r.bottom - minOf(16f * dp, h * 0.3f), r.right - r.width() * 0.08f, r.bottom - 1f * dp),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = led
                alpha = 120
                maskFilter = BlurMaskFilter(9f * dp, BlurMaskFilter.Blur.NORMAL)
            })
        val inset = 3.6f * dp
        val rl = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        val rr = maxOf(radius - inset, 0f)
        // alttan yukarı sönen renk: üst kısım saydam
        fun fade(col: Int) = LinearGradient(0f, rl.top, 0f, rl.bottom,
            intArrayOf(clear(col), clear(col), col and 0x66FFFFFF.toInt() or 0x66000000, col),
            floatArrayOf(0f, 0.3f, 0.65f, 1f), Shader.TileMode.CLAMP)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f * dp
            shader = fade(led)
            maskFilter = BlurMaskFilter(6f * dp, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRoundRect(rl, rr, rr, glow)
        c.drawRoundRect(rl, rr, rr, glow)
        c.drawRoundRect(rl, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.3f * dp
            shader = fade(mix(led, 0xFFFFFFFF.toInt(), 0.45f))
        })
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 0xFF
        fun m(s: Int) = (ch(a, s) * (1 - t) + ch(b, s) * t).toInt()
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    // ---------------- simgeler ----------------

    private val iconCache = LruCache<String, Bitmap>(160)

    /** Uygulama simgesi: yumuşak köşeli kare, koyu zemin, altın çizgi, alttan yanlara yayılan LED, ortada simge. */
    fun icon(c: Context, pkg: String, sizePx: Int, export: Boolean = false): Bitmap? {
        val key = "$pkg@$sizePx@${ledName(c)}@$export"
        if (!export) iconCache.get(key)?.let { return it }
        val d = try { c.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { return null }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // Dışa aktarılan büyük resimde çizgi kalınlıkları simge boyuna göre (62 dp'lik simge oranında)
        val dp = if (export) sizePx / 62f else c.resources.displayMetrics.density
        val s = sizePx.toFloat()
        val led = ledColor(c)
        // alt tarafta yansıma için biraz boşluk bırak
        val shape = RectF(3f * dp, 2f * dp, s - 3f * dp, s - 6f * dp)
        val radius = shape.width() * 0.28f
        if (led != 0) {
            cv.drawOval(RectF(shape.left + shape.width() * 0.12f, shape.bottom - 3f * dp, shape.right - shape.width() * 0.12f, s - 0.5f * dp),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = led; alpha = 150
                    maskFilter = BlurMaskFilter(4f * dp, BlurMaskFilter.Blur.NORMAL)
                })
        }
        cv.drawRoundRect(shape, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD908141B.toInt() })
        drawFrame(cv, shape, radius, dp * 0.85f, led)
        val inset = s * 0.2f
        d.setBounds(inset.toInt(), (shape.top + inset - 2f * dp).toInt(), (s - inset).toInt(), (shape.bottom - inset + 2f * dp).toInt())
        d.draw(cv)
        if (!export) iconCache.put(key, bmp)
        return bmp
    }

    /** Widget'ların ve kutuların arkasına konan çerçeve. */
    /** Aynı boyuttaki çerçeveler tek bir resmi paylaşır (her düğme için yeniden çizilmez). */
    private val frameCache = LruCache<String, Bitmap>(24)

    class FrameDrawable(private val ctx: Context, private val radiusDp: Float, private val fill: Int = 0x66061016) : Drawable() {

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val led = ledColor(ctx)
            val key = "${b.width()}x${b.height()}|$radiusDp|$fill|$led"
            val bmp = frameCache.get(key) ?: Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888).also { bm ->
                val c = Canvas(bm)
                val dp = ctx.resources.displayMetrics.density
                val r = RectF(0f, 0f, b.width().toFloat(), b.height().toFloat())
                val rad = radiusDp * dp
                if (fill != 0) c.drawRoundRect(RectF(2 * dp, 2 * dp, r.right - 2 * dp, r.bottom - 2 * dp), rad, rad,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
                drawFrame(c, r, rad, dp, led)
                frameCache.put(key, bm)
            }
            canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
