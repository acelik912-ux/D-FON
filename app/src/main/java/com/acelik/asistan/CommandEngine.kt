package com.acelik.asistan

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar

/**
 * Türkçe komutları anlayan, tamamen telefonda çalışan motor.
 * Yapay zekaya ya da internete bağlanmaz; anahtar kelime ve kurallarla çalışır.
 */
class CommandEngine(
    private val phone: PhoneTools,
    private val files: FileTools,
    private val clock: ClockTools,
    private val ctx: android.content.Context? = null,
    private val contacts: ContactTools? = null
) {

    enum class Action { PDF_TO_WORD, WORD_TO_PDF, IMAGES_TO_PDF, PDF_TO_IMAGES, MERGE_PDF, READ }

    /** Dosya seçilince yapılacak işlem. */
    private var pending: Action? = null

    /** 📎 ile eklenip henüz kullanılmamış dosyalar. */
    private val fresh = mutableListOf<Int>()

    fun reset() {
        pending = null
        fresh.clear()
    }

    /** Soru sorduğumuzda ("Alarm saat kaçta?") bir sonraki kısa cevabı bu komuta bağlarız. */
    private class FollowUp(val prefix: String, val any: Boolean)
    private var followUp: FollowUp? = null

    /** "Bunu mu demek istedin?" diye önerilen ve "evet" bekleyen komut. */
    private var suggestion: String? = null

    /** En son anlaşılamayan söz ("Öğret" penceresi bunu hazır getirir). */
    var lastUnknown: String? = null
        private set

    /** Pencere açması gereken komutlar için (kıble pusulası, imsakiye): MainActivity bağlar. */
    var onUi: ((String) -> Unit)? = null

    /** Telefonda yapay zeka yüklüyse, anlaşılamayan sözü ona sorar (yoksa null). */
    var askAi: ((String) -> String?)? = null

    private fun ask(prefix: String, question: String, any: Boolean = false): String {
        followUp = FollowUp(prefix, any)
        return question
    }

    fun handle(raw: String): List<String> {
        var text = raw.trim()
        if (text.isEmpty()) return emptyList()
        var t = norm(text)

        // Bekleyen öneri: "evet" → çalıştır
        val sug = suggestion
        suggestion = null
        if (sug != null) {
            if (t in YES) return handle(sug)
            if (t in NO) return listOf("Tamam, vazgeçtim.")
        }

        // Bekleyen soru: kısa cevabı sorulan komuta bağla ("Alarm saat kaçta?" → "7 buçuk")
        val fu = followUp
        followUp = null
        if (fu != null) {
            if (t in NO) return listOf("Tamam, vazgeçtim.")
            val answerLike = tokens(t).size <= 5 &&
                (fu.any || number(t) != null || has(t, "yari", "tam", "en yuksek", "en dusuk", "bucuk", "biraz"))
            if (answerLike) {
                text = "${fu.prefix} $text"
                t = norm(text)
            }
        }

        // Kullanıcının öğrettiği söz
        ctx?.let { c -> Aliases.get(c, t)?.let { text = it; t = norm(it) } }

        if (has(t, "yardim", "neler yapabilirsin", "ne yapabilirsin", "komutlar")) return listOf(HELP)

        core(text, t)?.let { return it }

        // Anlaşılmadı: çok benzer bir komut varsa onu yap, biraz benzeri varsa sor
        lastUnknown = text
        val best = bestMatch(t)
        if (best != null) {
            if (best.second >= 0.90) {
                core(best.first, norm(best.first))?.let { return listOf("(\"${best.first}\" olarak anladım)") + it }
            } else if (best.second >= 0.68) {
                suggestion = best.first
                return listOf("Bunu mu demek istedin: \"${best.first}\"? (evet / hayır)")
            }
        }
        askAi?.let { ai ->
            try { ai(text)?.let { return listOf(it) } } catch (ignored: Exception) {}
        }
        return listOf(unknown(t))
    }

    /** Bilinen komutlar, rutin adları, öğretilen sözler ve izinli uygulamalar içinde en benzeri. */
    private fun bestMatch(t: String): Pair<String, Double>? {
        if (t.length < 4) return null
        val candidates = ArrayList<String>(EXAMPLES)
        ctx?.let { c ->
            candidates.addAll(Routines.all(c).map { it.name })
            candidates.addAll(Aliases.all(c).keys)
        }
        candidates.addAll(files.allowedApps().map { "${it.label} aç" })
        val num = Regex("\\d+").find(t)?.value
        var best: Pair<String, Double>? = null
        for (cand in candidates) {
            val sc = Nlu.similarity(t, norm(cand).replace(Regex("\\d+"), num ?: "0"))
            if (best == null || sc > best.second) {
                best = (if (num != null) cand.replace(Regex("\\d+"), num) else cand) to sc
            }
        }
        return best
    }

    /** Asıl komut çözücü; anlaşılmazsa null. */
    private fun core(text: String, t: String): List<String>? {
        routineCommand(t)?.let { return it }
        reminderCommand(text, t)?.let { return listOf(it) }
        notesCommand(text, t)?.let { return listOf(it) }
        stopReading(t)?.let { return listOf(it) }
        textToDocument(text)?.let { return listOf(it) }
        contactCommand(text, t)?.let { return listOf(it) }
        webCommand(text)?.let { return listOf(it) }
        prayerCommand(t)?.let { return listOf(it) }
        readCommand(t)?.let { return listOf(it) }
        fileCommand(t)?.let { return listOf(it) }

        // Birden fazla komut: "parlaklığı 30 yap ve sessize al"
        val parts = text.split(Regex("\\s+ve\\s+|\\s+sonra\\s+|,\\s*|;\\s*"))
            .map { norm(it) }.filter { it.isNotBlank() }
        if (parts.size > 1) {
            val r = parts.map { single(it) }
            if (r.all { it != null }) return r.filterNotNull()
            if (r.any { it != null }) return parts.indices.map { r[it] ?: unknown(parts[it]) }
        }
        single(t)?.let { return listOf(it) }
        return null
    }

    fun onFilesPicked(nos: List<Int>): List<String> {
        val a = pending
        pending = null
        if (nos.isEmpty()) return if (a != null) listOf("Dosya seçilmedi, işlem iptal edildi.") else emptyList()
        if (a == null) {
            fresh.clear()
            fresh.addAll(nos)
            return listOf(
                "📎 Eklendi:\n" + nos.joinToString("\n") { "$it) ${files.name(it)}" } +
                    "\n\nNe yapayım? Örn: \"Word'e çevir\", \"PDF yap\", \"birleştir\", \"dosyayı aç\", \"dosyayı WhatsApp'tan gönder\"."
            )
        }
        return listOf(perform(a, nos))
    }

    // ======================= tek komut =======================

    private fun single(t: String): String? {
        status(t)?.let { return it }
        dateTime(t)?.let { return it }
        clockCommand(t)?.let { return it }
        if (tokens(t).any { it.startsWith("ayarlar") || it.startsWith("ayari") || it.startsWith("ayarin") }) {
            settingsScreen(t)?.let { return it }
        }
        torch(t)?.let { return it }
        dnd(t)?.let { return it }
        nightMode(t)?.let { return it }
        ringer(t)?.let { return it }
        brightness(t)?.let { return it }
        volume(t)?.let { return it }
        timeout(t)?.let { return it }
        rotation(t)?.let { return it }
        settingsScreen(t)?.let { return it }
        appCommand(t)?.let { return it }
        return null
    }

    // ---------------- rutinler ----------------

    private var depth = 0

    private fun routineCommand(t: String): List<String>? {
        val c = ctx ?: return null
        if (t in setOf("rutinler", "rutinlerim", "rutinleri goster", "rutin listesi", "rutinlerimi goster")) {
            val l = Routines.all(c)
            return listOf(
                if (l.isEmpty()) "Henüz rutinin yok. Butonlar ekranındaki ⚡ Rutinlerim bölümünden ekleyebilirsin."
                else "⚡ Rutinlerin:\n" + l.joinToString("\n") { r ->
                    "• ${r.name}" + (r.time?.let { " (her gün $it)" } ?: "") + ": " + r.commands.joinToString(", ")
                }
            )
        }
        if (depth > 0) return null // rutin içinde rutin çalışmasın
        val r = Routines.match(c, t) ?: return null
        depth++
        try {
            val out = mutableListOf("▶ ${r.name}")
            r.commands.forEach { cmd -> out.addAll(core(cmd, norm(cmd)) ?: listOf(unknown(norm(cmd)))) }
            return listOf(out.joinToString("\n"))
        } finally {
            depth--
        }
    }

    // ---------------- hatırlatıcılar ----------------

    private fun reminderCommand(raw: String, t: String): String? {
        val c = ctx ?: return null
        val tk = tokens(t)
        if (tk.none { it.startsWith("hatirlat") }) return null
        val listWord = tk.any { it.startsWith("hatirlatici") || it.startsWith("hatirlatma") }
        if (listWord && tk.none { it == "kur" || it == "ekle" }) {
            if (tk.any { it.startsWith("sil") }) {
                val all = Reminders.all(c)
                if (all.isEmpty()) return "Silinecek hatırlatıcı yok."
                if (has(t, "hepsini", "tum", "butun")) {
                    all.forEach { Reminders.remove(c, it.id) }
                    return "🗑 Tüm hatırlatıcılar silindi."
                }
                val r = all.getOrNull((number(t) ?: all.size) - 1) ?: return "O numarada hatırlatıcı yok."
                Reminders.remove(c, r.id)
                return "🗑 Silindi: ${r.text}"
            }
            return Reminders.listText(c)
        }
        val cleaned = raw.replace(Regex(":(?!\\d)"), " ")
        val w = Nlu.parseWhen(cleaned, Calendar.getInstance())
        if (w == null) {
            val what = cleaned.trim().split(Regex("\\s+"))
                .filter { !norm(it).startsWith("hatirlat") && norm(it) !in setOf("bana", "beni") }.joinToString(" ")
            return ask("hatırlat $what", "Ne zaman hatırlatayım? Örn: \"yarın 10'da\", \"akşam 8'de\", \"20 dakika sonra\".", true)
        }
        if (w.text.isBlank()) return "Neyi hatırlatayım? Örn: \"yarın 10'da faturayı hatırlat\"."
        Reminders.add(c, w.text, w.time)
        return "⏰ Hatırlatıcı kuruldu: ${Reminders.whenText(w.time)} — ${w.text}"
    }

    // ---------------- notlar ----------------

    private fun notesCommand(raw: String, t: String): String? {
        val c = ctx ?: return null
        val tk = tokens(t)
        // "Not al: ekmek al", "not al süt al", "bir not al: …"
        val colon = raw.indexOf(':')
        if (colon > 0) {
            val head = norm(raw.substring(0, colon))
            if (head in setOf("not", "not al", "not et", "not yaz", "bir not al", "notla", "not ekle")) {
                val body = raw.substring(colon + 1).trim()
                if (body.isEmpty()) return "Ne not alayım? Örn: \"Not al: ekmek al\""
                Notes.add(c, body)
                return "📝 Not alındı: $body"
            }
        }
        if (tk.size > 2 && tk[0] == "not" && tk[1] in setOf("al", "et", "yaz", "ekle")) {
            val body = raw.trim().split(Regex("\\s+")).drop(2).joinToString(" ")
            Notes.add(c, body)
            return "📝 Not alındı: $body"
        }
        if (has(t, "notu sil", "notlari sil", "notlarimi sil", "notumu sil")) {
            if (has(t, "hepsini", "tum", "butun", "notlari sil", "notlarimi sil")) {
                Notes.clear(c)
                return "🗑 Tüm notlar silindi."
            }
            val all = Notes.all(c)
            if (all.isEmpty()) return "Silinecek not yok."
            val idx = if (has(t, "son")) all.size - 1 else (number(t)?.minus(1) ?: (all.size - 1))
            val n = Notes.remove(c, idx) ?: return "O numarada not yok. \"Notlarım\" diyerek listeye bakabilirsin."
            return "🗑 Silindi: ${n.text}"
        }
        if (tk.any { it.startsWith("notlar") || it == "notum" || it == "notumu" }) {
            if (tk.any { it.startsWith("oku") }) Speaker.speak(c, Notes.speakText(c))
            return Notes.listText(c)
        }
        return null
    }

    // ---------------- kişiler ----------------

    private val sendStems = listOf("yaz", "at", "gonder", "yolla")
    private val waWords = listOf("whatsapp", "vatsap", "watsap", "whatsap")

    private fun contactCommand(raw: String, t: String): String? {
        val ct = contacts ?: return null
        val tk = tokens(t)
        val whatsapp = tk.any { w -> waWords.any { w.startsWith(it) } }
        // "Ahmet'e mesaj yaz geliyorum", "Ahmet'e mesaj yaz: geliyorum", "Ahmet'e WhatsApp'tan yaz geliyorum"
        val mi = tk.indexOf("mesaj")
        val vi = when {
            mi > 0 -> mi
            whatsapp -> tk.indexOfFirst { Nlu.isVerb(it, sendStems) }
            else -> -1
        }
        if (vi > 0) {
            val nameTk = tk.subList(0, vi).filter { w ->
                waWords.none { w.startsWith(it) } &&
                    w !in setOf("tan", "dan", "ten", "den", "ta", "da", "te", "de", "uzerinden", "ile", "sms", "bir")
            }
            if (nameTk.isNotEmpty()) {
                val body = if (raw.contains(':')) raw.substringAfter(':').trim() else messageBody(raw)
                return withContact(ct, nameTk.joinToString(" ")) {
                    if (whatsapp) ct.whatsapp(it, body) else ct.message(it, body)
                }
            }
        }
        // "Annemi ara", "Ahmet'i arar mısın"
        var callTk = tk
        while (callTk.size > 1 && callTk.last() in setOf("misin", "misiniz", "lutfen")) callTk = callTk.dropLast(1)
        if (callTk.size >= 2 && Nlu.isVerb(callTk.last(), listOf("ara"))) {
            var nameTk = callTk.dropLast(1)
            if (nameTk.lastOrNull() == "telefonla") nameTk = nameTk.dropLast(1)
            val first = nameTk.firstOrNull() ?: return null
            if (listOf("google", "internet", "web", "youtube", "vikipedi", "wikipedi", "harita").any { first.startsWith(it) }) return null
            val real = nameTk.filter { it.length > 3 || it !in setOf("i", "u", "yi", "yu", "e", "a", "ye", "ya") }
            if (!ct.hasContacts()) {
                if (real.size == 1) {
                    ct.requestPermissions()
                    return "📇 Rehberine bakabilmem için izin ver, sonra tekrar söyle. (İnternette aramak istersen \"internette … ara\" de.)"
                }
                return null
            }
            val hits = ct.find(nameTk.joinToString(" "))
            if (hits.isEmpty()) return null // rehberde yoksa internette ara
            return pickContact(ct, hits) { ct.call(it) }
        }
        return null
    }

    /** "… mesaj yaz geliyorum" → "geliyorum" (özgün yazımıyla). */
    private fun messageBody(raw: String): String {
        val words = raw.trim().split(Regex("\\s+"))
        val n = words.map { norm(it) }
        var i = n.indexOf("mesaj")
        if (i < 0) i = n.indexOfFirst { Nlu.isVerb(it, sendStems) } - 1
        if (i < -1) return ""
        var rest = words.drop(i + 1)
        if (rest.isNotEmpty() && Nlu.isVerb(norm(rest[0]), sendStems)) rest = rest.drop(1)
        return rest.joinToString(" ").trim()
    }

    private fun withContact(ct: ContactTools, name: String, action: (ContactTools.Contact) -> String): String {
        if (!ct.hasContacts()) {
            ct.requestPermissions()
            return "📇 Rehberine bakabilmem için izin ver, sonra tekrar söyle."
        }
        val hits = ct.find(name)
        if (hits.isEmpty()) return "📇 Rehberde \"$name\" bulamadım."
        return pickContact(ct, hits, action)
    }

    private fun pickContact(ct: ContactTools, hits: List<ContactTools.Contact>, action: (ContactTools.Contact) -> String): String {
        if (hits.size == 1) return action(hits[0])
        ct.choose(hits) { action(it) }
        return "📇 Birden fazla kişi buldum, hangisi olduğunu seç."
    }

    // ---------------- namaz vakitleri ----------------

    private fun prayerCommand(t: String): String? {
        val c = ctx ?: return null
        if (has(t, "kible")) {
            onUi?.invoke("kible")
            return "🧭 Kıble pusulası açılıyor."
        }
        if (has(t, "imsakiye", "aylik vakit", "bu ayin vakit")) {
            onUi?.invoke("imsakiye")
            return "🗓 30 günlük vakit çizelgesi açılıyor."
        }
        if (!has(t, "namaz", "ezan")) return null
        if (tokens(t).any { it.startsWith("sessiz") || it.startsWith("sustur") }) {
            val o = onOff(t)
            if (o == false || (o == null && has(t, "almasin", "almayin", "iptal"))) {
                Prayer.setEnabled(c, false)
                return "🔔 Ezanda sessize alma kapatıldı."
            }
            if (!Prayer.hasLocation(c)) {
                return "Önce konumunu bulmam lazım: ⊞ Butonlar ekranındaki namaz kartında \"📍 Konumu bul\"a bas."
            }
            Prayer.setEnabled(c, true)
            val next = Prayer.nextEzan(c)
            return "🔕 Ezanda sessize alma açık: her seçili vakitte ${Prayer.duration(c)} dk sessiz, sonra otomatik sesli." +
                (next?.let { "\nSıradaki: ${it.first} ${Prayer.fmt(it.second)}" } ?: "") +
                (if (!phone.hasDndAccess()) "\n⚠ Tam sessiz için \"Rahatsız Etmeyin erişimi\" gerekli (İzinler); yoksa titreşime alırım." else "")
        }
        return Prayer.todayText(c)
    }

    private fun unknown(t: String) =
        "\"$t\" komutunu anlayamadım. \"yardım\" yazarak neler yapabildiğimi görebilir ya da D-FON bölümündeki " +
            "🎓 Öğret ile bu sözü bildiğim bir komuta bağlayabilirsin."

    // ---------------- sesli okuma ----------------

    private fun stopReading(t: String): String? {
        val tk = tokens(t)
        val stopWord = tk.size <= 2 && tk.any { it in setOf("dur", "durdur", "sus", "yeter", "kes") }
        if (stopWord || has(t, "okumayi durdur", "okumayi kes", "okumayi birak", "sesli okumayi kapat", "okumayi kapat")) {
            Speaker.stop()
            return "Okuma durduruldu."
        }
        return null
    }

    private fun readCommand(t: String): String? {
        val tk = tokens(t)
        val read = tk.any { (it.startsWith("oku") && !it.startsWith("okul")) || it.startsWith("seslendir") } ||
            has(t, "sesli oku", "bana oku")
        if (!read) return null
        if (has(t, "ekran")) {
            if (files.screenReadEnabled()) {
                return "Ekrandaki yazıyı okutmak için okumak istediğin ekrana geç ve kenardaki erişilebilirlik " +
                    "düğmesine (küçük insan simgesi) bas. Tekrar basınca susar."
            }
            files.openAccessibilitySettings()
            return "Ekrandaki yazıyı okuyabilmem için bir kez izin vermen gerekiyor:\n" +
                "1) Açılan Erişilebilirlik ekranında \"Yüklü uygulamalar\" → \"D-FON Ekran Okuma\"yı aç.\n" +
                "2) Kısayolu açık bırak; ekranın kenarında küçük bir düğme çıkar.\n" +
                "3) Okumak istediğin ekranda o düğmeye bas, okurum. Tekrar basınca susarım.\n\n" +
                "Samsung \"kısıtlı ayar\" uyarısı verirse: Ayarlar → Uygulamalar → D-FON → sağ üstteki ⋮ → " +
                "\"Kısıtlı ayarlara izin ver\", sonra tekrar dene."
        }
        if (has(t, "sec", "yazi")) {
            return "Herhangi bir uygulamada yazıyı seç, açılan menüde \"D-FON ile oku\"ya bas " +
                "(görünmüyorsa menüdeki ⋮ ya da ok işaretine dokun). Ya da \"oku: metin\" yazarak bana okut."
        }
        val wantPdf = has(t, "pdf")
        val wantWord = has(t, "word", "docx")
        val readable = setOf("pdf", "word", "metin")
        val ready = fresh.filter { files.kind(it) in readable }
        if (ready.isNotEmpty() && !wantPdf && !wantWord || ready.any { (wantPdf && files.kind(it) == "pdf") || (wantWord && files.kind(it) == "word") }) {
            val no = ready.firstOrNull { (wantPdf && files.kind(it) == "pdf") || (wantWord && files.kind(it) == "word") } ?: ready.first()
            fresh.remove(no)
            return files.readAloud(no)
        }
        val last = files.lastFileNo
        if (has(t, "dosya", "belge") && last != null && files.kind(last) in readable) return files.readAloud(last)
        return need(Action.READ)
    }

    // ---------------- saat, alarm, zamanlayıcı ----------------

    private fun dateTime(t: String): String? {
        if (has(t, "ayar", "alarm")) return null
        if (has(t, "saat kac", "saati soyle", "saat ne")) return clock.now()
        if (has(t, "bugun gunlerden", "hangi gun", "bugunun tarihi", "tarih ne", "bugun ne", "gunlerden ne", "ayin kaci"))
            return clock.today()
        return null
    }

    private fun clockCommand(t: String): String? {
        val tk = tokens(t)
        if (has(t, "zamanlayici", "sayac", "geri sayim")) {
            val sec = durationSeconds(t)
            if (sec == null) {
                if (number(t) == null) return clock.showTimers()
                return ask("zamanlayıcı", "Ne kadar süre? Örn: \"5 dakika\", \"1 saat 20 dakika\".")
            }
            return clock.setTimer(sec.coerceIn(1, 86400), "D-FON")
        }
        val alarmWord = has(t, "alarm") || tk.any { it.startsWith("uyandir") }
        if (!alarmWord) return null
        // "alarm sesi 50" ses komutudur
        if (tk.any { it.startsWith("ses") && !it.startsWith("sessiz") }) return null
        if (has(t, "alarmlar", "goster", "listele") && number(t) == null) return clock.showAlarms()

        val clockTime = Regex("\\b(\\d{1,2}) (\\d{2})\\b").containsMatchIn(t) ||
            Regex("\\bsaat (\\d|$NUM_RE)").containsMatchIn(t)
        val dur = durationSeconds(t)
        if (dur != null && !clockTime) return clock.setTimer(dur.coerceIn(1, 86400), "D-FON") // "10 dakika sonra alarm"

        val hm = alarmTime(t)
            ?: return ask("alarm saat", "Alarm saat kaçta? Örn: \"7:30\", \"sabah 6\", \"akşam 9 buçuk\".")
        return clock.setAlarm(hm.first, hm.second, "D-FON")
    }

    /** "1 saat 20 dakika", "yirmi beş dakika", "yarım saat", "1 buçuk saat" -> saniye */
    private fun durationSeconds(t: String): Int? {
        var total = 0
        var found = false
        Regex("\\b((?:(?:\\d+|$NUM_RE)\\s*)+?)\\s*(bucuk\\s*)?(saat|dakika|dk|saniye|sn)\\b").findAll(t).forEach { m ->
            val n = number(m.groupValues[1]) ?: return@forEach
            val unit = when (m.groupValues[3]) {
                "saat" -> 3600
                "dakika", "dk" -> 60
                else -> 1
            }
            total += n * unit + (if (m.groupValues[2].isNotEmpty()) unit / 2 else 0)
            found = true
        }
        if (has(t, "yarim saat")) { total += 1800; found = true }
        if (has(t, "yarim dakika")) { total += 30; found = true }
        return if (found && total > 0) total else null
    }

    private fun alarmTime(t: String): Pair<Int, Int>? {
        var h: Int
        var m = 0
        val hm = Regex("\\b(\\d{1,2}) (\\d{2})\\b").find(t)
        if (hm != null) {
            h = hm.groupValues[1].toInt()
            m = hm.groupValues[2].toInt()
        } else {
            val digit = Regex("\\b(\\d{1,2})\\b").find(t)?.groupValues?.get(1)?.toInt()
            val word = Regex("\\bsaat ((?:$NUM_RE)(?: (?:$NUM_RE))?)\\b").find(t)?.groupValues?.get(1)?.let { number(it) }
            h = digit ?: word ?: return null
            if (has(t, "bucuk")) m = 30
        }
        if (h !in 0..23 || m !in 0..59) return null
        if (has(t, "aksam", "ogleden sonra", "ikindi") && h in 1..11) h += 12
        if (has(t, "gece") && h == 12) h = 0
        if (has(t, "gece") && h in 8..11) h += 12
        return h to m
    }

    // ---------------- telefon ----------------

    private fun status(t: String): String? {
        val stk = tokens(t)
        val battery = stk.any {
            (it.startsWith("pil") && it.length <= 6 && it !in setOf("pilav", "pilot", "pilic", "pilli")) ||
                it.startsWith("sarj") || it.startsWith("batarya")
        }
        val phoneStatus = t == "durum" || has(t, "telefon durumu", "telefonun durumu", "durumu soyle", "durum nedir",
            "durum ne", "durumu goster", "durum raporu")
        if (!(battery || phoneStatus) || has(t, "tasarruf", "hava")) return null
        val s = st() ?: return "Durum okunamadı."
        val v = s.optJSONObject("ses_yuzde") ?: JSONObject()
        return buildString {
            append("🔋 Pil: %${s.optInt("pil_yuzde")}")
            if (s.optBoolean("sarj_oluyor")) append(" (şarj oluyor)")
            append("\n☀ Parlaklık: %${s.optInt("parlaklik_yuzde")}")
            if (s.optBoolean("otomatik_parlaklik")) append(" (otomatik)")
            append("\n🔊 Medya %${v.optInt("medya")}, zil %${v.optInt("zil")}, alarm %${v.optInt("alarm")}")
            append("\n🔔 Zil modu: ${s.optString("zil_modu")}")
            append("\n🌙 Rahatsız etmeyin: ${if (s.optBoolean("rahatsiz_etme")) "açık" else "kapalı"}")
            append("\n⏱ Ekran kapanma: ${s.optInt("ekran_zaman_asimi_saniye")} sn")
            append("\n🔄 Otomatik döndürme: ${if (s.optBoolean("otomatik_dondurme")) "açık" else "kapalı"}")
        }
    }

    private fun torch(t: String): String? {
        if (!has(t, "fener", "flas")) return null
        return run("fener", JSONObject().put("acik", onOff(t) ?: !phone.torchOn))
    }

    private fun dnd(t: String): String? {
        if (!has(t, "rahatsiz")) return null
        return run("rahatsiz_etme", JSONObject().put("acik", onOff(t) ?: true))
    }

    private fun nightMode(t: String): String? {
        if (has(t, "gece modu", "gece moduna", "uyku modu")) {
            if (onOff(t) == false) return dayMode()
            return listOf(
                run("ekran_parlakligi_ayarla", JSONObject().put("seviye", 10)),
                run("rahatsiz_etme", JSONObject().put("acik", true))
            ).joinToString("\n")
        }
        if (has(t, "gunduz modu", "gunduz moduna", "sabah modu")) return dayMode()
        return null
    }

    private fun dayMode() = listOf(
        run("otomatik_parlaklik", JSONObject().put("acik", true)),
        run("rahatsiz_etme", JSONObject().put("acik", false))
    ).joinToString("\n")

    private fun ringer(t: String): String? {
        val normal = JSONObject().put("mod", "normal")
        if (has(t, "sessizden cik", "sessizi kapat", "sessiz modu kapat", "sessiz modunu kapat", "zili ac",
                "normal mod", "sesli mod", "sesliye al", "sesli yap")) return run("zil_modu", normal)
        if (has(t, "titresim", "titret")) {
            return if (onOff(t) == false) run("zil_modu", normal)
            else run("zil_modu", JSONObject().put("mod", "titresim"))
        }
        if (tokens(t).any { it.startsWith("sessiz") }) {
            return if (onOff(t) == false) run("zil_modu", normal)
            else run("zil_modu", JSONObject().put("mod", "sessiz"))
        }
        return null
    }

    private fun brightness(t: String): String? {
        val tk = tokens(t)
        if (!has(t, "parlak", "ekrani aydinlat") && tk.none { it.startsWith("karart") }) return null
        if (has(t, "otomatik", "uyarlanabilir")) {
            return run("otomatik_parlaklik", JSONObject().put("acik", onOff(t) ?: true))
        }
        val cur = st()?.optInt("parlaklik_yuzde") ?: 50
        val lvl = if (tk.any { it.startsWith("karart") } && number(t) == null) (cur - 20).coerceAtLeast(1)
        else level(t, cur, false) ?: return ask("parlaklık", "Parlaklığı kaç yapayım? Örn: \"40\" ya da \"yarıya\".")
        return run("ekran_parlakligi_ayarla", JSONObject().put("seviye", lvl.coerceAtLeast(1)))
    }

    private fun volume(t: String): String? {
        val tk = tokens(t)
        val sesWord = tk.any { it.startsWith("ses") && !it.startsWith("sessiz") && !it.startsWith("sesli") }
        val type = when {
            has(t, "muzik", "medya", "video", "film") -> "medya"
            has(t, "alarm") -> "alarm"
            has(t, "bildirim") -> "bildirim"
            has(t, "zil") -> "zil"
            has(t, "arama", "konusma", "gorusme", "cagri") -> "arama"
            else -> null
        }
        if (!sesWord && type == null) return null
        if (!sesWord && number(t) == null && !hasRelative(t)) return null
        val stream = type ?: "medya"
        val cur = st()?.optJSONObject("ses_yuzde")?.optInt(stream) ?: 50
        val lvl = level(t, cur, true)
            ?: return ask("$stream sesi", "Sesi kaç yapayım? Örn: \"50\", \"yarıya\", \"en yüksek\".")
        return run("ses_seviyesi", JSONObject().put("tur", stream).put("seviye", lvl))
    }

    private fun timeout(t: String): String? {
        val tk = tokens(t)
        val match = has(t, "zaman asimi", "uyku suresi", "kapanma suresi") ||
            (has(t, "ekran") && tk.any { it.startsWith("kapan") })
        if (!match) return null
        val sec = when {
            has(t, "yarim dakika") -> 30
            else -> {
                val n = number(t) ?: return ask("ekran zaman aşımı", "Ekran kaç dakika sonra kapansın? Örn: \"2 dakika\".")
                when {
                    has(t, "dakika", " dk") -> n * 60
                    has(t, "saniye", " sn") -> n
                    has(t, "saat") -> n * 3600
                    n <= 30 -> n * 60
                    else -> n
                }
            }
        }
        return run("ekran_zaman_asimi", JSONObject().put("saniye", sec))
    }

    private fun rotation(t: String): String? {
        if (!has(t, "dondur", "dondurme")) return null
        return run("otomatik_dondurme", JSONObject().put("acik", onOff(t) ?: true))
    }

    private fun settingsScreen(t: String): String? {
        for ((words, key, locked) in SCREENS) {
            if (words.any { t.contains(it) }) {
                val r = run("ayar_ekrani_ac", JSONObject().put("ekran", key))
                return if (locked && onOff(t) != null)
                    "$r Android, uygulamaların bunu kendi başına açıp kapatmasına izin vermiyor; açılan panelden bir dokunuşla değiştirebilirsin."
                else r
            }
        }
        return null
    }

    // ---------------- uygulamalar ----------------

    private fun appCommand(t: String): String? {
        if (has(t, "uygulamalar", "hangi uygulama", "uygulama listesi", "uygulamalari listele")) {
            val list = files.allowedApps()
            return if (list.isEmpty()) "Henüz izinli uygulama yok. Üstteki \"Uygulamalar\" düğmesinden seç."
            else "İzinli uygulamalar (${list.size}): " + list.joinToString(", ") { it.label }
        }
        val app = files.appInText(t, true)
        if (app != null) {
            val last = files.lastFileNo
            val after = " " + t.substringAfter(norm(app.label)).trim() + " "
            val withSuffix = listOf(" da ", " de ", " ta ", " te ", " ile ").any { after.startsWith(it) }
            if (last != null && (withSuffix || has(t, "dosya", "belge"))) {
                if (has(t, "gonder", "paylas", "yolla", "at ")) return files.shareFile(last, app)
                if (withSuffix && tokens(t).any { it.startsWith("ac") }) return files.openFile(last, app)
            }
            return files.openApp(app)
        }
        val other = files.appInText(t, false)
        if (other != null && tokens(t).any { it.startsWith("ac") || it.startsWith("baslat") || it.startsWith("calistir") }) {
            return "${other.label} izinli değil. Üstteki \"Uygulamalar\" düğmesinden işaretleyebilirsin."
        }
        return null
    }

    // ---------------- internet (tarayıcıda) ----------------

    private fun webCommand(raw: String): String? {
        val nt = norm(raw)
        if (nt.startsWith("hava durumu") || nt.startsWith("hava nasil") || nt.startsWith("bugun hava") || nt.startsWith("yarin hava")) {
            files.openUrl("https://www.google.com/search?q=" + URLEncoder.encode(raw.trim(), "UTF-8"))
            return "🌤 Hava durumu tarayıcıda açılıyor…"
        }
        val lower = raw.lowercase()
        Regex("(https?://\\S+)|(\\b[a-z0-9-]+(\\.[a-z0-9-]+)*\\.(com|net|org|tr|io|edu|gov|info|app|dev)(/\\S*)?)")
            .find(lower)?.let { return files.openUrl(it.value) }

        val words = raw.trim().split(Regex("\\s+")).toMutableList()
        if (words.size < 2) return null
        val last = norm(words.last())
        val searchWord = last in setOf("ara", "arat", "aratir", "arastir", "bul")
        val watchWord = last in setOf("izle", "dinle", "ac")
        val first = norm(words.first())
        val site = when {
            first.startsWith("youtube") -> "youtube"
            first.startsWith("google") || first.startsWith("internet") || first.startsWith("web") -> "google"
            first.startsWith("vikipedi") || first.startsWith("wikipedi") -> "wiki"
            first.startsWith("harita") -> "harita"
            else -> null
        }
        if (!searchWord && !(watchWord && site == "youtube")) return null
        if (!searchWord && site == null) return null
        if (site != null) words.removeAt(0)
        words.removeAt(words.size - 1)
        val q = words.joinToString(" ").trim()
        if (q.isEmpty()) return null
        val e = URLEncoder.encode(q, "UTF-8")
        val url = when (site) {
            "youtube" -> "https://www.youtube.com/results?search_query=$e"
            "wiki" -> "https://tr.wikipedia.org/w/index.php?search=$e"
            "harita" -> "https://www.google.com/maps/search/$e"
            else -> "https://www.google.com/search?q=$e"
        }
        files.openUrl(url)
        return "\"$q\" aranıyor…"
    }

    // ---------------- dosyalar ----------------

    /** "PDF yap: metin..." ya da "Word yap: metin..." */
    private fun textToDocument(raw: String): String? {
        val c = raw.indexOf(':')
        if (c < 0) return null
        val head = norm(raw.substring(0, c))
        val body = raw.substring(c + 1).trim()
        if (body.isEmpty() || head.split(" ").size > 6) return null
        val title = body.lineSequence().first().take(30)
        return when {
            head.contains("oku") || head.contains("seslendir") -> files.speakText(body)
            head.contains("word") -> files.textToDocx(body, title)
            head.contains("pdf") -> files.textToPdf(body, title)
            else -> null
        }
    }

    private fun fileCommand(t: String): String? {
        val tk = tokens(t)
        if (has(t, "dosya sec", "dosya ekle", "dosya yukle", "belge sec", "belge ekle")) {
            files.pick("hepsi", true)
            return "Dosyaları seç."
        }
        if (has(t, "dosyalar", "dosya listesi")) return files.describeFiles()

        val pdfI = idx(t, "pdf")
        val wordI = idx(t, "word", "docx")
        val imgI = idx(t, "resim", "resm", "foto", "gorsel", "png", "jpg", "galeri")
        val convert = tk.any {
            it.startsWith("cevir") || it.startsWith("donust") || it.startsWith("aktar") || it.startsWith("olustur")
        }
        val make = convert || tk.any { it == "yap" || it.startsWith("yapar") || it.startsWith("kaydet") }
        val merge = tk.any { it.startsWith("birlestir") }

        // son dosyayı aç / paylaş (uygulama adı yoksa seçtirir)
        if (has(t, "dosya", "belge") && !make && !merge) {
            val last = files.lastFileNo ?: return "Henüz dosya yok. 📎 ile ekle ya da \"PDF'i Word'e çevir\" gibi bir komut ver."
            val app = files.appInText(t, true)
            if (has(t, "gonder", "paylas", "yolla")) return files.shareFile(last, app)
            if (tk.any { it.startsWith("ac") || it.startsWith("goster") }) return files.openFile(last, app)
        }

        if (merge && (pdfI >= 0 || fresh.count { files.kind(it) == "pdf" } >= 2)) return need(Action.MERGE_PDF)
        if (!make) return null
        if (pdfI >= 0 && wordI >= 0) return need(if (pdfI < wordI) Action.PDF_TO_WORD else Action.WORD_TO_PDF)
        if (pdfI >= 0 && imgI >= 0) return need(if (imgI < pdfI) Action.IMAGES_TO_PDF else Action.PDF_TO_IMAGES)

        // Tek tür söylendiyse: hedef odur, kaynak eklenen dosyadan anlaşılır
        val freshKinds = fresh.map { files.kind(it) }.toSet()
        if (wordI >= 0) {
            return if ("pdf" in freshKinds || convert) need(Action.PDF_TO_WORD) else null
        }
        if (pdfI >= 0) {
            return when {
                "word" in freshKinds || "metin" in freshKinds -> need(Action.WORD_TO_PDF)
                "resim" in freshKinds -> need(Action.IMAGES_TO_PDF)
                else -> "Neyi PDF yapayım? Örn: \"Word'ü PDF yap\", \"resimleri PDF yap\" ya da \"PDF yap: metnin\"."
            }
        }
        if (imgI >= 0 && convert) return need(Action.PDF_TO_IMAGES)
        return null
    }

    private fun need(a: Action): String {
        val want = when (a) {
            Action.WORD_TO_PDF -> setOf("word", "metin")
            Action.IMAGES_TO_PDF -> setOf("resim")
            Action.READ -> setOf("pdf", "word", "metin")
            else -> setOf("pdf")
        }
        val ready = fresh.filter { files.kind(it) in want }
        if (ready.isNotEmpty() && (a != Action.MERGE_PDF || ready.size >= 2)) {
            fresh.removeAll(ready)
            return perform(a, ready)
        }
        pending = a
        when (a) {
            Action.WORD_TO_PDF -> files.pick("word", true)
            Action.IMAGES_TO_PDF -> files.pick("resim", true)
            Action.MERGE_PDF -> files.pick("pdf", true)
            Action.READ -> files.pick("hepsi", false)
            else -> files.pick("pdf", false)
        }
        return when (a) {
            Action.IMAGES_TO_PDF -> "PDF yapılacak resimleri seç (birden fazla seçebilirsin)."
            Action.MERGE_PDF -> "Birleştirilecek PDF'leri sırayla seç."
            Action.WORD_TO_PDF -> "Word (.docx) dosyasını seç."
            Action.READ -> "Okumamı istediğin PDF ya da Word dosyasını seç."
            else -> "PDF dosyasını seç."
        }
    }

    private fun perform(a: Action, nos: List<Int>): String {
        fun of(vararg k: String) = nos.filter { files.kind(it) in k }
        val result: List<String> = try {
            when (a) {
                Action.PDF_TO_WORD -> of("pdf").map { files.pdfToDocx(it) }
                Action.PDF_TO_IMAGES -> of("pdf").map { files.pdfToImages(it) }
                Action.WORD_TO_PDF -> of("word", "metin").map { files.filesToPdf(listOf(it)) }
                Action.IMAGES_TO_PDF -> of("resim").let { if (it.isEmpty()) emptyList() else listOf(files.filesToPdf(it)) }
                Action.MERGE_PDF -> of("pdf").let { if (it.isEmpty()) emptyList() else listOf(files.mergePdfs(it)) }
                Action.READ -> of("pdf", "word", "metin").take(1).map { files.readAloud(it) }
            }
        } catch (e: Exception) {
            return "İşlem başarısız: ${e.message ?: e.javaClass.simpleName}"
        }
        if (result.isEmpty()) return "Seçilen dosya bu işlem için uygun değil."
        val tip = if (a == Action.PDF_TO_IMAGES || a == Action.READ) "" else
            "\n\nAçmak için \"dosyayı aç\", göndermek için \"dosyayı WhatsApp'tan gönder\" de."
        return result.joinToString("\n") + tip
    }

    // ======================= yardımcılar =======================

    private fun run(tool: String, input: JSONObject): String = phone.run(tool, input)

    private fun st(): JSONObject? = try {
        JSONObject(phone.run("durum_oku", JSONObject()))
    } catch (e: Exception) {
        null
    }

    private fun norm(s: String) = FileTools.norm(s)
    private fun tokens(t: String) = t.split(" ")
    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }
    private fun idx(t: String, vararg w: String): Int =
        w.map { t.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1

    /** true = aç, false = kapat, null = söylenmemiş */
    private fun onOff(t: String): Boolean? {
        val tk = tokens(t)
        if (tk.any { it.startsWith("kapa") || it.startsWith("sondur") || it == "iptal" } || has(t, "devre disi")) return false
        if (tk.any { Nlu.isVerb(it, listOf("ac")) || it.startsWith("acik") || it == "yak" || it.startsWith("etkin") || it == "aktif" }) return true
        return null
    }

    private fun hasRelative(t: String) = tokens(t).any {
        it.startsWith("artir") || it.startsWith("arttir") || it.startsWith("yuksel") || it.startsWith("azal") ||
            it.startsWith("dusur") || it.startsWith("kis") || it.startsWith("kapa")
    }

    private fun number(t: String): Int? = Nlu.number(t)

    /** Hedef yüzde. current: şu anki yüzde. */
    private fun level(t: String, current: Int, offMeansZero: Boolean): Int? {
        val tk = tokens(t)
        if (has(t, "en yuksek", "en fazla", "sonuna kadar", "maksimum", "max") || tk.contains("tam")) return 100
        if (has(t, "en dusuk", "en az", "minimum")) return 0
        if (offMeansZero && tk.any { it.startsWith("kapa") }) return 0
        if (tk.any { it.startsWith("yari") }) return 50
        number(t)?.let { return it.coerceIn(0, 100) }
        val step = if (Nlu.smallStep(t)) 10 else 20
        if (tk.any { it.startsWith("artir") || it.startsWith("arttir") || it.startsWith("yuksel") || it.startsWith("cogal") || Nlu.isVerb(it, listOf("ac")) }) {
            return (current + step).coerceAtMost(100)
        }
        if (tk.any { it.startsWith("azal") || it.startsWith("dusur") || it.startsWith("kis") || it.startsWith("karart") }) {
            return (current - step).coerceAtLeast(0)
        }
        return null
    }

    private data class Screen(val words: List<String>, val key: String, val locked: Boolean)

    companion object {
        private val YES = setOf("evet", "evet yap", "aynen", "olur", "he", "hi hi", "tamam", "onayla")
        private val NO = setOf("hayir", "yok", "vazgec", "iptal", "bosver", "istemiyorum")

        /** Benzer komut önermek için bilinen örnekler. */
        val EXAMPLES = listOf(
            "feneri aç", "feneri kapat", "parlaklık 50", "parlaklığı artır", "parlaklığı azalt", "otomatik parlaklığı aç",
            "medya sesi 50", "sesi artır", "sesi azalt", "sesi kapat", "zil sesi 50", "sessize al", "titreşime al",
            "sessizden çık", "rahatsız etmeyi aç", "rahatsız etmeyi kapat", "gece modu", "gündüz modu", "durum",
            "saat kaç", "bugün günlerden ne", "alarmları göster", "namaz vakitleri", "ezanda sessize al", "kıble",
            "imsakiye", "notlarım", "notlarımı oku", "hatırlatıcılarım", "rutinlerim", "uygulamalar", "wifi", "bluetooth",
            "karanlık mod", "konum", "uçak modu", "pdf'i word'e çevir", "word'ü pdf yap", "resimleri pdf yap",
            "pdf'leri birleştir", "pdf'i resme çevir", "pdf'i oku", "ekrandaki yazıyı oku", "dosyayı aç",
            "otomatik döndürmeyi aç", "otomatik döndürmeyi kapat", "hava durumu", "yardım"
        )

        private val NUM_WORDS = mapOf(
            "bir" to 1, "iki" to 2, "uc" to 3, "dort" to 4, "bes" to 5, "alti" to 6, "yedi" to 7,
            "sekiz" to 8, "dokuz" to 9, "on" to 10, "yirmi" to 20, "otuz" to 30, "kirk" to 40,
            "elli" to 50, "altmis" to 60, "yetmis" to 70, "seksen" to 80, "doksan" to 90, "yuz" to 100
        )

        private val NUM_RE = NUM_WORDS.keys.joinToString("|")

        private val SCREENS = listOf(
            Screen(listOf("wifi", "wi fi", "kablosuz"), "wifi", true),
            Screen(listOf("bluetooth", "blutut"), "bluetooth", true),
            Screen(listOf("mobil veri", "hucresel"), "internet", true),
            Screen(listOf("hotspot", "erisim noktasi", "interneti paylas", "internet paylas"), "hotspot", true),
            Screen(listOf("ucak"), "ucak_modu", true),
            Screen(listOf("nfc"), "nfc", true),
            Screen(listOf("karanlik", "koyu tema", "koyu mod", "dark"), "karanlik_mod", true),
            Screen(listOf("gece isig", "mavi isik", "goz koruma"), "gece_isigi", true),
            Screen(listOf("yazi boyut", "yazilari buyut", "yazilari kucult", "yazi tipi", "font"), "yazi_boyutu", false),
            Screen(listOf("duvar kagid"), "duvar_kagidi", false),
            Screen(listOf("konum", "gps"), "konum", true),
            Screen(listOf("pil tasarruf", "guc tasarruf"), "pil", true),
            Screen(listOf("depolama", "hafiza"), "depolama", false),
            Screen(listOf("dil ayar", "telefon dili", "klavye dili"), "dil", false),
            Screen(listOf("tarih", "saat ayar"), "tarih_saat", false),
            Screen(listOf("bildirim ayar"), "bildirimler", false),
            Screen(listOf("ses ayar", "zil sesini degistir", "zil sesi sec"), "ses_ayarlari", false),
            Screen(listOf("ekran ayar"), "ekran", false),
            Screen(listOf("erisilebilirlik"), "erisilebilirlik", false),
            Screen(listOf("guvenlik", "ekran kilidi"), "guvenlik", false),
            Screen(listOf("gelistirici"), "gelistirici", false),
            Screen(listOf("ayarlari ac", "ayarlar"), "genel", false),
        )

        val HELP = """
Neler yapabilirim (hepsi internetsiz, telefonunda):

☀ Ekran: "parlaklık 40", "parlaklığı artır", "ekranı karart", "otomatik parlaklığı aç", "ekran 2 dakikada kapansın", "otomatik döndürmeyi kapat"
🔊 Ses: "medya sesi 50", "zil sesini artır", "sesi kapat", "sessize al", "titreşime al", "sessizden çık", "rahatsız etmeyi aç"
🔦 "feneri aç", "gece modu", "gündüz modu", "durum" (pil ve ayarlar)
⏰ Saat: "alarm 7:30", "sabah 6'da uyandır", "akşam 9 buçukta alarm kur", "5 dakika zamanlayıcı", "1 saat 20 dakika sayaç kur", "alarmları göster", "saat kaç", "bugün günlerden ne"
⚡ Rutinler: "uyku modu", "sabah modu", "rutinlerim" (Butonlar ekranından kendi rutinini ekle; istersen her gün belli saatte çalışır)
📝 Notlar: "not al ekmek al", "notlarım", "notlarımı oku", "son notu sil"
⏰ Hatırlatıcı: "yarın 10'da faturayı hatırlat", "20 dakika sonra çayı hatırlat", "hatırlatıcılarım"
📞 Kişiler: "annemi ara", "Ahmet'e mesaj yaz geliyorum", "Ahmet'e WhatsApp'tan yaz geliyorum"
🕌 Namaz: "namaz vakitleri", "ezanda sessize al", "kıble", "imsakiye"
🎓 Anlamadığım bir sözü D-FON bölümündeki "Öğret" ile bir komuta bağlayabilirsin
⚙ Ayar ekranları: "Wi-Fi", "Bluetooth", "karanlık mod", "yazı boyutu", "duvar kağıdı", "konum", "uçak modu"…
📱 Uygulamalar: "WhatsApp'ı aç", "uygulamalar" (sadece izin verdiklerin)
📄 Dosyalar: "PDF'i Word'e çevir", "Word'ü PDF yap", "resimleri PDF yap", "PDF'leri birleştir", "PDF'i resme çevir", "dosyayı aç", "dosyayı WhatsApp'tan gönder"
✍ "PDF yap: metnin…" / "Word yap: metnin…"
🖐 Sesle başka uygulamayı kullan ("👂 Uyandırma" ve "D-FON Uygulama Kilidi ve Sesli Kontrol" açıkken): "Bir iki üç fon, YouTube'u aç" → sonra 25 sn boyunca sözü tekrar etmeden: "aşağı kaydır", "yukarı kaydır", "Abonelikler'e dokun", "neler var", "ekranı oku", "yaz ninni", "geri", "ana ekran", "tamam" (dinlemeyi bitirir).
🎤 Ortadaki büyük mikrofona dokunup söyle. "👂 Uyandırma"yı açarsan ekran açıkken "Bir iki üç fon" demen yeter; arkasından komutu da söyleyebilirsin: "Bir iki üç fon, feneri aç".
🔊 Okuma: "PDF'i oku", "Word'ü oku", "dosyayı oku", "oku: metin…", "ekrandaki yazıyı oku", "dur" (susar). Başka bir uygulamada yazı seçip menüden "D-FON ile oku" da diyebilirsin.
🌐 İnternet (tarayıcıda açılır): "hava durumu ara", "YouTube'da Kuran tilaveti izle", "Vikipedi'de Osmanlı ara", "trt.net.tr"

Birden fazla komutu "ve" ile birleştirebilirsin: "parlaklık 20 ve sessize al".
Son komutlarını tekrar kullanmak için alttaki "🕘 Geçmiş" düğmesine bas.
""".trim()
    }
}
