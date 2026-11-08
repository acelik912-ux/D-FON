package com.acelik.asistan

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup

class MainActivity : Activity() {

    private lateinit var phone: PhoneTools
    private lateinit var files: FileTools
    private lateinit var engine: CommandEngine

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var permBtn: Button

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phone = PhoneTools(this)
        files = FileTools(this)
        engine = CommandEngine(phone, files)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())

        addBubble(
            "Merhaba! Ben D-FON. Tamamen telefonunda çalışırım: internete bağlanmam, ücret istemem, " +
                "kimseye veri göndermem.\n\nYaz ya da 🎤'ya basıp söyle. Ne yapabildiğimi görmek için \"yardım\" yaz.",
            Who.BOT
        )
        if (files.allowedPackages().isEmpty()) {
            addBubble("Açabileceğim uygulamaları seçmek için üstteki \"Uygulamalar\" düğmesine bas.", Who.INFO)
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
        top.addView(smallButton("Uygulamalar") { showAppPicker() })
        top.addView(smallButton("Temizle") { clearChat() })
        root.addView(top)

        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        scroll = ScrollView(this).apply { addView(chat) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val chips = LinearLayout(this).apply { setPadding(dp(8), 0, dp(8), 0) }
        listOf(
            "Yardım", "Durum", "Parlaklık 30", "Feneri aç", "Feneri kapat", "Titreşime al", "Sessizden çık",
            "Gece modu", "Medya sesi 50", "PDF'i Word'e çevir", "Word'ü PDF yap", "Resimleri PDF yap",
            "PDF'leri birleştir", "PDF'i resme çevir", "Dosyayı aç", "Wi-Fi", "Karanlık mod", "Uygulamalar"
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

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(10))
        }
        bar.addView(Button(this).apply {
            text = "📎"
            textSize = 18f
            background = rounded(CHIP, 24)
            setOnClickListener { if (!busy) files.pick("hepsi", true) }
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

    private enum class Who { USER, BOT, INFO }

    private fun addBubble(text: String, who: Who) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15.5f
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            when (who) {
                Who.USER -> { setTextColor(Color.BLACK); background = rounded(ACCENT, 16) }
                Who.BOT -> { setTextColor(Color.WHITE); background = rounded(BUBBLE, 16) }
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

    // ---------------- Komut çalıştırma ----------------

    private fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return
        input.setText("")
        addBubble(text, Who.USER)
        runInBackground { engine.handle(text) }
    }

    /** Dosya dönüştürme gibi uzun işler arayüzü dondurmasın diye arka planda çalışır. */
    private fun runInBackground(work: () -> List<String>) {
        setBusy(true)
        Thread {
            val replies = try {
                work()
            } catch (e: Exception) {
                listOf("Hata: ${e.message ?: e.javaClass.simpleName}")
            }
            runOnUiThread {
                replies.forEach { addBubble(it, Who.BOT) }
                setBusy(false)
                refreshPermButton()
            }
        }.start()
    }

    private fun clearChat() {
        if (busy) return
        engine.reset()
        files.files.clear()
        chat.removeAllViews()
        addBubble("Temizlendi. Ne yapayım?", Who.BOT)
    }

    // ---------------- Ses ----------------

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ne yapayım?")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_VOICE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Bu telefonda ses tanıma bulunamadı.", Toast.LENGTH_LONG).show()
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
            val picked = if (resultCode == RESULT_OK) files.handlePick(data) else emptyList()
            runInBackground { engine.onFilesPicked(picked) }
        }
    }

    // ---------------- Uygulama seçimi ----------------

    private fun showAppPicker() {
        val apps = files.installedApps()
        val selected = HashSet(files.allowedPackages())
        var shown = apps

        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val cb = (convertView as? CheckBox) ?: CheckBox(this@MainActivity).apply {
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    textSize = 16f
                    isClickable = false
                    isFocusable = false
                }
                val app = shown[position]
                cb.text = app.label
                cb.isChecked = app.pkg in selected
                return cb
            }
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        val info = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        }
        fun refreshInfo() { info.text = "D-FON sadece işaretlediğin ${selected.size} uygulamayı açabilir ve dosya gönderebilir." }
        refreshInfo()
        val search = EditText(this).apply { hint = "Ara…" }
        val list = ListView(this).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, pos, _ ->
                val p = shown[pos].pkg
                if (!selected.remove(p)) selected.add(p)
                adapter.notifyDataSetChanged()
                refreshInfo()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = FileTools.norm(s?.toString() ?: "")
                shown = if (q.isEmpty()) apps else apps.filter { FileTools.norm(it.label).contains(q) }
                adapter.notifyDataSetChanged()
            }
        })
        box.addView(info)
        box.addView(search)
        box.addView(list, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55).toInt()))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Kullanılabilecek uygulamalar")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                files.setAllowedPackages(selected)
                Toast.makeText(this, "${selected.size} uygulama izinli", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Hepsini seç", null)
            .setNegativeButton("İptal", null)
            .create()
        dialog.setOnShowListener {
            // "Hepsini seç / Hiçbiri" diyaloğu kapatmadan çalışsın
            val b = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            b.setOnClickListener {
                if (selected.containsAll(shown.map { it.pkg })) {
                    selected.removeAll(shown.map { it.pkg }.toSet())
                    b.text = "Hepsini seç"
                } else {
                    selected.addAll(shown.map { it.pkg })
                    b.text = "Hiçbiri"
                }
                adapter.notifyDataSetChanged()
                refreshInfo()
            }
        }
        dialog.show()
    }

    // ---------------- İzinler ----------------

    private fun showPermissions() {
        val w = phone.canWriteSettings()
        val d = phone.hasDndAccess()
        AlertDialog.Builder(this)
            .setTitle("İzinler")
            .setMessage(
                "${if (w) "✅" else "❌"} Sistem ayarlarını değiştirme\n" +
                    "   (parlaklık, ekran zaman aşımı, döndürme)\n\n" +
                    "${if (d) "✅" else "❌"} Rahatsız Etmeyin erişimi\n" +
                    "   (sessiz mod, rahatsız etmeyin)\n\n" +
                    "Açılan ekranda D-FON'u bulup izni aç, sonra geri dön."
            )
            .setPositiveButton("Sistem ayarları izni") { _, _ -> phone.requestWriteSettings() }
            .setNeutralButton("Rahatsız etmeyin izni") { _, _ -> phone.requestDndAccess() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun refreshPermButton() {
        if (!::permBtn.isInitialized) return
        val ok = phone.canWriteSettings() && phone.hasDndAccess()
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
        textSize = 13f
        background = rounded(CHIP, 16)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply { setMargins(dp(3), 0, dp(3), 0) }
    }

    companion object {
        private const val REQ_VOICE = 41
        private val BG = 0xFF0F1A2A.toInt()
        private val BUBBLE = 0xFF1F3A5F.toInt()
        private val CHIP = 0xFF22324A.toInt()
        private val ACCENT = 0xFFFFD166.toInt()
    }
}
