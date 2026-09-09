package dev.mkdev.portainerremote.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.core.errorText
import dev.mkdev.portainerremote.data.HostRepository
import dev.mkdev.portainerremote.domain.HostJournal
import dev.mkdev.portainerremote.domain.LogEntry
import dev.mkdev.portainerremote.domain.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Combien de lignes on demande a l'hote. Assez pour chercher, pas de quoi noyer. */
enum class JournalDepth(val lines: Int, val label: String) {
    SHORT(50, "50"),
    MEDIUM(100, "100"),
    LONG(200, "200"),
}

data class JournalUi(
    val title: String = "",
    val loading: Boolean = true,
    val journal: HostJournal = HostJournal(),
    /** Null veut dire « tous les niveaux » : c'est l'etat par defaut. */
    val level: LogLevel? = null,
    val depth: JournalDepth = JournalDepth.MEDIUM,
    val query: String = "",
    val sessionsOpen: Boolean = true,
    val error: String? = null,
    /** Vrai quand l'hote ne publie pas de journal du tout. */
    val unsupported: Boolean = false,
) {
    /**
     * La recherche se fait ici, sur ce qui est deja charge.
     *
     * Le niveau, lui, part vers l'hote : ce sont deux choses differentes.
     * Chercher un mot dans cent lignes a du sens ; chercher les erreurs dans
     * cent lignes n'en a pas, puisqu'il en existe peut-etre mille autres.
     */
    val visible: List<LogEntry>
        get() {
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) return journal.entries
            return journal.entries.filter {
                it.message.lowercase().contains(needle) ||
                    it.who.lowercase().contains(needle) ||
                    it.category.lowercase().contains(needle)
            }
        }

    val filtering: Boolean get() = query.isNotBlank()

    /** Vrai quand l'hote annonce plus d'entrees qu'on n'en a demande. */
    val truncated: Boolean get() = journal.total > journal.entries.size
}

/**
 * L'ecran du journal.
 *
 * Il n'existe que pour les hotes qui en publient un. Aucune entree n'est
 * fabriquee ici : ce qui s'affiche vient de la machine, avec son horodatage et
 * ses mots, parce qu'un journal qu'on reformule n'est plus une preuve.
 */
class JournalViewModel(
    private val hostId: String,
    title: String,
    private val hosts: HostRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(JournalUi(title = title))
    val ui: StateFlow<JournalUi> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val state = _ui.value
            when (val result = hosts.journal(hostId, state.level, state.depth.lines)) {
                is ApiResult.Ok -> _ui.update {
                    it.copy(loading = false, journal = result.value, unsupported = false)
                }

                is ApiResult.Unsupported -> _ui.update {
                    it.copy(loading = false, unsupported = true)
                }

                else -> _ui.update { it.copy(loading = false, error = result.errorText()) }
            }
        }
    }

    fun setLevel(level: LogLevel?) {
        if (_ui.value.level == level) return
        _ui.update { it.copy(level = level) }
        refresh()
    }

    fun setDepth(depth: JournalDepth) {
        if (_ui.value.depth == depth) return
        _ui.update { it.copy(depth = depth) }
        refresh()
    }

    fun setQuery(query: String) = _ui.update { it.copy(query = query) }

    fun toggleSessions() = _ui.update { it.copy(sessionsOpen = !it.sessionsOpen) }
}
