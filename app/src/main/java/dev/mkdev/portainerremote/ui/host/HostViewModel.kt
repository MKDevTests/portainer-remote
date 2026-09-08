package dev.mkdev.portainerremote.ui.host

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.core.errorText
import dev.mkdev.portainerremote.data.HostRepository
import dev.mkdev.portainerremote.data.store.ServerStore
import dev.mkdev.portainerremote.domain.HostApp
import dev.mkdev.portainerremote.domain.HostAppAction
import dev.mkdev.portainerremote.domain.HostConfig
import dev.mkdev.portainerremote.domain.HostPower
import dev.mkdev.portainerremote.domain.HostUsage
import dev.mkdev.portainerremote.domain.ScheduledOff
import dev.mkdev.portainerremote.domain.Server
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HostUi(
    val server: Server? = null,
    val config: HostConfig = HostConfig(),
    val loading: Boolean = false,
    val testing: Boolean = false,
    /** L'adresse proposee tant que rien n'est configure. */
    val suggestedUrl: String = "",
    val apps: List<HostApp> = emptyList(),
    val usage: HostUsage = HostUsage(),
    val scheduledOff: ScheduledOff? = null,
    val busyApp: String? = null,
    val message: String? = null,
) {
    val configured: Boolean get() = config.configured
    val portainerApp: HostApp? get() = apps.firstOrNull { it.id == config.portainerAppId }
}

/**
 * L'ecran de l'hote : la machine sous Portainer.
 *
 * Rien n'est charge tant que l'hote n'est pas configure. C'est la difference
 * entre une fonction optionnelle et une fonction desactivee : ici, l'ecran ne
 * parle de ZimaOS que si l'utilisateur lui en a donne l'adresse.
 */
class HostViewModel(
    private val serverId: String,
    private val serverStore: ServerStore,
    private val hosts: HostRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(HostUi())
    val ui: StateFlow<HostUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val server = serverStore.get(serverId)
            val config = hosts.config(serverId)
            _ui.update {
                it.copy(
                    server = server,
                    config = config,
                    suggestedUrl = config.baseUrl.ifBlank {
                        server?.baseUrl?.let(hosts::guessBaseUrl).orEmpty()
                    },
                )
            }
            if (config.configured) refresh()
        }
    }

    /**
     * Les trois lectures partent ensemble : elles sont independantes, et les
     * enchainer ferait attendre trois allers-retours la ou un seul suffit.
     */
    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val apps = async { hosts.apps(serverId) }
            val usage = async { hosts.usage(serverId) }
            val schedule = async { hosts.scheduledOff(serverId) }
            awaitAll(apps, usage, schedule)

            val appsResult = apps.await()
            _ui.update { state ->
                state.copy(
                    loading = false,
                    apps = (appsResult as? ApiResult.Ok)?.value ?: state.apps,
                    usage = (usage.await() as? ApiResult.Ok)?.value ?: HostUsage(),
                    // Une extinction programmee absente n'est pas une erreur :
                    // CasaOS ne connait pas cette route, seul ZimaOS la sert.
                    scheduledOff = (schedule.await() as? ApiResult.Ok)?.value,
                    message = if (appsResult is ApiResult.Ok) state.message else appsResult.errorText(),
                )
            }
        }
    }

    /** Verifie l'adresse et les identifiants avant d'enregistrer quoi que ce soit. */
    fun connect(baseUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _ui.update { it.copy(testing = true, message = null) }
            when (val result = hosts.test(baseUrl.trim(), username.trim(), password)) {
                is ApiResult.Ok -> {
                    hosts.save(
                        serverId,
                        HostConfig(baseUrl.trim(), username.trim(), _ui.value.config.portainerAppId),
                        password,
                    )
                    _ui.update {
                        it.copy(
                            testing = false,
                            config = hosts.config(serverId),
                            message = "Hôte connecté.",
                        )
                    }
                    refresh()
                }

                is ApiResult.Unsupported -> _ui.update {
                    it.copy(
                        testing = false,
                        message = "Aucun ZimaOS ni CasaOS à cette adresse.",
                    )
                }

                else -> _ui.update { it.copy(testing = false, message = result.errorText()) }
            }
        }
    }

    fun forget() {
        viewModelScope.launch {
            hosts.forget(serverId)
            _ui.update {
                HostUi(
                    server = it.server,
                    suggestedUrl = it.server?.baseUrl?.let(hosts::guessBaseUrl).orEmpty(),
                    message = "Hôte oublié.",
                )
            }
        }
    }

    /** Designe l'application qui heberge Portainer : c'est elle qu'on relancera. */
    fun choosePortainerApp(appId: String) {
        viewModelScope.launch {
            val config = _ui.value.config.copy(portainerAppId = appId)
            hosts.save(serverId, config, null)
            _ui.update { it.copy(config = config) }
        }
    }

    fun appAction(app: HostApp, action: HostAppAction) {
        viewModelScope.launch {
            _ui.update { it.copy(busyApp = app.id) }
            val result = hosts.setAppStatus(serverId, app.id, action)
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
        viewModelScope.launch {
            val result = hosts.power(serverId, action)
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
