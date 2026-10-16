package dev.po4yka.chur.android

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A one-way lock action. It never opens an activity or creates a host. */
class ChurLockTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        showAction()
    }

    override fun onClick() {
        super.onClick()
        ChurHost.lockExistingFromSystemControl()
    }

    private fun showAction() {
        val tile = qsTile ?: return
        val action = getString(R.string.quick_settings_lock_label)
        tile.label = action
        tile.contentDescription = action
        tile.icon = Icon.createWithResource(this, R.drawable.ic_lock_tile)
        // INACTIVE means an available action, not a disclosure of session state.
        tile.state = Tile.STATE_INACTIVE
        tile.subtitle = null
        if (Build.VERSION.SDK_INT >= 30) tile.stateDescription = null
        tile.updateTile()
    }
}
