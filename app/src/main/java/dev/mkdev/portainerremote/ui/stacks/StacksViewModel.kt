package dev.mkdev.portainerremote.ui.stacks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.core.errorText
import dev.mkdev.portainerremote.data.PortainerRepository
import dev.mkdev.portainerremote.data.WidgetSync
import dev.mkdev.portainerremote.data.store.FavoriteStack
import dev.mkdev.portainerremote.data.store.FavoritesStore
import dev.mkdev.portainerremote.data.store.ServerStore
import dev.mkdev.portainerremote.domain.ContainerView
import dev.mkdev.portainerremote.domain.EnvGroup
import dev.mkdev.portainerremote.domain.Outcome
import dev.mkdev.portainerremote.domain.RunState
import dev.mkdev.portainerremote.domain.Server
import dev.mkdev.portainerremote.domain.StackAction
import dev.mkdev.portainerremote.domain.StackFilter
import dev.mkdev.portainerremote.domain.StackSort
import dev.mkdev.portainerremote.domain.StackView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StacksTab(val label: String) {
    STACKS("Stacks"),
    CONTAINERS("Conteneurs"),
}

/**
 * Un conteneur sorti de son stack, pour l'onglet a plat.
 *
 * Il garde une reference a son stack : les routes d'action de Portainer ont
 * besoin de l'environnement, et l'affichage a besoin de dire d'ou vient le
 * conteneur — sans quoi deux `web` de deux stacks differents sont
 * indiscernables.
 */
data class ContainerEntry(
    val envId: Int,
    val envName: String,
    val stack: StackView,
    val container: ContainerView,
)

data class StacksUi(
    val server: Server? = null,
    val groups: List<EnvGroup> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val busy: Set<String> = emptySet(),
    val message: String? = null,
    /** Clés des stacks épinglés, pour le widget et la tuile. */
    val favorites: Set<String> = emptySet(),
    val query: String = "",
    val filter: StackFilter = StackFilter.ALL,
    val sort: StackSort = StackSort.NAME_ASC,
    val tab: StacksTab = StacksTab.STACKS,
) {
    /**
     * Recherche, filtre et tri appliqués à l'affichage seulement : les données
     * brutes restent intactes, si bien qu'effacer la recherche ne coûte pas un
     * appel réseau.
     */
    val visibleGroups: List<EnvGroup>
        get() {
            val needle = query.trim().lowercase()
            val comparator = when (sort) {
                StackSort.NAME_ASC -> compareBy<StackView> { it.name.lowercase() }
                StackSort.NAME_DESC -> compareByDescending<StackView> { it.name.lowercase() }
                // En marche d'abord, puis partiels, puis arrêtés.
                StackSort.STATE -> compareBy<StackView> { it.runState.ordinal }
                    .thenBy { it.name.lowercase() }
            }

            return groups.map { group ->
                group.copy(
                    stacks = group.stacks
                        .filter { stack ->
                            val matchesQuery = needle.isEmpty() ||
                                stack.name.lowercase().contains(needle) ||
                                // La recherche porte aussi sur les conteneurs :
                                // on cherche souvent un service, pas un stack.
                                stack.containers.any { it.name.lowercase().contains(needle) }

                            val matchesFilter = when (filter) {
                                StackFilter.ALL -> true
                                StackFilter.RUNNING -> stack.runState != RunState.STOPPED
                                StackFilter.STOPPED -> stack.runState == RunState.STOPPED
                            }

                            matchesQuery && matchesFilter
                        }
                        .sortedWith(comparator),
                )
            }
        }

    /**
     * Les memes conteneurs, sortis de leurs stacks. La recherche porte ici sur
     * l'image en plus du nom : c'est souvent par elle qu'on retrouve un
     * conteneur dont on ne sait plus dans quel stack il vit.
     */
    val visibleContainers: List<ContainerEntry>
        get() {
            val needle = query.trim().lowercase()
            val comparator = when (sort) {
                StackSort.NAME_ASC -> compareBy<ContainerEntry> { it.container.name.lowercase() }
                StackSort.NAME_DESC ->
                    compareByDescending<ContainerEntry> { it.container.name.lowercase() }
                StackSort.STATE -> compareBy<ContainerEntry> { if (it.container.running) 0 else 1 }
                    .thenBy { it.container.name.lowercase() }
            }

            return groups
                .flatMap { group ->
                    group.stacks.flatMap { stack ->
                        stack.containers.map { ContainerEntry(group.envId, group.envName, stack, it) }
                    }
                }
                .filter { entry ->
                    val matchesQuery = needle.isEmpty() ||
                        entry.container.name.lowercase().contains(needle) ||
                        entry.container.image.lowercase().contains(needle) ||
                        entry.stack.name.lowercase().contains(needle)

                    val matchesFilter = when (filter) {
                        StackFilter.ALL -> true
                        StackFilter.RUNNING -> entry.container.running
                        StackFilter.STOPPED -> !entry.container.running
                    }

                    matchesQuery && matchesFilter
                }
                .sortedWith(comparator)
        }

    val filtering: Boolean get() = query.isNotBlank() || filter != StackFilter.ALL

    val visibleCount: Int
        get() = when (tab) {
            StacksTab.STACKS -> visibleGroups.sumOf { it.stacks.size }
            StacksTab.CONTAINERS -> visibleContainers.size
        }
}

