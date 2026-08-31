package dev.mkdev.portainerremote.domain

enum class AuthMode { API_KEY, PASSWORD }

/**
 * Un serveur Portainer. C'est la seule chose que l'app persiste : tout le reste
 * (environnements, stacks, conteneurs) est decouvert a l'execution.
 */
data class Server(
    val id: String,
    val label: String,
    val baseUrl: String,
    val authMode: AuthMode,
    val username: String = "",
)

/**
 * D'ou vient un stack.
 *
 * Mesure de l'etude : /api/stacks n'a renvoye qu'un stack sur les quatre
 * reellement presents. Les autres n'existent que sous forme d'etiquettes sur
 * leurs conteneurs, et c'est l'app qui les reconstitue.
 */
enum class StackOrigin {
    /** Cree dans Portainer. Dispose des routes start et stop natives. */
    MANAGED,

    /** Deduit de l'etiquette com.docker.compose.project. */
    COMPOSE,

    /** Deduit de l'etiquette com.docker.stack.namespace (Swarm). */
    SWARM,

    /** Conteneurs sans aucune etiquette de projet. */
    LOOSE,
}

enum class RunState { RUNNING, PARTIAL, STOPPED, UNKNOWN }

data class ContainerView(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val statusText: String,
) {
    val running: Boolean get() = state.equals("running", ignoreCase = true)
}

data class StackView(
    val key: String,
    val name: String,
    val envId: Int,
    val envName: String,
    val origin: StackOrigin,
    /** Non nul seulement si le stack est gere par Portainer. */
    val managedId: Int? = null,
    /** Status renvoye par /api/stacks : 1 actif, 2 inactif. */
    val managedStatus: Int? = null,
    val containers: List<ContainerView> = emptyList(),
) {
    /**
     * Un stack gere puis arrete peut n'avoir aucun conteneur listable ; son
     * Status reste alors la seule source de verite.
     */
    val runState: RunState
        get() = when {
            containers.isNotEmpty() && containers.all { it.running } -> RunState.RUNNING
            containers.isNotEmpty() && containers.none { it.running } -> RunState.STOPPED
            containers.isNotEmpty() -> RunState.PARTIAL
            managedStatus == 1 -> RunState.RUNNING
            managedStatus == 2 -> RunState.STOPPED
            else -> RunState.UNKNOWN
        }

    val runningCount: Int get() = containers.count { it.running }

    /** Un stack sans conteneur ni route native ne peut pas etre demarre. */
    val actionable: Boolean get() = managedId != null || containers.isNotEmpty()
}

data class EnvGroup(
    val envId: Int,
    val envName: String,
    val kindLabel: String,
    val dockerCapable: Boolean,
    val stacks: List<StackView>,
)

/** Nomme StackAction et non Intent, pour ne pas entrer en collision avec android.content.Intent. */
enum class StackAction {
    START,
    STOP,

    /** Arret puis redemarrage, avec les images deja presentes. */
    RESTART,

    /** Retelecharge les images avant de redemarrer. Plus long, et peut changer ce qui tourne. */
    REDEPLOY,
}

enum class StackFilter(val label: String) {
    ALL("Tous"),
    RUNNING("En marche"),
    STOPPED("Arrêtés"),
}

enum class StackSort(val label: String) {
    NAME_ASC("Nom (A → Z)"),
    NAME_DESC("Nom (Z → A)"),
    STATE("État d'abord"),
}

enum class ImageFilter(val label: String) {
    ALL("Toutes"),
    UNUSED("Inutilisées"),

    /** Sous-ensemble des inutilisees : les residus de build et de mise a jour. */
    UNTAGGED("Sans étiquette"),
}

data class ImageView(
    val id: String,
    val tags: List<String>,
    val sizeBytes: Long,
    val inUse: Boolean,
) {
    /** Une image sans tag est un residu de build ou de mise a jour : le premier candidat au menage. */
    val dangling: Boolean get() = tags.isEmpty() || tags.all { it == "<none>:<none>" }

    val displayName: String
        get() = tags.firstOrNull { it != "<none>:<none>" } ?: "sans étiquette · ${shortId}"

    val shortId: String get() = id.removePrefix("sha256:").take(12)
}

data class ImageGroup(
    val envId: Int,
    val envName: String,
    val images: List<ImageView>,
) {
    val reclaimableBytes: Long get() = images.filterNot { it.inUse }.sumOf { it.sizeBytes }
    val unusedCount: Int get() = images.count { !it.inUse }
}

sealed interface Outcome {
    /** L'etat relu correspond a l'intention. */
    data class Done(val state: RunState) : Outcome

    /** L'appel a repondu, mais l'etat relu ne correspond pas. */
    data class Mismatch(val state: RunState) : Outcome

    data class Failed(val reason: String) : Outcome
}
