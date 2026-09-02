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

/**
 * Un port publie sur l'hote, deduplique.
 *
 * Docker rapporte la meme liaison deux fois quand elle couvre IPv4 et IPv6 :
 * 46 des 96 entrees mesurees sur une instance reelle etaient des doublons. La
 * deduplication a lieu avant d'arriver ici.
 */
data class PortBinding(
    val publicPort: Int,
    val privatePort: Int,
    val type: String,
    /** Interface d'ecoute cote hote. 0.0.0.0 et :: valent "toutes". */
    val bindIp: String,
    /**
     * Vrai quand le port ne vient pas d'une liaison rapportee par Docker mais
     * du EXPOSE de l'image, seule source disponible en reseau host. Tres
     * souvent juste, jamais garanti : l'information est portee jusqu'au
     * descriptif d'accessibilite plutot que tue.
     */
    val deduced: Boolean = false,
) {
    val udp: Boolean get() = type.equals("udp", ignoreCase = true)

    /**
     * Lie a la boucle locale de l'hote. Le service tourne, l'hote le joint, le
     * telephone jamais : afficher un lien serait mentir.
     */
    val loopback: Boolean get() = bindIp == "127.0.0.1" || bindIp == "::1"

    /** Un lien n'a de sens qu'en TCP, et seulement si quelque chose peut l'atteindre. */
    val linkable: Boolean get() = !udp && !loopback

    /**
     * Quand la liaison ne vise pas toutes les interfaces, l'adresse ecoutee est
     * la seule qui reponde : elle prime alors sur l'hote de l'environnement.
     */
    val boundHost: String?
        get() = bindIp.takeIf {
            it.isNotBlank() && it != "0.0.0.0" && it != "::" && !loopback
        }

    /** Compact, choix de l'utilisateur : le port hote suffit a s'y rendre. */
    val label: String get() = publicPort.toString()
}

/** Ce que le mode reseau explique quand aucun port n'apparait. */
enum class NetworkKind {
    /** Cas ordinaire : ce que la liste des ports dit fait foi. */
    NORMAL,

    /**
     * Le conteneur ecoute directement sur les interfaces de l'hote. Mesure :
     * Docker ne rapporte alors aucun port, pas meme un port prive. L'API ne
     * sait rien, et le compose non plus - un service en reseau host ne declare
     * pas de ports.
     */
    HOST,

    /** Le conteneur partage la pile reseau d'un autre : les ports sont les siens. */
    SHARED,
}

data class ContainerView(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val statusText: String,
    val ports: List<PortBinding> = emptyList(),
    val network: NetworkKind = NetworkKind.NORMAL,
    /** Nom du conteneur dont la pile reseau est partagee, si elle est retrouvable. */
    val sharesNetworkWith: String? = null,
    /** Identifiant de ce meme conteneur : c'est lui qui porte les ports publies. */
    val sharesNetworkId: String? = null,
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

    /**
     * Tous les ports du stack, sans doublon. Deux conteneurs peuvent rapporter
     * le meme port quand l'un partage la pile reseau de l'autre.
     */
    val ports: List<PortBinding>
        get() = containers
            .flatMap { it.ports }
            .distinctBy { it.publicPort to it.type }
            .sortedBy { it.publicPort }

    /** Un stack sans conteneur ni route native ne peut pas etre demarre. */
    val actionable: Boolean get() = managedId != null || containers.isNotEmpty()
}

data class EnvGroup(
    val envId: Int,
    val envName: String,
    val kindLabel: String,
    val dockerCapable: Boolean,
    val stacks: List<StackView>,
    /**
     * Hote a viser pour joindre un port publie de cet environnement. Resolu au
     * chargement, parce que Portainer et Docker ne tournent pas forcement sur
     * la meme machine. Vide si rien de fiable n'a pu etre deduit : on affiche
     * alors les ports sans lien.
     */
    val linkHost: String = "",
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
