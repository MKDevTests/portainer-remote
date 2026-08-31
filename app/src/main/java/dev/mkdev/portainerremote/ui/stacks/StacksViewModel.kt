package dev.mkdev.portainerremote.ui.stacks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.core.errorText
import dev.mkdev.portainerremote.data.PortainerRepository
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
)

class StacksViewModel(
    private val serverId: String,
    private val store: ServerStore,
    private val repository: PortainerRepository,
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
            when (val result = repository.load(server)) {
                is ApiResult.Ok -> _ui.update {
                    it.copy(server = server, groups = result.value, loading = false, error = null)
                }
                else -> _ui.update {
                    it.copy(server = server, loading = false, error = result.errorText())
                }
            }
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
