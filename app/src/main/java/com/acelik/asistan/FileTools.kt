package com.acelik.asistan

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
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
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class AttachedFile(val uri: Uri, val name: String, val mime: String, val size: Long)

/** Bir aracın sonucu: yapay zekaya gidecek metin + (varsa) PDF/resim içeriği. */
class ToolOutput(val text: String, val blocks: JSONArray? = null)

/**
 * Uygulama ve dosya araçları: uygulama açma, dosya seçme/açma/paylaşma,
 * PDF ve Word oluşturma, PDF'i resme çevirme, dosya içeriğini okuma.
 */
class FileTools(private val activity: Activity) {

    val files = mutableListOf<AttachedFile>()
    private val cr = activity.contentResolver
    private val pm = activity.packageManager

    var pickRequestedByAi = false

    // ---------------- tanımlar ----------------

    fun definitions(): JSONArray {
        val t = JSONArray()
        t.put(tool("uygulamalari_listele",
            "Telefonda yüklü uygulamaların adlarını listeler. İsteğe bağlı arama kelimesiyle filtreler.",
            JSONObject().put("arama", str("isteğe bağlı arama kelimesi")), listOf()))
        t.put(tool("uygulama_ac",
            "Yüklü bir uygulamayı adıyla açar (ör. WhatsApp, Word, YouTube, Kamera, Hesap Makinesi).",
            JSONObject().put("ad", str("uygulamanın adı")), listOf("ad")))
        t.put(tool("dosya_sec",
            "Kullanıcının telefonundan dosya seçmesi için dosya seçiciyi açar. Kullanıcı seçince seçilen dosyalar otomatik olarak yeni mesajla bildirilir; o zamana kadar bekle.",
            JSONObject()
                .put("tur", JSONObject().put("type", "string").put("enum", JSONArray(listOf("hepsi", "pdf", "word", "resim", "metin"))))
                .put("coklu", JSONObject().put("type", "boolean").put("description", "birden fazla dosya seçilebilsin mi")),
            listOf("tur")))
        t.put(tool("dosyalari_listele", "Bu sohbette eklenmiş/oluşturulmuş dosyaları numaralarıyla listeler.", JSONObject(), listOf()))
        t.put(tool("dosya_ac",
            "Bir dosyayı açar. 'uygulama' verilirse o uygulamada (ör. Word, Drive PDF Görüntüleyici) açar; verilmezse kullanıcıya uygulama seçtirir.",
            JSONObject().put("dosya_no", int("dosya numarası")).put("uygulama", str("isteğe bağlı uygulama adı")),
            listOf("dosya_no")))
        t.put(tool("dosya_paylas",
            "Bir dosyayı paylaşır (WhatsApp, Gmail vb.). 'uygulama' verilirse doğrudan o uygulamaya gönderir.",
            JSONObject().put("dosya_no", int("dosya numarası")).put("uygulama", str("isteğe bağlı uygulama adı")),
            listOf("dosya_no")))
        t.put(tool("dosya_oku",
            "Dosyanın içeriğini senin görmen için okur (PDF, resim, Word .docx, metin). Özetleme, çeviri, PDF'ten Word'e dönüştürme gibi işlerden önce kullan.",
            JSONObject().put("dosya_no", int("dosya numarası")), listOf("dosya_no")))
        t.put(tool("pdf_olustur",
            "PDF oluşturur ve İndirilenler/D-FON klasörüne kaydeder. Ya 'dosya_nolari' (resimler, Word .docx, metin dosyaları; sırayla birleştirilir) ya da 'metin' ver. Word'den PDF'e çevirmede yazı aktarılır, karmaşık biçimler (tablo, resim) aktarılmaz.",
            JSONObject()
                .put("dosya_nolari", JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")))
                .put("metin", str("PDF'e yazılacak metin. '# ' ile başlayan satırlar başlık olur."))
                .put("ad", str("dosya adı (uzantısız)")),
            listOf()))
        t.put(tool("word_olustur",
            "Word (.docx) belgesi oluşturur ve İndirilenler/D-FON klasörüne kaydeder. PDF'ten Word'e çevirirken önce dosya_oku ile içeriği oku, sonra metni buraya yaz. '# ' ile başlayan satırlar başlık olur.",
            JSONObject().put("metin", str("belgenin metni")).put("ad", str("dosya adı (uzantısız)")),
            listOf("metin")))
        t.put(tool("pdf_resme_cevir",
            "PDF'in her sayfasını PNG resim olarak Resimler/D-FON klasörüne kaydeder (en fazla 30 sayfa).",
            JSONObject().put("dosya_no", int("PDF dosyasının numarası")), listOf("dosya_no")))
        return t
    }

    fun has(name: String) = NAMES.contains(name)

    fun run(name: String, input: JSONObject): ToolOutput = try {
        when (name) {
            "uygulamalari_listele" -> ToolOutput(listApps(input.optString("arama")))
            "uygulama_ac" -> ToolOutput(openApp(input.optString("ad")))
            "dosya_sec" -> ToolOutput(pick(input.optString("tur", "hepsi"), input.optBoolean("coklu", false), true))
            "dosyalari_listele" -> ToolOutput(describeFiles())
            "dosya_ac" -> ToolOutput(openFile(input.optInt("dosya_no"), input.optString("uygulama")))
            "dosya_paylas" -> ToolOutput(shareFile(input.optInt("dosya_no"), input.optString("uygulama")))
            "dosya_oku" -> readFile(input.optInt("dosya_no"))
            "pdf_olustur" -> ToolOutput(makePdf(input))
            "word_olustur" -> ToolOutput(makeDocx(input.optString("metin"), input.optString("ad")))
            "pdf_resme_cevir" -> ToolOutput(pdfToImages(input.optInt("dosya_no")))
            else -> ToolOutput("Bilinmeyen araç: $name")
        }
    } catch (e: Exception) {
        ToolOutput("Hata: ${e.javaClass.simpleName}: ${e.message}")
    }

    // ---------------- uygulamalar ----------------

    data class App(val label: String, val pkg: String)

    fun installedApps(): List<App> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { norm(it.label) }
    }

