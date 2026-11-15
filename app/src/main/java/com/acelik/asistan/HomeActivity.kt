package com.acelik.asistan

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

/**
 * D-FON ana ekranı: telefonun ana ekranı olarak seçilince bütün uygulama simgeleri ve widget'lar
 * altın çizgili, altında LED parlayan çerçeveyle görünür. Duvar kağıdı telefonun kendisidir.
 */
class HomeActivity : Activity() {

    private lateinit var host: AppWidgetHost
    private lateinit var awm: AppWidgetManager

    private lateinit var root: FrameLayout
    private lateinit var homeCol: LinearLayout
    private lateinit var widgetsBox: LinearLayout
    private lateinit var appsBox: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var drawerGrid: GridView
    private lateinit var search: EditText

    private var editMode = false
    private var pendingId = -1
    private var pendingInfo: AppWidgetProviderInfo? = null

    private val prefs by lazy { getSharedPreferences("dfon", MODE_PRIVATE) }
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        awm = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(applicationContext, HOST_ID)
        setContentView(build())
        seedIfEmpty()
        refreshAll()
    }

    override fun onStart() {
        super.onStart()
        try { host.startListening() } catch (ignored: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try { host.stopListening() } catch (ignored: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) closeDrawer()
        else if (editMode) setEdit(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Ana ekran tuşuna basılınca çekmeceyi kapat, en üste dön
        if (::drawer.isInitialized && drawer.visibility == View.VISIBLE) closeDrawer()
        setEdit(false)
    }

    // ---------------- arayüz ----------------

    private fun build(): View {
        root = FrameLayout(this)
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        homeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(16f))
            isLongClickable = true
            setOnLongClickListener { homeMenu(); true }
        }
        widgetsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homeCol.addView(widgetsBox)
        homeCol.addView(appsBox)
        // boş alana basılı tutmak için alttaki boşluk da tıklanabilir
        homeCol.addView(View(this).apply {
            setOnLongClickListener { homeMenu(); true }
        }, LinearLayout.LayoutParams(-1, dp(260f)))
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(homeCol)
        }
        main.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = LedStyle.FrameDrawable(this@HomeActivity, 30f, 0x55061016)
            setPadding(dp(10f), dp(10f), dp(10f), dp(8f))
        }
        main.addView(dock, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12f), 0, dp(12f), dp(10f)) })

        drawer = buildDrawer()
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        drawer.visibility = View.GONE
        return root
    }

    private fun refreshAll() {
        refreshWidgets()
        refreshApps()
    }

    // ---------------- uygulamalar ----------------

    private data class App(val label: String, val pkg: String)

    private fun allApps(): List<App> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0)
            .map { App(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { FileTools.norm(it.label) }
    }

    private fun list(key: String): MutableList<String> {
        val raw = prefs.getString(key, null) ?: return mutableListOf()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save(key: String, l: List<String>) {
        prefs.edit().putString(key, JSONArray(l.distinct()).toString()).apply()
    }

    /** İlk açılış: dock'a telefon, mesaj, kamera; ana ekrana D-FON ve D-FON'daki uygulama düğmeleri. */
    private fun seedIfEmpty() {
        if (prefs.contains("ev_dock")) return
        fun resolve(i: Intent) = packageManager.resolveActivity(i, 0)?.activityInfo?.packageName
            ?.takeIf { it != "android" }
        val dockPkgs = listOfNotNull(
            resolve(Intent(Intent.ACTION_DIAL)),
            resolve(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))),
            resolve(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)),
            resolve(Intent(Intent.ACTION_VIEW, Uri.parse("https://"))),
        ).filter { packageManager.getLaunchIntentForPackage(it) != null }
        save("ev_dock", dockPkgs)
        val fromLayout = mutableListOf(packageName)
        prefs.getString("duzen", null)?.let { raw ->
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val items = arr.getJSONObject(i).getJSONArray("i")
                    for (k in 0 until items.length()) items.getJSONObject(k).optString("a").takeIf { it.isNotEmpty() }?.let { fromLayout.add(it) }
                }
            } catch (ignored: Exception) {
            }
        }
        save("ev_uygulamalar", fromLayout.filter { it !in dockPkgs }.take(20))
    }

    private fun refreshApps() {
        if (!::appsBox.isInitialized) return
        val byPkg = allApps().associateBy { it.pkg }
        appsBox.removeAllViews()
        val home = list("ev_uygulamalar").mapNotNull { byPkg[it] }
        home.chunked(4).forEach { rowApps ->
            val row = LinearLayout(this)
            rowApps.forEach { row.addView(appView(it, true), LinearLayout.LayoutParams(0, -2, 1f)) }
            repeat(4 - rowApps.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            appsBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6f), 0, dp(6f)) })
        }
        dock.removeAllViews()
        list("ev_dock").mapNotNull { byPkg[it] }.take(4).forEach {
            dock.addView(appView(it, false), LinearLayout.LayoutParams(0, -2, 1f))
        }
        // Tüm uygulamalar düğmesi
        dock.addView(TextView(this).apply {
            text = "⋯\nTümü"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFD166.toInt())
            textSize = 12f
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setOnClickListener { openDrawer() }
            setOnLongClickListener { homeMenu(); true }
        }, LinearLayout.LayoutParams(0, dp(78f), 1f))
    }

    private fun appView(app: App, showLabel: Boolean): View {
        val size = dp(62f)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2f), 0, dp(2f))
        }
        col.addView(ImageView(this).apply {
            setImageBitmap(LedStyle.icon(this@HomeActivity, app.pkg, size))
            contentDescription = app.label
        }, LinearLayout.LayoutParams(size, size))
        if (showLabel) col.addView(TextView(this).apply {
            text = app.label
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setShadowLayer(5f, 0f, 1f, Color.BLACK)
        }, LinearLayout.LayoutParams(-1, -2))
        col.setOnClickListener { launch(app.pkg) }
        col.setOnLongClickListener { appMenu(app); true }
        return col
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        AppLock.allow(this, pkg) // D-FON'dan açıldığı için kilitli uygulama da açılır
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appMenu(app: App) {
        val onHome = app.pkg in list("ev_uygulamalar")
        val onDock = app.pkg in list("ev_dock")
        val locked = AppLock.isLocked(this, app.pkg)
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        opts += (if (onHome) "➖ Ana ekrandan kaldır" else "➕ Ana ekrana ekle") to {
            val l = list("ev_uygulamalar"); if (onHome) l.remove(app.pkg) else l.add(app.pkg); save("ev_uygulamalar", l); refreshApps()
        }
        opts += (if (onDock) "➖ Alt çubuktan kaldır" else "⬇ Alt çubuğa ekle (en fazla 4)") to {
            val l = list("ev_dock")
            if (onDock) l.remove(app.pkg) else { if (l.size >= 4) l.removeAt(0); l.add(app.pkg) }
            save("ev_dock", l); refreshApps()
        }
        if (onHome) {
            opts += "⬅ Sola taşı" to { move(app.pkg, -1) }
            opts += "➡ Sağa taşı" to { move(app.pkg, 1) }
        }
        opts += (if (locked) "🔓 Kilidi kaldır" else "🔒 Sadece D-FON'dan açılsın") to {
            AppLock.setLocked(this, app.pkg, !locked)
            if (!locked && !AppLock.serviceEnabled(this)) {
                Toast.makeText(this, "Kilit için Erişilebilirlik'te D-FON servisini aç", Toast.LENGTH_LONG).show()
            }
            refreshApps()
        }
        opts += "ℹ Uygulama bilgisi" to {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        }
        opts += "🗑 Telefondan sil" to { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))) }
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
            .show()
    }

    private fun move(pkg: String, d: Int) {
        val l = list("ev_uygulamalar")
        val i = l.indexOf(pkg)
        val j = i + d
        if (i < 0 || j !in l.indices) return
        l[i] = l[j]; l[j] = pkg
        save("ev_uygulamalar", l)
        refreshApps()
    }

    // ---------------- çekmece (tüm uygulamalar) ----------------

    private var drawerApps: List<App> = emptyList()

    private fun buildDrawer(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6050A10.toInt())
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            isClickable = true
        }
        search = EditText(this).apply {
            hint = "Uygulama ara…"
            setHintTextColor(0xFF8A94A6.toInt())
            setTextColor(Color.WHITE)
            background = LedStyle.FrameDrawable(this@HomeActivity, 22f, 0x88061016.toInt())
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) = filterDrawer(e?.toString() ?: "")
            })
        }
        box.addView(search, LinearLayout.LayoutParams(-1, -2))
        drawerGrid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(10f)
            setPadding(0, dp(12f), 0, dp(12f))
            clipToPadding = false
            adapter = DrawerAdapter()
            setOnItemClickListener { _, _, pos, _ -> launch(drawerApps[pos].pkg); closeDrawer() }
            setOnItemLongClickListener { _, _, pos, _ -> appMenu(drawerApps[pos]); true }
        }
        box.addView(drawerGrid, LinearLayout.LayoutParams(-1, 0, 1f))
        return box
    }

    private fun filterDrawer(q: String) {
        val n = FileTools.norm(q)
        // Kilitli uygulamalar çekmecede görünmez; D-FON'daki düğmelerinden açılır
        val locked = AppLock.locked(this)
        drawerApps = allApps().filter { it.pkg !in locked && (n.isEmpty() || FileTools.norm(it.label).contains(n)) }
        (drawerGrid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun openDrawer() {
        search.setText("")
        filterDrawer("")
        drawer.visibility = View.VISIBLE
    }

    private fun closeDrawer() {
        drawer.visibility = View.GONE
    }

    private inner class DrawerAdapter : BaseAdapter() {
        override fun getCount() = drawerApps.size
        override fun getItem(p: Int) = drawerApps[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convertView: View?, parent: ViewGroup?): View {
            val size = dp(58f)
            val v = (convertView as? LinearLayout) ?: LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageView(this@HomeActivity), LinearLayout.LayoutParams(size, size))
                addView(TextView(this@HomeActivity).apply {
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, -2))
            }
            val app = drawerApps[p]
            (v.getChildAt(0) as ImageView).setImageBitmap(LedStyle.icon(this@HomeActivity, app.pkg, size))
            (v.getChildAt(1) as TextView).text = app.label
            return v
        }
    }

    // ---------------- widget'lar ----------------

    private fun refreshWidgets() {
        widgetsBox.removeAllViews()
        val width = resources.displayMetrics.widthPixels - dp(28f)
        for (idStr in list("ev_widgetlar")) {
            val id = idStr.toIntOrNull() ?: continue
            val info = awm.getAppWidgetInfo(id) ?: continue
            val frame = FrameLayout(this).apply {
                background = LedStyle.FrameDrawable(this@HomeActivity, 26f, 0)
                setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
            }
            val hv = host.createView(applicationContext, id, info)
            val h = maxOf(info.minHeight, dp(110f))
            val wDp = (width / resources.displayMetrics.density).toInt()
            val hDp = (h / resources.displayMetrics.density).toInt()
            @Suppress("DEPRECATION")
            hv.updateAppWidgetSize(null, wDp, hDp, wDp, hDp)
            frame.addView(hv, FrameLayout.LayoutParams(-1, h))
            if (editMode) {
                frame.addView(TextView(this).apply {
                    text = "✕"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = LedStyle.FrameDrawable(this@HomeActivity, 18f, 0xCC300A0A.toInt())
                    setOnClickListener { removeWidget(id) }
                }, FrameLayout.LayoutParams(dp(40f), dp(40f), Gravity.END or Gravity.TOP))
            }
            widgetsBox.addView(frame, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4f), 0, dp(10f)) })
        }
    }

    private fun pickWidget() {
        val providers = awm.installedProviders
            .map { it to (it.loadLabel(packageManager) + "  ·  " + appName(it.provider.packageName)) }
            .sortedBy { FileTools.norm(it.second) }
        AlertDialog.Builder(this)
            .setTitle("Widget ekle")
            .setItems(providers.map { it.second }.toTypedArray()) { _, w -> addWidget(providers[w].first) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun appName(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        pendingId = id
        pendingInfo = info
        if (awm.bindAppWidgetIdIfAllowed(id, info.provider)) {
            configureOrFinish(id, info)
        } else {
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_BIND)
        }
    }

    private fun configureOrFinish(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            try {
                host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
                return
            } catch (e: Exception) {
                // ayar ekranı açılamazsa doğrudan ekle
            }
        }
        finishWidget(id)
    }

    private fun finishWidget(id: Int) {
        save("ev_widgetlar", list("ev_widgetlar") + id.toString())
        refreshWidgets()
    }

    private fun removeWidget(id: Int) {
        host.deleteAppWidgetId(id)
        save("ev_widgetlar", list("ev_widgetlar").filter { it != id.toString() })
        refreshWidgets()
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = pendingId
        val info = pendingInfo
        if (id == -1) return
        when (requestCode) {
            REQ_BIND -> if (resultCode == RESULT_OK && info != null) configureOrFinish(id, info) else host.deleteAppWidgetId(id)
            REQ_CONFIGURE -> if (resultCode == RESULT_OK) finishWidget(id) else host.deleteAppWidgetId(id)
        }
        if (requestCode == REQ_CONFIGURE || resultCode != RESULT_OK) { pendingId = -1; pendingInfo = null }
    }

    // ---------------- menü ----------------

    private fun setEdit(on: Boolean) {
        if (editMode == on) return
        editMode = on
        refreshWidgets()
    }

    private fun homeMenu() {
        val opts = mutableListOf<Pair<String, () -> Unit>>(
            "🧩 Widget ekle" to { pickWidget() },
            "📱 Uygulama ekle (Tümü'nden basılı tut)" to { openDrawer() },
            (if (editMode) "✔ Düzenlemeyi bitir" else "✏ Widget'ları düzenle / kaldır") to { setEdit(!editMode) },
            "🎨 Tema ve duvar kağıdı" to { PhoneThemes.show(this) { LedThemeRefresh() } },
            "✦ D-FON'u aç" to { startActivity(Intent(this, MainActivity::class.java)) },
            "🏠 Ana ekran ayarları" to { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
        )
        AlertDialog.Builder(this)
            .setTitle("D-FON ana ekran")
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
            .show()
    }

    @Suppress("FunctionName")
    private fun LedThemeRefresh() {
        dock.background = LedStyle.FrameDrawable(this, 30f, 0x55061016)
        search.background = LedStyle.FrameDrawable(this, 22f, 0x88061016.toInt())
        refreshAll()
    }

    companion object {
        private const val HOST_ID = 4242
        private const val REQ_BIND = 301
        private const val REQ_CONFIGURE = 302
    }
}
