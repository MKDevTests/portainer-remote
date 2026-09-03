package dev.mkdev.portainerremote.ui.stacks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.core.errorText
import dev.mkdev.portainerremote.data.PortainerRepository
import dev.mkdev.portainerremote.data.WidgetSync
import dev.mkdev.portainerremote.data.store.FavoriteStack
import dev.mkdev.portainerremote.data.store.FavoritesStore
import dev.mkdev.portainerremote.data.store.PrefsStore
import dev.mkdev.portainerremote.data.store.ServerStore
import dev.mkdev.portainerremote.data.store.CustomLabel
import dev.mkdev.portainerremote.data.store.LabelKind
import dev.mkdev.portainerremote.data.store.containerNameOfKey
import dev.mkdev.portainerremote.data.store.labelKey
import dev.mkdev.portainerremote.data.store.envIdOfKey
import dev.mkdev.portainerremote.data.store.portPinKey
import dev.mkdev.portainerremote.data.store.serverIdOfKey
import dev.mkdev.portainerremote.domain.ContainerView
import dev.mkdev.portainerremote.domain.EnvGroup
import dev.mkdev.portainerremote.domain.FavoritesView
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
    FAVORITES("Favoris"),
}

/**
 * Un conteneur favori.
 *
 * [entry] est nul quand le conteneur n'est plus dans la liste : renomme,
 * supprime, ou environnement injoignable. Ce cas doit rester affiche, sinon le
 * favori devient impossible a retirer.
 */
