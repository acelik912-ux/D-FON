package com.acelik.asistan

import android.accessibilityservice.AccessibilityService
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
