package com.acelik.asistan

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Yapay zekanın telefonda çalıştırabildiği araçlar.
 * Her araç kısa bir Türkçe sonuç metni döndürür; bu metin yapay zekaya geri gönderilir.
 */
class PhoneTools(private val activity: Activity) {

    private val ctx: Context = activity.applicationContext
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notif = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    var torchOn = false
        private set

    // ---------- Araç tanımları (Claude API "tools" alanı) ----------

    fun definitions(): JSONArray {
        val t = JSONArray()
        t.put(tool("durum_oku",
            "Telefonun şu anki ayarlarını okur: parlaklık, ses seviyeleri, zil modu, rahatsız etme, ekran zaman aşımı, otomatik döndürme, pil, verilen izinler. Göreceli isteklerden (biraz artır, azalt) önce çağır.",
            JSONObject()))
        t.put(tool("ekran_parlakligi_ayarla",
            "Ekran parlaklığını yüzde olarak ayarlar ve otomatik parlaklığı kapatır.",
            JSONObject().put("seviye", intProp("0-100 arası yüzde"))))
        t.put(tool("otomatik_parlaklik",
            "Otomatik (uyarlanabilir) parlaklığı açar veya kapatır.",
            JSONObject().put("acik", boolProp())))
        t.put(tool("ekran_zaman_asimi",
            "Ekranın kaç saniye dokunulmazsa kapanacağını ayarlar (ör. 30, 60, 120, 300, 600).",
            JSONObject().put("saniye", intProp("saniye"))))
        t.put(tool("otomatik_dondurme",
            "Ekranın otomatik dönmesini açar veya kapatır.",
            JSONObject().put("acik", boolProp())))
        t.put(tool("ses_seviyesi",
            "Bir ses türünün seviyesini yüzde olarak ayarlar.",
            JSONObject()
                .put("tur", enumProp(listOf("medya", "zil", "bildirim", "alarm", "arama")))
                .put("seviye", intProp("0-100 arası yüzde"))))
        t.put(tool("zil_modu",
            "Zil modunu değiştirir: normal, titresim veya sessiz.",
            JSONObject().put("mod", enumProp(listOf("normal", "titresim", "sessiz")))))
        t.put(tool("rahatsiz_etme",
            "Rahatsız Etmeyin modunu açar veya kapatır.",
            JSONObject().put("acik", boolProp())))
        t.put(tool("fener",
            "Feneri (flaş ışığını) açar veya kapatır.",
            JSONObject().put("acik", boolProp())))
        t.put(tool("ayar_ekrani_ac",
            "Doğrudan değiştirilemeyen ayarlar için ilgili Android ayar ekranını veya hızlı panelini açar (Wi-Fi, Bluetooth, karanlık mod, yazı boyutu, duvar kağıdı vb.).",
            JSONObject().put("ekran", enumProp(SCREENS.keys.toList()))))
        return t
    }

    // ---------- Araçları çalıştırma ----------

    fun run(name: String, input: JSONObject): String = try {
        when (name) {
            "durum_oku" -> status()
            "ekran_parlakligi_ayarla" -> setBrightness(input.optInt("seviye", 50))
            "otomatik_parlaklik" -> setAutoBrightness(input.optBoolean("acik"))
            "ekran_zaman_asimi" -> setTimeout(input.optInt("saniye", 60))
            "otomatik_dondurme" -> setRotation(input.optBoolean("acik"))
            "ses_seviyesi" -> setVolume(input.optString("tur", "medya"), input.optInt("seviye", 50))
            "zil_modu" -> setRinger(input.optString("mod", "normal"))
            "rahatsiz_etme" -> setDnd(input.optBoolean("acik"))
            "fener" -> setTorch(input.optBoolean("acik"))
            "ayar_ekrani_ac" -> openScreen(input.optString("ekran", "genel"))
            else -> "Bilinmeyen araç: $name"
        }
    } catch (e: SecurityException) {
        "İzin hatası: ${e.message}. Üstteki 'İzinler' düğmesinden izinleri ver."
    } catch (e: Exception) {
        "Hata: ${e.javaClass.simpleName}: ${e.message}"
    }

