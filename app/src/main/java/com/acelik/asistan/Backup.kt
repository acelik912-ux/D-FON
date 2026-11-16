package com.acelik.asistan

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Yedekle / geri yükle: rutinler, notlar, düğme düzeni, kilitler, namaz ayarları, temalar…
 * Tek bir dosyaya yazılır; dosyayı nereye koyacağını kullanıcı seçer. Hiçbir yere gönderilmez.
 */
object Backup {

    const val REQ_SAVE = 51
    const val REQ_LOAD = 52
    private val FILES = listOf("dfon", "namaz", "kilit")

    /** Geçici durumlar yedeğe girmez. */
    private fun skip(key: String) = key.startsWith("kul_") || key.startsWith("ek_") ||
        key in setOf("izin_pkg", "izin_zaman", "biz_aldik", "bizim_mod", "geri_al", "son_ezan", "son_olay")

    fun toJson(c: Context): String {
        val root = JSONObject().put("v", 1).put("surum", Diag.version(c)).put("tarih", System.currentTimeMillis())
        val prefs = JSONObject()
        for (name in FILES) {
            val o = JSONObject()
            for ((k, v) in c.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                if (v == null || skip(k)) continue
                val e = JSONObject()
                when (v) {
                    is Boolean -> e.put("t", "b").put("v", v)
                    is Int -> e.put("t", "i").put("v", v)
                    is Long -> e.put("t", "l").put("v", v)
                    is Float -> e.put("t", "f").put("v", v.toDouble())
                    is String -> e.put("t", "s").put("v", v)
                    is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> continue
                }
                o.put(k, e)
            }
            prefs.put(name, o)
        }
        return root.put("prefs", prefs).toString(1)
    }

    /** @return geri yüklenen ayar sayısı */
    fun fromJson(c: Context, text: String): Int {
        val root = JSONObject(text)
        val prefs = root.getJSONObject("prefs")
        var count = 0
        for (name in FILES) {
            val o = prefs.optJSONObject(name) ?: continue
            val sp = c.getSharedPreferences(name, Context.MODE_PRIVATE)
            val e = sp.edit()
            for (k in sp.all.keys) if (!skip(k)) e.remove(k)
            for (k in o.keys()) {
                val item = o.getJSONObject(k)
                when (item.getString("t")) {
                    "b" -> e.putBoolean(k, item.getBoolean("v"))
                    "i" -> e.putInt(k, item.getInt("v"))
                    "l" -> e.putLong(k, item.getLong("v"))
                    "f" -> e.putFloat(k, item.getDouble("v").toFloat())
                    "s" -> e.putString(k, item.getString("v"))
                    "set" -> {
                        val arr = item.getJSONArray("v")
                        e.putStringSet(k, (0 until arr.length()).map { arr.getString(it) }.toHashSet())
                    }
                }
                count++
            }
            e.commit()
        }
        return count
    }

    fun menu(a: Activity) {
        AlertDialog.Builder(a)
            .setTitle("💾 Yedekleme")
            .setMessage(
                "Rutinlerin, notların, düğme düzenin, kilitlerin, namaz ve tema ayarların tek bir dosyaya yazılır. " +
                    "Uygulamayı silmen ya da telefon değiştirmen gerekirse bu dosyadan geri yüklersin."
            )
            .setPositiveButton("Yedek al") { _, _ ->
                val name = "D-FON-yedek-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json"
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, name)
                @Suppress("DEPRECATION")
                a.startActivityForResult(i, REQ_SAVE)
            }
            .setNeutralButton("Geri yükle") { _, _ ->
                Guard.ask(a, "Yedekten geri yükle", force = true) {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    @Suppress("DEPRECATION")
                    a.startActivityForResult(i, REQ_LOAD)
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    /** MainActivity.onActivityResult'tan çağrılır. İşlediyse kullanıcıya gösterilecek metni döner. */
    fun onResult(a: Activity, requestCode: Int, uri: Uri?): String? {
        if (requestCode != REQ_SAVE && requestCode != REQ_LOAD) return null
        if (uri == null) return "Yedekleme iptal edildi."
        return try {
            if (requestCode == REQ_SAVE) {
                a.contentResolver.openOutputStream(uri)?.use { it.write(toJson(a).toByteArray(Charsets.UTF_8)) }
                Diag.log(a, "yedek", "Yedek alındı")
                "💾 Yedek alındı. Dosyayı güvenli bir yerde sakla (Drive, e-posta ya da bilgisayar)."
            } else {
                val text = a.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: return "Dosya okunamadı."
                val n = fromJson(a, text)
                Prayer.recover(a)
                Prayer.refreshExtras(a)
                Routines.schedule(a)
                Reminders.schedule(a)
                Diag.log(a, "yedek", "Geri yüklendi: $n ayar")
                a.recreate()
                "♻ $n ayar geri yüklendi."
            }
        } catch (e: Exception) {
            "Yedek işlemi başarısız: ${e.message ?: e.javaClass.simpleName}. Dosya bir D-FON yedeği olmayabilir."
        }
    }
}
