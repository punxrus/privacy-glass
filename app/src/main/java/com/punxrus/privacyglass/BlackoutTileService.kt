package com.punxrus.privacyglass

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile. Works while the phone is black: pull down the shade while looking at the glasses. */
class BlackoutTileService : TileService() {

    private val listener: () -> Unit = { refresh() }

    override fun onStartListening() {
        PrivacyGlassService.listeners += listener
        refresh()
    }

    override fun onStopListening() {
        PrivacyGlassService.listeners -= listener
    }

    override fun onClick() {
        val svc = PrivacyGlassService.instance
        if (svc == null) {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
            return
        }
        svc.toggle()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val svc = PrivacyGlassService.instance
        tile.state = when {
            svc == null -> Tile.STATE_INACTIVE
            svc.active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            svc == null -> "Set up in app"
            svc.active -> "Phone dark"
            else -> "Off"
        }
        tile.updateTile()
    }
}
