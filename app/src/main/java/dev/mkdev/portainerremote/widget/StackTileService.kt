package dev.mkdev.portainerremote.widget

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.mkdev.portainerremote.PortainerRemoteApp
import dev.mkdev.portainerremote.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tuile Quick Settings : le chemin le plus court vers un stack.
 *
 * Elle agit sur le premier favori. Un panneau de reglages n'apporterait rien
 * ici : une tuile n'a qu'un etat et qu'un tap, l'ordre des favoris suffit a
 * designer sa cible.
 */
class StackTileService : TileService() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        refreshTile()
    }

    override fun onStopListening() {
        scope.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        scope.launch {
            val container = (applicationContext as PortainerRemoteApp).container
            val target = withContext(Dispatchers.IO) { container.favoritesStore.current().firstOrNull() }
            if (target == null) {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.updateTile()
                return@launch
            }

            // Retour immediat : le tap doit se voir avant meme que le reseau reponde.
            tile.state = Tile.STATE_UNAVAILABLE
            tile.subtitle = "en cours…"
            tile.updateTile()

            StackActionWorker.enqueueAction(applicationContext, target.serverId, target.stackKey)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        scope.launch {
            val container = (applicationContext as PortainerRemoteApp).container
            val entry = withContext(Dispatchers.IO) {
                val first = container.favoritesStore.current().firstOrNull()
                first to container.favoritesStore.snapshot().entries.firstOrNull {
                    it.serverId == first?.serverId && it.stackKey == first.stackKey
                }
            }

            val favorite = entry.first
            val state = entry.second?.state

            tile.icon = Icon.createWithResource(applicationContext, R.drawable.ic_widget_play)
            if (favorite == null) {
                tile.label = "Portainer"
                tile.subtitle = "aucun favori"
                tile.state = Tile.STATE_UNAVAILABLE
            } else {
                // L'instantane porte deja le nom personnalise ; le favori ne
                // connait que le nom officiel, qui sert de repli.
                tile.label = entry.second?.name?.takeIf { it.isNotBlank() } ?: favorite.name
                tile.subtitle = when (state) {
                    "RUNNING" -> "en marche · relancer"
                    "PARTIAL" -> "partiel · relancer"
                    "STOPPED" -> "arrêté · démarrer"
                    else -> "état inconnu"
                }
                tile.state = if (state == "RUNNING") Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            }
            tile.updateTile()
        }
    }
}
