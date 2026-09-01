package dev.mkdev.portainerremote.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.core.errorText
import dev.mkdev.portainerremote.data.PortainerRepository
import dev.mkdev.portainerremote.data.store.ServerStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Fenetres proposees, comme Portainer : assez pour diagnostiquer sans tout tirer. */
enum class LogTail(val lines: Int, val label: String) {
    SHORT(100, "100"),
    MEDIUM(500, "500"),
    LONG(2000, "2000"),
}

data class LogsUi(
    val containerName: String = "",
    val lines: List<String> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val tail: LogTail = LogTail.MEDIUM,
    val timestamps: Boolean = true,
    val following: Boolean = false,
    val query: String = "",
    /** Vide veut dire « le conteneur n'a rien ecrit », ce qui n'est pas une erreur. */
    val empty: Boolean = false,
) {
    val visibleLines: List<String>
        get() {
            val needle = query.trim().lowercase()
            return if (needle.isEmpty()) lines
            else lines.filter { it.lowercase().contains(needle) }
        }

    val filtering: Boolean get() = query.isNotBlank()
}

class LogsViewModel(
    private val serverId: String,
    private val envId: Int,
    private val containerId: String,
    containerName: String,
    private val store: ServerStore,
    private val repository: PortainerRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(LogsUi(containerName = containerName))
    val ui: StateFlow<LogsUi> = _ui.asStateFlow()

    private var followJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            fetch()
        }
    }

    private suspend fun fetch() {
        val server = store.get(serverId)
        if (server == null) {
            _ui.update { it.copy(loading = false, error = "Serveur introuvable.") }
            return
        }

        val state = _ui.value
        when (
            val result = repository.logs(
                server = server,
                envId = envId,
                containerId = containerId,
                tail = state.tail.lines,
                timestamps = state.timestamps,
            )
        ) {
            is ApiResult.Ok -> {
                val text = result.value
                _ui.update {
                    it.copy(
                        // Docker termine chaque trame par un saut de ligne : sans
                        // le retirer, la liste finit par une ligne vide.
                        lines = text.split('\n').dropLastWhile { line -> line.isEmpty() },
                        loading = false,
                        error = null,
                        empty = text.isBlank(),
                    )
                }
            }
            ApiResult.Unsupported -> _ui.update {
                it.copy(
                    loading = false,
                    error = "Cet environnement n'expose pas les logs des conteneurs.",
                )
            }
            else -> _ui.update { it.copy(loading = false, error = result.errorText()) }
        }
    }

    /**
     * Suivi par sondage, et non par flux : la route de logs de Docker sait
     * diffuser en continu, mais une connexion maintenue ouverte a travers un VPN
     * mobile se coupe sans prevenir. Redemander toutes les trois secondes est
     * moins elegant et bien plus previsible.
     */
    fun toggleFollow() {
        val following = !_ui.value.following
        _ui.update { it.copy(following = following) }

        followJob?.cancel()
        followJob = if (!following) {
            null
        } else {
            viewModelScope.launch {
                while (isActive) {
                    delay(3_000)
                    if (!_ui.value.following) break
                    fetch()
                }
            }
        }
    }

    fun setTail(value: LogTail) {
        _ui.update { it.copy(tail = value) }
        load()
    }

    fun toggleTimestamps() {
        _ui.update { it.copy(timestamps = !it.timestamps) }
        load()
    }

    fun setQuery(value: String) = _ui.update { it.copy(query = value) }

    /** Le texte affiche, filtre compris : c'est ce que l'utilisateur voit. */
    fun copyText(): String = _ui.value.visibleLines.joinToString("\n")

    override fun onCleared() {
        followJob?.cancel()
        super.onCleared()
    }
}
