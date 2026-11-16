package com.acelik.asistan

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kilit şifresi (PIN / parmak izi) ve kilitli uygulamalar için günlük süre sınırı.
 * Şifre telefonda, tuzlanmış özet (SHA-256) olarak saklanır; düz hali tutulmaz.
 */
object Guard {

    private fun prefs(c: Context) = c.getSharedPreferences("kilit", Context.MODE_PRIVATE)

    // ---------------- şifre ----------------

    fun hasPin(c: Context) = prefs(c).contains("pin_ozet")

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun setPin(c: Context, pin: String?) {
        val e = prefs(c).edit()
        if (pin == null) {
            e.remove("pin_ozet").remove("pin_tuz")
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            e.putString("pin_tuz", salt).putString("pin_ozet", hash(salt, pin))
        }
        e.apply()
    }

    fun checkPin(c: Context, pin: String): Boolean {
        val p = prefs(c)
        val salt = p.getString("pin_tuz", null) ?: return false
        return hash(salt, pin) == p.getString("pin_ozet", null)
    }

    fun biometricOn(c: Context) = prefs(c).getBoolean("biyometrik", true)

    @Volatile
    private var authedUntil = 0L
    private var wrong = 0
    private var blockedUntil = 0L

    /** Şifre az önce (1 dakika içinde) doğrulandı mı? */
    fun authed() = System.currentTimeMillis() < authedUntil
    private fun markAuthed() {
        authedUntil = System.currentTimeMillis() + 60_000
        wrong = 0
    }

    /**
     * Şifre belirlenmişse sorar (önce parmak izi, olmazsa PIN); doğruysa ok() çalışır.
     * Şifre yoksa doğrudan ok(). force: az önce doğrulanmış olsa bile yine sor.
     */
    fun ask(a: Activity, title: String, force: Boolean = false, ok: () -> Unit) {
        if (!hasPin(a) || (!force && authed())) { ok(); return }
        if (biometricOn(a)) biometric(a, title, ok) else pinDialog(a, title, ok)
    }

