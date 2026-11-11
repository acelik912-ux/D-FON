package com.acelik.asistan

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Ses tanıma yardımcıları: mümkünse telefonun içindeki (internetsiz) tanıyıcıyı kullanır. */
object Voice {

    /** Telefon içi tanıyıcı Türkçe paketi olmadığı için hata verdiyse normal tanıyıcıya geçilir. */
    @Volatile
    var onDeviceFailed = false

    fun create(c: Context): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= 31 && !onDeviceFailed && SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
        } else {
            SpeechRecognizer.createSpeechRecognizer(c)
        }

    fun intent(partial: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

    /** Telefon içi tanıyıcı dil desteği yüzünden hata verdiyse true döner ve bir dahakine normal tanıyıcıyı seçer. */
    fun languageError(error: Int): Boolean {
        // 12 = ERROR_LANGUAGE_NOT_SUPPORTED, 13 = ERROR_LANGUAGE_UNAVAILABLE (Android 12+)
        if ((error == 12 || error == 13) && !onDeviceFailed) {
            onDeviceFailed = true
            return true
        }
        return false
    }

    fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Duyamadım, tekrar dokun ve söyle."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni gerekiyor."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Ses tanıma internet istedi. Telefonda Türkçe çevrimdışı konuşma paketi yüklü olmayabilir."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Ses tanıma meşgul, birazdan tekrar dene."
        else -> "Ses tanınamadı (hata $error). Tekrar dene."
    }

    // ---------------- "bir iki üç fon" ----------------

    private val FON_WORDS = setOf("fon", "phone", "fone", "von", "fun", "fan", "ton")

    /**
     * Söylenen cümlede uyandırma sözü ("bir iki üç fon", "123 fon") var mı?
     * Varsa ardından gelen komutu döner (yoksa boş metin); uyandırma sözü yoksa null.
     */
    fun wakeCommand(said: String): String? {
        val words = said.trim().split(Regex("\\s+"))
        val n = words.map { FileTools.norm(it).replace(" ", "") }
        for (i in n.indices) {
            if (n[i] !in FON_WORDS && !n[i].endsWith("fon")) continue
            // "fon"dan önceki kısım: "bir iki üç" / "1 2 3" / "123" / "birikiüç"
            val before = n.subList(maxOf(0, i - 3), i).joinToString("") +
                (if (n[i].endsWith("fon") && n[i] != "fon") n[i].removeSuffix("fon") else "")
            val digits = before
                .replace("bir", "1").replace("iki", "2").replace("uc", "3")
            if (digits.endsWith("123")) {
                return words.drop(i + 1).joinToString(" ").trim()
            }
        }
        return null
    }
}
