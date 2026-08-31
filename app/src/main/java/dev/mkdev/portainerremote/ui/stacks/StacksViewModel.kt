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
import dev.mkdev.portainerremote.domain.StackView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StacksUi(
    val server: Server? = null,
    val groups: List<EnvGroup> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val busy: Set<String> = emptySet(),
    val message: String? = null,
    /** Clés des stacks épinglés, pour le widget et la tuile. */
    val favorites: Set<String> = emptySet(),
)

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
