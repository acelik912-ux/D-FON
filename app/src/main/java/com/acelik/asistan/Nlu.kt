package com.acelik.asistan

import java.util.Calendar
import java.util.Locale

/**
 * Saf (Android'e bağlı olmayan) dil yardımcıları. Telefonda deneyemediğimiz için
 * buradaki her şey birim testleriyle (LogicTest) doğrulanır.
 */
object Nlu {

    /** Türkçe karakterleri sadeleştirip küçük harfe çevirir (eşleştirme için). */
    fun norm(s: String): String = s.lowercase(Locale("tr"))
        .replace('ı', 'i').replace('ş', 's').replace('ç', 'c')
        .replace('ğ', 'g').replace('ö', 'o').replace('ü', 'u')
        .replace('â', 'a').replace('î', 'i').replace('û', 'u')
        .replace(Regex("[^a-z0-9% ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    // ---------------- fiiller ----------------

    private val VERB_ENDINGS = setOf(
        "", "ar", "er", "ir", "ur", "r", "sana", "sene", "in", "un", "yin", "iniz", "yiniz",
        "iver", "abilir", "ebilir", "alim", "elim", "ayim", "eyim"
    )

    /**
     * Kelime, verilen fiil köklerinden birinin emir/istek hali mi?
     * "bas", "basar", "bassana" → evet; "baska", "basla", "acik", "secim", "girdi" → hayır.
     */
    fun isVerb(token: String, stems: Collection<String>): Boolean =
        stems.any { s -> token.startsWith(s) && token.substring(s.length) in VERB_ENDINGS }

    fun hasVerb(tokens: List<String>, stems: Collection<String>) = tokens.any { isVerb(it, stems) }

    // ---------------- sayılar ----------------

    val NUM_WORDS = mapOf(
        "bir" to 1, "iki" to 2, "uc" to 3, "dort" to 4, "bes" to 5, "alti" to 6, "yedi" to 7,
        "sekiz" to 8, "dokuz" to 9, "on" to 10, "yirmi" to 20, "otuz" to 30, "kirk" to 40,
        "elli" to 50, "altmis" to 60, "yetmis" to 70, "seksen" to 80, "doksan" to 90, "yuz" to 100
    )

    /** "bir tık", "bir kademe" gibi sözlerde "bir" sayı değildir. */
    private val FILLER_AFTER_BIR = setOf(
        "tik", "kademe", "miktar", "az", "parca", "kez", "daha", "sey", "adim", "basamak", "nebze", "kere"
    )

    /** Metindeki sayı: rakam varsa o, yoksa sayı kelimelerinin toplamı ("yirmi beş" → 25). */
    fun number(t: String): Int? {
        Regex("\\d+").find(t)?.let { return it.value.toIntOrNull() }
        val tk = t.split(" ")
        var sum = 0
        var found = false
        for ((i, w) in tk.withIndex()) {
            val v = NUM_WORDS[w] ?: continue
            if (w == "bir" && tk.getOrNull(i + 1) in FILLER_AFTER_BIR) continue
            sum += v
            found = true
        }
        return if (found) sum else null
    }

    /** "biraz", "bir tık", "azıcık", "hafif" → küçük adım. */
    fun smallStep(t: String): Boolean {
        val tk = t.split(" ")
        return tk.any { it.startsWith("biraz") || it.startsWith("azicik") || it.startsWith("hafif") } ||
            tk.withIndex().any { (i, w) -> w == "bir" && tk.getOrNull(i + 1) in FILLER_AFTER_BIR }
    }

    // ---------------- kişi adları ----------------

    private val CASE = listOf(
        "nin", "nun", "yle", "yla", "den", "dan", "ten", "tan", "yi", "yu", "ye", "ya", "ni", "nu", "ne", "na",
        "le", "la", "de", "da", "te", "ta", "in", "un", "i", "u", "e", "a"
    )
    private val POSS = listOf("imiz", "umuz", "miz", "muz", "im", "um", "m")

    /**
     * Söylenen addan rehberde aranacak biçimler; ilki söylenenin kendisi.
     * "annemi" → [annemi, annem, anne], "ahmet i" → [ahmet], "ahmedi" → [ahmedi, ahmed, ahmet]
     */
    fun nameVariants(q: String): List<String> {
        val words = norm(q).split(" ").filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && words.last() in CASE) words.removeAt(words.size - 1)
        val base = words.joinToString(" ")
        if (base.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out.add(base)
        val noCase = CASE.filter { base.endsWith(it) && base.length - it.length >= 3 }.map { base.removeSuffix(it) }
        out.addAll(noCase)
        for (b in listOf(base) + noCase) {
            for (p in POSS) if (b.endsWith(p) && b.length - p.length >= 3) out.add(b.removeSuffix(p))
        }
        // ünsüz yumuşaması: "ahmed(i)" → "ahmet", "zeyneb(i)" → "zeynep"
        for (v in out.toList()) {
            when (v.last()) {
                'd' -> out.add(v.dropLast(1) + "t")
                'b' -> out.add(v.dropLast(1) + "p")
            }
        }
        return out.toList()
    }

    // ---------------- benzerlik ----------------

    private fun lev(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 0..1 arası benzerlik; kelime sırası farkını da hoş görür. (Girdi norm() edilmiş olmalı.) */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        fun ratio(x: String, y: String): Double {
            val m = maxOf(x.length, y.length)
            return if (m == 0) 0.0 else 1.0 - lev(x, y).toDouble() / m
        }
        val sa = a.split(" ").sorted().joinToString(" ")
        val sb = b.split(" ").sorted().joinToString(" ")
        return maxOf(ratio(a, b), ratio(sa, sb))
    }

    // ---------------- zaman ifadeleri (hatırlatıcılar) ----------------

    class When(val time: Long, val text: String)

    private val DAY_NAMES = listOf(
        "pazartesi" to Calendar.MONDAY, "sali" to Calendar.TUESDAY, "carsamba" to Calendar.WEDNESDAY,
        "persembe" to Calendar.THURSDAY, "cumartesi" to Calendar.SATURDAY, "cuma" to Calendar.FRIDAY,
        "pazar" to Calendar.SUNDAY
    )
    private val LOC = listOf("da", "de", "ta", "te")
    private val SKIP = setOf("bana", "beni", "saat", "saati", "lutfen")

    private fun wordHour(tok: String): Pair<Int, Boolean>? {
        NUM_WORDS[tok]?.let { return it to false }
        for (s in LOC) if (tok.endsWith(s)) NUM_WORDS[tok.removeSuffix(s)]?.let { return it to true }
        return null
    }

    /**
     * "yarın 10'da faturayı hatırlat", "5 dakika sonra çayı hatırlat", "akşam 8 buçukta ilacı hatırlat"
     * → (zaman, hatırlatılacak şey). Zaman ifadesi bulunamazsa null.
     */
    fun parseWhen(raw: String, now: Calendar): When? {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val n = words.map { norm(it) }              // "10'da" → "10 da", "8:30'da" → "8 30 da"
        val used = BooleanArray(words.size)
        for (i in n.indices) if (n[i].startsWith("hatirlat") || n[i] in SKIP) used[i] = true

        val cal = now.clone() as Calendar
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // --- göreli: "5 dakika sonra", "yarım saat sonra", "bir buçuk saat sonra"
        val si = n.indexOf("sonra")
        if (si >= 2) {
            val j = si - 1
            val unit = when {
                n[j].startsWith("dakika") || n[j] == "dk" -> 60_000L
                n[j].startsWith("saat") -> 3_600_000L
                n[j].startsWith("gun") -> 86_400_000L
                else -> 0L
            }
            if (unit > 0) {
                var k = j - 1
                var half = 0.0
                if (k >= 0 && n[k] == "bucuk") { half = 0.5; k-- }
                var start = k
                while (start >= 0 && (n[start].toIntOrNull() != null || n[start] in NUM_WORDS || n[start] == "yarim")) start--
                start++
                if (start <= k) {
                    val chunk = n.subList(start, k + 1)
                    val value = if (chunk == listOf("yarim")) 0.5 else (number(chunk.joinToString(" ")) ?: 0).toDouble()
                    val ms = ((value + half) * unit).toLong()
                    if (ms > 0) {
                        for (x in start..si) used[x] = true
                        return When(now.timeInMillis + ms, restText(words, used))
                    }
                }
            }
        }

        // --- gün
        var dayOffset: Int? = null
        for (i in n.indices) {
            if (used[i]) continue
            when {
                n[i] == "bugun" -> { dayOffset = 0; used[i] = true }
                n[i] == "yarin" -> { dayOffset = 1; used[i] = true }
                n[i] == "obur" && n.getOrNull(i + 1)?.startsWith("gun") == true -> {
                    dayOffset = 2; used[i] = true; used[i + 1] = true
                }
                else -> {
                    val d = DAY_NAMES.firstOrNull { (name, _) ->
                        n[i] == name || LOC.any { s -> n[i] == "$name $s" } || n[i] == name + "ye" || n[i] == name + "ya" ||
                            n[i] == "$name ye" || n[i] == "$name ya"
                    }
                    if (d != null) {
                        var diff = (d.second - now.get(Calendar.DAY_OF_WEEK) + 7) % 7
                        if (diff == 0) diff = 7
                        dayOffset = diff
                        used[i] = true
                        if (n.getOrNull(i + 1) in setOf("gunu", "gun")) used[i + 1] = true
                    }
                }
            }
        }

        // --- günün bölümü
        var part: String? = null
        for (i in n.indices) {
            if (used[i]) continue
            when {
                n[i] == "ogleden" && n.getOrNull(i + 1) == "sonra" -> { part = "aksam"; used[i] = true; used[i + 1] = true }
                n[i].startsWith("sabah") -> { part = "sabah"; used[i] = true }
                n[i] == "ogle" || n[i] == "oglen" || n[i] == "oglene" || n[i] == "ogleyin" -> { part = "ogle"; used[i] = true }
                n[i].startsWith("aksam") || n[i].startsWith("ikindi") -> { part = "aksam"; used[i] = true }
                n[i].startsWith("gece") -> { part = "gece"; used[i] = true }
            }
        }

        // --- saat
        var hour: Int? = null
        var minute = 0
        for (i in words.indices) {
            if (used[i] && n[i] !in setOf("saat", "saati")) continue
            if (hour != null) break
            val colon = Regex("^(\\d{1,2})[:.](\\d{2})").find(words[i])
            if (colon != null) {
                hour = colon.groupValues[1].toInt(); minute = colon.groupValues[2].toInt(); used[i] = true
                continue
            }
            val prevSaat = n.getOrNull(i - 1) in setOf("saat", "saati")
            val nextBucuk = n.getOrNull(i + 1)?.startsWith("bucuk") == true
            val digit = Regex("^(\\d{1,2})( ?(da|de|ta|te|a|e|ya|ye))?$").find(n[i])
            if (digit != null) {
                val suffix = digit.groupValues[2].isNotEmpty()
                if (suffix || prevSaat || nextBucuk) {
                    hour = digit.groupValues[1].toInt(); used[i] = true
                    if (nextBucuk) { minute = 30; used[i + 1] = true }
                }
                continue
            }
            // "on birde", "sekiz buçukta", "saat dokuz"
            var wh = wordHour(n[i])
            var span = 1
            if (n[i] == "on") {
                val nx = n.getOrNull(i + 1)?.let { wordHour(it) }
                if (nx != null && nx.first in 1..9) { wh = (10 + nx.first) to nx.second; span = 2 }
            }
            if (wh != null && wh.first in 1..23) {
                val bucuk = n.getOrNull(i + span)?.startsWith("bucuk") == true
                if (wh.second || prevSaat || bucuk) {
                    hour = wh.first
                    for (x in i until i + span) used[x] = true
                    if (bucuk) { minute = 30; used[i + span] = true }
                }
            }
        }
        if (hour == null && dayOffset == null && part == null) return null
        if (hour != null && (hour !in 0..23 || minute !in 0..59)) return null

        var h = hour ?: when (part) { "sabah" -> 8; "ogle" -> 12; "aksam" -> 19; "gece" -> 22; else -> 9 }
        if (hour == null && part == "ogle") minute = 30
        if (hour != null) {
            when (part) {
                "aksam" -> if (h in 1..11) h += 12
                "gece" -> if (h in 8..11) h += 12 else if (h == 12) h = 0
                "ogle" -> if (h in 1..5) h += 12
                null -> if (h in 1..11) {
                    if (dayOffset != null && dayOffset > 0) {
                        if (h in 1..6) h += 12
                    } else {
                        // bugün için: önce gelecek olan (sabah ya da akşam)
                        val am = (cal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, minute) }
                        val pm = (cal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, h + 12); set(Calendar.MINUTE, minute) }
                        if (am.timeInMillis <= now.timeInMillis && pm.timeInMillis > now.timeInMillis) h += 12
                    }
                }
            }
        }
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, minute)
        if (dayOffset != null) cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        else if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
        return When(cal.timeInMillis, restText(words, used))
    }

    private fun restText(words: List<String>, used: BooleanArray): String =
        words.filterIndexed { i, _ -> !used[i] }.joinToString(" ").trim(' ', ',', '.', ':', ';', '-')
}
