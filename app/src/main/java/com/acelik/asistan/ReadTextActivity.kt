package com.acelik.asistan

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Herhangi bir uygulamada yazı seçip menüden "D-FON ile oku" denince
 * ya da "Paylaş → D-FON ile oku" ile gelen yazıyı sesli okur.
 */
class ReadTextActivity : Activity() {

    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> null
        }
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Okunacak yazı bulunamadı.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Speaker.onDone = { runOnUiThread { if (!isFinishing) dialog?.setTitle("🔊 Okuma bitti") } }
        Speaker.speak(this, text)

        val preview = if (text.length > 400) text.take(400) + "…" else text
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("🔊 D-FON okuyor")
            .setMessage(preview)
            .setPositiveButton("Durdur") { _, _ -> Speaker.stop() }
            .setNeutralButton("Baştan oku", null)
            .setNegativeButton("Arka planda oku", null)
            .setOnDismissListener { finish() }
            .create()
        dialog!!.setOnShowListener {
            dialog!!.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                Speaker.speak(this, text)
                dialog?.setTitle("🔊 D-FON okuyor")
            }
        }
        dialog!!.show()
    }

    override fun onDestroy() {
        Speaker.onDone = null
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        super.onDestroy()
    }
}