class StacksViewModel(
    private val serverId: String,
    private val store: ServerStore,
    private val repository: PortainerRepository,
    private val favoritesStore: FavoritesStore,
    private val widgetSync: WidgetSync,
) : ViewModel() {

    private val _ui = MutableStateFlow(StacksUi())
    val ui: StateFlow<StacksUi> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val server = store.get(serverId)
            if (server == null) {
                _ui.update { it.copy(loading = false, error = "Serveur introuvable.") }
                return@launch
            }
            val pinned = favoritesStore.current()
                .filter { it.serverId == serverId }
                .map { it.stackKey }
                .toSet()

            when (val result = repository.load(server)) {
                is ApiResult.Ok -> _ui.update {
                    it.copy(
                        server = server,
                        groups = result.value,
                        loading = false,
                        error = null,
                        favorites = pinned,
                    )
                }
                else -> _ui.update {
                    it.copy(
                        server = server,
                        loading = false,
                        error = result.errorText(),
                        favorites = pinned,
                    )
                }
            }
        }
    }

    fun toggleFavorite(stack: StackView) {
        val server = _ui.value.server ?: return
        viewModelScope.launch {
            val pinned = favoritesStore.toggle(
                FavoriteStack(
                    serverId = server.id,
                    serverLabel = server.label,
                    stackKey = stack.key,
                    name = stack.name,
                    envId = stack.envId,
                ),
            )
            _ui.update {
                it.copy(
                    favorites = if (pinned) it.favorites + stack.key else it.favorites - stack.key,
                    message = if (pinned) {
                        "${stack.name} épinglé au widget."
                    } else {
                        "${stack.name} retiré du widget."
                    },
                )
            }
            widgetSync.refresh()
        }
    }

    fun act(stack: StackView, action: StackAction) = run(stack.key) { server ->
        repository.act(server, stack, action)
    }

    fun actOnContainer(stack: StackView, container: ContainerView, action: StackAction) =
        run(container.id) { server ->
            repository.actOnContainer(server, stack, container, action)
        }

    private fun run(busyKey: String, block: suspend (Server) -> Outcome) {
        val server = _ui.value.server ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = it.busy + busyKey) }
            val outcome = block(server)
            _ui.update { it.copy(busy = it.busy - busyKey, message = outcome.toMessage()) }
            refresh()
            // Le widget doit refleter ce que l'utilisateur vient de faire dans l'app,
            // sinon il affiche un etat perime des qu'on revient a l'ecran d'accueil.
            widgetSync.refresh()
        }
    }

    fun setTab(value: StacksTab) = _ui.update { it.copy(tab = value) }

    fun setQuery(value: String) = _ui.update { it.copy(query = value) }

    fun setFilter(value: StackFilter) = _ui.update { it.copy(filter = value) }

    fun setSort(value: StackSort) = _ui.update { it.copy(sort = value) }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    private fun Outcome.toMessage(): String = when (this) {
        is Outcome.Done -> when (state) {
            RunState.RUNNING -> "En marche."
            RunState.STOPPED -> "Arrêté."
            else -> "Terminé."
        }
        // Le redeploiement peut durer : les images se telechargent avant que les
        // conteneurs ne reviennent, donc l'etat relu peut encore etre transitoire.
        // L'appel est passe mais l'etat ne suit pas : c'est le cas qu'il faut dire,
        // pas masquer derriere un code HTTP 200.
        is Outcome.Mismatch -> when (state) {
            RunState.PARTIAL -> "Partiellement appliqué : certains conteneurs n'ont pas suivi."
            RunState.RUNNING -> "Toujours en marche après la demande d'arrêt."
            RunState.STOPPED -> "Toujours arrêté après la demande de démarrage."
            RunState.UNKNOWN -> "État indéterminé après l'action."
        }
        is Outcome.Failed -> reason
    }
}
