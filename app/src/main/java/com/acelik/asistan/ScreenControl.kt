package com.acelik.asistan

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Başka bir uygulamanın içindeyken sesle kontrol: geri, ana ekran, kaydır,
 * "X'e dokun", "ekranı oku", "neler var", "yaz …", "X'i aç".
 * D-FON'u öne getirmeden, erişilebilirlik servisi (D-FON Uygulama Kilidi) üzerinden çalışır.
 */
object ScreenControl {

    /** Sadece bu fiillerin emir halleri dokunma sayılır ("başka", "seçim", "açık", "girdi" sayılmaz). */
    val TAP_STEMS = listOf("dokun", "bas", "tikla", "gir", "sec", "ac")
    private val OPEN_STEMS = listOf("ac", "baslat", "calistir")
    private val SUFFIXES = setOf(
        "a", "e", "ya", "ye", "i", "u", "yi", "yu", "na", "ne", "ni", "nu",
        "da", "de", "ta", "te", "dan", "den", "tan", "ten", "in", "un", "nin", "nun"
    )

    /** Geri alınması zor işler: bu yazıları taşıyan düğmelere basmadan önce onay istenir. */
    private val RISKY = listOf(
        "sil", "kaldir", "gonder", "yolla", "paylas", "satin", "ode", "odeme", "onayla", "siparis",
        "transfer", "havale", "eft", "engelle", "sifirla", "bosalt", "cikis", "abone"
    )
    private val YES = setOf("evet", "evet bas", "bas", "onayla", "onayliyorum", "evet dokun")
    private val NO = setOf("hayir", "iptal", "vazgec", "basma", "hayir basma")

    private var pendingLabel: String? = null
    private var pendingAt = 0L

    fun isRisky(label: String): Boolean =
        Nlu.norm(label).split(" ").any { tok -> RISKY.any { tok == it || (it.length >= 4 && tok.startsWith(it)) } }

    /** Komutu ekranda yapmayı dener. Yapabildiyse true. */
    fun handle(c: Context, raw: String, reportMissing: Boolean): Boolean {
        val s = AppLockService.instance ?: return false
        val t = Nlu.norm(raw)
        if (t.isEmpty()) return false
        val tk = t.split(" ")

        // Onay bekleyen riskli dokunma
        val waiting = pendingLabel
        if (waiting != null && System.currentTimeMillis() - pendingAt < 20_000) {
            if (t in YES) { pendingLabel = null; return s.clickText(waiting, true) }
            if (t in NO) { pendingLabel = null; s.say("Vazgeçtim."); return true }
        }

        // Yazı yazma: "yaz: merhaba", "yaz merhaba", "merhaba yaz"
        val colon = raw.indexOf(':')
        if (colon > 0 && Nlu.norm(raw.substring(0, colon)) in setOf("yaz", "ara", "arama")) {
            return s.typeText(raw.substring(colon + 1).trim())
        }
        if (tk.size > 1 && "mesaj" !in tk && tk[0] != "not" && !t.contains("hatirlat")) {
            val words = raw.trim().split(Regex("\\s+"))
            if (tk[0] == "yaz") return s.typeText(words.drop(1).joinToString(" "))
            if (tk.last() == "yaz" && words.size > 1) return s.typeText(words.dropLast(1).joinToString(" "))
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
        if (has(t, "neler var", "secenekleri oku", "dugmeleri oku", "nereye girebilirim") || t == "ne var") {
            return s.readClickables()
        }

        // "Ayarlar'a dokun", "Profil'e gir": ekranda görünen yazıyla
        val vi = tk.indexOfLast { Nlu.isVerb(it, TAP_STEMS) }
        if (vi > 0) {
            val target = tk.subList(0, vi).toMutableList()
            while (target.size > 1 && target.last() in SUFFIXES) target.removeAt(target.size - 1)
            val q = target.joinToString(" ")
            if (q.isNotBlank()) {
                val label = s.findLabel(q)
                if (label == null) {
                    if (reportMissing) s.say("\"$q\" ekranda bulunamadı. \"Neler var\" diyerek seçenekleri dinleyebilirsin.")
                    return false
                }
                if (isRisky(label)) {
                    pendingLabel = label
                    pendingAt = System.currentTimeMillis()
                    val ask = "\"$label\" düğmesine basayım mı? Evet ya da hayır de."
                    s.say(ask)
                    Speaker.speak(c, ask)
                    return true
                }
                return s.clickText(label, reportMissing)
            }
        }
        return false
    }

    /** "WhatsApp'ı aç" gibi: izinli bir uygulamayı D-FON'u öne getirmeden açar. */
    fun openApp(c: Context, raw: String, allowGir: Boolean): Boolean {
        val s = AppLockService.instance ?: return false
        val t = " " + Nlu.norm(raw) + " "
        val tk = t.trim().split(" ")
        if (!Nlu.hasVerb(tk, if (allowGir) OPEN_STEMS + "gir" else OPEN_STEMS)) return false
        val allowed = c.getSharedPreferences("dfon", Context.MODE_PRIVATE).getStringSet("izinli", emptySet()) ?: emptySet()
        val pm = c.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val app = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first in allowed }
            .filter { (_, label) ->
                val l = Nlu.norm(label)
                l.length >= 2 && (t.contains(" $l ") || t.contains(" $l") || t.contains(" ${l.replace(" ", "")}"))
            }
            .maxByOrNull { Nlu.norm(it.second).length } ?: return false
        val reason = Guard.blockedReason(c, app.first, app.second)
        if (reason != null) {
            Toast.makeText(c, reason, Toast.LENGTH_LONG).show()
            return true
        }
        val i = pm.getLaunchIntentForPackage(app.first) ?: return false
        AppLock.allow(c, app.first)
        s.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Toast.makeText(c, "${app.second} açılıyor", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }
}
