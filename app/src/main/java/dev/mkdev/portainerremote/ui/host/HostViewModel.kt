package dev.mkdev.portainerremote.ui.host

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.core.errorText
import dev.mkdev.portainerremote.data.HostRepository
import dev.mkdev.portainerremote.data.store.ServerStore
import dev.mkdev.portainerremote.domain.DiskSleep
import dev.mkdev.portainerremote.domain.Host
import dev.mkdev.portainerremote.domain.HostApp
import dev.mkdev.portainerremote.domain.HostAppAction
import dev.mkdev.portainerremote.domain.HostKind
import dev.mkdev.portainerremote.domain.HostDisk
import dev.mkdev.portainerremote.domain.HostMachine
import dev.mkdev.portainerremote.domain.HostPower
import dev.mkdev.portainerremote.domain.HostUpdate
import dev.mkdev.portainerremote.domain.HostUsage
import dev.mkdev.portainerremote.domain.NetRate
import dev.mkdev.portainerremote.domain.ScheduledOff
import dev.mkdev.portainerremote.domain.SignIn
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
    /**
     * L'identifiant du NAS en cours de modification, vide si on en ajoute un.
     *
     * C'est lui qui fait la difference entre creer et modifier : enregistrer
     * sous le meme identifiant conserve le mot de passe scelle, le jeton
     * d'appareil et le Portainer associe.
     */
    val editingId: String = "",
    val apps: List<HostApp> = emptyList(),
    /** Identifiants des applications pour lesquelles l'hote annonce une mise a jour. */
    val upgradable: Set<String> = emptySet(),
    val usage: HostUsage = HostUsage(),
    val machine: HostMachine = HostMachine(),
    val disks: List<HostDisk> = emptyList(),
    val systemUpdate: HostUpdate = HostUpdate(),
    val diskSleep: DiskSleep? = null,
    /** Vide tant qu'une seule mesure existe : un debit demande deux points. */
    val rates: List<NetRate> = emptyList(),
    val scheduledOff: ScheduledOff? = null,
    val savingSchedule: Boolean = false,
    /**
     * Vrai quand l'hote a reclame un code de verification. La carte de saisie
     * fait alors apparaitre son champ, plutot que d'echouer sans expliquer.
     */
    val otpNeeded: Boolean = false,
    /**
     * Vrai quand l'hote deja enregistre reclame un code. La carte de saisie
     * apparait alors sur son ecran : le seul endroit ou l'on puisse repondre.
     */
    val otpAsked: Boolean = false,
    val sendingOtp: Boolean = false,
    val busyApp: String? = null,
    val message: String? = null,
) {
    val selected: Host? get() = hosts.firstOrNull { it.id == selectedId }

    /** L'ecran de saisie s'affiche tant qu'aucun NAS n'est configure, ou sur demande. */
    val setup: Boolean get() = adding || selected == null

    val portainerApp: HostApp? get() = apps.firstOrNull { selected?.isPortainerApp(it.id) == true }

    /** Le NAS que la carte de saisie doit pre-remplir, s'il y en a un. */
    val edited: Host? get() = hosts.firstOrNull { it.id == editingId }
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

    /**
     * Change de NAS, et oublie tout ce qui appartenait au precedent.
     *
     * Garder la derniere valeur connue a du sens pendant un rafraichissement du
     * meme hote : ca evite de faire clignoter l'ecran. Ca n'en a aucun en
     * changeant de machine - on afficherait les disques de l'une sous le nom de
     * l'autre, le temps que les lectures reviennent.
     */
    fun select(hostId: String) {
        _ui.update {
            it.copy(
                selectedId = hostId,
                adding = false,
                apps = emptyList(),
                upgradable = emptySet(),
                usage = HostUsage(),
                machine = HostMachine(),
                disks = emptyList(),
                systemUpdate = HostUpdate(),
                diskSleep = null,
                rates = emptyList(),
                scheduledOff = null,
                busyApp = null,
            )
        }
        viewModelScope.launch { refresh() }
    }

    fun startAdding() =
        _ui.update { it.copy(adding = true, editingId = "", otpNeeded = false, message = null) }

    fun startEditing() = _ui.update {
        it.copy(adding = true, editingId = it.selectedId, otpNeeded = false, message = null)
    }

    fun cancelAdding() = _ui.update { it.copy(adding = false, editingId = "") }

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
            val upgradable = async { hosts.upgradable(hostId) }
            val machine = async { hosts.machine(hostId) }
            val sleep = async { hosts.diskSleep(hostId) }
            val disks = async { hosts.disks(hostId) }
            val systemUpdate = async { hosts.systemUpdate(hostId) }
            awaitAll(apps, usage, schedule, upgradable, machine, sleep, disks, systemUpdate)

            val appsResult = apps.await()
            val fresh = (usage.await() as? ApiResult.Ok)?.value ?: HostUsage()
            _ui.update { state ->
                state.copy(
                    loading = false,
                    apps = (appsResult as? ApiResult.Ok)?.value ?: state.apps,
                    // Ne pas savoir qu'une mise a jour existe n'empeche rien :
                    // un echec ici laisse simplement la liste vide.
                    upgradable = (upgradable.await() as? ApiResult.Ok)?.value ?: emptySet(),
                    // L'identite de la machine ne change pas : on garde la
                    // derniere connue plutot que de la faire clignoter.
                    machine = (machine.await() as? ApiResult.Ok)?.value ?: state.machine,
                    diskSleep = (sleep.await() as? ApiResult.Ok)?.value ?: state.diskSleep,
                    // Un disque ne disparait pas parce qu'une lecture a echoue :
                    // on garde la derniere liste connue plutot qu'une carte vide.
                    disks = (disks.await() as? ApiResult.Ok)?.value?.takeIf { it.isNotEmpty() }
                        ?: state.disks,
                    // Une version ne change pas d'une minute a l'autre : garder
                    // la derniere connue evite que la carte clignote.
                    systemUpdate = (systemUpdate.await() as? ApiResult.Ok)?.value
                        ?: state.systemUpdate,
                    rates = ratesBetween(state.usage, fresh),
                    usage = fresh,
                    // Une extinction programmee absente n'est pas une erreur :
                    // tous les systemes ne la proposent pas.
                    scheduledOff = (schedule.await() as? ApiResult.Ok)?.value,
                    // Le message vient de la lecture qui compte pour cet
                    // hote : sur un systeme sans applications, s'en tenir a
                    // « apps » reviendrait a ne jamais rien dire.
                    otpAsked = needsOtp(appsResult, usage.await()),
                    message = messageFor(state, appsResult, usage.await()),
                )
            }
            realignPortainerApp()
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
        otp: String = "",
    ) {
        viewModelScope.launch {
            _ui.update { it.copy(testing = true, message = null) }
            val edited = _ui.value.edited
            val candidate = Host(
                // Modifier, c'est reecrire la meme entree : un identifiant neuf
                // en creerait une seconde et laisserait l'ancienne derriere.
                id = edited?.id.orEmpty(),
                kind = kind,
                label = label.trim(),
                baseUrl = baseUrl.trim(),
                username = username.trim(),
                serverId = serverId,
                portainerAppId = edited?.portainerAppId.orEmpty(),
            )
            // Un champ laisse vide sur une modification veut dire « garde le
            // mot de passe actuel ». Il faut quand meme un mot de passe pour
            // tester l'adresse : on reprend celui qui est deja scelle.
            val secret = password.ifEmpty {
                edited?.let { hosts.storedPassword(it.id) }.orEmpty()
            }
            val result = hosts.test(candidate, secret, otp.trim().ifBlank { null })
            when (val outcome = result.outcome) {
                is ApiResult.Ok -> when (outcome.value) {
                    SignIn.OK -> {
                        val id = hosts.save(candidate, password.ifEmpty { null })
                        // Le jeton d'appareil se range apres l'enregistrement :
                        // avant, l'hote n'a pas encore d'identifiant sous
                        // lequel le ranger.
                        hosts.rememberDevice(id, result.deviceId)
                        _ui.update {
                            it.copy(
                                testing = false,
                                adding = false,
                                editingId = "",
                                otpNeeded = false,
                                selectedId = id,
                            )
                        }
                        reload()
                        _ui.update {
                            it.copy(
                                message = when {
                                    edited != null -> "NAS mis à jour."
                                    result.deviceId != null ->
                                        "NAS connecté. Cet appareil est reconnu : " +
                                            "plus de code à saisir."

                                    else -> "NAS connecté."
                                },
                            )
                        }
                    }

                    SignIn.OTP_REQUIRED -> _ui.update {
                        it.copy(
                            testing = false,
                            otpNeeded = true,
                            message = "Ce NAS demande un code de vérification.",
                        )
                    }

                    SignIn.OTP_REFUSED -> _ui.update {
                        it.copy(
                            testing = false,
                            otpNeeded = true,
                            message = "Code refusé. Il expire vite : réessaie avec le suivant.",
                        )
                    }

                    SignIn.REFUSED -> _ui.update {
                        it.copy(
                            testing = false,
                            message = "Identifiant ou mot de passe refusé par le NAS.",
                        )
                    }
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

                else -> _ui.update { it.copy(testing = false, message = outcome.errorText()) }
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
     * Met a jour une application.
     *
     * L'hote travaille ensuite en arriere-plan : on ne saura que la mise a jour
     * est finie qu'au rafraichissement suivant, et l'ecran le dit plutot que de
     * faire croire a une operation instantanee.
     */
    fun upgrade(app: HostApp) {
        val hostId = _ui.value.selectedId
        if (hostId.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(busyApp = app.id) }
            val result = hosts.upgrade(hostId, app)
            _ui.update {
                it.copy(
                    busyApp = null,
                    message = when (result) {
                        is ApiResult.Ok -> "${app.name} : mise à jour lancée sur l'hôte."
                        is ApiResult.Unsupported -> "L'hôte ne dit pas comment mettre à jour ${app.name}."
                        else -> result.errorText()
                    },
                )
            }
            if (result is ApiResult.Ok) refresh()
        }
    }

    /**
     * Enregistre l'extinction programmee, puis relit ce que l'hote annonce.
     *
     * La relecture n'est pas une precaution de style : le corps de cette requete
     * est deduit de la forme de la lecture, pas d'un contrat publie. Afficher ce
     * que la machine dit ensuite, plutot que ce qu'on lui a demande, est la
     * seule facon honnete de presenter un reglage qui l'eteindra.
     */
    fun saveSchedule(schedule: ScheduledOff) {
        val hostId = _ui.value.selectedId
        if (hostId.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(savingSchedule = true) }
            val result = hosts.setScheduledOff(hostId, schedule)
            val reread = if (result is ApiResult.Ok) hosts.scheduledOff(hostId) else null
            _ui.update { state ->
                state.copy(
                    savingSchedule = false,
                    scheduledOff = (reread as? ApiResult.Ok)?.value ?: state.scheduledOff,
                    message = when (result) {
                        is ApiResult.Ok -> if (schedule.active) {
                            "Extinction programmée enregistrée."
                        } else {
                            "Extinction programmée désactivée."
                        }

                        else -> result.errorText()
                    },
                )
            }
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

    /**
     * Le debit, deduit de deux mesures successives.
     *
     * L'ecart de temps vient de l'horloge de l'appareil, pas de l'hote : le
     * champ de temps de l'hote existe mais son unite n'a pas ete mesuree, et un
     * debit calcule sur une unite supposee serait faux sans le dire. Un
     * compteur qui recule - l'hote a redemarre - est ignore plutot qu'affiche
     * en negatif.
     */
    private fun ratesBetween(before: HostUsage, after: HostUsage): List<NetRate> {
        // Certains hotes publient deja des debits : les differencier les
        // ramenerait a zero. On les prend tels quels.
        if (after.networkIsRate) {
            return after.network.map { NetRate(it.name, it.sentBytes, it.receivedBytes) }
        }
        val elapsed = after.takenAt - before.takenAt
        if (before.takenAt <= 0L || elapsed < 1_000L) return emptyList()
        val seconds = elapsed / 1000.0
        return after.network.mapNotNull { now ->
            val past = before.network.firstOrNull { it.name == now.name } ?: return@mapNotNull null
            val sent = now.sentBytes - past.sentBytes
            val received = now.receivedBytes - past.receivedBytes
            if (sent < 0 || received < 0) null
            else NetRate(now.name, (sent / seconds).toLong(), (received / seconds).toLong())
        }
    }

    /**
     * Reecrit l'identifiant de l'application Portainer quand l'hote a change sa
     * forme. Sans cela, la relance enverrait un identifiant que l'hote ne
     * connait plus, et echouerait sans dire pourquoi.
     */
    /**
     * Le message d'echec, choisi parmi les lectures faites.
     *
     * Un 403 sur un Synology n'est pas un mot de passe faux : c'est un jeton
     * d'appareil revoque, et le dire evite de faire ressaisir un mot de passe
     * qui n'a jamais change.
     */
    private fun needsOtp(apps: ApiResult<List<HostApp>>, usage: ApiResult<HostUsage>): Boolean {
        val principal = if (apps is ApiResult.Unsupported) usage else apps
        return principal is ApiResult.HttpError && principal.code == 403
    }

    /**
     * Donne le code au NAS, sans rien ressaisir d'autre.
     *
     * Si l'hote rend un jeton d'appareil, c'est la derniere fois qu'on lui
     * demande. Sinon, on le dit : un code par session est penible, mais le
     * decouvrir soi-meme l'est davantage.
     */
    fun submitOtp(code: String) {
        val hostId = _ui.value.selectedId
        if (hostId.isBlank() || code.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(sendingOtp = true, message = null) }
            val result = hosts.signIn(hostId, code.trim())
            val outcome = (result as? ApiResult.Ok)?.value
            if (outcome == SignIn.OK) {
                val remembered = hosts.hasDevice(hostId)
                _ui.update {
                    it.copy(
                        sendingOtp = false,
                        otpAsked = false,
                        message = if (remembered) {
                            "Connecté. Cet appareil est reconnu : plus de code à saisir."
                        } else {
                            "Connecté. Ce NAS n'a pas délivré de jeton d'appareil : " +
                                "un code sera redemandé à la prochaine session."
                        },
                    )
                }
                refresh()
            } else {
                _ui.update {
                    it.copy(
                        sendingOtp = false,
                        message = when (outcome) {
                            SignIn.OTP_REFUSED ->
                                "Code refusé. Il expire vite : réessaie avec le suivant."

                            SignIn.OTP_REQUIRED -> "Le NAS attend toujours un code."
                            else -> "Le NAS a refusé la connexion."
                        },
                    )
                }
            }
        }
    }

    private fun messageFor(
        state: HostUi,
        apps: ApiResult<List<HostApp>>,
        usage: ApiResult<HostUsage>,
    ): String? {
        val principal = if (apps is ApiResult.Unsupported) usage else apps
        if (principal is ApiResult.Ok) return state.message
        // La carte de saisie dit deja quoi faire : un message par-dessus
        // repeterait la meme chose au meme moment.
        if (principal is ApiResult.HttpError && principal.code == 403) return null
        if (principal is ApiResult.Unsupported) return state.message
        return principal.errorText()
    }

    private suspend fun realignPortainerApp() {
        val host = _ui.value.selected ?: return
        val app = _ui.value.portainerApp ?: return
        if (app.id == host.portainerAppId) return
        hosts.save(host.copy(portainerAppId = app.id), null)
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
}
