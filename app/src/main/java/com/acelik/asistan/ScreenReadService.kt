package com.acelik.asistan

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * "Ekrandaki yazıyı oku": Erişilebilirlik düğmesine (ekranın kenarındaki küçük insan simgesi)
 * basınca o an ekranda görünen yazıları sırayla okur. Tekrar basınca susar.
 * Ekrandaki yazılar hiçbir yere kaydedilmez ve gönderilmez; sadece o an okunur.
 */
class ScreenReadService : AccessibilityService() {

    private var callback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val cb = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                toggle()
            }
        }
        callback = cb
        accessibilityButtonController.registerAccessibilityButtonCallback(cb)
    }

    private fun toggle() {
        if (Speaker.speaking) {
            Speaker.stop()
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            Toast.makeText(this, "Ekran okunamadı.", Toast.LENGTH_SHORT).show()
            return
        }
        val parts = ArrayList<String>()
        collect(root, parts, 0)
        val text = parts.joinToString("\n")
        if (text.isBlank() || !Speaker.speak(this, text)) {
            Toast.makeText(this, "Ekranda okunacak yazı yok.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 60 || out.size > 2000) return
        if (!node.isVisibleToUser) return
        if (node.isPassword) return
        val t = node.text?.toString()?.trim()
        val label = if (!t.isNullOrEmpty()) t else node.contentDescription?.toString()?.trim()
        if (!label.isNullOrEmpty() && out.lastOrNull() != label) out.add(label)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Speaker.stop()
    }

    override fun onDestroy() {
        callback?.let { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) }
        super.onDestroy()
    }
}
