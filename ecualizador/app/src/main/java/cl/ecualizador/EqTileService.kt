package cl.ecualizador

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Mosaico de Ajustes rápidos para activar o desactivar el ecualizador sin abrir la app. */
class EqTileService : TileService() {
    companion object {
        fun refresh(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, EqTileService::class.java)) }
        }
    }

    override fun onStartListening() = update()

    override fun onClick() {
        val engine = EqEngine.get(this)
        engine.setEnabled(!engine.state.value.enabled)
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val s = EqEngine.get(this).state.value
        tile.state = if (s.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Ecualizador"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = if (s.enabled) s.preset ?: "Personalizado" else "Apagado"
        }
        tile.updateTile()
    }
}