    private fun biometric(a: Activity, title: String, ok: () -> Unit) {
        try {
            val exec = a.mainExecutor
            val prompt = BiometricPrompt.Builder(a)
                .setTitle("D-FON kilidi")
                .setSubtitle(title)
                .setNegativeButton("Şifre gir", exec) { _, _ -> pinDialog(a, title, ok) }
                .build()
            prompt.authenticate(CancellationSignal(), exec, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    markAuthed()
                    ok()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    // 5 = iptal edildi, 10 = kullanıcı vazgeçti; diğerlerinde (parmak izi yok, kilitlendi…) şifreye geç
                    if (errorCode != 5 && errorCode != 10) pinDialog(a, title, ok)
                }
            })
        } catch (e: Exception) {
            pinDialog(a, title, ok)
        }
    }

    private fun pinField(a: Activity, hint: String) = EditText(a).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun pinDialog(a: Activity, title: String, ok: () -> Unit) {
        if (a.isFinishing) return
        val wait = blockedUntil - System.currentTimeMillis()
        if (wait > 0) {
            Toast.makeText(a, "Çok fazla yanlış deneme. ${wait / 1000 + 1} sn bekle.", Toast.LENGTH_LONG).show()
            return
        }
        val field = pinField(a, "Şifre")
        val box = LinearLayout(a).apply {
            setPadding(60, 20, 60, 0)
            addView(field, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(a)
            .setTitle("🔑 $title")
            .setView(box)
            .setPositiveButton("Tamam") { _, _ ->
                if (checkPin(a, field.text.toString())) {
                    markAuthed()
                    ok()
                } else {
                    wrong++
                    if (wrong >= 5) { blockedUntil = System.currentTimeMillis() + 30_000; wrong = 0 }
                    Toast.makeText(a, "Yanlış şifre", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** Şifre belirleme / değiştirme / kaldırma ekranı. */
    fun setup(a: Activity, done: (String) -> Unit) {
        if (!hasPin(a)) { newPin(a, done); return }
        ask(a, "Kilit ayarları", force = true) {
            val bio = biometricOn(a)
            AlertDialog.Builder(a)
                .setTitle("🔑 Kilit şifresi")
                .setItems(arrayOf("Şifreyi değiştir", "Şifreyi kaldır", if (bio) "Parmak izini kapat" else "Parmak izini aç")) { _, w ->
                    when (w) {
                        0 -> newPin(a, done)
                        1 -> { setPin(a, null); done("🔓 Kilit şifresi kaldırıldı.") }
                        else -> {
                            prefs(a).edit().putBoolean("biyometrik", !bio).apply()
                            done(if (bio) "Parmak izi kapatıldı; sadece şifre sorulacak." else "Parmak izi açıldı.")
                        }
                    }
                }
                .setNegativeButton("Kapat", null)
                .show()
        }
    }

    private fun newPin(a: Activity, done: (String) -> Unit) {
        val p1 = pinField(a, "Yeni şifre (4-8 rakam)")
        val p2 = pinField(a, "Tekrar")
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
            addView(TextView(a).apply {
                text = "Bu şifre; kilitli uygulamaları açarken, kilidi kaldırırken ve süre sınırını değiştirirken sorulur. " +
                    "Telefonda kayıtlı parmak izin de geçerlidir. Şifreyi unutursan sıfırlamanın tek yolu D-FON'un " +
                    "verilerini silmektir (önce yedek al)."
                textSize = 13f
            })
            addView(p1)
            addView(p2)
        }
        AlertDialog.Builder(a)
            .setTitle("🔑 Kilit şifresi belirle")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                val a1 = p1.text.toString()
                when {
                    a1.length !in 4..8 -> done("Şifre 4-8 rakam olmalı; kaydedilmedi.")
                    a1 != p2.text.toString() -> done("İki şifre aynı değil; kaydedilmedi.")
                    else -> { setPin(a, a1); markAuthed(); done("🔑 Kilit şifresi kaydedildi.") }
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---------------- günlük süre sınırı ----------------

    private fun day() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** Günlük sınır (dakika); 0 = sınır yok. */
    fun limit(c: Context, pkg: String) = prefs(c).getInt("sure_$pkg", 0)

    fun setLimit(c: Context, pkg: String, minutes: Int) {
        val e = prefs(c).edit()
        if (minutes <= 0) e.remove("sure_$pkg") else e.putInt("sure_$pkg", minutes)
        e.apply()
    }

    fun usedMs(c: Context, pkg: String) = prefs(c).getLong("kul_${pkg}_${day()}", 0L)
    private fun bonusMs(c: Context, pkg: String) = prefs(c).getLong("ek_${pkg}_${day()}", 0L)

    fun addUsage(c: Context, pkg: String, ms: Long) {
        if (ms <= 0) return
        val p = prefs(c)
        val today = day()
        val e = p.edit().putLong("kul_${pkg}_$today", usedMs(c, pkg) + ms)
        // eski günlerin kayıtlarını temizle
        for (k in p.all.keys) if ((k.startsWith("kul_") || k.startsWith("ek_")) && !k.endsWith("_$today")) e.remove(k)
        e.apply()
    }

    fun addBonus(c: Context, pkg: String, minutes: Int) {
        prefs(c).edit().putLong("ek_${pkg}_${day()}", bonusMs(c, pkg) + minutes * 60_000L).apply()
    }

    /** Bugün kalan süre (ms). Sınır yoksa çok büyük bir sayı. */
    fun remainingMs(c: Context, pkg: String): Long {
        val l = limit(c, pkg)
        if (l <= 0) return Long.MAX_VALUE / 4
        return l * 60_000L + bonusMs(c, pkg) - usedMs(c, pkg)
    }

    fun remainingText(c: Context, pkg: String): String? {
        if (limit(c, pkg) <= 0) return null
        val m = Math.ceil(remainingMs(c, pkg).coerceAtLeast(0) / 60_000.0).toInt()
        return if (m <= 0) "süre doldu" else "$m dk kaldı"
    }

    /** Sesle/yazıyla açma ve dosya gönderme için: uygulama şu an açılamıyorsa nedenini döner. */
    fun blockedReason(c: Context, pkg: String, name: String): String? {
        if (!AppLock.isLocked(c, pkg)) return null
        if (limit(c, pkg) > 0 && remainingMs(c, pkg) <= 0) {
            return "⏳ $name için bugünkü süre (${limit(c, pkg)} dk) doldu. Yarın yenilenir."
        }
        if (hasPin(c) && !authed()) {
            return "🔒 $name şifreyle kilitli. Açmak için D-FON'daki düğmesine dokunup şifreni gir."
        }
        return null
    }
}
