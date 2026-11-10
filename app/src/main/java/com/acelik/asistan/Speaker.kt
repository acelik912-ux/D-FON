package com.acelik.asistan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Yazıyı sesli okur. Telefonun kendi seslendirme motorunu (Samsung / Google TTS) kullanır,
 * Türkçe ses paketi yüklüyse internetsiz çalışır.
 */
object Speaker {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val queue = ArrayList<String>()
    private var appCtx: Context? = null

    /** Okuma bitince ya da durunca çağrılır (arayüzü güncellemek için). */
    var onDone: (() -> Unit)? = null

    @Volatile
    var speaking = false
        private set

    var rate: Float = 1.0f

    @Synchronized
    private fun ensure(c: Context) {
        if (tts != null) return
        appCtx = c.applicationContext
        tts = TextToSpeech(c.applicationContext) { status ->
            synchronized(this) {
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    val t = tts!!
                    val r = t.setLanguage(Locale("tr", "TR"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        t.setLanguage(Locale.getDefault())
                    }
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == LAST) finished()
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            finished()
                        }
                    })
                    if (queue.isNotEmpty()) {
                        val pending = queue.joinToString("\n")
                        queue.clear()
                        speakNow(pending)
                    }
                } else {
                    queue.clear()
                    speaking = false
                }
            }
        }
    }

    /** Türkçe ses paketi yoksa false. Motor henüz hazır değilse null. */
    fun turkishAvailable(): Boolean? {
        val t = tts ?: return null
        if (!ready) return null
        val r = t.isLanguageAvailable(Locale("tr", "TR"))
        return r >= TextToSpeech.LANG_AVAILABLE
    }

    @Synchronized
    fun speak(c: Context, text: String): Boolean {
        val clean = text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (clean.isEmpty()) return false
        ensure(c)
        speaking = true
        if (!ready) {
            queue.clear()
            queue.add(clean)
        } else {
            speakNow(clean)
        }
        return true
    }

    @Synchronized
    fun stop() {
        queue.clear()
        tts?.stop()
        finished()
    }

    private fun finished() {
        speaking = false
        onDone?.invoke()
    }

    /** Motorun bir seferde okuyabildiği uzunluk sınırlı; metni cümle sınırlarından böler. */
    private fun speakNow(text: String) {
        val t = tts ?: return
        t.setSpeechRate(rate)
        val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceIn(500, 3500)
        val parts = split(text, max)
        t.stop()
        parts.forEachIndexed { i, p ->
            val id = if (i == parts.lastIndex) LAST else "p$i"
            t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
        }
    }

    private fun split(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            val window = rest.substring(0, max)
            var cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("\n"), window.lastIndexOf("? "), window.lastIndexOf("! "))
            if (cut < max / 3) cut = window.lastIndexOf(' ')
            if (cut < max / 3) cut = max - 1
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out.add(rest.trim())
        return out
    }

    /** Türkçe ses paketi indirme ekranı. */
    fun installVoiceIntent(): Intent =
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val LAST = "son"
}
