package com.acelik.asistan

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
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

    /** "annemi", "ahmet'i", "ayşeye" → rehberdeki en uygun kişi(ler). */
    fun find(query: String): List<Contact> {
        val variants = Nlu.nameVariants(query)
        if (variants.isEmpty()) return emptyList()
        var best = 0
        val hits = mutableListOf<Contact>()
        for (c in load()) {
            val n = FileTools.norm(c.name)
            val first = n.split(" ").firstOrNull() ?: ""
            var score = 0
            variants.forEachIndexed { vi, q ->
                // Eki atılmış biçimlerde sadece tam ad / ilk ad eşleşmesi (yanlış kişiyi aramamak için)
                val sc = when {
                    n == q -> 100
                    first == q -> 85
                    n.startsWith("$q ") -> 80
                    vi == 0 && q.length >= 3 && n.contains(q) -> 50
                    else -> 0
                } - vi * 3
                if (sc > score) score = sc
            }
            if (score <= 0) continue
            if (score > best) { best = score; hits.clear() }
            if (score == best) hits.add(c)
        }
        return hits
    }

    /** Aramadan önce 3 saniyelik iptal süresi verir (yanlış anlaşılmaya karşı). */
    fun call(c: Contact): String {
        val uri = Uri.fromParts("tel", c.number, null)
        if (!hasCall()) {
            activity.onMain { activity.startActivity(Intent(Intent.ACTION_DIAL, uri)) }
            return "📞 ${c.name} numarası açıldı, aramak için yeşil tuşa bas. (Doğrudan araması için \"arama\" iznini ver.)"
        }
        activity.onMain {
            var cancelled = false
            val d = AlertDialog.Builder(activity)
                .setTitle("📞 ${c.name}")
                .setMessage("${c.number}\n\n3 saniye içinde aranacak…")
                .setNegativeButton("İptal") { _, _ -> cancelled = true }
                .setOnCancelListener { cancelled = true }
                .show()
            Handler(Looper.getMainLooper()).postDelayed({
                if (!cancelled && !activity.isFinishing) {
                    try { d.dismiss() } catch (ignored: Exception) {}
                    try { activity.startActivity(Intent(Intent.ACTION_CALL, uri)) } catch (ignored: Exception) {}
                }
            }, 3000)
        }
        return "📞 ${c.name} 3 saniye içinde aranacak (ekrandan iptal edebilirsin)."
    }

    /** WhatsApp sohbetini mesaj yazılı halde açar; göndermek için gönder tuşuna basılır. */
    fun whatsapp(c: Contact, body: String): String {
        Guard.blockedReason(activity, WHATSAPP, "WhatsApp")?.let { return it }
        var d = c.number.filter { it.isDigit() }
        when {
            c.number.trim().startsWith("+") -> {}
            d.startsWith("00") -> d = d.drop(2)
            d.startsWith("0") && d.length == 11 -> d = "9$d"
            d.length == 10 && d.startsWith("5") -> d = "90$d"
        }
        val url = "https://wa.me/$d" + if (body.isNotBlank()) "?text=" + java.net.URLEncoder.encode(body, "UTF-8") else ""
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(WHATSAPP)
        return try {
            AppLock.allow(activity, WHATSAPP)
            activity.onMain { activity.startActivity(i) }
            "💬 ${c.name} için WhatsApp açıldı" + (if (body.isNotBlank()) ": \"$body\". Göndermek için gönder tuşuna bas." else ".")
        } catch (e: Exception) {
            "WhatsApp açılamadı (yüklü olmayabilir)."
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
        private const val WHATSAPP = "com.whatsapp"
    }
}
