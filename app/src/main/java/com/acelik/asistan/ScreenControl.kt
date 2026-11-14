package com.acelik.asistan

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Başka bir uygulamanın içindeyken sesle kontrol: geri, ana ekran, kaydır,
 * "X'e dokun", "ekranı oku", "neler var", "yaz: …", "X'i aç".
 * D-FON'u öne getirmeden, erişilebilirlik servisi (D-FON Uygulama Kilidi) üzerinden çalışır.
 */
object ScreenControl {

    private val TAP_VERBS = listOf("dokun", "bas", "tikla", "gir", "sec", "ac")
    private val SUFFIXES = setOf(
        "a", "e", "ya", "ye", "i", "u", "yi", "yu", "na", "ne", "ni", "nu",
        "da", "de", "ta", "te", "dan", "den", "tan", "ten", "in", "un", "nin", "nun"
    )

    /** Komutu ekranda yapmayı dener. Yapabildiyse true. */
    fun handle(c: Context, raw: String, reportMissing: Boolean): Boolean {
        val s = AppLockService.instance ?: return false
        val t = FileTools.norm(raw)
        if (t.isEmpty()) return false
        val tk = t.split(" ")

        // Yazı yazma: "yaz: merhaba", "ara: hava durumu"
        val colon = raw.indexOf(':')
        if (colon > 0 && FileTools.norm(raw.substring(0, colon)) in setOf("yaz", "ara", "arama")) {
            return s.typeText(raw.substring(colon + 1).trim())
        }

        when {
            t in setOf("geri", "geri don", "geri gel", "geriye", "geri git", "geriye don") ->
                return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            has(t, "ana ekran", "ana sayfaya don", "anasayfaya don", "eve don") ->
                return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            has(t, "son uygulama") ->
                return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            tk.size <= 3 && tk[0].startsWith("bildirim") && !has(t, "ayar") ->
                return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            tk.size <= 2 && tk.any { it in setOf("dur", "durdur", "sus", "yeter", "kes") } -> {
                Speaker.stop()
                return true
            }
        }

        // Kaydırma
        if (tk.any { it.startsWith("kaydir") } || has(t, "asagi in", "yukari cik", "sonraki sayfa", "onceki sayfa")) {
            val dir = when {
                has(t, "yukari", "onceki") -> AppLockService.Dir.UP
                has(t, "saga") -> AppLockService.Dir.RIGHT
                has(t, "sola") -> AppLockService.Dir.LEFT
                else -> AppLockService.Dir.DOWN
            }
            return s.swipe(dir)
        }

        // Okuma
        if (t in setOf("oku", "ekrani oku", "burayi oku", "sayfayi oku", "ekrandakileri oku", "ne yaziyor",
                "neler yaziyor", "ekrandaki yaziyi oku", "hepsini oku")) {
            return s.readScreen()
        }
        if (has(t, "neler var", "secenekleri oku", "dugmeleri oku", "ne var", "nereye girebilirim")) {
            return s.readClickables()
        }

        // "Ayarlar'a dokun", "Profil'e gir", "ikinci videoyu aç" yerine görünen yazıyla
        val vi = tk.indexOfLast { w -> TAP_VERBS.any { w.startsWith(it) } }
        if (vi > 0) {
            val target = tk.subList(0, vi).toMutableList()
            while (target.size > 1 && target.last() in SUFFIXES) target.removeAt(target.size - 1)
            val q = target.joinToString(" ")
            if (q.isNotBlank()) return s.clickText(q, reportMissing)
        }
        return false
    }

    /** "WhatsApp'ı aç" gibi: izinli bir uygulamayı D-FON'u öne getirmeden açar. */
    fun openApp(c: Context, raw: String, allowGir: Boolean): Boolean {
        val s = AppLockService.instance ?: return false
        val t = " " + FileTools.norm(raw) + " "
        val tk = t.trim().split(" ")
        val verb = tk.any {
            (it.startsWith("ac") && !it.startsWith("acil")) || it.startsWith("baslat") || it.startsWith("calistir") ||
                (allowGir && it.startsWith("gir"))
        }
        if (!verb) return false
        val allowed = c.getSharedPreferences("dfon", Context.MODE_PRIVATE).getStringSet("izinli", emptySet()) ?: emptySet()
        val pm = c.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val app = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first in allowed }
            .filter { (_, label) ->
                val l = FileTools.norm(label)
                l.length >= 2 && (t.contains(" $l ") || t.contains(" $l") || t.contains(" ${l.replace(" ", "")}"))
            }
            .maxByOrNull { FileTools.norm(it.second).length } ?: return false
        val i = pm.getLaunchIntentForPackage(app.first) ?: return false
        AppLock.allow(c, app.first)
        s.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Toast.makeText(c, "${app.second} açılıyor", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }
}