    fun canWriteSettings(): Boolean = Settings.System.canWrite(ctx)
    fun hasDndAccess(): Boolean = notif.isNotificationPolicyAccessGranted

    fun requestWriteSettings() {
        val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        activity.onMain { activity.startActivity(i) }
    }

    fun requestDndAccess() {
        activity.onMain { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
    }

    private fun needWrite(): String? {
        if (canWriteSettings()) return null
        requestWriteSettings()
        return "İzin gerekli: açılan ekranda D-FON için 'Sistem ayarlarını değiştirme' iznini aç, geri dön ve komutu tekrar ver."
    }

    private fun needDnd(): String? {
        if (hasDndAccess()) return null
        requestDndAccess()
        return "İzin gerekli: açılan listede D-FON'a 'Rahatsız Etmeyin erişimi' ver, geri dön ve komutu tekrar ver."
    }

    private fun status(): String {
        val cr = ctx.contentResolver
        val o = JSONObject()
        o.put("parlaklik_yuzde", (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255.0).roundToInt())
        o.put("otomatik_parlaklik", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1)
        o.put("ekran_zaman_asimi_saniye", Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000) / 1000)
        o.put("otomatik_dondurme", Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1)
        val vols = JSONObject()
        for ((k, s) in STREAMS) {
            val max = audio.getStreamMaxVolume(s).coerceAtLeast(1)
            vols.put(k, (audio.getStreamVolume(s) * 100.0 / max).roundToInt())
        }
        o.put("ses_yuzde", vols)
        o.put("zil_modu", when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "sessiz"
            AudioManager.RINGER_MODE_VIBRATE -> "titresim"
            else -> "normal"
        })
        o.put("rahatsiz_etme", notif.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
        o.put("fener", torchOn)
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        o.put("pil_yuzde", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        o.put("sarj_oluyor", bm.isCharging)
        o.put("izin_sistem_ayarlari", canWriteSettings())
        o.put("izin_rahatsiz_etme", hasDndAccess())
        o.put("android_surumu", Build.VERSION.RELEASE)
        return o.toString()
    }

    private fun setBrightness(pct: Int): String {
        needWrite()?.let { return it }
        val p = pct.coerceIn(0, 100)
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (p * 255 / 100.0).roundToInt().coerceIn(1, 255))
        return "Parlaklık %$p yapıldı, otomatik parlaklık kapatıldı."
    }

    private fun setAutoBrightness(on: Boolean): String {
        needWrite()?.let { return it }
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        return "Otomatik parlaklık ${if (on) "açıldı" else "kapatıldı"}."
    }

