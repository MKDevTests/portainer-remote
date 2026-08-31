package dev.mkdev.portainerremote.data

import android.content.Context
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.data.store.FavoriteStack
import dev.mkdev.portainerremote.data.store.FavoritesStore
import dev.mkdev.portainerremote.data.store.ServerStore
import dev.mkdev.portainerremote.data.store.WidgetEntry
import dev.mkdev.portainerremote.data.store.WidgetSnapshot
import dev.mkdev.portainerremote.domain.StackView
import dev.mkdev.portainerremote.widget.StackWidget

/**
 * Tient a jour l'instantane que lisent le widget et la tuile.
 *
 * Le widget ne fait jamais d'appel reseau lui-meme : il n'a que quelques
 * millisecondes de vie. Tout passe par ici, appele depuis l'application ou
 * depuis un travail en arriere-plan.
 */
class WidgetSync(
    private val context: Context,
    private val serverStore: ServerStore,
    private val favorites: FavoritesStore,
    private val repository: PortainerRepository,
) {

    /** Retrouve un stack favori dans l'etat courant du serveur. */
    suspend fun resolve(favorite: FavoriteStack): StackView? {
        val server = serverStore.get(favorite.serverId) ?: return null
        val groups = (repository.load(server) as? ApiResult.Ok)?.value ?: return null
        return groups.flatMap { it.stacks }.firstOrNull { it.key == favorite.stackKey }
    }

    /**
     * Recharge l'etat de tous les favoris et redessine le widget.
     *
     * Un serveur injoignable ne vide pas l'instantane : on conserve le dernier
     * etat connu et on le marque perime, pour que le widget affiche une donnee
     * grisee plutot qu'un ecran vide.
     */
    suspend fun refresh() {
        val pinned = favorites.current()
        if (pinned.isEmpty()) {
            favorites.saveSnapshot(WidgetSnapshot(emptyList(), System.currentTimeMillis()))
            StackWidget.refreshAll(context)
            return
        }

        val previous = favorites.snapshot().entries.associateBy { it.serverId to it.stackKey }
        var anyFailure = false

        val entries = pinned.groupBy { it.serverId }.flatMap { (serverId, group) ->
            val server = serverStore.get(serverId)
            val stacks = if (server == null) {
                anyFailure = true
                emptyList()
            } else {
                when (val loaded = repository.load(server)) {
                    is ApiResult.Ok -> loaded.value.flatMap { it.stacks }
                    else -> {
                        anyFailure = true
                        emptyList()
                    }
                }
            }

            group.map { favorite ->
                val fresh = stacks.firstOrNull { it.key == favorite.stackKey }
                WidgetEntry(
                    serverId = favorite.serverId,
                    stackKey = favorite.stackKey,
                    name = favorite.name,
                    serverLabel = favorite.serverLabel,
                    state = fresh?.runState?.name
                        ?: previous[favorite.serverId to favorite.stackKey]?.state
                        ?: "UNKNOWN",
                )
            }
        }

        favorites.saveSnapshot(
            WidgetSnapshot(
                entries = entries,
                updatedAt = System.currentTimeMillis(),
                stale = anyFailure,
            ),
        )
        StackWidget.refreshAll(context)
    }
}