    private fun findApp(name: String): App? {
        val q = norm(name)
        if (q.isBlank()) return null
        val apps = installedApps()
        return apps.firstOrNull { norm(it.label) == q }
            ?: apps.firstOrNull { norm(it.label).startsWith(q) }
            ?: apps.firstOrNull { norm(it.label).contains(q) }
            ?: apps.firstOrNull { q.contains(norm(it.label)) && norm(it.label).length >= 3 }
            ?: apps.firstOrNull { it.pkg.lowercase().contains(q.replace(" ", "")) }
    }

    private fun listApps(search: String): String {
        val q = norm(search)
        val list = installedApps().filter { q.isBlank() || norm(it.label).contains(q) }
        if (list.isEmpty()) return "Eşleşen uygulama yok."
        return "${list.size} uygulama: " + list.joinToString(", ") { it.label }
    }

    private fun openApp(name: String): String {
        val app = findApp(name) ?: return "'$name' adında uygulama bulunamadı. uygulamalari_listele ile kontrol et."
        val i = pm.getLaunchIntentForPackage(app.pkg) ?: return "${app.label} açılamadı."
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)
        return "${app.label} açıldı."
    }

    // ---------------- dosya seçme ----------------

    fun pick(type: String, multiple: Boolean, byAi: Boolean): String {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        val mimes = when (type) {
            "pdf" -> arrayOf("application/pdf")
            "word" -> arrayOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/msword"
            )
            "resim" -> arrayOf("image/*")
            "metin" -> arrayOf("text/*")
            else -> arrayOf("*/*")
        }
        i.type = if (mimes.size == 1) mimes[0] else "*/*"
        if (mimes.size > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        pickRequestedByAi = byAi
        @Suppress("DEPRECATION")
        activity.startActivityForResult(i, REQ_PICK)
        return "Dosya seçici açıldı. Kullanıcı seçince dosyalar yeni mesajla gelecek; şimdilik kısaca 'Dosyayı seç' de ve bekle."
    }

    /** Seçici sonucunu işler, eklenen dosyaları döndürür. */
    fun handlePick(data: Intent?): List<Pair<Int, AttachedFile>> {
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

    private fun add(uri: Uri, name: String, mime: String): Pair<Int, AttachedFile> {
        val f = AttachedFile(uri, name, mime, querySize(uri))
        files.add(f)
        return files.size to f
    }

    fun describeFiles(): String =
        if (files.isEmpty()) "Henüz dosya yok. dosya_sec ile kullanıcıdan dosya iste."
        else files.mapIndexed { i, f -> "${i + 1}) ${f.name} [${kind(f)}, ${f.size / 1024} KB]" }.joinToString("\n")

    private fun file(no: Int): AttachedFile =
        files.getOrNull(no - 1) ?: throw IllegalArgumentException("$no numaralı dosya yok. Mevcut dosyalar:\n${describeFiles()}")

    // ---------------- açma & paylaşma ----------------

    private fun openFile(no: Int, appName: String): String {
        val f = file(no)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(f.uri, f.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(i, appName, "${f.name} açıldı", "açacak")
    }

    private fun shareFile(no: Int, appName: String): String {
        val f = file(no)
        val i = Intent(Intent.ACTION_SEND)
            .setType(f.mime)
            .putExtra(Intent.EXTRA_STREAM, f.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        i.clipData = ClipData.newRawUri(f.name, f.uri)
        return launch(i, appName, "${f.name} paylaşım için gönderildi", "paylaşacak")
    }

    private fun launch(i: Intent, appName: String, ok: String, verb: String): String {
        if (appName.isNotBlank()) {
            val app = findApp(appName) ?: return "'$appName' uygulaması bulunamadı."
            i.setPackage(app.pkg)
            return try {
                activity.startActivity(i); "$ok (${app.label})."
            } catch (e: ActivityNotFoundException) {
                "${app.label} bu dosyayı $verb şekilde açılamadı. Uygulama belirtmeden dene."
            }
        }
        return try {
            activity.startActivity(Intent.createChooser(i, "Uygulama seç").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "$ok; kullanıcı uygulama seçecek."
        } catch (e: ActivityNotFoundException) {
            "Bu dosyayı $verb uygulama bulunamadı."
        }
    }

    // ---------------- okuma ----------------

    private fun readFile(no: Int): ToolOutput {
        val f = file(no)
        return when (kind(f)) {
            "pdf" -> {
                if (f.size > 15L * 1024 * 1024) return ToolOutput("PDF çok büyük (15 MB üstü), okunamıyor.")
                val b64 = Base64.encodeToString(readBytes(f.uri), Base64.NO_WRAP)
                ToolOutput("${f.name} içeriği ekte.", JSONArray().put(
                    JSONObject().put("type", "document").put("source",
                        JSONObject().put("type", "base64").put("media_type", "application/pdf").put("data", b64))
                ))
            }
            "resim" -> {
                val b64 = Base64.encodeToString(jpegForAi(f.uri), Base64.NO_WRAP)
                ToolOutput("${f.name} resmi ekte.", JSONArray().put(
                    JSONObject().put("type", "image").put("source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64))
                ))
            }
            "word" -> ToolOutput("${f.name} metni:\n" + docxText(f.uri).take(60_000))
            "metin" -> ToolOutput("${f.name} metni:\n" + String(readBytes(f.uri), Charsets.UTF_8).take(60_000))
            else -> ToolOutput("Bu dosya türü (${f.mime}) okunamıyor.")
        }
    }

    // ---------------- PDF oluşturma ----------------

    private fun makePdf(input: JSONObject): String {
        val nos = input.optJSONArray("dosya_nolari")
        val text = input.optString("metin")
        val doc = PdfDocument()
        var page = 0
        try {
            if (nos != null && nos.length() > 0) {
                for (k in 0 until nos.length()) {
                    val f = file(nos.getInt(k))
                    page = when (kind(f)) {
                        "resim" -> drawImage(doc, f.uri, page)
                        "word" -> drawText(doc, docxText(f.uri), page)
                        "metin" -> drawText(doc, String(readBytes(f.uri), Charsets.UTF_8), page)
                        else -> throw IllegalArgumentException("${f.name} PDF'e eklenemez (sadece resim, Word, metin).")
                    }
                }
            } else if (text.isNotBlank()) {
                page = drawText(doc, text, page)
            } else {
                return "PDF için 'dosya_nolari' ya da 'metin' gerekli."
            }
            val name = fileName(input.optString("ad"), firstName(nos), "pdf")
            val (no, _) = save(name, "application/pdf", Environment.DIRECTORY_DOWNLOADS) { doc.writeTo(it) }
            return "PDF oluşturuldu: İndirilenler/D-FON/$name ($page sayfa). Dosya no: $no"
        } finally {
            doc.close()
        }
    }

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
        // Her paragrafı ayrı düzen olarak hazırla, sonra sayfalara yerleştir
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
                // Bu sayfaya sığan satırları birlikte çiz
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

    // ---------------- Word oluşturma / okuma ----------------

    private fun makeDocx(text: String, name: String): String {
        if (text.isBlank()) return "Word için metin gerekli."
        val fname = fileName(name, null, "docx")
        val (no, _) = save(fname, DOCX_MIME, Environment.DIRECTORY_DOWNLOADS) { out ->
            ZipOutputStream(out).use { z ->
                fun put(path: String, content: String) {
                    z.putNextEntry(ZipEntry(path))
                    z.write(content.toByteArray(Charsets.UTF_8))
                    z.closeEntry()
                }
                put("[Content_Types].xml", CONTENT_TYPES)
                put("_rels/.rels", RELS)
                put("word/document.xml", documentXml(text))
            }
        }
        return "Word belgesi oluşturuldu: İndirilenler/D-FON/$fname. Dosya no: $no"
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

    private fun docxText(uri: Uri): String {
        cr.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name == "word/document.xml") {
                        val xml = String(z.readBytes(), Charsets.UTF_8)
                        return xml
                            .replace(Regex("</w:p>"), "\n")
                            .replace(Regex("<w:tab/>"), "\t")
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

    // ---------------- PDF -> resim ----------------

    private fun pdfToImages(no: Int): String {
        val f = file(no)
        if (kind(f) != "pdf") return "${f.name} bir PDF değil."
        val pfd = cr.openFileDescriptor(f.uri, "r") ?: throw IOException("PDF açılamadı")
        val base = f.name.substringBeforeLast('.')
        val made = mutableListOf<Int>()
        var total = 0
        pfd.use { fd ->
            PdfRenderer(fd).use { r ->
                total = r.pageCount
                val count = minOf(r.pageCount, 30)
                for (k in 0 until count) {
                    r.openPage(k).use { pg ->
                        val bmp = Bitmap.createBitmap(pg.width * 2, pg.height * 2, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val (n, _) = save("${base}_sayfa${k + 1}_${stamp()}.png", "image/png", Environment.DIRECTORY_PICTURES) {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bmp.recycle()
                        made.add(n)
                    }
                }
            }
        }
        if (made.isEmpty()) return "PDF'te sayfa bulunamadı."
        val more = if (total > 30) " (ilk 30 sayfa)" else ""
        return "${made.size} sayfa resme çevrildi$more: Resimler/D-FON klasörü. Dosya no: ${made.first()}-${made.last()}"
    }

    // ---------------- kaydetme & yardımcılar ----------------

    private fun save(name: String, mime: String, folder: String, write: (OutputStream) -> Unit): Pair<Int, AttachedFile> {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/D-FON")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (folder == Environment.DIRECTORY_PICTURES)
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
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

    private fun jpegForAi(uri: Uri): ByteArray {
        val src = decodeScaled(uri, 3000) ?: throw IOException("Resim açılamadı")
        val s = minOf(1f, 1568f / maxOf(src.width, src.height))
        val bmp = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (bmp !== src) bmp.recycle()
        src.recycle()
        return out.toByteArray()
    }

    private fun queryName(uri: Uri): String {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
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
            f.mime == DOCX_MIME || n.endsWith(".docx") -> "word"
            f.mime == "application/msword" || n.endsWith(".doc") -> "eski-word"
            f.mime.startsWith("text/") || n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv") -> "metin"
            else -> f.mime
        }
    }

    private fun firstName(nos: JSONArray?): String? =
        if (nos == null || nos.length() == 0) null
        else files.getOrNull(nos.optInt(0) - 1)?.name?.substringBeforeLast('.')

    private fun fileName(given: String, fallback: String?, ext: String): String {
        val base = given.ifBlank { fallback ?: "D-FON" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").removeSuffix(".$ext").take(60)
        return "${base}_${stamp()}.$ext"
    }

    private fun stamp() = SimpleDateFormat("HHmmss", Locale.US).format(Date())

    private fun xmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))
        return JSONObject().put("name", name).put("description", desc).put("input_schema", schema)
    }

    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun int(d: String) = JSONObject().put("type", "integer").put("description", d)

    companion object {
        const val REQ_PICK = 42
        private const val PAGE_W = 595 // A4, 1/72 inç
        private const val PAGE_H = 842
        private const val MARGIN = 50
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        private val NAMES = setOf(
            "uygulamalari_listele", "uygulama_ac", "dosya_sec", "dosyalari_listele", "dosya_ac",
            "dosya_paylas", "dosya_oku", "pdf_olustur", "word_olustur", "pdf_resme_cevir"
        )

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
        fun norm(s: String): String = s.lowercase(Locale("tr"))
            .replace('ı', 'i').replace('ş', 's').replace('ç', 'c')
            .replace('ğ', 'g').replace('ö', 'o').replace('ü', 'u')
            .replace(Regex("[^a-z0-9 ]"), "").trim()
    }
}