    private fun setTimeout(sec: Int): String {
        needWrite()?.let { return it }
        val s = sec.coerceIn(10, 1800)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, s * 1000)
        return "Ekran zaman aşımı $s saniye yapıldı."
    }

    private fun setRotation(on: Boolean): String {
        needWrite()?.let { return it }
        Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
        return "Otomatik döndürme ${if (on) "açıldı" else "kapatıldı"}."
    }

    private fun setVolume(type: String, pct: Int): String {
        val stream = STREAMS[type] ?: return "Bilinmeyen ses türü: $type"
        val max = audio.getStreamMaxVolume(stream)
        val v = (pct.coerceIn(0, 100) * max / 100.0).roundToInt()
        audio.setStreamVolume(stream, v, AudioManager.FLAG_SHOW_UI)
        return "$type sesi %${pct.coerceIn(0, 100)} yapıldı ($v/$max)."
    }

    private fun setRinger(mode: String): String {
        val m = when (mode) {
            "sessiz" -> AudioManager.RINGER_MODE_SILENT
            "titresim" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        // Sessiz moda geçmek ve sessizden çıkmak için Rahatsız Etmeyin erişimi gerekir.
        if (m == AudioManager.RINGER_MODE_SILENT || audio.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            needDnd()?.let { return it }
        }
        audio.ringerMode = m
        return "Zil modu '$mode' yapıldı."
    }

    private fun setDnd(on: Boolean): String {
        needDnd()?.let { return it }
        notif.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return "Rahatsız Etmeyin ${if (on) "açıldı" else "kapatıldı"}."
    }

    private fun setTorch(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Bu telefonda flaş bulunamadı."
        cm.setTorchMode(id, on)
        torchOn = on
        return "Fener ${if (on) "açıldı" else "kapatıldı"}."
    }

    private fun openScreen(key: String): String {
        val actions = SCREENS[key] ?: SCREENS.getValue("genel")
        for (a in actions) {
            try {
                val i = Intent(a)
                if (a == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                    i.data = Uri.parse("package:${ctx.packageName}")
                }
                activity.onMain { activity.startActivity(i) }
                return "Ayar ekranını açtım."
            } catch (ignored: Exception) {
                // bu telefonda yok, sıradakini dene
            }
        }
        return "Bu ayar ekranı telefonunda açılamadı."
    }

    // ---------- yardımcılar ----------

    private fun tool(name: String, desc: String, props: JSONObject): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", props)
        val req = JSONArray()
        props.keys().forEach { req.put(it) }
        schema.put("required", req)
        return JSONObject().put("name", name).put("description", desc).put("input_schema", schema)
    }

    private fun intProp(d: String) = JSONObject().put("type", "integer").put("description", d)
    private fun boolProp() = JSONObject().put("type", "boolean")
    private fun enumProp(v: List<String>) = JSONObject().put("type", "string").put("enum", JSONArray(v))

    companion object {
        val STREAMS = linkedMapOf(
            "medya" to AudioManager.STREAM_MUSIC,
            "zil" to AudioManager.STREAM_RING,
            "bildirim" to AudioManager.STREAM_NOTIFICATION,
            "alarm" to AudioManager.STREAM_ALARM,
            "arama" to AudioManager.STREAM_VOICE_CALL,
        )

        /** Ekran adı -> denenecek Intent action'ları (ilk çalışan açılır). */
        val SCREENS: LinkedHashMap<String, List<String>> = linkedMapOf(
            "wifi" to listOf("android.settings.panel.action.WIFI", Settings.ACTION_WIFI_SETTINGS),
            "internet" to listOf("android.settings.panel.action.INTERNET_CONNECTIVITY", Settings.ACTION_WIRELESS_SETTINGS),
            "mobil_veri" to listOf(Settings.ACTION_DATA_ROAMING_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS),
            "bluetooth" to listOf(Settings.ACTION_BLUETOOTH_SETTINGS),
            "nfc" to listOf("android.settings.panel.action.NFC", Settings.ACTION_NFC_SETTINGS),
            "ucak_modu" to listOf(Settings.ACTION_AIRPLANE_MODE_SETTINGS),
            "hotspot" to listOf("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS),
            "ses_paneli" to listOf("android.settings.panel.action.VOLUME", Settings.ACTION_SOUND_SETTINGS),
            "ses_ayarlari" to listOf(Settings.ACTION_SOUND_SETTINGS),
            "ekran" to listOf(Settings.ACTION_DISPLAY_SETTINGS),
            "karanlik_mod" to listOf("android.settings.DARK_THEME_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS),
            "gece_isigi" to listOf("android.settings.NIGHT_DISPLAY_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS),
            "yazi_boyutu" to listOf("android.settings.TEXT_READING_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS),
            "duvar_kagidi" to listOf(Intent.ACTION_SET_WALLPAPER),
            "pil" to listOf(Settings.ACTION_BATTERY_SAVER_SETTINGS, Intent.ACTION_POWER_USAGE_SUMMARY),
            "konum" to listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            "bildirimler" to listOf("android.settings.NOTIFICATION_SETTINGS", Settings.ACTION_SETTINGS),
            "uygulamalar" to listOf(Settings.ACTION_APPLICATION_SETTINGS),
            "depolama" to listOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            "guvenlik" to listOf(Settings.ACTION_SECURITY_SETTINGS),
            "erisilebilirlik" to listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            "dil" to listOf(Settings.ACTION_LOCALE_SETTINGS),
            "tarih_saat" to listOf(Settings.ACTION_DATE_SETTINGS),
            "gelistirici" to listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            "bu_uygulama" to listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            "genel" to listOf(Settings.ACTION_SETTINGS),
        )
    }
}
