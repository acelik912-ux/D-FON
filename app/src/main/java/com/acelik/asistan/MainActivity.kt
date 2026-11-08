package com.acelik.asistan

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

class MainActivity : Activity() {

    private lateinit var tools: PhoneTools
    private lateinit var files: FileTools
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var permBtn: Button

    private val history = mutableListOf<JSONObject>()
    private var busy = false
    private val unannounced = mutableListOf<String>()
    private var autoMessage: String? = null

    private val prefs by lazy { getSharedPreferences("asistan", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tools = PhoneTools(this)
        files = FileTools(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())

        addBubble(
            "Merhaba! Telefon ayarlarını benim üzerimden değiştirebilirsin. " +
                "Uygulamaları açabilir, dosyalarını PDF/Word'e çevirebilirim. " +
                "Yaz ya da 🎤'ya basıp söyle; dosya eklemek için 📎.\n\nÖrnek: \"Parlaklığı %30 yap\", " +
                "\"WhatsApp'ı aç\", \"Bu PDF'i Word'e çevir\", \"Fotoğraflarımdan PDF yap\".",
            Who.BOT
        )
        if (apiKey().isBlank()) {
            addBubble("Başlamadan önce ⚙ düğmesine basıp Claude API anahtarını gir.", Who.INFO)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermButton()
    }

    // ---------------- Arayüz ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }

        // Üst çubuk
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
        }
        top.addView(TextView(this).apply {
            text = "✦ D-FON"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        permBtn = smallButton("İzinler") { showPermissions() }
        top.addView(permBtn)
        top.addView(smallButton("Yeni") { newChat() })
        top.addView(smallButton("⚙") { showSettings() })
        root.addView(top)

        // Sohbet alanı
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        scroll = ScrollView(this).apply { addView(chat) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Hızlı komutlar
        val chips = LinearLayout(this).apply { setPadding(dp(8), 0, dp(8), 0) }
        listOf(
            "Parlaklığı %30 yap", "Feneri aç", "Feneri kapat", "Titreşime al",
            "Rahatsız etmeyi aç", "Medya sesini %50 yap", "Ekran 2 dakikada kapansın",
            "Wi-Fi ayarlarını aç", "Karanlık mod", "Durumu söyle",
            "PDF'i Word'e çevir", "Word'ü PDF yap", "Resimlerden PDF yap", "PDF'i özetle",
            "PDF'i resme çevir", "Word'ü aç", "Hangi uygulamalar var?"
        ).forEach { cmd ->
            chips.addView(TextView(this).apply {
                text = cmd
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = rounded(CHIP, 18)
                setOnClickListener { submit(cmd) }
            }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })

        // Giriş satırı
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(10))
        }
        bar.addView(Button(this).apply {
            text = "📎"
            textSize = 18f
            background = rounded(CHIP, 24)
            setOnClickListener { if (!busy) files.pick("hepsi", true, false) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, 0, dp(4), 0) })
        bar.addView(Button(this).apply {
            text = "🎤"
            textSize = 18f
            background = rounded(CHIP, 24)
            setOnClickListener { startVoice() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        input = EditText(this).apply {
            hint = "Ne yapayım?"
            setHintTextColor(0xFF8A94A6.toInt())
            setTextColor(Color.WHITE)
            background = rounded(CHIP, 24)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { submit(text.toString()); true } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        sendBtn = Button(this).apply {
            text = "Gönder"
            setTextColor(Color.BLACK)
            background = rounded(ACCENT, 24)
            setOnClickListener { submit(input.text.toString()) }
        }
        bar.addView(sendBtn, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(bar)
        return root
    }

    private enum class Who { USER, BOT, ACTION, INFO }

    private fun addBubble(text: String, who: Who) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = if (who == Who.ACTION) 13f else 15.5f
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            when (who) {
                Who.USER -> { setTextColor(Color.BLACK); background = rounded(ACCENT, 16) }
                Who.BOT -> { setTextColor(Color.WHITE); background = rounded(BUBBLE, 16) }
                Who.ACTION -> { setTextColor(0xFF9FE2BF.toInt()); background = rounded(0xFF15283A.toInt(), 12) }
                Who.INFO -> { setTextColor(0xFFFFB4A2.toInt()); background = rounded(0xFF3A1F24.toInt(), 12) }
            }
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            setMargins(0, dp(4), 0, dp(4))
            gravity = if (who == Who.USER) Gravity.END else Gravity.START
        }
        chat.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        sendBtn.isEnabled = !b
        sendBtn.text = if (b) "…" else "Gönder"
    }

    // ---------------- Sohbet döngüsü ----------------

    private fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return
        if (apiKey().isBlank()) { showSettings(); return }
        input.setText("")
        addBubble(text, Who.USER)
        setBusy(true)

        val snapshot = history.size
        val announced = unannounced.toList()
        unannounced.clear()
        val content = if (announced.isEmpty()) text
            else "[Yeni eklenen dosyalar: ${announced.joinToString(", ")}]\n$text"
        history.add(JSONObject().put("role", "user").put("content", content))
        trimHistory()
        val client = ClaudeClient(apiKey(), model())
        val defs = tools.definitions()
        val fdefs = files.definitions()
        for (k in 0 until fdefs.length()) defs.put(fdefs.get(k))

        Thread {
            try {
                var rounds = 0
                while (rounds++ < 8) {
                    val resp = client.send(SYSTEM_PROMPT, JSONArray(history), defs)
                    val content = resp.getJSONArray("content")
                    history.add(JSONObject().put("role", "assistant").put("content", content))

                    val results = JSONArray()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        when (block.optString("type")) {
                            "text" -> {
                                val t = block.optString("text").trim()
                                if (t.isNotEmpty()) runOnUiThread { addBubble(t, Who.BOT) }
                            }
                            "tool_use" -> {
                                val name = block.getString("name")
                                val args = block.optJSONObject("input") ?: JSONObject()
                                val out: ToolOutput = onMain {
                                    if (files.has(name)) files.run(name, args) else ToolOutput(tools.run(name, args))
                                }
                                if (name != "durum_oku" && name != "dosya_oku" && name != "dosyalari_listele") {
                                    runOnUiThread { addBubble("⚙ ${out.text}", Who.ACTION) }
                                }
                                val resultContent: Any = if (out.blocks == null) out.text else {
                                    val arr = JSONArray().put(JSONObject().put("type", "text").put("text", out.text))
                                    for (k in 0 until out.blocks.length()) arr.put(out.blocks.get(k))
                                    arr
                                }
                                results.put(
                                    JSONObject().put("type", "tool_result")
                                        .put("tool_use_id", block.getString("id"))
                                        .put("content", resultContent)
                                )
                            }
                        }
                    }
                    if (resp.optString("stop_reason") != "tool_use" || results.length() == 0) break
                    history.add(JSONObject().put("role", "user").put("content", results))
                }
                runOnUiThread { stripHeavyContent() }
            } catch (e: Exception) {
                // Yarım kalan turu geri al ki sohbet bozulmasın
                runOnUiThread {
                    while (history.size > snapshot) history.removeAt(history.size - 1)
                    addBubble(e.message ?: e.toString(), Who.INFO)
                }
            } finally {
                runOnUiThread {
                    setBusy(false)
                    refreshPermButton()
                    autoMessage?.let { autoMessage = null; submit(it) }
                }
            }
        }.start()
    }

    /**
     * Okunan PDF/resim içeriği her istekte tekrar gönderilmesin (pahalı olur):
     * tur bitince bunları kısa bir nota çevir.
     */
    private fun stripHeavyContent() {
        for (msg in history) {
            val c = msg.opt("content") as? JSONArray ?: continue
            for (i in 0 until c.length()) {
                val block = c.optJSONObject(i) ?: continue
                if (block.optString("type") != "tool_result") continue
                val inner = block.opt("content") as? JSONArray ?: continue
                val kept = JSONArray()
                for (k in 0 until inner.length()) {
                    val b = inner.getJSONObject(k)
                    if (b.optString("type") == "text") kept.put(b)
                    else kept.put(JSONObject().put("type", "text")
                        .put("text", "[Bu içerik daha önce okundu; gerekirse dosya_oku ile tekrar oku.]"))
                }
                block.put("content", kept)
            }
        }
    }

    /** Sohbet çok uzarsa en eski turları at (her zaman düz bir kullanıcı mesajıyla başlasın). */
    private fun trimHistory() {
        if (history.size <= 40) return
        while (history.size > 20) history.removeAt(0)
        while (history.isNotEmpty() &&
            !(history[0].optString("role") == "user" && history[0].opt("content") is String)
        ) history.removeAt(0)
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        runOnUiThread {
            result = runCatching(block)
            latch.countDown()
        }
        latch.await()
        return result!!.getOrThrow()
    }

    private fun queueOrSubmit(msg: String) {
        if (busy) autoMessage = msg else submit(msg)
    }

    private fun newChat() {
        if (busy) return
        history.clear()
        files.files.clear()
        unannounced.clear()
        chat.removeAllViews()
        addBubble("Yeni sohbet. Ne yapayım?", Who.BOT)
    }

    // ---------------- Ses ----------------

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ne yapayım?")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_VOICE)
        } catch (ignored: ActivityNotFoundException) {
            Toast.makeText(this, "Bu telefonda ses tanıma bulunamadı (Google uygulaması gerekli).", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) submit(said)
        }
        if (requestCode == FileTools.REQ_PICK) {
            val byAi = files.pickRequestedByAi
            files.pickRequestedByAi = false
            val added = if (resultCode == RESULT_OK) files.handlePick(data) else emptyList()
            if (added.isEmpty()) {
                if (byAi) queueOrSubmit("Dosya seçmekten vazgeçtim.")
                return
            }
            val names = added.map { (no, f) -> "$no) ${f.name}" }
            unannounced.addAll(names)
            addBubble("📎 " + names.joinToString("\n"), Who.ACTION)
            if (byAi) queueOrSubmit("Dosyaları seçtim, devam et.")
        }
    }

