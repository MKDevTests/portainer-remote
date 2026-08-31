package dev.mkdev.portainerremote.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.core.errorText
import dev.mkdev.portainerremote.data.PortainerRepository
import dev.mkdev.portainerremote.data.WidgetSync
import dev.mkdev.portainerremote.data.store.FavoritesStore
import dev.mkdev.portainerremote.data.net.ReleaseInfo
import dev.mkdev.portainerremote.data.net.UpdateChecker
import dev.mkdev.portainerremote.data.store.ServerStore
import dev.mkdev.portainerremote.domain.Server
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ServersViewModel(
    private val store: ServerStore,
    private val repository: PortainerRepository,
    private val favoritesStore: FavoritesStore,
    private val widgetSync: WidgetSync,
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    val servers: StateFlow<List<Server>> = store.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _update = MutableStateFlow<ReleaseInfo?>(null)

    /** Non nul seulement si une release plus recente que la version installee existe. */
    val update: StateFlow<ReleaseInfo?> = _update.asStateFlow()

    init {
        // Une fois par cycle de vie du ViewModel. Un echec reseau est silencieux :
        // rater une mise a jour ne merite pas un message d'erreur.
        viewModelScope.launch {
            _update.value = (updateChecker.check() as? ApiResult.Ok)?.value
        }
    }

    fun dismissUpdate() {
        _update.value = null
    }

    fun load(id: String, onLoaded: (Server?) -> Unit) {
        viewModelScope.launch { onLoaded(store.get(id)) }
    }

    fun save(server: Server, plainSecret: String?, onSaved: (String) -> Unit) {
        viewModelScope.launch {
            val id = store.upsert(server, plainSecret)
            repository.invalidate(id)
            onSaved(id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.invalidate(id)
            store.delete(id)
            // Sans ça, le widget garderait des favoris pointant vers un serveur
            // qui n'existe plus.
            favoritesStore.forgetServer(id)
            widgetSync.refresh()
        }
    }

    /**
     * Test de connexion. Interroge une route authentifiee : la route de version,
     * publique sur certaines instances, repondrait meme avec un jeton faux.
     */
    fun test(server: Server, plainSecret: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            when (val result = repository.probe(server, plainSecret)) {
                is ApiResult.Ok -> onResult(true, "Connexion et identifiants valides. ${result.value}")
                is ApiResult.Unsupported ->
                    onResult(false, "Cette instance n'expose pas la liste des environnements.")
                else -> onResult(false, result.errorText())
            }
        }
    }
}
