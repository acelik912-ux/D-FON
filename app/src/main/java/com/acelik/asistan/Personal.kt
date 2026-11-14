package com.acelik.asistan

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sesli notlar: telefonda saklanır, hiçbir yere gönderilmez. */
object Notes {
    data class Note(val text: String, val time: Long)

    private fun prefs(c: Context) = c.getSharedPreferences("dfon", Context.MODE_PRIVATE)
    private val fmt = SimpleDateFormat("d MMM HH:mm", Locale("tr"))

    fun all(c: Context): List<Note> {
        val raw = prefs(c).getString("notlar", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Note(o.getString("m"), o.getLong("z")) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(c: Context, list: List<Note>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("m", it.text).put("z", it.time)) }
        prefs(c).edit().putString("notlar", a.toString()).apply()
    }

    fun add(c: Context, text: String) = save(c, all(c) + Note(text, System.currentTimeMillis()))

    fun remove(c: Context, index: Int): Note? {
        val l = all(c).toMutableList()
        if (index !in l.indices) return null
        val n = l.removeAt(index)
        save(c, l)
        return n
    }

    fun clear(c: Context) = save(c, emptyList())

    fun listText(c: Context): String {
        val l = all(c)
        if (l.isEmpty()) return "📝 Hiç notun yok. \"Not al: …\" diyerek ekleyebilirsin."
        return "📝 Notların:\n" + l.mapIndexed { i, n -> "${i + 1}) ${n.text}  (${fmt.format(Date(n.time))})" }.joinToString("\n")
    }

    /** Sesli okumak için sade hali. */
    fun speakText(c: Context): String {
        val l = all(c)
        if (l.isEmpty()) return "Hiç notun yok."
        return "${l.size} notun var. " + l.mapIndexed { i, n -> "${i + 1}: ${n.text}." }.joinToString(" ")
    }
}

/** Rehberden arama ve mesaj. Rehber telefonda okunur, dışarı gönderilmez. */
class ContactTools(private val activity: Activity) {

    data class Contact(val name: String, val number: String)

    fun hasContacts() = activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    private fun hasCall() = activity.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    fun requestPermissions() {
        activity.onMain {
            activity.requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), REQ_CONTACTS
            )
        }
    }

    private fun load(): List<Contact> {
        if (!hasContacts()) return emptyList()
        val out = LinkedHashMap<String, Contact>()
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        activity.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0) ?: continue
                val num = cur.getString(1) ?: continue
                val mobile = cur.getInt(2) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                // Her kişi için bir numara: cep varsa cep
                if (!out.containsKey(name) || mobile) out[name] = Contact(name, num)
            }
        }
        return out.values.toList()
    }

    /** "annemi", "ahmet i", "ayşeye" → rehberdeki en uygun kişi(ler). */
    fun find(query: String): List<Contact> {
        val variants = variants(FileTools.norm(query))
        if (variants.isEmpty()) return emptyList()
        var best = 0
        val hits = mutableListOf<Contact>()
        for (c in load()) {
            val n = FileTools.norm(c.name)
            val first = n.split(" ").firstOrNull() ?: ""
            val score = variants.maxOf { q ->
                when {
                    n == q -> 100
                    first == q -> 85
                    n.startsWith("$q ") -> 80
                    q.length >= 3 && n.contains(q) -> 50
                    else -> 0
                }
            }
            if (score == 0) continue
            if (score > best) { best = score; hits.clear() }
            if (score == best) hits.add(c)
        }
        return hits
    }

    private fun variants(q: String): List<String> {
        val words = q.split(" ").filter { it.isNotEmpty() }.toMutableList()
        // "ahmet i ara" gibi kesme işaretinden kalan ekleri at
        while (words.size > 1 && words.last().length <= 3 && words.last() in SUFFIXES) words.removeAt(words.size - 1)
        val base = words.joinToString(" ")
        if (base.isEmpty()) return emptyList()
        val out = linkedSetOf(base)
        for (s in SUFFIXES.sortedByDescending { it.length }) {
            if (base.endsWith(s) && base.length - s.length >= 3) out.add(base.removeSuffix(s))
        }
        return out.toList()
    }

    fun call(c: Contact): String {
        return if (hasCall()) {
            activity.onMain { activity.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${c.number}"))) }
            "📞 ${c.name} aranıyor…"
        } else {
            activity.onMain { activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.number}"))) }
            "📞 ${c.name} numarası açıldı, aramak için yeşil tuşa bas. (Doğrudan araması için \"arama\" iznini ver.)"
        }
    }

    fun message(c: Contact, body: String): String {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${c.number}"))
        if (body.isNotBlank()) i.putExtra("sms_body", body)
        activity.onMain { activity.startActivity(i) }
        return "✉ ${c.name} için mesaj hazır" + (if (body.isNotBlank()) ": \"$body\". Göndermek için gönder tuşuna bas." else ".")
    }

    /** Birden fazla kişi eşleşirse seçtirir. */
    fun choose(list: List<Contact>, then: (Contact) -> Unit) {
        activity.onMain {
            AlertDialog.Builder(activity)
                .setTitle("Hangisi?")
                .setItems(list.map { "${it.name}  ${it.number}" }.toTypedArray()) { _, w -> Thread { then(list[w]) }.start() }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
    }

    companion object {
        const val REQ_CONTACTS = 45
        private val SUFFIXES = setOf(
            "i", "u", "yi", "yu", "e", "a", "ye", "ya", "ni", "nu", "ne", "na", "in", "un", "nin", "nun", "le", "la", "yle", "yla"
        )
    }
}