    // ---------------- Ayarlar & izinler ----------------

    private fun apiKey() = prefs.getString("key", "") ?: ""
    private fun model() = (prefs.getString("model", "") ?: "").ifBlank { DEFAULT_MODEL }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val key = EditText(this).apply {
            hint = "sk-ant-..."
            setText(apiKey())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val mdl = EditText(this).apply {
            hint = DEFAULT_MODEL
            setText(prefs.getString("model", ""))
        }
        box.addView(label("Claude API anahtarı (console.anthropic.com)"))
        box.addView(key)
        box.addView(label("Model (boş bırakırsan: $DEFAULT_MODEL)"))
        box.addView(mdl)
        AlertDialog.Builder(this)
            .setTitle("Ayarlar")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit()
                    .putString("key", key.text.toString().trim())
                    .putString("model", mdl.text.toString().trim())
                    .apply()
                Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showPermissions() {
        val w = tools.canWriteSettings()
        val d = tools.hasDndAccess()
        AlertDialog.Builder(this)
            .setTitle("İzinler")
            .setMessage(
                "${if (w) "✅" else "❌"} Sistem ayarlarını değiştirme\n" +
                    "   (parlaklık, ekran zaman aşımı, döndürme)\n\n" +
                    "${if (d) "✅" else "❌"} Rahatsız Etmeyin erişimi\n" +
                    "   (sessiz mod, rahatsız etmeyin)\n\n" +
                    "Açılan ekranda D-FON'ı bulup izni aç, sonra geri dön."
            )
            .setPositiveButton("Sistem ayarları izni") { _, _ -> tools.requestWriteSettings() }
            .setNeutralButton("Rahatsız etmeyin izni") { _, _ -> tools.requestDndAccess() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun refreshPermButton() {
        if (!::permBtn.isInitialized) return
        val ok = tools.canWriteSettings() && tools.hasDndAccess()
        permBtn.text = if (ok) "İzinler ✓" else "İzinler !"
    }

    // ---------------- küçük yardımcılar ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(CHIP, 16)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply { setMargins(dp(4), 0, dp(4), 0) }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setPadding(0, dp(12), 0, 0)
    }

    companion object {
        private const val REQ_VOICE = 41
        private const val DEFAULT_MODEL = "claude-haiku-5-5"

        private val BG = 0xFF0F1A2A.toInt()
        private val BUBBLE = 0xFF1F3A5F.toInt()
        private val CHIP = 0xFF22324A.toInt()
        private val ACCENT = 0xFFFFD166.toInt()

        private const val SYSTEM_PROMPT = """
Sen kullanıcının Android telefonunda çalışan kişisel asistansın. Adın "D-FON".
Kullanıcı Türkçe konuşur; samimi Türkçe cevap ver. Ayar ve dosya işlemlerinde kısa ol (1-2 cümle);
özet, çeviri gibi isteklerde gerektiği kadar yaz.

Görevin, kullanıcının söylediği telefon ayarlarını araçlarla değiştirmek:
- Doğrudan değiştirebildiklerin: parlaklık, otomatik parlaklık, ekran zaman aşımı, otomatik döndürme,
  ses seviyeleri, zil modu, Rahatsız Etmeyin, fener.
- Android'in uygulamalara doğrudan değiştirtmediği ayarlar (Wi-Fi, Bluetooth, mobil veri, karanlık mod,
  yazı boyutu, duvar kağıdı, uçak modu, hotspot vb.) için ayar_ekrani_ac ile ilgili ekranı aç ve
  kullanıcıya orada hangi düğmeye dokunacağını tek cümleyle söyle.
- "Biraz artır", "azalt", "yarıya indir" gibi göreceli isteklerde önce durum_oku çağır.
- Bir araç "İzin yok" derse, kullanıcıya açılan ekranda D-FON'ın iznini açmasını ve komutu tekrar vermesini söyle.
- Birden fazla istek varsa hepsini yap ("gece moduna geç" → parlaklığı düşür + titreşime al gibi mantıklı yorumla).

Uygulamalar ve dosyalar:
- Kullanıcı bir uygulamayı açmak isterse uygulama_ac kullan. Emin değilsen uygulamalari_listele ile ara.
- Dosyalar numarayla anılır (1, 2, 3...). Kullanıcının mesajında "[Yeni eklenen dosyalar: ...]" görürsen
  o dosyalar hazırdır. İşlem için dosya yoksa dosya_sec ile kullanıcıdan iste ve seçmesini bekle.
- Word → PDF ve resim → PDF: pdf_olustur(dosya_nolari).
- PDF → Word: önce dosya_oku, sonra içeriği (başlıkları "# " ile) word_olustur'a yaz.
- PDF'i özetleme/çeviri/soru: dosya_oku ile oku, sonra cevapla; istenirse sonucu PDF ya da Word yap.
- PDF → resim: pdf_resme_cevir.
- Oluşan dosyayı açmak/paylaşmak için dosya_ac / dosya_paylas (uygulama adı verilebilir, ör. "Word", "WhatsApp").
- Dönüştürmede yazı aktarılır; tablo, resim gibi karmaşık biçimler kaybolabilir — gerekirse bunu kısaca belirt.
- Telefon ve dosyalarla ilgili olmayan sorulara da kısaca yardımcı olabilirsin.
Yaptığın değişikliği abartmadan, kısaca onayla.
"""
    }
}
