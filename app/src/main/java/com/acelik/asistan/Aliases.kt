package com.acelik.asistan

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Kullanıcının öğrettiği sözler: "ışığı yak" → "feneri aç" gibi.
 * D-FON'un anlamadığı bir söz, bildiği bir komuta bağlanır. Telefonda saklanır.
 */
object Aliases {

    private fun prefs(c: Context) = c.getSharedPreferences("dfon", Context.MODE_PRIVATE)

    /** söz (sadeleştirilmiş) → komut */
    fun all(c: Context): Map<String, String> {
        val raw = prefs(c).getString("ogretilenler", null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun save(c: Context, m: Map<String, String>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs(c).edit().putString("ogretilenler", o.toString()).apply()
    }

    fun get(c: Context, normalized: String): String? = all(c)[normalized]

    fun put(c: Context, phrase: String, command: String) = save(c, all(c) + (Nlu.norm(phrase) to command.trim()))

    fun remove(c: Context, key: String) = save(c, all(c) - key)

    /** Öğrettiklerim penceresi: listele, sil, yeni ekle. prefill: en son anlaşılamayan söz. */
    fun show(a: Activity, prefill: String?, done: (String) -> Unit) {
        val m = all(a)
        val keys = m.keys.sorted()
        val b = AlertDialog.Builder(a).setTitle("🎓 Öğrettiklerim")
        if (keys.isEmpty()) {
            b.setMessage(
                "Anlamadığım bir sözü bildiğim bir komuta bağlayabilirsin.\n\nÖrnek: \"ışığı yak\" dersen → \"feneri aç\" yap."
            )
        } else {
            b.setItems(keys.map { "\"$it\" → ${m[it]}   🗑" }.toTypedArray()) { _, w ->
                remove(a, keys[w])
                done("🗑 \"${keys[w]}\" silindi.")
            }
        }
        b.setPositiveButton("➕ Yeni öğret") { _, _ -> add(a, prefill, done) }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun add(a: Activity, prefill: String?, done: (String) -> Unit) {
        val phrase = EditText(a).apply {
            hint = "Ben şöyle dersem… (ör. ışığı yak)"
            setText(prefill ?: "")
        }
        val command = EditText(a).apply { hint = "…şunu yap (ör. feneri aç)" }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
            addView(phrase)
            addView(command)
            addView(TextView(a).apply {
                text = "İkinci kutuya D-FON'un bildiği bir komutu ya da bir rutinin adını yaz."
                textSize = 12f
            })
        }
        AlertDialog.Builder(a)
            .setTitle("🎓 Yeni söz öğret")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                val p = phrase.text.toString().trim()
                val cmd = command.text.toString().trim()
                if (p.isEmpty() || cmd.isEmpty()) {
                    done("İki kutu da dolu olmalı; kaydedilmedi.")
                } else if (Nlu.norm(p) == Nlu.norm(cmd)) {
                    done("Söz ile komut aynı olamaz; kaydedilmedi.")
                } else {
                    put(a, p, cmd)
                    done("🎓 Öğrendim: \"$p\" dersen \"$cmd\" yapacağım.")
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }
}
