package com.acelik.asistan

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
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

    private lateinit var panel: ScrollView
    private lateinit var panelBtn: Button
    private lateinit var resultLine: TextView
    private lateinit var sectionsBox: LinearLayout
    private lateinit var wakeBar: TextView
    private lateinit var routinesBox: LinearLayout
    private lateinit var voiceReplyTile: TextView
    /** Son komut sesle mi verildi (sesli cevap için) */
    private var fromVoice = false
    private var layout: MutableList<Section> = mutableListOf()
    private lateinit var prayerInfo: TextView
    private lateinit var prayerRow: LinearLayout
    private lateinit var prayerToggle: TextView
    private lateinit var prayerEvent: TextView
    private lateinit var prayerWarn: TextView
    private var pendingLocate = false

    private lateinit var micBtn: TextView
    private lateinit var micHint: TextView
    private lateinit var wakeChip: TextView
    private var recognizer: SpeechRecognizer? = null
    private var voiceOn = false
    private var pendingVoice = false
    private var pendingWake = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phone = PhoneTools(this)
        files = FileTools(this)
        engine = CommandEngine(phone, files, ClockTools(this), this, ContactTools(this))
        engine.onUi = { what ->
            runOnUiThread {
                when (what) {
                    "kible" -> PrayerExtras.qibla(this)
                    "imsakiye" -> PrayerExtras.monthly(this)
                }
            }
        }
        Reminders.schedule(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())

        addBubble(
            "Merhaba! Ben D-FON (sürüm ${Diag.version(this)}). Tamamen telefonunda çalışırım: internete bağlanmam, ücret istemem, " +
                "kimseye veri göndermem.\n\nYaz ya da ortadaki büyük 🎤'ya dokunup söyle. Ne yapabildiğimi görmek için \"yardım\" yaz.",
            Who.BOT
        )
        if (files.allowedPackages().isEmpty()) {
            addBubble("Açabileceğim uygulamaları seçmek için üstteki \"Uygulamalar\" düğmesine bas.", Who.INFO)
        }
        handleWake(intent)
        handleExtras(intent)
        Prayer.refreshExtras(this)
        if (Build.VERSION.SDK_INT >= 33 && Prayer.hasLocation(this) && Prayer.countdownEnabled(this) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    /** Rutin saati geldiğinde ya da bildirim paneli kutucuğundan açıldığında. */
    private fun handleExtras(i: Intent?) {
        if (i == null) return
        i.getStringExtra(Routines.EXTRA_ROUTINE)?.let { name ->
            val background = i.getBooleanExtra(Routines.EXTRA_BACKGROUND, false)
            i.removeExtra(Routines.EXTRA_ROUTINE)
            addBubble("⏰ $name", Who.USER)
            runInBackground({ engine.handle(name) }) {
                if (background) moveTaskToBack(true)
            }
        }
        if (i.getBooleanExtra(EXTRA_TILE_WAKE, false)) {
            i.removeExtra(EXTRA_TILE_WAKE)
            if (!WakeService.isEnabled(this)) toggleWake()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWake(intent)
        handleExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPermButton()
        WakeService.appVisible = true
        WakeService.instance?.pause()
        if (WakeService.isEnabled(this) && micGranted()) WakeService.start(this)
        updateWakeChip()
        Prayer.recover(this)
        if (::resultLine.isInitialized) PrayerExtras.maybeRefreshLocation(this)?.let { showResult(it) }
        if (::prayerRow.isInitialized) refreshPrayerCard()
        if (::sectionsBox.isInitialized) {
            refreshSections()
            if (AppLock.locked(this).isNotEmpty() && !AppLock.serviceEnabled(this)) {
                showResult("⚠ Uygulama kilidi çalışmıyor: Erişilebilirlik → Yüklü uygulamalar → \"D-FON Uygulama Kilidi\"ni aç.")
            }
        }
    }

    override fun onPause() {
        stopVoice(null)
        WakeService.appVisible = false
        WakeService.instance?.resume()
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
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
        panelBtn = smallButton("⊞ Butonlar") { showPanel(panel.visibility != View.VISIBLE) }
        top.addView(panelBtn)
        root.addView(top)

        // En üstte, her zaman görünen büyük aç/kapat düğmesi: "bir iki üç fon" dinlemesi
        wakeBar = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            elevation = dp(3).toFloat()
            setOnClickListener { toggleWake() }
        }
        root.addView(wakeBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })

        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        // Sohbet ve buton ekranı aynı yerde; üstteki düğmeyle değişir
        val content = FrameLayout(this)
        scroll = ScrollView(this).apply { addView(chat) }
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        panel = buildPanel()
        content.addView(panel, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // Ortadaki büyük mikrofon
        val micPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(4))
        }
        micBtn = TextView(this).apply {
            text = "🎤"
            textSize = 38f
            gravity = Gravity.CENTER
            background = oval(ACCENT)
            elevation = dp(6).toFloat()
            contentDescription = "Konuş"
            setOnClickListener { if (voiceOn) stopVoice(null) else startVoice() }
        }
        micPanel.addView(micBtn, LinearLayout.LayoutParams(dp(96), dp(96)))
        micHint = TextView(this).apply {
            text = "Dokun ve söyle"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF8A94A6.toInt())
            setPadding(dp(16), dp(6), dp(16), 0)
            maxLines = 2
        }
        micPanel.addView(micHint, LinearLayout.LayoutParams(-1, -2))
        root.addView(micPanel, LinearLayout.LayoutParams(-1, -2))

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
        showPanel(true)
        return root
    }

    // ---------------- Buton ekranı ----------------

    private fun showPanel(on: Boolean) {
        panel.visibility = if (on) View.VISIBLE else View.GONE
        scroll.visibility = if (on) View.GONE else View.VISIBLE
        panelBtn.text = if (on) "💬 Sohbet" else "⊞ Butonlar"
        if (!on) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Seçilen dosyalar için tek dokunuşluk işlem listesi: aç, oku, çevir, paylaş. */
    private fun showFileActions(nos: List<Int>) {
        val kinds = nos.map { files.kind(it) }
        val first = nos.first()
        val items = ArrayList<Pair<String, () -> List<String>>>()
        items.add("📂 Aç" to { listOf(files.openFile(first, null)) })
        if (kinds.any { it == "pdf" || it == "word" || it == "metin" })
            items.add("🔊 Sesli oku" to { listOf(files.readAloud(nos[kinds.indexOfFirst { it == "pdf" || it == "word" || it == "metin" }])) })
        if (kinds.any { it == "resim" || it == "word" || it == "metin" }) {
            val ok = nos.filterIndexed { i, _ -> kinds[i] in listOf("resim", "word", "metin") }
            items.add("📄 PDF yap" to { listOf(files.filesToPdf(ok)) })
        }
        kinds.indexOf("pdf").let { i ->
            if (i >= 0) {
                items.add("📝 Word'e çevir (PDF → .docx)" to { listOf(files.pdfToDocx(nos[i])) })
                items.add("🖼️ PDF'i resimlere çevir" to { listOf(files.pdfToImages(nos[i])) })
            }
        }
        val pdfs = nos.filterIndexed { i, _ -> kinds[i] == "pdf" }
        if (pdfs.size >= 2) items.add("➕ PDF'leri birleştir" to { listOf(files.mergePdfs(pdfs)) })
        items.add("📤 Paylaş" to { listOf(files.shareFile(first, null)) })
        val title = if (nos.size == 1) files.name(first) else "${nos.size} dosya seçildi"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                val job = items[which].second
                runInBackground({ job() })
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showResult(text: String) {
        resultLine.text = text
        resultLine.visibility = View.VISIBLE
        panel.post { panel.smoothScrollTo(0, 0) }
    }

    private fun buildPanel(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(12))
        }
        resultLine = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(BUBBLE, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextIsSelectable(true)
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        col.addView(resultLine, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(3), 0, dp(3), dp(8)) })
        col.addView(buildPrayerCard(), LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(3), 0, dp(3), dp(4)) })

        col.addView(TextView(this).apply {
            text = "İpucu: Bir düğmeye basılı tutarak başka bölüme taşıyabilir, yerini değiştirebilir, " +
                "kilitleyebilir ya da kaldırabilirsin. Bölüm adına basılı tutarak bölümü düzenlersin."
            textSize = 11.5f
            setTextColor(0xFF6F7A8C.toInt())
            setPadding(dp(4), dp(8), dp(4), 0)
        })
        sectionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sectionsBox)
        refreshSections()
        col.addView(TextView(this).apply {
            text = "➕ Yeni bölüm ekle"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.WHITE)
            background = rounded(CHIP, 14)
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { newSection(null) }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(3), dp(10), dp(3), 0) })

        col.addView(sectionTitle("⚡ Rutinlerim", "basılı tut: düzenle"))
        routinesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(routinesBox)
        refreshRoutines()

        wakeChip = tile("👂", "Uyandırma") { toggleWake() }
        voiceReplyTile = tile("🔊", "Sesli cevap") { toggleVoiceReply() }
        updateVoiceReplyTile()
        col.addView(sectionTitle("🛠 D-FON", null))
        col.addView(grid(listOf(
            tile("❓", "Yardım") { submit("Yardım", true) },
            tile("🕘", "Geçmiş") { showHistory() },
            wakeChip,
            voiceReplyTile,
            tile("📝", "Notlarım") { submit("Notlarım", true) },
            tile("⏰", "Hatırlatıcılar") { Reminders.show(this) { showResult(it) } },
            tile("🎓", "Öğret") { Aliases.show(this, engine.lastUnknown) { showResult(it) } },
            tile("🔑", "Kilit şifresi") { Guard.setup(this) { showResult(it); refreshSections() } },
            tile("✅", "İzinli uygulamalar") { showAppPicker() },
            tile("🔐", "İzinler") { showPermissions() },
            tile("🎨", "Widget teması") { showWidgetStyle() },
            tile("🖼", "Telefon teması") { PhoneThemes.show(this) { refreshSections() } },
            tile("🧩", "Temalı simgeleri kaydet") { exportIcons() },
            tile("💾", "Yedekle") { Backup.menu(this) },
            tile("🩺", "Tanılama") { Diag.show(this) },
            tile("🧹", "Sohbeti temizle") { clearChat() },
        )))
        return ScrollView(this).apply { addView(col) }
    }

    private fun sectionTitle(t: String, hint: String?) = TextView(this).apply {
        text = if (hint == null) t else "$t   ·  $hint"
        textSize = 13f
        setTextColor(0xFF8A94A6.toInt())
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(12), dp(4), dp(2))
    }

    private fun setTileText(tv: TextView, emoji: String, label: String) {
        val sp = SpannableString("$emoji\n$label")
        sp.setSpan(RelativeSizeSpan(1.7f), 0, emoji.length, 0)
        tv.text = sp
    }

    private fun tile(emoji: String, label: String, onClick: () -> Unit) = TextView(this).apply {
        setTileText(this, emoji, label)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 11.5f
        maxLines = 3
        setPadding(dp(3), dp(6), dp(3), dp(6))
        background = LedStyle.FrameDrawable(this@MainActivity, 14f, CHIP)
        setOnClickListener { onClick() }
    }

    /** Görünümleri 4 sütunlu küçük düğme ızgarasına dizer. */
    private fun grid(tiles: List<View>, cols: Int = 4): LinearLayout {
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tiles.chunked(cols).forEach { row ->
            val r = LinearLayout(this)
            fun lp() = LinearLayout.LayoutParams(0, dp(74), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            row.forEach { r.addView(it, lp()) }
            repeat(cols - row.size) { r.addView(View(this), lp()) }
            g.addView(r)
        }
        return g
    }

    // ---------------- Düzenlenebilir bölümler ----------------

    /** Bir düğme: komut (emoji, yazı, komut) ya da uygulama (paket adı). */
    private class Item(val emoji: String = "", val label: String = "", val cmd: String = "", val pkg: String? = null) {
        fun json(): JSONObject =
            if (pkg != null) JSONObject().put("a", pkg)
            else JSONObject().put("e", emoji).put("l", label).put("c", cmd)

        companion object {
            fun from(o: JSONObject) =
                if (o.has("a")) Item(pkg = o.getString("a"))
                else Item(o.optString("e"), o.optString("l"), o.optString("c"))
        }
    }

    private class Section(var title: String, val items: MutableList<Item>)

    /** Eski sürümdeki "Uygulamalarım" kısayolları (ilk açılışta yeni düzene taşınır). */
    private fun oldShortcuts(): List<String> {
        val raw = getSharedPreferences("dfon", MODE_PRIVATE).getString("kisayollar", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadLayout(): MutableList<Section> {
        val raw = getSharedPreferences("dfon", MODE_PRIVATE).getString("duzen", null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                return (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val items = o.getJSONArray("i")
                    Section(o.getString("t"), (0 until items.length()).map { k -> Item.from(items.getJSONObject(k)) }.toMutableList())
                }.toMutableList()
            } catch (e: Exception) {
            }
        }
        val list = mutableListOf(Section("📱 Uygulamalarım", oldShortcuts().map { Item(pkg = it) }.toMutableList()))
        SECTIONS.forEach { (t, items) ->
            list.add(Section(t, items.map { (e, l, c) -> Item(e, l, c) }.toMutableList()))
        }
        return list
    }

    private fun saveLayout() {
        val arr = JSONArray()
        layout.forEach { sec ->
            val items = JSONArray()
            sec.items.forEach { items.put(it.json()) }
            arr.put(JSONObject().put("t", sec.title).put("i", items))
        }
        getSharedPreferences("dfon", MODE_PRIVATE).edit().putString("duzen", arr.toString()).apply()
    }

    private fun refreshSections() {
        if (layout.isEmpty()) layout = loadLayout()
        sectionsBox.removeAllViews()
        val apps = files.installedApps().associateBy { it.pkg }
        layout.forEachIndexed { si, sec ->
            sectionsBox.addView(sectionTitle(sec.title, null).apply {
                setOnLongClickListener { sectionMenu(si); true }
            })
            val tiles = mutableListOf<View>()
            sec.items.forEachIndexed { ii, item ->
                val v: View? = if (item.pkg != null) apps[item.pkg]?.let { appTile(it) }
                else tile(item.emoji, item.label) { submit(item.cmd, true) }
                if (v != null) {
                    v.setOnLongClickListener { itemMenu(si, ii); true }
                    tiles.add(v)
                }
            }
            tiles.add(tile("➕", "Ekle") { addMenu(si) })
            sectionsBox.addView(grid(tiles))
        }
    }

    private fun appLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** Uygulama düğmesi: simgesiz, sadece adı. */
    private fun appTile(app: FileTools.App) = TextView(this).apply {
        val locked = AppLock.isLocked(this@MainActivity, app.pkg)
        val left = if (locked) Guard.remainingText(this@MainActivity, app.pkg) else null
        text = when {
            left != null -> "🔒 ${app.label}\n⏳ $left"
            locked -> "🔒\n${app.label}"
            else -> app.label
        }
        gravity = Gravity.CENTER
        textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        background = LedStyle.FrameDrawable(this@MainActivity, 14f, if (locked) LOCKED_BG else APP_BG)
        setPadding(dp(4), dp(6), dp(4), dp(6))
        setOnClickListener { openGuarded(app) }
    }

    /** Kilitli uygulamayı açmadan önce süre sınırına ve şifreye bakar. */
    private fun openGuarded(app: FileTools.App) {
        if (!AppLock.isLocked(this, app.pkg)) {
            showResult(files.openApp(app))
            return
        }
        if (Guard.limit(this, app.pkg) > 0 && Guard.remainingMs(this, app.pkg) <= 0) {
            AlertDialog.Builder(this)
                .setTitle("⏳ ${app.label}")
                .setMessage("Bugünkü süre (${Guard.limit(this, app.pkg)} dk) doldu. Yarın yenilenir.")
                .setPositiveButton("Tamam", null)
                .setNeutralButton("15 dk ekle (şifreyle)") { _, _ ->
                    if (!Guard.hasPin(this)) {
                        showResult("Süre eklemek için önce D-FON bölümünden 🔑 Kilit şifresi belirle.")
                    } else {
                        Guard.ask(this, "${app.label}: 15 dk ekle", force = true) {
                            Guard.addBonus(this, app.pkg, 15)
                            refreshSections()
                            showResult(files.openApp(app))
                        }
                    }
                }
                .show()
            return
        }
        Guard.ask(this, app.label) { showResult(files.openApp(app)) }
    }

    private fun limitDialog(pkg: String) {
        val name = appLabel(pkg)
        val options = listOf(0, 15, 30, 45, 60, 90, 120)
        val labels = options.map { if (it == 0) "Sınır yok" else "Günde $it dakika" }.toTypedArray()
        Guard.ask(this, "$name: süre sınırı", force = true) {
            AlertDialog.Builder(this)
                .setTitle("⏳ $name için günlük süre")
                .setSingleChoiceItems(labels, options.indexOf(Guard.limit(this, pkg)).coerceAtLeast(0)) { d, w ->
                    Guard.setLimit(this, pkg, options[w])
                    d.dismiss()
                    if (options[w] > 0 && !AppLock.isLocked(this, pkg)) AppLock.setLocked(this, pkg, true)
                    refreshSections()
                    val msg = StringBuilder(
                        if (options[w] == 0) "⏳ $name için süre sınırı kaldırıldı."
                        else "⏳ $name günde ${options[w]} dakika açık kalabilir; süre dolunca kapanır ve ertesi gün yenilenir. " +
                            "Uygulama kilitli olduğu için sadece D-FON'dan açılır."
                    )
                    if (options[w] > 0 && !Guard.hasPin(this)) {
                        msg.append("\n\n⚠ Kilit şifresi yok: sınırı herkes değiştirebilir. D-FON bölümünden 🔑 Kilit şifresi belirle.")
                    }
                    if (options[w] > 0 && !AppLock.serviceEnabled(this)) lockSetupDialog() else showResult(msg.toString())
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
    }

    private fun itemMenu(si: Int, ii: Int) {
        val sec = layout[si]
        val item = sec.items[ii]
        val pkg = item.pkg
        val name = if (pkg != null) appLabel(pkg) else item.label
        val opts = mutableListOf<Pair<String, () -> Any?>>()
        opts += "📂 Başka bölüme taşı" to { moveDialog(si, ii) }
        if (ii > 0) opts += "⬅ Öne al" to { swap(si, ii, ii - 1) }
        if (ii < sec.items.size - 1) opts += "➡ Arkaya al" to { swap(si, ii, ii + 1) }
        if (pkg != null) {
            opts += (if (AppLock.isLocked(this, pkg)) "🔓 Kilidi kaldır (her yerden açılsın)"
            else "🔒 Sadece D-FON'dan açılsın") to { toggleLock(pkg) }
            opts += "⏳ Günlük süre sınırı" to { limitDialog(pkg) }
        }
        opts += "🗑 Butonu kaldır" to {
            val remove = {
                sec.items.removeAt(ii)
                saveLayout()
                if (pkg != null) unlockIfOrphan(pkg)
                refreshSections()
            }
            if (pkg != null && AppLock.isLocked(this, pkg)) Guard.ask(this, "$name: düğmeyi kaldır", force = true) { remove() }
            else remove()
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun swap(si: Int, a: Int, b: Int) {
        val items = layout[si].items
        val t = items[a]
        items[a] = items[b]
        items[b] = t
        saveLayout()
        refreshSections()
    }

    private fun moveDialog(si: Int, ii: Int) {
        val targets = layout.indices.filter { it != si }
        val names = targets.map { layout[it].title } + "➕ Yeni bölüm…"
        AlertDialog.Builder(this)
            .setTitle("Hangi bölüme?")
            .setItems(names.toTypedArray()) { _, w ->
                val item = layout[si].items.removeAt(ii)
                if (w < targets.size) {
                    layout[targets[w]].items.add(item)
                    saveLayout()
                    refreshSections()
                } else {
                    saveLayout()
                    newSection(item)
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** Yeni bölüm açar; item verilirse içine koyar (iptal edilirse eski yerine değil "Uygulamalarım"a döner). */
    private fun newSection(item: Item?) {
        val input = EditText(this).apply { hint = "Bölüm adı (ör. 👦 Oğlum için)" }
        AlertDialog.Builder(this)
            .setTitle("Yeni bölüm")
            .setView(input.also { it.setPadding(dp(20), dp(12), dp(20), dp(12)) })
            .setPositiveButton("Ekle") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Yeni bölüm" }
                layout.add(Section(name, if (item != null) mutableListOf(item) else mutableListOf()))
                saveLayout()
                refreshSections()
            }
            .setNegativeButton("Vazgeç") { _, _ ->
                if (item != null) {
                    (layout.firstOrNull() ?: Section("📱 Uygulamalarım", mutableListOf()).also { layout.add(it) }).items.add(item)
                    saveLayout()
                    refreshSections()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun sectionMenu(si: Int) {
        val sec = layout[si]
        val opts = mutableListOf<Pair<String, () -> Any?>>()
        opts += "✏ Adını değiştir" to {
            val input = EditText(this).apply { setText(sec.title) }
            AlertDialog.Builder(this)
                .setTitle("Bölüm adı")
                .setView(input.also { it.setPadding(dp(20), dp(12), dp(20), dp(12)) })
                .setPositiveButton("Kaydet") { _, _ ->
                    sec.title = input.text.toString().trim().ifEmpty { sec.title }
                    saveLayout()
                    refreshSections()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        if (si > 0) opts += "⬆ Yukarı taşı" to { layout.add(si - 1, layout.removeAt(si)); saveLayout(); refreshSections() }
        if (si < layout.size - 1) opts += "⬇ Aşağı taşı" to { layout.add(si + 1, layout.removeAt(si)); saveLayout(); refreshSections() }
        opts += "🗑 Bölümü sil" to {
            AlertDialog.Builder(this)
                .setMessage("\"${sec.title}\" bölümü ve içindeki ${sec.items.size} düğme silinsin mi?")
                .setPositiveButton("Sil") { _, _ ->
                    layout.removeAt(si)
                    saveLayout()
                    sec.items.mapNotNull { it.pkg }.forEach { unlockIfOrphan(it) }
                    refreshSections()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        AlertDialog.Builder(this)
            .setTitle(sec.title)
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun addMenu(si: Int) {
        val opts = listOf("📱 Uygulama ekle", "⚡ Hazır komut ekle", "✍ Kendi komutunu yaz")
        AlertDialog.Builder(this)
            .setTitle("${layout[si].title} — ekle")
            .setItems(opts.toTypedArray()) { _, w ->
                when (w) {
                    0 -> showAddApp(si)
                    1 -> addPresetCommand(si)
                    else -> addCustomCommand(si)
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun addPresetCommand(si: Int) {
        val all = SECTIONS.flatMap { it.second }
        AlertDialog.Builder(this)
            .setTitle("Komut seç")
            .setItems(all.map { "${it.first}  ${it.second}" }.toTypedArray()) { _, w ->
                val (e, l, c) = all[w]
                layout[si].items.add(Item(e, l, c))
                saveLayout()
                refreshSections()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun addCustomCommand(si: Int) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val label = EditText(this).apply { hint = "Düğmede yazacak (ör. Ninni)" }
        val cmd = EditText(this).apply { hint = "Komut (ör. YouTube'da ninni izle)" }
        box.addView(label)
        box.addView(cmd)
        AlertDialog.Builder(this)
            .setTitle("Kendi komutun")
            .setView(box)
            .setPositiveButton("Ekle") { _, _ ->
                val c = cmd.text.toString().trim()
                if (c.isEmpty()) return@setPositiveButton
                layout[si].items.add(Item("⭐", label.text.toString().trim().ifEmpty { c.take(20) }, c))
                saveLayout()
                refreshSections()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun showAddApp(si: Int) {
        val have = layout.flatMap { s -> s.items.mapNotNull { it.pkg } }.toSet()
        val apps = files.installedApps().filter { it.pkg !in have }
        var shown = apps
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, apps.map { it.label }.toMutableList())
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        val search = EditText(this).apply { hint = "Ara…" }
        val list = ListView(this).apply { this.adapter = adapter }
        box.addView(search)
        box.addView(list, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55).toInt()))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Uygulama ekle")
            .setView(box)
            .setNegativeButton("Kapat", null)
            .create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val q = FileTools.norm(e?.toString() ?: "")
                shown = if (q.isEmpty()) apps else apps.filter { FileTools.norm(it.label).contains(q) }
                adapter.clear()
                adapter.addAll(shown.map { it.label })
            }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val app = shown[pos]
            layout[si].items.add(Item(pkg = app.pkg))
            saveLayout()
            // Düğmeye eklenen uygulamayı D-FON sesli komutla da açabilsin
            files.setAllowedPackages(files.allowedPackages() + app.pkg)
            refreshSections()
            dialog.dismiss()
            AlertDialog.Builder(this)
                .setTitle(app.label)
                .setMessage("Bu uygulama sadece D-FON'dan açılabilsin mi? (Ana ekrandan, son uygulamalardan ya da bildirimden açılmaz.)")
                .setPositiveButton("🔒 Evet, kilitle") { _, _ -> toggleLock(app.pkg) }
                .setNegativeButton("Hayır", null)
                .show()
        }
        dialog.show()
    }

    // ---------------- Uygulama kilidi ----------------

    private fun toggleLock(pkg: String) {
        val name = appLabel(pkg)
        if (AppLock.isLocked(this, pkg)) {
            Guard.ask(this, "$name: kilidi kaldır", force = true) {
                AppLock.setLocked(this, pkg, false)
                Guard.setLimit(this, pkg, 0)
                refreshSections()
                showResult("🔓 $name artık her yerden açılabilir.")
            }
            return
        }
        AppLock.setLocked(this, pkg, true)
        refreshSections()
        if (!AppLock.serviceEnabled(this)) {
            lockSetupDialog()
        } else {
            showResult("🔒 $name artık sadece D-FON'dan açılır.\n\n$HIDE_TIP")
        }
    }

    private fun lockSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Kilidi bir kez açman gerekiyor")
            .setMessage(
                "Kilitli uygulamaların başka yerden açılmasını engelleyebilmem için:\n\n" +
                    "1. Açılan ekranda \"Yüklü uygulamalar\"a gir.\n" +
                    "2. \"D-FON Uygulama Kilidi\"ni seç ve aç.\n\n" +
                    "Gri görünüp açılmıyorsa: Ayarlar → Uygulamalar → D-FON → sağ üstteki ⋮ → " +
                    "\"Kısıtlı ayarlara izin ver\", sonra tekrar dene.\n\n" +
                    "Bu servis sadece hangi uygulamanın açıldığına bakar; ekrandaki hiçbir şeyi okumaz ya da göndermez."
            )
            .setPositiveButton("Ayarları aç") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (ignored: Exception) {}
            }
            .setNegativeButton("Sonra", null)
            .show()
        showResult("🔒 Kilit kaydedildi ama \"D-FON Uygulama Kilidi\" erişilebilirlikte açılınca çalışmaya başlar.\n\n$HIDE_TIP")
    }

    /** Düğmesi kalmayan kilitli uygulama hiçbir yerden açılamaz olmasın. */
    private fun unlockIfOrphan(pkg: String) {
        val stillThere = layout.any { s -> s.items.any { it.pkg == pkg } }
        if (!stillThere && AppLock.isLocked(this, pkg)) {
            AppLock.setLocked(this, pkg, false)
            Guard.setLimit(this, pkg, 0)
            showResult("🔓 ${appLabel(pkg)} düğmesi kaldırıldığı için kilidi de kaldırıldı.")
        }
    }

    // ---------------- Namaz vakitleri kartı ----------------

    private fun buildPrayerCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = LedStyle.FrameDrawable(this@MainActivity, 16f, PRAYER_BG)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(TextView(this).apply {
            text = "🕌 Namaz vakitleri"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        prayerInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFB8D8C8.toInt())
            setPadding(0, dp(2), 0, dp(6))
        }
        card.addView(prayerInfo)
        prayerRow = LinearLayout(this)
        card.addView(prayerRow, LinearLayout.LayoutParams(-1, -2))
        prayerEvent = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFB8D8C8.toInt())
            setPadding(dp(2), dp(6), dp(2), 0)
        }
        card.addView(prayerEvent)
        prayerWarn = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.BLACK)
            background = rounded(0xFFFFB4A2.toInt(), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            text = "⚠ Pil tasarrufu D-FON'u uyutabilir; ezan vakti kaçabilir. Düzeltmek için dokun."
            setOnClickListener { fixBattery() }
        }
        card.addView(prayerWarn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, 0) })

        val buttons = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        prayerToggle = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12.5f
            setTextColor(Color.BLACK)
            setPadding(dp(6), dp(9), dp(6), dp(9))
            setOnClickListener { togglePrayer() }
        }
        buttons.addView(prayerToggle, LinearLayout.LayoutParams(0, -2, 1.6f).apply { setMargins(0, 0, dp(4), 0) })
        fun mini(t: String, onClick: () -> Unit) = TextView(this).apply {
            text = t
            gravity = Gravity.CENTER
            textSize = 12.5f
            setTextColor(Color.WHITE)
            background = rounded(PRAYER_BTN, 12)
            setPadding(dp(6), dp(9), dp(6), dp(9))
            setOnClickListener { onClick() }
        }
        buttons.addView(mini("📍 Konumu bul") { locate() }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        buttons.addView(mini("⚙ Ayarlar") { showPrayerSettings() }, LinearLayout.LayoutParams(0, -2, 0.8f).apply { setMargins(dp(4), 0, 0, 0) })
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        card.addView(TextView(this).apply {
            text = "🎨  Ana ekran widget'ı: tema ve yazı boyutu  ›"
            textSize = 13f
            setTextColor(0xFFFFD98A.toInt())
            setPadding(dp(2), dp(10), dp(2), dp(2))
            setOnClickListener { showWidgetStyle() }
        })
        val more = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        fun link(t: String, onClick: () -> Unit) = TextView(this).apply {
            text = t
            gravity = Gravity.CENTER
            textSize = 12.5f
            setTextColor(Color.WHITE)
            background = rounded(PRAYER_BTN, 12)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener { onClick() }
        }
        more.addView(link("🧭 Kıble") { PrayerExtras.qibla(this) }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(4), 0) })
        more.addView(link("🗓 30 gün") { PrayerExtras.monthly(this) }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        more.addView(link("🛠 İnce ayar") {
            PrayerExtras.advanced(this) { refreshPrayerCard(); showResult("Kaydedildi.\n\n" + Prayer.todayText(this)) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, 0, 0) })
        card.addView(more, LinearLayout.LayoutParams(-1, -2))
        refreshPrayerCard()
        return card
    }

    /**
     * Samsung ana ekranı için: seçilen uygulamaların simgelerini temalı çerçeveyle PNG olarak galeriye kaydeder.
     * Theme Park'ta her uygulamanın simgesi bu resimlerle değiştirilir.
     */
    private fun exportIcons() {
        val apps = files.installedApps()
        val pre = files.allowedPackages() + layout.flatMap { s -> s.items.mapNotNull { it.pkg } }
        val checked = BooleanArray(apps.size) { apps[it].pkg in pre }
        AlertDialog.Builder(this)
            .setTitle("Hangi uygulamaların simgesi?")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Kaydet") { _, _ ->
                val chosen = apps.filterIndexed { i, _ -> checked[i] }
                if (chosen.isEmpty()) return@setPositiveButton
                showResult("🧩 ${chosen.size} simge hazırlanıyor…")
                Thread {
                    var n = 0
                    for (a in chosen) {
                        val bmp = LedStyle.icon(this, a.pkg, 432, export = true) ?: continue
                        val name = a.label.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".png"
                        val cv = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/D-FON Simgeler")
                        }
                        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: continue
                        try {
                            contentResolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            n++
                        } catch (e: Exception) {
                            contentResolver.delete(uri, null, null)
                        }
                        bmp.recycle()
                    }
                    runOnUiThread {
                        showResult(
                            "🧩 $n simge galeriye kaydedildi (Resimler/D-FON Simgeler).\n\n" +
                                "Samsung ana ekranında kullanmak için Theme Park:\n" +
                                "1. Theme Park → Simge → bir simge paketi oluştur ya da düzenle.\n" +
                                "2. Değiştirmek istediğin uygulamaya dokun → Galeri'den resim seç.\n" +
                                "3. D-FON Simgeler klasöründen o uygulamanın resmini seç ve kaydet; sonra paketi uygula."
                        )
                    }
                }.start()
            }
            .setNeutralButton("Hepsini seç", null)
            .setNegativeButton("Vazgeç", null)
            .create().also { dlg ->
                dlg.setOnShowListener {
                    dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        for (i in checked.indices) { checked[i] = true; dlg.listView.setItemChecked(i, true) }
                    }
                }
                dlg.show()
            }
    }

    /** Widget teması ve yazı büyüklüğü seçimi, canlı önizlemeli. */
    private fun showWidgetStyle() {
        var selected = WidgetArt.theme(this).id
        var scale = WidgetArt.textScale(this)
        val density = resources.displayMetrics.density
        val pw = minOf((resources.displayMetrics.widthPixels * 0.78f).toInt(), 760)
        val ph = pw * 250 / 440

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        val sizeLabel = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }
        val seek = SeekBar(this).apply {
            max = 9 // %70 … %160
            progress = Math.round((scale - 0.7f) / 0.1f).coerceIn(0, 9)
        }
        box.addView(sizeLabel)
        box.addView(seek)
        box.addView(TextView(this).apply {
            text = "Tema seç:"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        })
        val items = WidgetArt.THEMES.map { t ->
            val iv = ImageView(this).apply {
                adjustViewBounds = true
                setPadding(dp(4), dp(4), dp(4), dp(4))
                contentDescription = t.name
            }
            val label = TextView(this).apply {
                text = t.name
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, dp(10))
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(iv, LinearLayout.LayoutParams(-1, -2))
                addView(label)
            }
            box.addView(cell)
            Triple(t, iv, cell)
        }
        fun render() {
            sizeLabel.text = "Yazı ve sayı büyüklüğü: %${Math.round(scale * 100)}"
            items.forEach { (t, iv, _) ->
                iv.setImageBitmap(WidgetArt.preview(t, pw, ph, scale, density))
                iv.background = if (t.id == selected) rounded(ACCENT, 18) else null
            }
        }
        items.forEach { (t, _, cell) -> cell.setOnClickListener { selected = t.id; render() } }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                scale = 0.7f + p * 0.1f
                render()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        render()

        AlertDialog.Builder(this)
            .setTitle("Namaz widget'ı")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Kaydet") { _, _ ->
                WidgetArt.setTheme(this, selected)
                WidgetArt.setTextScale(this, scale)
                PrayerWidget.updateAll(this)
                val placed = android.appwidget.AppWidgetManager.getInstance(this)
                    .getAppWidgetIds(android.content.ComponentName(this, PrayerWidget::class.java)).isNotEmpty()
                showResult(
                    "🎨 Widget: ${WidgetArt.THEMES.first { it.id == selected }.name}, yazı %${Math.round(scale * 100)}." +
                        if (placed) "" else "\n\nAna ekrana eklemek için: ana ekranda boş bir yere basılı tut → Widget'lar → D-FON → D-FON Namaz Vakitleri."
                )
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun refreshPrayerCard() {
        prayerRow.removeAllViews()
        val t = Prayer.timesFor(this, 0)
        val on = Prayer.isEnabled(this)
        if (t == null) {
            prayerInfo.text = "Konumun henüz bulunmadı. \"📍 Konumu bul\"a bas (GPS internetsiz çalışır)."
        } else {
            val now = System.currentTimeMillis()
            val nextIdx = t.indexOfFirst { it > now }
            PrayerTimes.NAMES.forEachIndexed { i, n ->
                val cell = TextView(this).apply {
                    text = "$n\n${Prayer.fmt(t[i])}"
                    gravity = Gravity.CENTER
                    textSize = 12f
                    setTextColor(if (i == nextIdx) Color.BLACK else Color.WHITE)
                    if (i == nextIdx) background = rounded(ACCENT, 10)
                    setPadding(0, dp(5), 0, dp(5))
                }
                prayerRow.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(1), 0, dp(1), 0) })
            }
            val next = Prayer.nextEzan(this)
            prayerInfo.text = "📍 ${Prayer.locationText(this)}" +
                if (on && next != null) "   ·   Sıradaki sessiz: ${next.first} ${Prayer.fmt(next.second)}" else ""
        }
        prayerToggle.text = if (on) "🔕 Ezanda sessiz: AÇIK (${Prayer.duration(this)} dk)" else "🔔 Ezanda sessiz: KAPALI"
        prayerToggle.background = rounded(if (on) ACCENT else 0xFF9FB3AA.toInt(), 12)
        val ev = Prayer.lastEvent(this)
        prayerEvent.text = if (ev != null) "Son olay: $ev" else ""
        prayerEvent.visibility = if (ev != null) View.VISIBLE else View.GONE
        prayerWarn.visibility = if (on && Prayer.batteryRestricted(this)) View.VISIBLE else View.GONE
    }

    private fun togglePrayer() {
        if (Prayer.isEnabled(this)) {
            Prayer.setEnabled(this, false)
            refreshPrayerCard()
            showResult("🔔 Ezanda sessize alma kapatıldı.")
            return
        }
        if (!Prayer.hasLocation(this)) {
            showResult("Önce konumunu bulayım; sonra tekrar bas.")
            locate()
            return
        }
        Prayer.setEnabled(this, true)
        if (Prayer.batteryRestricted(this)) fixBattery()
        refreshPrayerCard()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        val next = Prayer.nextEzan(this)
        showResult(
            "🔕 Açıldı. Seçili her vakitte ezanla birlikte ${Prayer.duration(this)} dk " +
                (if (Prayer.mode(this) == "titresim") "titreşime" else "sessize") +
                " alırım, sonra kendiliğinden sesli yaparım." +
                (next?.let { "\nSıradaki: ${it.first} ${Prayer.fmt(it.second)}" } ?: "") +
                "\nTelefon zaten sessiz ya da titreşimdeyse dokunmam."
        )
        if (!phone.hasDndAccess() && Prayer.mode(this) == "sessiz") {
            AlertDialog.Builder(this)
                .setTitle("Bir izin gerekli")
                .setMessage(
                    "Telefonu tam sessize alabilmem için \"Rahatsız Etmeyin erişimi\" iznini açman gerekiyor. " +
                        "Açmazsan ezanda titreşime alırım."
                )
                .setPositiveButton("İzni aç") { _, _ -> phone.requestDndAccess() }
                .setNegativeButton("Titreşim yeter", null)
                .show()
        }
    }

    private fun showPrayerSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(2))
        }
        val enabledBox = CheckBox(this).apply {
            text = "Ezanda sessize al (açık)"
            textSize = 16f
            isChecked = true
        }
        box.addView(enabledBox)
        box.addView(label("Hangi vakitlerde sessize alınsın?"))
        val sel = Prayer.selected(this)
        val checks = Prayer.SLOTS.map { (name, _) ->
            CheckBox(this).apply { text = name; isChecked = name in sel }.also { box.addView(it) }
        }

        box.addView(label("Kaç dakika sessiz kalsın?"))
        val durGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        Prayer.DURATIONS.forEach { m ->
            durGroup.addView(RadioButton(this).apply {
                text = "$m"
                id = View.generateViewId()
                tag = m
                isChecked = m == Prayer.duration(this@MainActivity)
            })
        }
        box.addView(durGroup)

        box.addView(label("Nasıl?"))
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val silent = RadioButton(this).apply { text = "Sessiz"; id = View.generateViewId() }
        val vib = RadioButton(this).apply { text = "Titreşim"; id = View.generateViewId() }
        modeGroup.addView(silent)
        modeGroup.addView(vib)
        (if (Prayer.mode(this) == "titresim") vib else silent).isChecked = true
        box.addView(modeGroup)

        box.addView(label("Sabah ezanı imsaktan kaç dk sonra okunuyor?"))
        val offset = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prayer.sabahOffset(this@MainActivity).toString())
        }
        box.addView(offset)

        val countdownBox = CheckBox(this).apply {
            text = "Üst çubukta sıradaki vakte kalan süreyi göster"
            isChecked = Prayer.countdownEnabled(this@MainActivity)
        }
        box.addView(countdownBox)

        box.addView(label("Konum (enlem, boylam) — istersen elle yaz"))
        val loc = EditText(this).apply {
            hint = "ör. 41.01, 28.97"
            if (Prayer.hasLocation(this@MainActivity)) setText(Prayer.locationText(this@MainActivity))
        }
        box.addView(loc)
        lateinit var settingsDialog: AlertDialog
        box.addView(TextView(this).apply {
            text = "📋 Listeden il seç (81 il)"
            setTextColor(ACCENT)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                settingsDialog.dismiss()
                showCityPicker()
            }
        })

        settingsDialog = AlertDialog.Builder(this)
            .setTitle("Ezanda sessize alma")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Kaydet") { _, _ ->
                Prayer.setSelected(this, checks.filter { it.isChecked }.map { it.text.toString() }.toSet())
                val chosen = (0 until durGroup.childCount).map { durGroup.getChildAt(it) as RadioButton }
                    .firstOrNull { it.isChecked }?.tag as? Int
                if (chosen != null) Prayer.setDuration(this, chosen)
                Prayer.setMode(this, if (vib.isChecked) "titresim" else "sessiz")
                Prayer.setSabahOffset(this, offset.text.toString().toIntOrNull()?.coerceIn(0, 120) ?: 0)
                Prayer.setCountdownEnabled(this, countdownBox.isChecked)
                val parts = loc.text.toString().split(",", " ").mapNotNull { it.trim().toDoubleOrNull() }
                if (parts.size == 2 && parts[0] in -66.0..66.0 && parts[1] in -180.0..180.0) {
                    Prayer.setLocation(this, parts[0], parts[1])
                }
                if (enabledBox.isChecked && !Prayer.hasLocation(this)) {
                    showResult("Kaydedildi ama konum yok. \"📍 Konumu bul\"a bas, sonra \"Ezanda sessiz\" düğmesiyle aç.")
                    refreshPrayerCard()
                    return@setPositiveButton
                }
                if (enabledBox.isChecked != Prayer.isEnabled(this)) {
                    if (enabledBox.isChecked) togglePrayer() else Prayer.setEnabled(this, false)
                } else {
                    Prayer.schedule(this)
                }
                refreshPrayerCard()
                if (!enabledBox.isChecked || resultLine.visibility != View.VISIBLE || !Prayer.isEnabled(this)) {
                    showResult("Kaydedildi.\n\n" + Prayer.todayText(this))
                }
            }
            .setNeutralButton("🧪 Şimdi dene (1 dk)") { _, _ -> testSilence() }
            .setNegativeButton("İptal", null)
            .show()
    }

    /** Ezanı beklemeden: telefonu 1 dakika sessize alıp geri açar. */
    private fun testSilence() {
        if (Prayer.mode(this) == "sessiz" && !phone.hasDndAccess()) {
            AlertDialog.Builder(this)
                .setTitle("Önce bir izin")
                .setMessage(
                    "Tam sessize alabilmem için \"Rahatsız Etmeyin erişimi\" gerekli. " +
                        "Açılan listede D-FON'u açıp geri dön, sonra tekrar dene."
                )
                .setPositiveButton("İzni aç") { _, _ -> phone.requestDndAccess() }
                .setNegativeButton("Titreşimle dene") { _, _ ->
                    showResult("🧪 Deneme: " + Prayer.silenceNow(this, "Deneme", 1))
                }
                .show()
            return
        }
        showResult("🧪 Deneme: " + Prayer.silenceNow(this, "Deneme", 1))
    }

    // ---------------- Konum (GPS, internetsiz) ----------------

    private fun locGranted() =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locate() {
        if (!locGranted()) {
            pendingLocate = true
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOC
            )
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val last = lm.getProviders(true).mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (e: SecurityException) { null }
        }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 3L * 24 * 3600_000) {
            useLocation(last)
            return
        }
        if (!lm.isLocationEnabled) {
            showResult("Telefonun konumu kapalı. Açılan ekrandan konumu aç, sonra \"📍 Konumu bul\"a tekrar bas.")
            try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (ignored: Exception) {}
            return
        }
        showResult("📍 Konum aranıyor… (GPS internetsiz çalışır; açık havada ya da pencere kenarında daha hızlı)")
        val providers = lm.getProviders(true)
        val provider = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { it in providers } ?: providers.firstOrNull()
        if (provider == null) {
            locateFailed()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
                    if (loc != null) useLocation(loc) else locateFailed()
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) = useLocation(location)
                    @Deprecated("API")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            locateFailed()
        }
    }

    private fun useLocation(l: Location) {
        Prayer.setLocation(this, l.latitude, l.longitude)
        refreshPrayerCard()
        showResult("📍 Konum kaydedildi.\n\n" + Prayer.todayText(this))
    }

    private fun locateFailed() {
        showResult("Konum bulunamadı. Listeden ilini seçebilirsin; vakitler il merkezine göre hesaplanır.")
        showCityPicker()
    }

    /** 81 il listesinden seçim (konum bulunamazsa). */
    private fun showCityPicker() {
        val all = Cities.ALL
        var shown = all
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, all.map { "%02d · %s".format(it.plate, it.name) }.toMutableList())
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        val search = EditText(this).apply { hint = "İl adı ya da plaka (ör. Kocaeli, 41)" }
        val list = ListView(this).apply { this.adapter = adapter }
        box.addView(search)
        box.addView(list, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55).toInt()))
        val dialog = AlertDialog.Builder(this)
            .setTitle("İlini seç")
            .setView(box)
            .setNegativeButton("Vazgeç", null)
            .create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val q = FileTools.norm(e?.toString() ?: "")
                shown = if (q.isEmpty()) all else all.filter { FileTools.norm(it.name).contains(q) || it.plate.toString() == q }
                adapter.clear()
                adapter.addAll(shown.map { "%02d · %s".format(it.plate, it.name) })
            }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val city = shown[pos]
            Prayer.setLocation(this, city.lat, city.lon, city.name)
            refreshPrayerCard()
            showResult("📍 ${city.name} seçildi.\n\n" + Prayer.todayText(this))
            dialog.dismiss()
        }
        dialog.show()
    }

    /** Pil tasarrufu muafiyeti iste; Samsung'un "uyuyan uygulamalar" listesini de hatırlat. */
    private fun fixBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (ignored: Exception) {}
        }
        showResult(
            "Açılan pencerede \"İzin ver\"e bas.\n\nSamsung'da ayrıca: Ayarlar → Pil → Arka plan kullanım sınırları → " +
                "\"Uyuyan uygulamalar\" ve \"Derin uyuyan uygulamalar\" listelerinde D-FON varsa çıkar; " +
                "\"Hiç uyumayan uygulamalar\"a ekle. Bu ayarlar olmadan Samsung gece D-FON'u uyutup ezan alarmını geciktirebilir."
        )
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

    private fun submit(raw: String, fromTile: Boolean = false, voice: Boolean = false) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return
        if (!fromTile) showPanel(false)
        input.setText("")
        remember(text)
        addBubble(text, Who.USER)
        fromVoice = voice
        runInBackground({ engine.handle(text) })
    }

    /** Dosya dönüştürme gibi uzun işler arayüzü dondurmasın diye arka planda çalışır. */
    private fun runInBackground(work: () -> List<String>, after: (() -> Unit)? = null) {
        setBusy(true)
        val started = System.currentTimeMillis()
        val speakIt = fromVoice && voiceReplyOn()
        fromVoice = false
        Thread {
            val replies = try {
                work()
            } catch (e: Exception) {
                listOf("Hata: ${e.message ?: e.javaClass.simpleName}")
            }
            runOnUiThread {
                replies.forEach { addBubble(it, Who.BOT) }
                if (panel.visibility == View.VISIBLE && replies.isNotEmpty()) showResult(replies.joinToString("\n\n"))
                setBusy(false)
                refreshPermButton()
                refreshPrayerCard()
                // Sesli cevap: komut sesle verildiyse ve o sırada bir okuma başlamadıysa
                if (speakIt && replies.isNotEmpty() && Speaker.lastStart < started) {
                    Speaker.speak(this, speakable(replies.joinToString(". ")))
                }
                after?.invoke()
            }
        }.start()
    }

    /** Emojileri ve sembolleri atıp okunabilir hale getirir; çok uzunsa kısaltır. */
    private fun speakable(t: String): String {
        val clean = t.replace(Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D•▶→]"), " ")
            .replace(Regex("[ \\t]+"), " ").trim()
        return if (clean.length > 600) clean.take(600).substringBeforeLast(' ') + ". Devamı ekranda." else clean
    }

    private fun voiceReplyOn() = getSharedPreferences("dfon", MODE_PRIVATE).getBoolean("sesli_cevap", true)

    private fun toggleVoiceReply() {
        val on = !voiceReplyOn()
        getSharedPreferences("dfon", MODE_PRIVATE).edit().putBoolean("sesli_cevap", on).apply()
        updateVoiceReplyTile()
        showResult(if (on) "🔊 Sesle verdiğin komutların cevabını sesli söyleyeceğim." else "🔇 Cevapları artık sadece yazıyla göstereceğim.")
    }

    private fun updateVoiceReplyTile() {
        if (!::voiceReplyTile.isInitialized) return
        val on = voiceReplyOn()
        setTileText(voiceReplyTile, if (on) "🔊" else "🔇", if (on) "Sesli cevap açık" else "Sesli cevap kapalı")
        voiceReplyTile.background = rounded(if (on) WAKE_ON else CHIP, 14)
    }

    // ---------------- Rutinler ----------------

    private fun refreshRoutines() {
        routinesBox.removeAllViews()
        val list = Routines.all(this)
        val tiles: List<View> = list.mapIndexed { i, r ->
            tile("⚡", r.name + (r.time?.let { "\n⏰ $it" } ?: "")) { submit(r.name, true) }.apply {
                setOnLongClickListener { routineMenu(i); true }
            }
        } + tile("➕", "Rutin ekle") { editRoutine(-1) }
        routinesBox.addView(grid(tiles))
    }

    private fun routineMenu(i: Int) {
        val list = Routines.all(this)
        val r = list.getOrNull(i) ?: return
        AlertDialog.Builder(this)
            .setTitle(r.name)
            .setItems(arrayOf("✏ Düzenle", "▶ Şimdi çalıştır", "🗑 Sil")) { _, w ->
                when (w) {
                    0 -> editRoutine(i)
                    1 -> submit(r.name, true)
                    else -> {
                        Routines.save(this, list.filterIndexed { k, _ -> k != i })
                        refreshRoutines()
                    }
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun editRoutine(i: Int) {
        val list = Routines.all(this).toMutableList()
        val old = list.getOrNull(i)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(2))
        }
        box.addView(label("Adı (bunu söyleyince çalışır)"))
        val name = EditText(this).apply {
            hint = "ör. Uyku modu"
            setText(old?.name ?: "")
        }
        box.addView(name)
        box.addView(label("Komutlar (her satıra bir komut)"))
        val cmds = EditText(this).apply {
            hint = "Titreşime al\nParlaklık 10\nRahatsız etmeyi aç"
            minLines = 4
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(old?.commands?.joinToString("\n") ?: "")
        }
        box.addView(cmds)
        val pick = TextView(this).apply {
            text = "➕ Hazır komutlardan ekle"
            setTextColor(ACCENT)
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener {
                val all = SECTIONS.flatMap { it.second }
                AlertDialog.Builder(this@MainActivity)
                    .setItems(all.map { "${it.first}  ${it.third}" }.toTypedArray()) { _, w ->
                        val cur = cmds.text.toString().trimEnd()
                        cmds.setText(if (cur.isEmpty()) all[w].third else cur + "\n" + all[w].third)
                    }
                    .show()
            }
        }
        box.addView(pick)
        box.addView(label("Her gün şu saatte kendiliğinden çalışsın (boş bırakabilirsin)"))
        val time = EditText(this).apply {
            hint = "ör. 23:00"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            setText(old?.time ?: "")
        }
        box.addView(time)

        AlertDialog.Builder(this)
            .setTitle(if (old == null) "Yeni rutin" else "Rutini düzenle")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Kaydet") { _, _ ->
                val n = name.text.toString().trim()
                val c = cmds.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (n.isEmpty() || c.isEmpty()) {
                    showResult("Rutin için ad ve en az bir komut gerekli.")
                    return@setPositiveButton
                }
                val tText = time.text.toString().trim()
                val t = if (tText.isEmpty()) null else Routines.parseTime(tText)
                if (tText.isNotEmpty() && t == null) showResult("Saat anlaşılmadı (ör. 23:00); saatsiz kaydettim.")
                val r = Routine(n, c, t)
                if (old == null) list.add(r) else list[i] = r
                Routines.save(this, list)
                refreshRoutines()
                if (t != null) showResult("⚡ $n her gün $t'de kendiliğinden çalışacak.\n" + c.joinToString(" → "))
                else showResult("⚡ $n kaydedildi. \"$n\" demen ya da düğmesine basman yeterli.")
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun clearChat() {
        if (busy) return
        engine.reset()
        files.files.clear()
        chat.removeAllViews()
        addBubble("Temizlendi. Ne yapayım?", Who.BOT)
    }

    // ---------------- Komut geçmişi ----------------

    private fun history(): List<String> {
        val raw = getSharedPreferences("dfon", MODE_PRIVATE).getString("gecmis", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun remember(cmd: String) {
        val list = (listOf(cmd) + history().filter { it != cmd }).take(HISTORY_MAX)
        getSharedPreferences("dfon", MODE_PRIVATE).edit()
            .putString("gecmis", JSONArray(list).toString())
            .apply()
    }

    private fun showHistory() {
        val list = history()
        if (list.isEmpty()) {
            Toast.makeText(this, "Henüz komut geçmişi yok.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Son komutlar")
            .setItems(list.toTypedArray()) { _, which ->
                input.setText(list[which])
                input.setSelection(input.text.length)
                input.requestFocus()
            }
            .setNeutralButton("Geçmişi sil") { _, _ ->
                getSharedPreferences("dfon", MODE_PRIVATE).edit().remove("gecmis").apply()
                Toast.makeText(this, "Geçmiş silindi.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    // ---------------- Ses ----------------

    private fun micGranted() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun startVoice() {
        if (busy || voiceOn) return
        if (!micGranted()) {
            pendingVoice = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            startVoicePopup()
            return
        }
        Speaker.stop()
        try {
            recognizer?.destroy()
            recognizer = Voice.create(this).also {
                it.setRecognitionListener(voiceListener)
                it.startListening(Voice.intent(true))
            }
            setVoiceUi(true, "Dinliyorum…")
        } catch (e: Exception) {
            setVoiceUi(false, null)
            startVoicePopup()
        }
    }

    private fun stopVoice(hint: String?) {
        if (voiceOn) {
            try { recognizer?.cancel() } catch (_: Exception) {}
        }
        setVoiceUi(false, hint)
    }

    private fun setVoiceUi(on: Boolean, hint: String?) {
        voiceOn = on
        micBtn.background = oval(if (on) MIC_ON else ACCENT)
        if (!on) {
            micBtn.scaleX = 1f
            micBtn.scaleY = 1f
        }
        micHint.text = hint ?: if (on) "Dinliyorum…" else "Dokun ve söyle"
    }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (!voiceOn) return
            val s = 1f + rmsdB.coerceIn(0f, 10f) / 30f
            micBtn.scaleX = s
            micBtn.scaleY = s
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (voiceOn) micHint.text = "Anlıyorum…"
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (voiceOn && !p.isNullOrBlank()) micHint.text = p
        }
        override fun onResults(results: Bundle?) {
            val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            setVoiceUi(false, null)
            if (!said.isNullOrBlank()) {
                // "Bir iki üç fon, ..." diye başlandıysa uyandırma sözünü at
                val cmd = Voice.wakeCommand(said) ?: said
                if (cmd.isBlank()) startVoice() else submit(cmd, voice = true)
            }
        }
        override fun onError(error: Int) {
            if (!voiceOn) return
            setVoiceUi(false, null)
            if (Voice.languageError(error)) {
                startVoice()
                return
            }
            setVoiceUi(false, Voice.errorText(error))
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------------- Sesle uyandırma ----------------

    private fun handleWake(i: Intent?) {
        if (i == null || !i.getBooleanExtra(WakeService.EXTRA_WAKE, false)) return
        i.removeExtra(WakeService.EXTRA_WAKE)
        val cmd = i.getStringExtra(WakeService.EXTRA_COMMAND).orEmpty().trim()
        WakeService.followUntil = System.currentTimeMillis() + WakeService.FOLLOW_MS
        if (cmd.isNotEmpty()) submit(cmd, voice = true) else micBtn.postDelayed({ startVoice() }, 400)
    }

    private fun updateWakeChip() {
        val on = WakeService.isEnabled(this)
        if (::wakeBar.isInitialized) {
            wakeBar.text = if (on) "🎙  Sesle uyandırma AÇIK  —  kapatmak için dokun"
            else "🔇  Sesle uyandırma KAPALI  —  açmak için dokun"
            wakeBar.setTextColor(if (on) Color.WHITE else 0xFFCFD6E0.toInt())
            wakeBar.background = rounded(if (on) WAKE_ON else WAKE_OFF, 16)
        }
        if (!::wakeChip.isInitialized) return
        setTileText(wakeChip, "👂", if (on) "Uyandırma açık" else "Uyandırma kapalı")
        wakeChip.background = rounded(if (on) MIC_ON else CHIP, 14)
    }

    private fun toggleWake() {
        if (WakeService.isEnabled(this)) {
            WakeService.setEnabled(this, false)
            WakeService.stop(this)
            updateWakeChip()
            addBubble("Sesle uyandırma kapatıldı. Mikrofon artık arka planda dinlemiyor.", Who.INFO)
            return
        }
        if (!micGranted()) {
            pendingWake = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        WakeService.setEnabled(this, true)
        WakeService.start(this)
        updateWakeChip()
        addBubble(
            "👂 Sesle uyandırma açık. Ekran açık ve kilitsizken \"Bir iki üç fon\" de, D-FON açılıp seni dinlesin. " +
                "Arkasından komutu da söyleyebilirsin: \"Bir iki üç fon, YouTube'u aç\".\n\n" +
                "Ekran kapalı ya da kilitliyken mikrofon kullanılmaz. Duyduklarım hiçbir yere kaydedilmez. " +
                "Bildirim çubuğunda \"D-FON dinliyor\" yazısı görünür; oradan da kapatabilirsin.",
            Who.INFO
        )
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Bir izin daha")
                .setMessage(
                    "Başka bir uygulamadayken seni duyunca D-FON'u öne getirebilmem için " +
                        "\"Diğer uygulamaların üzerinde göster\" iznini açman gerekiyor. " +
                        "Açmazsan seni duyduğumda dokunman için bir bildirim gösteririm."
                )
                .setPositiveButton("İzni aç") { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    } catch (_: Exception) {}
                }
                .setNegativeButton("Şimdi değil", null)
                .show()
        }
        addBubble(
            "İpucu: Samsung pil tasarrufu servisi kapatmasın diye Ayarlar → Uygulamalar → D-FON → Pil → " +
                "\"Kısıtlamasız\" seç.",
            Who.INFO
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ContactTools.REQ_CONTACTS) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            showResult(if (ok) "📇 Teşekkürler. Şimdi komutu tekrar söyle (ör. \"annemi ara\")." else "Rehber izni verilmedi.")
            return
        }
        if (requestCode == REQ_LOC) {
            val wasPending = pendingLocate
            pendingLocate = false
            if (locGranted()) {
                if (wasPending) locate()
            } else {
                showResult("Konum izni verilmedi. Listeden ilini seçebilirsin.")
                showCityPicker()
            }
            return
        }
        if (requestCode != REQ_MIC) return
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        val v = pendingVoice
        val w = pendingWake
        pendingVoice = false
        pendingWake = false
        if (!ok) {
            addBubble("Sesli komut için mikrofon izni gerekiyor.", Who.INFO)
            return
        }
        if (w) toggleWake() else if (v) startVoice()
    }

    private fun startVoicePopup() {
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
            if (!said.isNullOrBlank()) submit(said, voice = true)
        }
        Backup.onResult(this, requestCode, if (resultCode == RESULT_OK) data?.data else null)?.let {
            if (!isFinishing) showResult(it)
            return
        }
        if (requestCode == FileTools.REQ_PICK) {
            val picked = if (resultCode == RESULT_OK) files.handlePick(data) else emptyList()
            var fresh = false
            runInBackground({
                val r = engine.onFilesPicked(picked)
                fresh = picked.isNotEmpty() && r.firstOrNull()?.startsWith("📎 Eklendi") == true
                r
            }, { if (fresh && !isFinishing) showFileActions(picked) })
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
        private const val REQ_MIC = 42
        private const val REQ_NOTIF = 43
        private const val REQ_LOC = 44
        const val EXTRA_TILE_WAKE = "kutucuk_dinleme"
        private val WAKE_ON = 0xFF1E8E5A.toInt()
        private val WAKE_OFF = 0xFF3A4458.toInt()
        private val APP_BG = 0xFF2C3E5C.toInt()
        private val LOCKED_BG = 0xFF4A2F55.toInt()
        private const val HIDE_TIP =
            "İpucu: Simgesini ana ekrandan ve uygulamalar listesinden de gizlemek için ana ekranda boş bir yere " +
                "basılı tut → Ayarlar → \"Uygulamaları gizle\" → uygulamayı seç → Bitti. Gizlesen de D-FON'daki düğmesi çalışır."
        private val PRAYER_BG = 0xFF173A2E.toInt()
        private val PRAYER_BTN = 0xFF24543F.toInt()

        /** Buton ekranındaki bölümler: (emoji, yazı, komut) */
        private val SECTIONS = listOf(
            "⚡ Hızlı" to listOf(
                Triple("📊", "Durum", "Durum"),
                Triple("🔦", "Fener aç", "Feneri aç"),
                Triple("⚫", "Fener kapat", "Feneri kapat"),
                Triple("🕐", "Saat kaç", "Saat kaç"),
                Triple("🌙", "Gece modu", "Gece modu"),
                Triple("☀️", "Gündüz modu", "Gündüz modu"),
                Triple("🕌", "Vakitler", "Namaz vakitleri"),
                Triple("⏱", "5 dk sayaç", "5 dakika zamanlayıcı"),
            ),
            "🔊 Ses" to listOf(
                Triple("🔕", "Sessiz", "Sessize al"),
                Triple("📳", "Titreşim", "Titreşime al"),
                Triple("🔔", "Sesli", "Sessizden çık"),
                Triple("⛔", "Rahatsız etme", "Rahatsız etmeyi aç"),
                Triple("🔉", "Medya %30", "Medya sesi 30"),
                Triple("🔊", "Medya %70", "Medya sesi 70"),
                Triple("🔇", "Sesi kapat", "Sesi kapat"),
                Triple("⏰", "Alarmlar", "Alarmları göster"),
            ),
            "📱 Ekran ve bağlantı" to listOf(
                Triple("🔅", "Parlaklık %30", "Parlaklık 30"),
                Triple("🔆", "Parlaklık %80", "Parlaklık 80"),
                Triple("✨", "Otomatik parlaklık", "Otomatik parlaklığı aç"),
                Triple("🌑", "Karanlık mod", "Karanlık mod"),
                Triple("📶", "Wi-Fi", "Wi-Fi"),
                Triple("🔵", "Bluetooth", "Bluetooth"),
                Triple("📍", "Konum ayarı", "Konum"),
                Triple("✈️", "Uçak modu", "Uçak modu"),
            ),
            "📄 Dosyalar" to listOf(
                Triple("📝", "PDF → Word", "PDF'i Word'e çevir"),
                Triple("📕", "Word → PDF", "Word'ü PDF yap"),
                Triple("🖼", "Resim → PDF", "Resimleri PDF yap"),
                Triple("📚", "PDF birleştir", "PDF'leri birleştir"),
                Triple("🏞", "PDF → resim", "PDF'i resme çevir"),
                Triple("📂", "Dosyayı aç", "Dosyayı aç"),
            ),
            "🗣 Sesli okuma" to listOf(
                Triple("📖", "PDF oku", "PDF'i oku"),
                Triple("📄", "Word oku", "Word'ü oku"),
                Triple("👀", "Ekranı oku", "Ekrandaki yazıyı oku"),
                Triple("⏹", "Dur", "Dur"),
            ),
        )
        private val MIC_ON = 0xFFE05A5A.toInt()
        private const val HISTORY_MAX = 20
        private val BG = 0xFF0F1A2A.toInt()
        private val BUBBLE = 0xFF1F3A5F.toInt()
        private val CHIP = 0xFF22324A.toInt()
        private val ACCENT = 0xFFFFD166.toInt()
    }
}