data class FavoriteEntry(
    val key: String,
    val name: String,
    val envId: Int,
    val entry: ContainerEntry?,
)

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
    /** Hote a viser pour joindre les ports publies de cet environnement. */
    val linkHost: String = "",
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
    /**
     * Ports de raccourci choisis a la main, par clef de conteneur. Aucune
     * heuristique ne peut deviner lequel des ports publies porte l'interface
     * web ; ce choix-la est la seule source sure.
     */
    val pinnedPorts: Map<String, Int> = emptyMap(),
    /** Clefs des conteneurs favoris, tous serveurs confondus. */
    val favoriteContainers: Set<String> = emptySet(),
    val favoritesView: FavoritesView = FavoritesView.SHORTCUTS,
    /** Noms et descriptions personnalises, par clef typee. */
    val labels: Map<String, CustomLabel> = emptyMap(),
) {

    fun labelOf(kind: LabelKind, envId: Int, name: String): CustomLabel? {
        val id = server?.id ?: return null
        return labels[labelKey(kind, id, envId, name)]
    }

    /**
     * Le nom a afficher : celui de l'utilisateur s'il en a choisi un, sinon le
     * nom officiel. Ce meme titre sert au tri et a la recherche, sans quoi la
     * liste paraitrait desordonnee et le nom choisi introuvable.
     */
    fun titleOf(kind: LabelKind, envId: Int, name: String): String =
        labelOf(kind, envId, name)?.name?.takeIf { it.isNotBlank() } ?: name

    private fun matches(kind: LabelKind, envId: Int, name: String, needle: String): Boolean {
        if (name.lowercase().contains(needle)) return true
        val label = labelOf(kind, envId, name) ?: return false
        return label.name.lowercase().contains(needle) ||
            label.description.lowercase().contains(needle)
    }
    /**
     * Recherche, filtre et tri appliqués à l'affichage seulement : les données
     * brutes restent intactes, si bien qu'effacer la recherche ne coûte pas un
     * appel réseau.
     */
    val visibleGroups: List<EnvGroup>
        get() {
            val needle = query.trim().lowercase()
            fun title(stack: StackView) =
                titleOf(LabelKind.STACK, stack.envId, stack.name).lowercase()

            val comparator = when (sort) {
                StackSort.NAME_ASC -> compareBy<StackView> { title(it) }
                StackSort.NAME_DESC -> compareByDescending<StackView> { title(it) }
                // En marche d'abord, puis partiels, puis arrêtés.
                StackSort.STATE -> compareBy<StackView> { it.runState.ordinal }
                    .thenBy { title(it) }
            }

            return groups.map { group ->
                group.copy(
                    stacks = group.stacks
                        .filter { stack ->
                            val matchesQuery = needle.isEmpty() ||
                                matches(LabelKind.STACK, stack.envId, stack.name, needle) ||
                                // La recherche porte aussi sur les conteneurs :
                                // on cherche souvent un service, pas un stack.
                                stack.containers.any {
                                    matches(LabelKind.CONTAINER, stack.envId, it.name, needle)
                                }

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
            fun title(entry: ContainerEntry) =
                titleOf(LabelKind.CONTAINER, entry.envId, entry.container.name).lowercase()

            val comparator = when (sort) {
                StackSort.NAME_ASC -> compareBy<ContainerEntry> { title(it) }
                StackSort.NAME_DESC -> compareByDescending<ContainerEntry> { title(it) }
                StackSort.STATE -> compareBy<ContainerEntry> { if (it.container.running) 0 else 1 }
                    .thenBy { title(it) }
            }

            return groups
                .flatMap { group ->
                    group.stacks.flatMap { stack ->
                        stack.containers.map {
                            ContainerEntry(group.envId, group.envName, stack, it, group.linkHost)
                        }
                    }
                }
                .filter { entry ->
                    val matchesQuery = needle.isEmpty() ||
                        matches(LabelKind.CONTAINER, entry.envId, entry.container.name, needle) ||
                        entry.container.image.lowercase().contains(needle) ||
                        matches(LabelKind.STACK, entry.envId, entry.stack.name, needle)

                    val matchesFilter = when (filter) {
                        StackFilter.ALL -> true
                        StackFilter.RUNNING -> entry.container.running
                        StackFilter.STOPPED -> !entry.container.running
                    }

                    matchesQuery && matchesFilter
                }
                .sortedWith(comparator)
        }

    /**
     * Les favoris du serveur courant, resolus quand c'est possible.
     *
     * Le filtre d'etat ne s'applique qu'aux favoris retrouves : un favori
     * introuvable n'a pas d'etat, et le masquer sur ce critere le rendrait
     * inaccessible.
     */
    val visibleFavorites: List<FavoriteEntry>
        get() {
            val serverId = server?.id ?: return emptyList()
            val needle = query.trim().lowercase()
            val resolved = visibleContainers.associateBy {
                "$serverId|${it.envId}|${it.container.name}"
            }
            val all = groups
                .flatMap { group -> group.stacks.flatMap { it.containers.map { c -> c.name } } }
                .toSet()

            return favoriteContainers
                .filter { serverIdOfKey(it) == serverId }
                .map { key ->
                    FavoriteEntry(
                        key = key,
                        name = containerNameOfKey(key),
                        envId = envIdOfKey(key),
                        entry = resolved[key],
                    )
                }
                // Un favori absent des donnees brutes est un fantome ; un favori
                // present mais ecarte par le filtre courant se cache normalement.
                .filter { it.entry != null || it.name !in all }
                .filter {
                    needle.isEmpty() || matches(LabelKind.CONTAINER, it.envId, it.name, needle)
                }
                .filter { it.entry != null || filter == StackFilter.ALL }
                .sortedBy { titleOf(LabelKind.CONTAINER, it.envId, it.name).lowercase() }
        }

    val filtering: Boolean get() = query.isNotBlank() || filter != StackFilter.ALL

    val visibleCount: Int
        get() = when (tab) {
            StacksTab.STACKS -> visibleGroups.sumOf { it.stacks.size }
            StacksTab.CONTAINERS -> visibleContainers.size
            StacksTab.FAVORITES -> visibleFavorites.size
        }
}

class StacksViewModel(
    private val serverId: String,
    private val store: ServerStore,
    private val repository: PortainerRepository,
    private val favoritesStore: FavoritesStore,
    private val widgetSync: WidgetSync,
    private val prefsStore: PrefsStore,
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
            val pins = prefsStore.currentPinnedPorts()
            val favoriteContainers = prefsStore.currentFavoriteContainers()
            val customLabels = prefsStore.currentCustomLabels()
            val view = FavoritesView.entries
                .firstOrNull { it.name == prefsStore.favoritesView() }
                ?: FavoritesView.SHORTCUTS

            when (val result = repository.load(server)) {
                is ApiResult.Ok -> _ui.update {
                    it.copy(
                        server = server,
                        groups = result.value,
                        loading = false,
                        error = null,
                        favorites = pinned,
                        pinnedPorts = pins,
                        favoriteContainers = favoriteContainers,
                        favoritesView = view,
                        labels = customLabels,
                    )
                }
                else -> _ui.update {
                    it.copy(
                        server = server,
                        loading = false,
                        error = result.errorText(),
                        favorites = pinned,
                        pinnedPorts = pins,
                        favoriteContainers = favoriteContainers,
                        favoritesView = view,
                        labels = customLabels,
                    )
                }
            }
        }
    }

    /**
     * Enregistre - ou efface, avec un libelle vide - le nom et la description
     * d'un stack ou d'un conteneur.
     *
     * Un changement de nom de stack touche le widget : son instantane porte le
     * nom affiche, et il ne peut rien resoudre au moment de se dessiner.
     */
    fun setLabel(kind: LabelKind, envId: Int, name: String, label: CustomLabel) {
        viewModelScope.launch {
            val key = labelKey(kind, serverId, envId, name)
            prefsStore.setCustomLabel(key, label)
            _ui.update {
                it.copy(
                    labels = if (label.empty) it.labels - key else it.labels + (key to label),
                    message = if (label.empty) {
                        "Nom personnalisé de $name effacé."
                    } else {
                        "$name affiché sous « ${label.name.ifBlank { name }} »."
                    },
                )
            }
            if (kind == LabelKind.STACK) widgetSync.refresh()
        }
    }

    fun toggleFavoriteContainer(envId: Int, containerName: String) {
        viewModelScope.launch {
            val key = portPinKey(serverId, envId, containerName)
            val nowFavorite = prefsStore.toggleFavoriteContainer(key)
            _ui.update {
                it.copy(
                    favoriteContainers = if (nowFavorite) {
                        it.favoriteContainers + key
                    } else {
                        it.favoriteContainers - key
                    },
                    message = if (nowFavorite) {
                        "$containerName ajouté aux favoris."
                    } else {
                        "$containerName retiré des favoris."
                    },
                )
            }
        }
    }

    /** Retrait par clef : le seul moyen de se debarrasser d'un favori introuvable. */
    fun removeFavoriteContainer(key: String) {
        viewModelScope.launch {
            prefsStore.toggleFavoriteContainer(key)
            _ui.update {
                it.copy(
                    favoriteContainers = it.favoriteContainers - key,
                    message = "${containerNameOfKey(key)} retiré des favoris.",
                )
            }
        }
    }

    fun setFavoritesView(mode: FavoritesView) {
        viewModelScope.launch {
            prefsStore.setFavoritesView(mode.name)
            _ui.update { it.copy(favoritesView = mode) }
        }
    }

    /**
     * Fixe - ou efface, avec un port nul - le port de raccourci d'un conteneur.
     * Le reglage est garde sous le nom du conteneur, donc il survit a un
     * redeploiement qui lui donnerait un nouvel identifiant.
     */
    fun setPinnedPort(envId: Int, containerName: String, port: Int?) {
        viewModelScope.launch {
            val key = portPinKey(serverId, envId, containerName)
            prefsStore.setPinnedPort(key, port)
            _ui.update {
                it.copy(
                    pinnedPorts = if (port == null) it.pinnedPorts - key else it.pinnedPorts + (key to port),
                    message = if (port == null) {
                        "Raccourci de $containerName effacé."
                    } else {
                        "Raccourci de $containerName sur le port $port."
                    },
                )
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
