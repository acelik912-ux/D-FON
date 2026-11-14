package com.acelik.asistan

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Bildirim panelindeki (Wi-Fi'ın yanındaki) D-FON kutucukları. */
abstract class BaseTile : TileService() {

    protected fun openApp(extra: String?) {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (extra != null) i.putExtra(extra, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 30, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    protected fun show(active: Boolean, sub: String) {
        val t = qsTile ?: return
        t.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = sub
        t.updateTile()
    }
}

/** "Bir iki üç fon" dinlemesini aç/kapat. */
class WakeTile : BaseTile() {
    override fun onStartListening() {
        val on = WakeService.isEnabled(this)
        show(on, if (on) "Açık" else "Kapalı")
    }

    override fun onClick() {
        if (WakeService.isEnabled(this)) {
            WakeService.setEnabled(this, false)
            WakeService.stop(this)
            show(false, "Kapalı")
        } else {
            // Mikrofonlu servis sadece uygulama açıkken başlatılabiliyor: D-FON kısaca açılıp başlatır
            openApp(MainActivity.EXTRA_TILE_WAKE)
        }
    }
}

/** Ezanda sessize almayı aç/kapat. */
class PrayerTile : BaseTile() {
    override fun onStartListening() {
        val on = Prayer.isEnabled(this)
        val next = Prayer.nextTime(this)
        show(on, next?.let { "${it.first} ${Prayer.fmt(it.second)}" } ?: if (on) "Açık" else "Kapalı")
    }

    override fun onClick() {
        if (!Prayer.hasLocation(this)) {
            openApp(null)
            return
        }
        Prayer.setEnabled(this, !Prayer.isEnabled(this))
        onStartListening()
    }
}

/** D-FON'u aç. */
class OpenTile : BaseTile() {
    override fun onStartListening() = show(false, "Aç")
    override fun onClick() = openApp(null)
}
