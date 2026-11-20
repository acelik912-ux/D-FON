package com.acelik.asistan

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class AttachedFile(val uri: Uri, val name: String, val mime: String, val size: Long)

/**
 * Uygulama ve dosya işlemleri. Hepsi telefonun içinde çalışır; internete bağlanmaz.
 */
class FileTools(private val activity: Activity) {

    val files = mutableListOf<AttachedFile>()
    /** En son oluşturulan ya da seçilen dosyanın numarası. */
    var lastFileNo: Int? = null
        private set

    private val cr = activity.contentResolver
    private val pm = activity.packageManager
    private val prefs = activity.getSharedPreferences("dfon", Context.MODE_PRIVATE)

    init {
        PDFBoxResourceLoader.init(activity.applicationContext)
    }

    // ---------------- izinli uygulamalar ----------------

    data class App(val label: String, val pkg: String)

    fun installedApps(): List<App> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != activity.packageName }
            .distinctBy { it.pkg }
            .sortedBy { norm(it.label) }
    }

    fun allowedPackages(): Set<String> = prefs.getStringSet("izinli", emptySet()) ?: emptySet()

    fun setAllowedPackages(p: Set<String>) {
        prefs.edit().putStringSet("izinli", HashSet(p)).apply()
    }

    fun allowedApps(): List<App> {
        val allowed = allowedPackages()
        return installedApps().filter { it.pkg in allowed }
    }

    /** Metnin içinde adı geçen uygulamayı bulur (en uzun eşleşme). */
    fun appInText(text: String, onlyAllowed: Boolean): App? {
        val t = " ${norm(text)} "
        val list = if (onlyAllowed) allowedApps() else installedApps()
        return list
            .filter { norm(it.label).length >= 2 }
            .filter { a ->
                val l = norm(a.label)
                // kelime başında geçsin: "word" -> "wordu ac" eşleşir, "sword" eşleşmez
                t.contains(" $l") || t.contains(" ${l.replace(" ", "")}")
            }
            .maxByOrNull { norm(it.label).length }
    }

    fun openApp(app: App): String {
        Guard.blockedReason(activity, app.pkg, app.label)?.let { return it }
        val i = pm.getLaunchIntentForPackage(app.pkg) ?: return "${app.label} açılamadı."
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        AppLock.allow(activity, app.pkg)
        activity.onMain { activity.startActivity(i) }
        return "${app.label} açıldı."
    }

    // ---------------- internet (tarayıcıda) ----------------

    fun openUrl(url: String): String {
        val u = if (url.startsWith("http")) url else "https://$url"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            activity.onMain { activity.startActivity(i) }
            "Açıldı: $u"
        } catch (e: ActivityNotFoundException) {
            "Telefonda tarayıcı bulunamadı."
        }
    }

    // ---------------- dosya seçme ----------------

    fun pick(type: String, multiple: Boolean) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        val mimes = when (type) {
            "pdf" -> arrayOf("application/pdf")
            "word" -> arrayOf(DOCX_MIME)
            "resim" -> arrayOf("image/*")
            "ses" -> arrayOf("audio/*")
            else -> arrayOf("*/*")
        }
        i.type = mimes[0]
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        activity.onMain {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(i, REQ_PICK)
        }
    }

    /** Seçici sonucunu işler, eklenen dosyaların numaralarını döndürür. */
    fun handlePick(data: Intent?): List<Int> {
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        return uris.map { uri ->
            try {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ignored: Exception) {
            }
            add(uri, queryName(uri), cr.getType(uri) ?: "application/octet-stream")
        }
    }

    private fun add(uri: Uri, name: String, mime: String): Int {
        files.add(AttachedFile(uri, name, mime, querySize(uri)))
        lastFileNo = files.size
        return files.size
    }

    fun name(no: Int) = file(no).name

    fun describeFiles(): String =
        if (files.isEmpty()) "Henüz dosya yok. 📎 ile ekleyebilirsin."
        else files.mapIndexed { i, f -> "${i + 1}) ${f.name}" }.joinToString("\n")

    private fun file(no: Int): AttachedFile =
        files.getOrNull(no - 1) ?: throw IllegalArgumentException("$no numaralı dosya yok.")

    fun kind(no: Int): String = kind(file(no))

    // ---------------- açma & paylaşma (sadece izinli uygulamalarla) ----------------

    fun openFile(no: Int, app: App?): String {
        val f = file(no)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(f.uri, f.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchWithAllowed(i, app, "${f.name} açılıyor")
    }

    fun shareFile(no: Int, app: App?): String {
        val f = file(no)
        val i = Intent(Intent.ACTION_SEND)
            .setType(f.mime)
            .putExtra(Intent.EXTRA_STREAM, f.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        i.clipData = ClipData.newRawUri(f.name, f.uri)
        return launchWithAllowed(i, app, "${f.name} gönderiliyor")
    }

    private fun launchWithAllowed(i: Intent, app: App?, ok: String): String {
        val allowed = allowedPackages()
        val all = pm.queryIntentActivities(i, 0).distinctBy { it.activityInfo.packageName }
        // İzinli uygulamalar arasında uygun olan yoksa, dosyanın açılabilmesi için diğer uygun uygulamaları göster
        val handlers = all.filter { it.activityInfo.packageName in allowed }.ifEmpty { all }
        if (app != null) {
            if (handlers.none { it.activityInfo.packageName == app.pkg }) {
                return "${app.label} bu dosyayla çalışamıyor ya da izinli değil."
            }
            i.setPackage(app.pkg)
            AppLock.allow(activity, app.pkg)
            activity.onMain { activity.startActivity(i) }
            return "$ok (${app.label})."
        }
        if (handlers.isEmpty()) {
            return "Bu dosyayla çalışabilen izinli uygulama yok. Üstteki 'Uygulamalar' düğmesinden işaretle."
        }
        if (handlers.size == 1) {
            val h = handlers[0].activityInfo
            i.setClassName(h.packageName, h.name)
            AppLock.allow(activity, h.packageName)
            activity.onMain { activity.startActivity(i) }
            return "$ok (${handlers[0].loadLabel(pm)})."
        }
        val labels = handlers.map { it.loadLabel(pm).toString() }.toTypedArray()
        activity.onMain {
            AlertDialog.Builder(activity)
                .setTitle("Hangi uygulama?")
                .setItems(labels) { _, which ->
                    val h = handlers[which].activityInfo
                    AppLock.allow(activity, h.packageName)
                    activity.startActivity(Intent(i).setClassName(h.packageName, h.name))
                }
                .setNegativeButton("İptal", null)
                .show()
        }
        return "$ok; uygulamayı seç."
    }

    // ---------------- dönüştürmeler ----------------

    /** Resimler, Word ve metin dosyalarından (sırayla) tek PDF yapar. */
    fun filesToPdf(nos: List<Int>): String {
        val doc = PdfDocument()
        var page = 0
        try {
            for (no in nos) {
                val f = file(no)
                page = when (kind(f)) {
                    "resim" -> drawImage(doc, f.uri, page)
                    "word" -> drawText(doc, docxText(f.uri), page)
                    "metin" -> drawText(doc, String(readBytes(f.uri), Charsets.UTF_8), page)
                    else -> throw IllegalArgumentException("${f.name} PDF'e çevrilemez (resim, Word .docx veya metin olmalı).")
                }
            }
            val name = fileName(file(nos.first()).name.substringBeforeLast('.'), "pdf")
            val no = save(name, "application/pdf", Environment.DIRECTORY_DOWNLOADS) { doc.writeTo(it) }
            return "PDF hazır: İndirilenler/D-FON/$name ($page sayfa)."
                .also { lastFileNo = no }
        } finally {
            doc.close()
        }
    }

    fun textToPdf(text: String, title: String): String {
        val doc = PdfDocument()
        try {
            val pages = drawText(doc, text, 0)
            val name = fileName(title, "pdf")
            save(name, "application/pdf", Environment.DIRECTORY_DOWNLOADS) { doc.writeTo(it) }
            return "PDF hazır: İndirilenler/D-FON/$name ($pages sayfa)."
        } finally {
            doc.close()
        }
    }

    fun pdfToDocx(no: Int): String {
        val f = file(no)
        val text = pdfText(f.uri)
        if (text.isBlank()) {
            return "${f.name} içinde okunabilir yazı yok (taranmış/fotoğraf PDF olabilir). Bunun yerine 'PDF'i resme çevir' deneyebilirsin."
        }
        val name = fileName(f.name.substringBeforeLast('.'), "docx")
        save(name, DOCX_MIME, Environment.DIRECTORY_DOWNLOADS) { writeDocx(it, text) }
        return "Word belgesi hazır: İndirilenler/D-FON/$name"
    }

    fun textToDocx(text: String, title: String): String {
        val name = fileName(title, "docx")
        save(name, DOCX_MIME, Environment.DIRECTORY_DOWNLOADS) { writeDocx(it, text) }
        return "Word belgesi hazır: İndirilenler/D-FON/$name"
    }

    fun mergePdfs(nos: List<Int>): String {
        if (nos.size < 2) return "Birleştirmek için en az 2 PDF seç."
        val name = fileName("birlesik", "pdf")
        save(name, "application/pdf", Environment.DIRECTORY_DOWNLOADS) { out ->
            val m = PDFMergerUtility()
            val streams = nos.map { cr.openInputStream(file(it).uri) ?: throw IOException("PDF açılamadı") }
            try {
                streams.forEach { m.addSource(it) }
                m.destinationStream = out
                m.mergeDocuments(MemoryUsageSetting.setupMixed(64L * 1024 * 1024))
            } finally {
                streams.forEach { s -> runCatching { s.close() } }
            }
        }
        return "${nos.size} PDF birleştirildi: İndirilenler/D-FON/$name"
    }

    /** Ses ya da video dosyasını MP3'e çevirir; Müzik/D-FON klasörüne kaydeder. */
    fun toMp3(nos: List<Int>): String {
        val done = ArrayList<String>(); val bad = ArrayList<String>()
        for (no in nos) {
            val f = file(no)
            try {
                val base = f.name.substringBeforeLast('.')
                var sec = 0L
                save(fileName(base, "mp3"), "audio/mpeg", Environment.DIRECTORY_MUSIC) { os ->
                    sec = Mp3Converter.convert(activity, f.uri, java.io.BufferedOutputStream(os, 1 shl 16))
                }
                done.add("${f.name} (${sec / 60}:${"%02d".format(sec % 60)})")
            } catch (e: Exception) {
                bad.add("${f.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        val sb = StringBuilder()
        if (done.isNotEmpty()) sb.append("🎵 ${done.size} dosya MP3'e çevrildi → Müzik/D-FON klasörü:\n").append(done.joinToString("\n") { "• $it" })
        if (bad.isNotEmpty()) sb.append(if (sb.isEmpty()) "" else "\n\n").append("Çevrilemeyenler:\n").append(bad.joinToString("\n") { "• $it" })
        return sb.toString()
    }

    fun pdfToImages(no: Int): String {
        val f = file(no)
        val pfd = cr.openFileDescriptor(f.uri, "r") ?: throw IOException("PDF açılamadı")
        val base = f.name.substringBeforeLast('.')
        var made = 0
        var total = 0
        pfd.use { fd ->
            PdfRenderer(fd).use { r ->
                total = r.pageCount
                for (k in 0 until minOf(total, 50)) {
                    r.openPage(k).use { pg ->
                        val bmp = Bitmap.createBitmap(pg.width * 2, pg.height * 2, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        save(fileName("${base}_sayfa${k + 1}", "png"), "image/png", Environment.DIRECTORY_PICTURES) {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bmp.recycle()
                        made++
                    }
                }
            }
        }
        val more = if (total > 50) " (ilk 50 sayfa)" else ""
        return "$made sayfa resme çevrildi$more: Resimler/D-FON klasörü (Galeri'de görünür)."
    }

    // ---------------- çizim ----------------

    private fun drawImage(doc: PdfDocument, uri: Uri, startPage: Int): Int {
        val bmp = decodeScaled(uri, 2000) ?: throw IOException("Resim açılamadı")
        val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, startPage + 1).create())
        val m = 24f
        val scale = minOf((PAGE_W - 2 * m) / bmp.width, (PAGE_H - 2 * m) / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (PAGE_W - w) / 2
        val top = (PAGE_H - h) / 2
        p.canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(p)
        bmp.recycle()
        return startPage + 1
    }

    private fun drawText(doc: PdfDocument, raw: String, startPage: Int): Int {
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
        val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16f; color = Color.BLACK; isFakeBoldText = true
        }
        val width = PAGE_W - 2 * MARGIN
        val usable = PAGE_H - 2 * MARGIN
        val layouts = raw.replace("\r", "").split("\n").map { line ->
            val isHead = line.startsWith("# ")
            val t = if (isHead) line.removePrefix("# ") else line
            StaticLayout.Builder.obtain(t, 0, t.length, if (isHead) head else body, width)
                .setLineSpacing(0f, 1.2f).build()
        }
        var pageNo = startPage
        var page: PdfDocument.Page? = null
        var y = 0
        for (lay in layouts) {
            var line = 0
            while (line < lay.lineCount) {
                if (page == null || y + (lay.getLineBottom(line) - lay.getLineTop(line)) > usable) {
                    page?.let { doc.finishPage(it) }
                    page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ++pageNo).create())
                    y = 0
                }
                val top = lay.getLineTop(line)
                var end = line
                while (end < lay.lineCount && y + (lay.getLineBottom(end) - top) <= usable) end++
                if (end == line) end = line + 1
                val bottom = lay.getLineBottom(end - 1)
                val c: Canvas = page!!.canvas
                c.save()
                c.translate(MARGIN.toFloat(), (MARGIN + y - top).toFloat())
                c.clipRect(0, top, width, bottom)
                lay.draw(c)
                c.restore()
                y += bottom - top
                line = end
            }
        }
        page?.let { doc.finishPage(it) }
        return pageNo
    }

    // ---------------- Word & PDF metni ----------------

    private fun writeDocx(out: OutputStream, text: String) {
        val z = ZipOutputStream(out)
        fun put(path: String, content: String) {
            z.putNextEntry(ZipEntry(path))
            z.write(content.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        put("[Content_Types].xml", CONTENT_TYPES)
        put("_rels/.rels", RELS)
        put("word/document.xml", documentXml(text))
        z.finish()
    }

    private fun documentXml(text: String): String {
        val sb = StringBuilder()
        for (raw in text.replace("\r", "").split("\n")) {
            val isHead = raw.startsWith("# ")
            val t = xmlEsc(if (isHead) raw.removePrefix("# ") else raw)
            if (isHead) {
                sb.append("<w:p><w:r><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr><w:t xml:space=\"preserve\">")
                    .append(t).append("</w:t></w:r></w:p>")
            } else {
                sb.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(t).append("</w:t></w:r></w:p>")
            }
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
            sb +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>"
    }

    // ---------------- sesli okuma ----------------

    fun readAloud(no: Int): String {
        val f = file(no)
        val text = when (kind(f)) {
            "pdf" -> pdfText(f.uri)
            "word" -> docxText(f.uri)
            "metin" -> String(readBytes(f.uri), Charsets.UTF_8)
            else -> return "${f.name} okunabilen bir dosya değil. PDF, Word (.docx) ya da metin dosyası seç."
        }
        if (text.isBlank()) return "${f.name} içinde yazı bulunamadı. Taranmış (resim) bir PDF olabilir."
        speakText(text)
        return "🔊 ${f.name} okunuyor. Durdurmak için \"dur\" yaz ya da söyle."
    }

    /** Düzenleme için dosyanın yazısını verir (PDF, Word, metin). */
    fun textOf(no: Int): String {
        val f = file(no)
        return when (kind(f)) {
            "pdf" -> pdfText(f.uri)
            "word" -> docxText(f.uri)
            "metin" -> String(readBytes(f.uri), Charsets.UTF_8)
            else -> throw IllegalArgumentException("${f.name} düzenlenebilen bir dosya değil (PDF, Word ya da metin olmalı).")
        }
    }

    fun speakText(text: String): String {
        if (!activity.onMain { Speaker.speak(activity, text) }) return "Okunacak yazı yok."
        return "🔊 Okuyorum. Durdurmak için \"dur\" de."
    }

    fun screenReadEnabled(): Boolean {
        val s = android.provider.Settings.Secure.getString(
            cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = android.content.ComponentName(activity, ScreenReadService::class.java)
        return s.split(':').any { android.content.ComponentName.unflattenFromString(it) == me }
    }

    fun openAccessibilitySettings() {
        val i = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.onMain { activity.startActivity(i) }
    }

    private fun docxText(uri: Uri): String {
        cr.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name == "word/document.xml") {
                        val xml = String(z.readBytes(), Charsets.UTF_8)
                        return xml
                            .replace("</w:p>", "\n")
                            .replace("<w:tab/>", "\t")
                            .replace(Regex("<w:br[^>]*/>"), "\n")
                            .replace(Regex("<[^>]+>"), "")
                            .replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&quot;", "\"").replace("&apos;", "'")
                            .replace("&amp;", "&")
                            .trim()
                    }
                    e = z.nextEntry
                }
            }
        }
        throw IOException("Bu bir .docx değil ya da okunamadı (eski .doc biçimi desteklenmiyor).")
    }

    private fun pdfText(uri: Uri): String {
        val input = cr.openInputStream(uri) ?: throw IOException("PDF açılamadı")
        input.use {
            PDDocument.load(it).use { doc ->
                val sb = StringBuilder()
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                for (p in 1..doc.numberOfPages) {
                    stripper.startPage = p
                    stripper.endPage = p
                    sb.append(stripper.getText(doc).trimEnd()).append("\n\n")
                }
                return sb.toString().trim()
            }
        }
    }

    // ---------------- kaydetme & yardımcılar ----------------

    private fun save(name: String, mime: String, folder: String, write: (OutputStream) -> Unit): Int {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/D-FON")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = when (folder) {
            Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Environment.DIRECTORY_MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val uri = cr.insert(collection, cv) ?: throw IOException("Dosya oluşturulamadı")
        try {
            (cr.openOutputStream(uri) ?: throw IOException("Dosyaya yazılamadı")).use(write)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        cv.clear()
        cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
        cr.update(uri, cv, null, null)
        return add(uri, name, mime)
    }

    private fun readBytes(uri: Uri): ByteArray =
        cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Dosya okunamadı")

    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > maxSide) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
    }

    private fun queryName(uri: Uri): String {
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (ignored: Exception) {
        }
        return uri.lastPathSegment ?: "dosya"
    }

    private fun querySize(uri: Uri): Long {
        try {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (ignored: Exception) {
        }
        return 0
    }

    private fun kind(f: AttachedFile): String {
        val n = f.name.lowercase()
        return when {
            f.mime == "application/pdf" || n.endsWith(".pdf") -> "pdf"
            f.mime.startsWith("image/") -> "resim"
            f.mime.startsWith("audio/") -> "ses"
            f.mime.startsWith("video/") -> "video"
            f.mime == DOCX_MIME || n.endsWith(".docx") -> "word"
            f.mime.startsWith("text/") || n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv") -> "metin"
            else -> "diger"
        }
    }

    private fun fileName(base: String, ext: String): String {
        val b = base.ifBlank { "D-FON" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        return "${b}_${SimpleDateFormat("HHmmss", Locale.US).format(Date())}.$ext"
    }

    private fun xmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")

    companion object {
        const val REQ_PICK = 42
        private const val PAGE_W = 595 // A4, 1/72 inç
        private const val PAGE_H = 842
        private const val MARGIN = 50
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        private const val CONTENT_TYPES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "</Types>"

        private const val RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>"

        /** Türkçe karakterleri sadeleştirip küçük harfe çevirir (eşleştirme için). */
        fun norm(s: String): String = Nlu.norm(s)
    }
}
