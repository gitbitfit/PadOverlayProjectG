package com.example.padoverlay

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to start / stop the overlay. */
class OverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render(OverlayService.running)
    }

    override fun onClick() {
        super.onClick()
        if (OverlayService.running) {
            OverlayControl.stop(this)
            render(false)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            openApp() // overlay permission still missing: open the app to set it up
            return
        }
        OverlayControl.start(this)
        render(true)
    }

    private fun render(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Pad overlay"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
