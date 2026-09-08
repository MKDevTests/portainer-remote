package dev.mkdev.portainerremote.ui.host

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.core.errorText
import dev.mkdev.portainerremote.data.HostRepository
import dev.mkdev.portainerremote.data.store.ServerStore
import dev.mkdev.portainerremote.domain.Host
import dev.mkdev.portainerremote.domain.HostApp
import dev.mkdev.portainerremote.domain.HostAppAction
import dev.mkdev.portainerremote.domain.HostKind
import dev.mkdev.portainerremote.domain.HostPower
import dev.mkdev.portainerremote.domain.HostUsage
import dev.mkdev.portainerremote.domain.ScheduledOff
import dev.mkdev.portainerremote.domain.Server
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HostUi(
    val hosts: List<Host> = emptyList(),
    val selectedId: String = "",
    /** Les serveurs Portainer connus, pour proposer une adresse et un lien. */
    val servers: List<Server> = emptyList(),
    val loading: Boolean = false,
    val testing: Boolean = false,
    /** Vrai quand on saisit un nouveau NAS, meme s'il en existe deja. */
    val adding: Boolean = false,
    val apps: List<HostApp> = emptyList(),
    val usage: HostUsage = HostUsage(),
    val scheduledOff: ScheduledOff? = null,
    val busyApp: String? = null,
    val message: String? = null,
) {
    val selected: Host? get() = hosts.firstOrNull { it.id == selectedId }

    /** L'ecran de saisie s'affiche tant qu'aucun NAS n'est configure, ou sur demande. */
    val setup: Boolean get() = adding || selected == null

    val portainerApp: HostApp? get() = apps.firstOrNull { it.id == selected?.portainerAppId }
}

/**
 * L'ecran des NAS.
 *
 * Il ne connait aucun systeme en particulier : il demande un type a
 * l'utilisateur, le range, et laisse le depot choisir le client. Ajouter un
 * systeme ne devrait rien changer ici.
 */
class HostViewModel(
    private val hosts: HostRepository,
    private val serverStore: ServerStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(HostUi())
    val ui: StateFlow<HostUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { reload(selectFirst = true) }
    }

    private suspend fun reload(selectFirst: Boolean = false) {
        val known = hosts.current()
        val servers = serverStore.servers.first()
        _ui.update { state ->
            val selected = when {
                selectFirst || state.selectedId.isBlank() -> known.firstOrNull()?.id.orEmpty()
                known.none { it.id == state.selectedId } -> known.firstOrNull()?.id.orEmpty()
                else -> state.selectedId
            }
            state.copy(hosts = known, servers = servers, selectedId = selected)
        }
        if (_ui.value.selected != null) refresh()
    }

    /** L'adresse proposee : celle du Portainer lie, sinon celle du premier serveur connu. */
    fun suggestedUrl(serverId: String): String {
        val servers = _ui.value.servers
        val server = servers.firstOrNull { it.id == serverId } ?: servers.firstOrNull()
        return server?.baseUrl?.let(hosts::guessBaseUrl).orEmpty()
    }

    fun select(hostId: String) {
        _ui.update { it.copy(selectedId = hostId, adding = false, apps = emptyList()) }
        viewModelScope.launch { refresh() }
    }

    fun startAdding() = _ui.update { it.copy(adding = true, message = null) }

    fun cancelAdding() = _ui.update { it.copy(adding = false) }

    /**
     * Les trois lectures partent ensemble : elles sont independantes, et les
     * enchainer ferait attendre trois allers-retours la ou un seul suffit.
     */
    fun refresh() {
        val hostId = _ui.value.selectedId
        if (hostId.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val apps = async { hosts.apps(hostId) }
            val usage = async { hosts.usage(hostId) }
            val schedule = async { hosts.scheduledOff(hostId) }
            awaitAll(apps, usage, schedule)

            val appsResult = apps.await()
            _ui.update { state ->
                state.copy(
                    loading = false,
                    apps = (appsResult as? ApiResult.Ok)?.value ?: state.apps,
                    usage = (usage.await() as? ApiResult.Ok)?.value ?: HostUsage(),
                    // Une extinction programmee absente n'est pas une erreur :
                    // tous les systemes ne la proposent pas.
                    scheduledOff = (schedule.await() as? ApiResult.Ok)?.value,
                    message = if (appsResult is ApiResult.Ok) state.message else appsResult.errorText(),
                )
            }
        }
    }

    /** Verifie l'adresse et les identifiants avant d'enregistrer quoi que ce soit. */
    fun connect(
        kind: HostKind,
        label: String,
        baseUrl: String,
        username: String,
        password: String,
        serverId: String,
    ) {
        viewModelScope.launch {
            _ui.update { it.copy(testing = true, message = null) }
            val candidate = Host(
                kind = kind,
                label = label.trim(),
                baseUrl = baseUrl.trim(),
                username = username.trim(),
                serverId = serverId,
            )
            when (val result = hosts.test(candidate, password)) {
                is ApiResult.Ok -> {
                    val id = hosts.save(candidate, password)
                    _ui.update { it.copy(testing = false, adding = false, selectedId = id) }
                    reload()
                    _ui.update { it.copy(message = "NAS connecté.") }
                }

                is ApiResult.Unsupported -> _ui.update {
                    it.copy(
                        testing = false,
                        message = if (kind.supported) {
                            "Aucun ${kind.label} à cette adresse."
                        } else {
                            "${kind.label} n'est pas encore géré."
                        },
                    )
                }

                else -> _ui.update { it.copy(testing = false, message = result.errorText()) }
            }
        }
    }

    fun forget() {
        val hostId = _ui.value.selectedId
        if (hostId.isBlank()) return
        viewModelScope.launch {
            hosts.forget(hostId)
            _ui.update {
                it.copy(
                    selectedId = "",
                    apps = emptyList(),
                    usage = HostUsage(),
                    scheduledOff = null,
                    message = "NAS oublié.",
                )
            }
            reload(selectFirst = true)
        }
    }

    /** Designe l'application qui heberge Portainer : c'est elle qu'on relancera. */
    fun choosePortainerApp(appId: String) {
        val host = _ui.value.selected ?: return
        viewModelScope.launch {
            hosts.save(host.copy(portainerAppId = appId), null)
            reload()
        }
    }

    fun appAction(app: HostApp, action: HostAppAction) {
        val hostId = _ui.value.selectedId
        if (hostId.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(busyApp = app.id) }
            val result = hosts.setAppStatus(hostId, app.id, action)
            _ui.update {
                it.copy(
                    busyApp = null,
                    message = when (result) {
                        is ApiResult.Ok -> "${app.name} : ${action.value} demandé."
                        else -> result.errorText()
                    },
                )
            }
            if (result is ApiResult.Ok) refresh()
        }
    }

    /**
     * Extinction et redemarrage. La confirmation appartient a l'ecran : ici on
     * suppose qu'elle a eu lieu, et on ne prevoit pas d'annulation - une machine
     * qui s'eteint ne repondra plus pour dire qu'elle a compris.
     */
    fun power(action: HostPower) {
        val hostId = _ui.value.selectedId
        if (hostId.isBlank()) return
        viewModelScope.launch {
            val result = hosts.power(hostId, action)
            _ui.update {
                it.copy(
                    message = when (result) {
                        is ApiResult.Ok -> when (action) {
                            HostPower.OFF -> "Extinction demandée."
                            HostPower.RESTART -> "Redémarrage demandé."
                        }

                        else -> result.errorText()
                    },
                )
            }
        }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
}
