package com.acelik.asistan

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * "Sadece D-FON'dan açılsın": Kilitli bir uygulama D-FON dışında bir yerden
 * (ana ekran, son uygulamalar, bildirim, mağaza…) açılırsa hemen ana ekrana döner.
 * D-FON'dan açılınca, o uygulamadan çıkana kadar serbesttir.
 * Telefonda çalışır; hiçbir şey kaydedilmez ya da gönderilmez.
 */
object AppLock {

    private fun prefs(c: Context) = c.getSharedPreferences("kilit", Context.MODE_PRIVATE)

    fun locked(c: Context): Set<String> = prefs(c).getStringSet("kilitli", emptySet()) ?: emptySet()
    fun isLocked(c: Context, pkg: String) = pkg in locked(c)
    fun setLocked(c: Context, pkg: String, on: Boolean) {
        val s = HashSet(locked(c))
        if (on) s.add(pkg) else s.remove(pkg)
        prefs(c).edit().putStringSet("kilitli", s).apply()
    }

    /** D-FON bu uygulamayı açmak üzere: kısa süreliğine geçiş izni ver. */
    fun allow(c: Context, pkg: String) {
        prefs(c).edit().putString("izin_pkg", pkg).putLong("izin_zaman", System.currentTimeMillis()).apply()
    }

    internal fun takePass(c: Context, pkg: String): Boolean {
        val p = prefs(c)
        val ok = p.getString("izin_pkg", null) == pkg &&
            System.currentTimeMillis() - p.getLong("izin_zaman", 0) < 15_000
        if (ok) p.edit().remove("izin_pkg").remove("izin_zaman").apply()
        return ok
    }

    /** Erişilebilirlik ayarlarında kilit servisi açık mı? */
    fun serviceEnabled(c: Context): Boolean {
        val want = ComponentName(c, AppLockService::class.java).flattenToString()
        val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(want, ignoreCase = true) }
    }
}

class AppLockService : AccessibilityService() {

    enum class Dir { UP, DOWN, LEFT, RIGHT }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------------- sesle ekran kontrolü ----------------

    fun swipe(d: Dir): Boolean {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val path = Path()
        when (d) {
            // Aşağı kaydır = içerik yukarı akar: parmak aşağıdan yukarı
            Dir.DOWN -> { path.moveTo(w / 2, h * 0.72f); path.lineTo(w / 2, h * 0.28f) }
            Dir.UP -> { path.moveTo(w / 2, h * 0.28f); path.lineTo(w / 2, h * 0.72f) }
            Dir.RIGHT -> { path.moveTo(w * 0.15f, h / 2); path.lineTo(w * 0.85f, h / 2) }
            Dir.LEFT -> { path.moveTo(w * 0.85f, h / 2); path.lineTo(w * 0.15f, h / 2) }
        }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build(),
            null, null
        )
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null
        )
    }

    private fun label(n: AccessibilityNodeInfo): String? {
        val t = n.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) return t
        val d = n.contentDescription?.toString()?.trim()
        return if (d.isNullOrEmpty()) null else d
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null || depth > 60) return
        if (!n.isVisibleToUser) return
        f(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, f)
    }

    fun readScreen(): Boolean {
        val out = ArrayList<String>()
        walk(rootInActiveWindow, 0) { n ->
            if (!n.isPassword) label(n)?.let { if (out.lastOrNull() != it && out.size < 2000) out.add(it) }
        }
        if (out.isEmpty()) {
            say("Ekranda okunacak yazı bulamadım.")
            return true
        }
        Speaker.speak(this, out.joinToString("\n"))
        return true
    }

    fun readClickables(): Boolean {
        val out = LinkedHashSet<String>()
        walk(rootInActiveWindow, 0) { n ->
            if (n.isClickable || n.parent?.isClickable == true) label(n)?.let { if (it.length <= 40) out.add(it) }
        }
        if (out.isEmpty()) say("Ekranda adı olan bir düğme bulamadım.")
        else Speaker.speak(this, "Şunlara dokunabilirsin: " + out.take(25).joinToString(", "))
        return true
    }

    /** Görünen yazısı söylenene en çok benzeyen öğeye dokunur. */
    fun clickText(query: String, reportMissing: Boolean): Boolean {
        val q = FileTools.norm(query)
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        walk(rootInActiveWindow, 0) { n ->
            val l = label(n) ?: return@walk
            val ln = FileTools.norm(l)
            if (ln.isEmpty()) return@walk
            val score = when {
                ln == q -> 100
                ln.startsWith(q) -> 80
                q.startsWith(ln) && ln.length >= 3 -> 70   // "ayarlara" ~ "ayarlar"
                ln.contains(" $q") || ln.contains(q) && q.length >= 4 -> 50
                else -> 0
            } - minOf(ln.length / 20, 10)
            if (score > bestScore) { bestScore = score; best = n }
        }
        val n = best
        if (n == null) {
            if (reportMissing) say("\"$query\" ekranda bulunamadı. \"Neler var\" diyerek seçenekleri dinleyebilirsin.")
            return false
        }
        var c: AccessibilityNodeInfo? = n
        var hops = 0
        while (c != null && !c.isClickable && hops < 6) { c = c.parent; hops++ }
        if (c != null && c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect()
        n.getBoundsInScreen(r)
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow
        var field = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (field == null || !field.isEditable) {
            field = null
            walk(root, 0) { n -> if (field == null && n.isEditable) field = n }
        }
        val f = field
        if (f == null) {
            say("Yazı yazılacak bir kutu bulamadım.")
            return true
        }
        f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return true
    }

    private fun say(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        @Volatile
        var instance: AppLockService? = null
            private set
    }

    // ---------------- uygulama kilidi ----------------

    /** Şu an D-FON'dan izinle açılmış ve hâlâ kullanılan kilitli uygulama. */
    private var session: String? = null
    private var lastBlock = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (ignored(pkg)) return

        // Başka bir uygulamaya (ana ekran dahil) geçildiyse oturum biter.
        // İzin penceresi gibi simgesi olmayan sistem ekranları oturumu bozmaz.
        if (pkg != session && isRealApp(pkg)) session = null
        if (!AppLock.isLocked(this, pkg)) return
        if (session == pkg) return
        if (AppLock.takePass(this, pkg)) {
            session = pkg
            return
        }
        block(pkg)
    }

    private fun block(pkg: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = System.currentTimeMillis()
        if (now - lastBlock > 2000) {
            lastBlock = now
            val name = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                "Bu uygulama"
            }
            Toast.makeText(this, "🔒 $name sadece D-FON'dan açılır.", Toast.LENGTH_SHORT).show()
        }
    }

    private val realCache = HashMap<String, Boolean>()

    private fun isRealApp(pkg: String): Boolean = realCache.getOrPut(pkg) {
        packageManager.getLaunchIntentForPackage(pkg) != null || isHome(pkg)
    }

    private fun isHome(pkg: String): Boolean {
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(i, 0).any { it.activityInfo.packageName == pkg }
    }

    /** Klavye, bildirim perdesi, sistem pencereleri ve D-FON'un kendisi oturumu bozmasın. */
    private fun ignored(pkg: String): Boolean {
        if (pkg == "android" || pkg == "com.android.systemui") return true
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() {}
}
