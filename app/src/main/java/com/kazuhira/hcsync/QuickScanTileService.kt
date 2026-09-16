package com.kazuhira.hcsync

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class QuickScanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.quick_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.quick_tile_subtitle)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_QUICK_SCAN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_QUICK_SCAN, true)
        }

        val launchAction = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }

        if (isLocked) {
            unlockAndRun {
                launchAction()
            }
        } else {
            launchAction()
        }
    }
}
