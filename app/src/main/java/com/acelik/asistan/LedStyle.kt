package com.acelik.asistan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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

    /** Dışta altın çizgi, hemen içinde LED parıltısı. dp: 1 dp'nin piksel karşılığı. */
    fun drawFrame(c: Canvas, r: RectF, radius: Float, dp: Float, led: Int) {
        if (led != 0) {
            val inset = 4f * dp
            val rl = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
            val rr = maxOf(radius - inset, 0f)
            c.drawRoundRect(rl, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = led
                strokeWidth = 4f * dp
                maskFilter = BlurMaskFilter(5f * dp, BlurMaskFilter.Blur.NORMAL)
            })
            c.drawRoundRect(rl, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = mix(led, 0xFFFFFFFF.toInt(), 0.45f)
                strokeWidth = 1.2f * dp
            })
        }
        val g = RectF(r.left + 1.2f * dp, r.top + 1.2f * dp, r.right - 1.2f * dp, r.bottom - 1.2f * dp)
        c.drawRoundRect(g, radius, radius, gold(g).apply { strokeWidth = 2.4f * dp })
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 0xFF
        fun m(s: Int) = (ch(a, s) * (1 - t) + ch(b, s) * t).toInt()
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    // ---------------- simgeler ----------------

    private val iconCache = LruCache<String, Bitmap>(160)

    /** Uygulama simgesi: koyu yuvarlak zemin, LED halka, altın çember ve ortada simge. */
    fun icon(c: Context, pkg: String, sizePx: Int): Bitmap? {
        val key = "$pkg@$sizePx@${ledName(c)}"
        iconCache.get(key)?.let { return it }
        val d = try { c.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { return null }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val dp = c.resources.displayMetrics.density
        val s = sizePx.toFloat()
        val cx = s / 2
        val led = ledColor(c)
        val outer = s / 2 - 2.5f * dp
        cv.drawCircle(cx, cx, outer, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC08141B.toInt() })
        if (led != 0) {
            cv.drawCircle(cx, cx, outer - 4f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = led; strokeWidth = 4f * dp
                maskFilter = BlurMaskFilter(4.5f * dp, BlurMaskFilter.Blur.NORMAL)
            })
            cv.drawCircle(cx, cx, outer - 4f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = mix(led, 0xFFFFFFFF.toInt(), 0.45f); strokeWidth = 1.1f * dp
            })
        }
        val gr = RectF(cx - outer, cx - outer, cx + outer, cx + outer)
        cv.drawCircle(cx, cx, outer, gold(gr).apply { strokeWidth = 2.6f * dp })
        val inset = (s * 0.22f).toInt()
        d.setBounds(inset, inset, sizePx - inset, sizePx - inset)
        d.draw(cv)
        iconCache.put(key, bmp)
        return bmp
    }

    /** Widget'ların ve kutuların arkasına konan çerçeve. */
    class FrameDrawable(private val ctx: Context, private val radiusDp: Float, private val fill: Int = 0x66061016) : Drawable() {
        private var cache: Bitmap? = null

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            cache = null
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val bmp = cache ?: Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888).also { bm ->
                val c = Canvas(bm)
                val dp = ctx.resources.displayMetrics.density
                val r = RectF(0f, 0f, b.width().toFloat(), b.height().toFloat())
                val rad = radiusDp * dp
                if (fill != 0) c.drawRoundRect(RectF(2 * dp, 2 * dp, r.right - 2 * dp, r.bottom - 2 * dp), rad, rad,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
                drawFrame(c, r, rad, dp, ledColor(ctx))
                cache = bm
            }
            canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
