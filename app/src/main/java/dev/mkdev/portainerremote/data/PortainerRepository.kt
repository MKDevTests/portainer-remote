package dev.mkdev.portainerremote.data

import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.core.valueOr
import dev.mkdev.portainerremote.core.WebUi
import dev.mkdev.portainerremote.core.valueOrNull
import dev.mkdev.portainerremote.data.model.DockerContainer
import dev.mkdev.portainerremote.data.model.DockerPort
import dev.mkdev.portainerremote.data.model.PortainerEndpoint
import dev.mkdev.portainerremote.data.model.PortainerStack
import dev.mkdev.portainerremote.data.model.StackUpdatePayload
import dev.mkdev.portainerremote.data.net.DockerLogStream
import dev.mkdev.portainerremote.data.net.PortainerClient
import dev.mkdev.portainerremote.data.store.ServerStore
import dev.mkdev.portainerremote.domain.ContainerView
import dev.mkdev.portainerremote.domain.EnvGroup
import dev.mkdev.portainerremote.domain.ImageGroup
import dev.mkdev.portainerremote.domain.ImageView
import dev.mkdev.portainerremote.domain.NetworkKind
import dev.mkdev.portainerremote.domain.PortBinding
import dev.mkdev.portainerremote.domain.Outcome
import dev.mkdev.portainerremote.domain.RunState
import dev.mkdev.portainerremote.domain.Server
import dev.mkdev.portainerremote.domain.StackAction
import dev.mkdev.portainerremote.domain.StackOrigin
import dev.mkdev.portainerremote.domain.StackView
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val LABEL_COMPOSE = "com.docker.compose.project"
private const val LABEL_SWARM = "com.docker.stack.namespace"
private const val NETWORK_HOST = "host"
private const val NETWORK_SHARED = "container:"

/**
 * Fusionne les deux sources de stacks et applique les actions.
 *
 * Toute la logique nee des mesures de l'etude vit ici :
 *  - /api/stacks ne voit que les stacks geres, les autres se deduisent des etiquettes ;
 *  - une action native indisponible bascule sur un eventail conteneur par conteneur ;
 *  - apres toute action, c'est l'etat relu qui tranche, jamais le code HTTP.
 */
class PortainerRepository(private val store: ServerStore) {

    private val clients = mutableMapOf<String, PortainerClient>()
    private val lock = Mutex()

    private suspend fun clientFor(server: Server): PortainerClient? = lock.withLock {
        clients[server.id]?.let { return@withLock it }
        val secret = store.secretOf(server.id) ?: return@withLock null
        PortainerClient(server.baseUrl, server.authMode, server.username, secret)
            .also { clients[server.id] = it }
    }

    /** A appeler apres modification d'un serveur : l'ancien client porte l'ancienne URL. */
    suspend fun invalidate(serverId: String) = lock.withLock {
        clients.remove(serverId)?.close()
        Unit
    }

    /**
     * Test de connexion de l'ecran de configuration.
     *
     * Mesure : /api/system/status repond sans authentification sur Portainer 2.19.4.
     * Un test base sur elle affiche donc "connexion etablie" avec un jeton faux.
     * La version ne sert qu'a enrichir le message ; c'est /api/endpoints, qui exige
     * une authentification, qui tranche.
     */
    suspend fun probe(server: Server, plainSecret: String): ApiResult<String> {
        val client = PortainerClient(server.baseUrl, server.authMode, server.username, plainSecret)
        return try {
            val version = client.version().valueOrNull()?.takeIf { it.isNotBlank() }
            when (val envs = client.endpoints()) {
                is ApiResult.Ok -> {
                    val count = envs.value.size
                    val envText = if (count > 1) "$count environnements" else "$count environnement"
                    ApiResult.Ok(
                        if (version != null) "Portainer $version, $envText." else "$envText.",
                    )
                }

                is ApiResult.Unsupported -> ApiResult.Unsupported
                is ApiResult.HttpError -> ApiResult.HttpError(envs.code)
                is ApiResult.NetworkError -> ApiResult.NetworkError(envs.reason)
            }
        } finally {
            client.close()
        }
    }

    // ------------------------------------------------------------------ lecture

    suspend fun load(server: Server): ApiResult<List<EnvGroup>> {
        val client = clientFor(server)
            ?: return ApiResult.NetworkError("Secret illisible. Ressaisis le jeton dans la configuration.")

        return when (val envs = client.endpoints()) {
            is ApiResult.Ok -> {
                val managed = client.stacks().valueOr(emptyList())
                val groups = envs.value.map { env ->
                    if (!env.dockerCapable) {
                        EnvGroup(env.id, env.name, env.kindLabel, dockerCapable = false, stacks = emptyList())
                    } else {
                        val containers = client.containers(env.id).valueOr(emptyList())
                        EnvGroup(
                            envId = env.id,
                            envName = env.name,
                            kindLabel = env.kindLabel,
                            dockerCapable = true,
                            stacks = merge(env, managed.filter { it.endpointId == env.id }, containers),
                            linkHost = WebUi.resolveHost(server.baseUrl, env.publicUrl, env.url),
                        )
                    }
                }
                ApiResult.Ok(groups)
            }

            is ApiResult.Unsupported -> ApiResult.Unsupported
            is ApiResult.HttpError -> ApiResult.HttpError(envs.code)
            is ApiResult.NetworkError -> ApiResult.NetworkError(envs.reason)
        }
    }

    private fun merge(
        env: PortainerEndpoint,
        managed: List<PortainerStack>,
        containers: List<DockerContainer>,
    ): List<StackView> {
        // Index par identifiant : un conteneur en reseau partage designe sa
        // cible par son id, et c'est elle qui porte les ports.
        val byId = containers.associateBy { it.id }

        val byProject = containers
            .groupBy { it.labels[LABEL_COMPOSE] ?: it.labels[LABEL_SWARM] }
            .toMutableMap()

        val loose = byProject.remove(null).orEmpty()
        val out = mutableListOf<StackView>()

        // 1. Les stacks que Portainer connait, avec leurs conteneurs rattaches par nom.
        managed.forEach { stack ->
            val own = byProject.remove(stack.name).orEmpty()
            out += StackView(
                key = "managed:${stack.id}",
                name = stack.name,
                envId = env.id,
                envName = env.name,
                origin = StackOrigin.MANAGED,
                managedId = stack.id,
                managedStatus = stack.status,
                containers = own.map { it.toView(byId) },
            )
        }

        // 2. Ce que /api/stacks ignore : reconstruit depuis les etiquettes.
        byProject.forEach { (project, group) ->
            if (project == null) return@forEach
            val swarm = group.any { it.labels.containsKey(LABEL_SWARM) }
            out += StackView(
                key = "derived:${env.id}:$project",
                name = project,
                envId = env.id,
                envName = env.name,
                origin = if (swarm) StackOrigin.SWARM else StackOrigin.COMPOSE,
                containers = group.map { it.toView(byId) },
            )
        }

        // 3. Les conteneurs lances a la main, qui n'appartiennent a rien.
        if (loose.isNotEmpty()) {
            out += StackView(
                key = "loose:${env.id}",
                name = "Conteneurs isoles",
                envId = env.id,
                envName = env.name,
                origin = StackOrigin.LOOSE,
                containers = loose.map { it.toView(byId) },
            )
        }

        return out.sortedWith(compareBy({ it.origin.ordinal }, { it.name.lowercase() }))
    }

    private fun DockerContainer.toView(byId: Map<String, DockerContainer>): ContainerView {
        val mode = hostConfig.networkMode
        val sharedId = if (mode.startsWith(NETWORK_SHARED)) mode.removePrefix(NETWORK_SHARED) else null

        return ContainerView(
            id = id,
            name = displayName,
            image = image,
            state = state,
            statusText = status,
            // Un conteneur en reseau partage ne recopie pas les ports de sa
            // cible : elle en publie parfois vingt, et rien ne dit lequel lui
            // appartient. Le dire est juste ; le deviner ne le serait pas.
            ports = if (sharedId != null) emptyList() else ports.toBindings(),
            network = when {
                mode.equals(NETWORK_HOST, ignoreCase = true) -> NetworkKind.HOST
                sharedId != null -> NetworkKind.SHARED
                else -> NetworkKind.NORMAL
            },
            sharesNetworkWith = sharedId?.let { byId[it]?.displayName },
        )
    }

    /**
     * Mesure : 46 des 96 entrees d'une instance reelle etaient des doublons,
     * la meme liaison rapportee en IPv4 puis en IPv6. Sans deduplication,
     * chaque port s'afficherait deux fois.
     *
     * Une entree sans port public est ecartee : elle decrit un port ouvert dans
     * le reseau de Docker, que rien n'atteint depuis le telephone.
     */
    private fun List<DockerPort>.toBindings(): List<PortBinding> = this
        .filter { it.publicPort > 0 }
        .map { PortBinding(it.publicPort, it.privatePort, it.type, it.ip) }
        // Tri stable : entre deux doublons, celui qui n'est pas sur la boucle
        // locale l'emporte, car lui seul est joignable.
        .sortedBy { if (it.loopback) 1 else 0 }
        .distinctBy { it.publicPort to it.type }
        .sortedBy { it.publicPort }

    // ------------------------------------------------------------------- images

    /**
     * Une image est "utilisee" si un conteneur, meme arrete, la reference. On
     * compare sur ImageID et non sur le tag : deux tags peuvent pointer la meme
     * image, et une image mise a jour laisse l'ancienne sans tag mais toujours
     * referencee par un conteneur arrete.
     */
    suspend fun loadImages(server: Server): ApiResult<List<ImageGroup>> {
        val client = clientFor(server)
            ?: return ApiResult.NetworkError("Secret illisible. Ressaisis le jeton dans la configuration.")

        return when (val envs = client.endpoints()) {
            is ApiResult.Ok -> {
                val groups = envs.value.filter { it.dockerCapable }.map { env ->
                    val images = client.images(env.id).valueOr(emptyList())
                    val referenced = client.containers(env.id).valueOr(emptyList())
                        .map { it.imageId }
                        .filter { it.isNotBlank() }
                        .toSet()

                    ImageGroup(
                        envId = env.id,
                        envName = env.name,
                        images = images
                            .map { image ->
                                ImageView(
                                    id = image.id,
                                    tags = image.repoTags.filter { it != "<none>:<none>" },
                                    sizeBytes = image.size,
                                    inUse = image.id in referenced,
                                )
                            }
                            // Inutilisees d'abord, puis les plus lourdes : l'ordre
                            // du menage, pas l'ordre alphabetique.
                            .sortedWith(compareBy({ it.inUse }, { -it.sizeBytes })),
                    )
                }
                ApiResult.Ok(groups)
            }

            is ApiResult.Unsupported -> ApiResult.Unsupported
            is ApiResult.HttpError -> ApiResult.HttpError(envs.code)
            is ApiResult.NetworkError -> ApiResult.NetworkError(envs.reason)
        }
    }

    suspend fun deleteImage(server: Server, envId: Int, imageId: String): ApiResult<Int> {
        val client = clientFor(server) ?: return ApiResult.NetworkError("Secret illisible.")
        return client.deleteImage(envId, imageId)
    }

    // --------------------------------------------------------------------- logs

    /**
     * Logs d'un conteneur, deja demultiplexes.
     *
     * Docker renvoie un flux d'octets ou chaque trame porte un entete binaire de
     * 8 octets ; sans [DockerLogStream], chaque ligne s'afficherait prefixee de
     * caracteres parasites.
     */
    suspend fun logs(
        server: Server,
        envId: Int,
        containerId: String,
        tail: Int,
        timestamps: Boolean,
    ): ApiResult<String> {
        val client = clientFor(server) ?: return ApiResult.NetworkError("Secret illisible.")
        return when (val result = client.logs(envId, containerId, tail, timestamps)) {
            is ApiResult.Ok -> ApiResult.Ok(DockerLogStream.decode(result.value))
            is ApiResult.HttpError -> ApiResult.HttpError(result.code)
            is ApiResult.NetworkError -> ApiResult.NetworkError(result.reason)
            ApiResult.Unsupported -> ApiResult.Unsupported
        }
    }

    // ------------------------------------------------------------------ actions

    suspend fun act(server: Server, stack: StackView, action: StackAction): Outcome {
        val client = clientFor(server) ?: return Outcome.Failed("Secret illisible.")
        if (!stack.actionable) {
            return Outcome.Failed("Ce stack n'expose aucune action : ni route native, ni conteneur listable.")
        }

        // Voie native quand elle existe. Unsupported n'est pas une erreur : c'est
        // le signal qu'il faut passer par l'eventail sur les conteneurs.
        var handledNatively = false
        val id = stack.managedId
        if (id != null) {
            handledNatively = when (action) {
                StackAction.START -> client.startStack(id, stack.envId) !is ApiResult.Unsupported
                StackAction.STOP -> client.stopStack(id, stack.envId) !is ApiResult.Unsupported
                StackAction.RESTART -> {
                    val stopped = client.stopStack(id, stack.envId)
                    if (stopped is ApiResult.Unsupported) {
                        false
                    } else {
                        awaitManagedStatus(client, id, wanted = 2)
                        client.startStack(id, stack.envId)
                        true
                    }
                }

                StackAction.REDEPLOY -> {
                    if (!redeployWithPull(client, stack, id)) {
                        return Outcome.Failed(
                            "Cette instance n'expose pas le redéploiement de stack.",
                        )
                    }
                    true
                }
            }
        }

        // Un stack deduit n'a pas de definition chez Portainer : on pourrait
        // telecharger ses images, mais un simple redemarrage relancerait quand
        // meme l'ancienne. Mieux vaut le dire que faire semblant.
        if (action == StackAction.REDEPLOY && stack.managedId == null) {
            return Outcome.Failed(
                "Seuls les stacks gérés par Portainer peuvent être redéployés. " +
                    "Celui-ci est déduit des étiquettes de ses conteneurs.",
            )
        }

        if (!handledNatively) {
            val verb = when (action) {
                StackAction.START -> "start"
                StackAction.STOP -> "stop"
                else -> "restart"
            }
            stack.containers.forEach { container ->
                client.containerAction(stack.envId, container.id, verb)
            }
        }

        return reconcile(client, stack, action)
    }

    /**
     * Redeploiement avec images a jour.
     *
     * Portainer exige le contenu complet du compose dans le PUT : on le relit
     * d'abord, et on reinjecte les variables d'environnement telles quelles,
     * sans quoi elles seraient effacees par la mise a jour.
     */
    private suspend fun redeployWithPull(
        client: PortainerClient,
        stack: StackView,
        stackId: Int,
    ): Boolean {
        val content = client.stackFile(stackId).valueOrNull()?.takeIf { it.isNotBlank() }
            ?: return false
        val env = client.stack(stackId).valueOrNull()?.env.orEmpty()
        val result = client.updateStack(
            stackId = stackId,
            envId = stack.envId,
            payload = StackUpdatePayload(
                stackFileContent = content,
                env = env,
                prune = false,
                pullImage = true,
            ),
        )
        return result !is ApiResult.Unsupported
    }

    suspend fun actOnContainer(
        server: Server,
        stack: StackView,
        container: ContainerView,
        action: StackAction,
    ): Outcome {
        val client = clientFor(server) ?: return Outcome.Failed("Secret illisible.")
        // Le redeploiement n'a pas de sens a l'echelle d'un conteneur : Docker
        // ne sait que le redemarrer avec l'image qu'il porte deja.
        val verb = when (action) {
            StackAction.START -> "start"
            StackAction.STOP -> "stop"
            else -> "restart"
        }
        client.containerAction(stack.envId, container.id, verb)
        return reconcile(client, stack, action)
    }

    /**
     * L'etat fait foi.
     *
     * Mesure : arreter un stack deja arrete renvoie 400, alors que demarrer un
     * conteneur deja en marche renvoie 304. Deux facons de dire "c'etait deja
     * fait", l'une classee erreur. Juger sur le code afficherait un echec sur une
     * operation reussie, donc on relit l'etat et on le compare a l'intention.
     */
    private suspend fun reconcile(
        client: PortainerClient,
        stack: StackView,
        action: StackAction,
    ): Outcome {
        val target = if (action == StackAction.STOP) RunState.STOPPED else RunState.RUNNING
        var last: StackView? = null

        // Un redeploiement telecharge des images avant de recreer les conteneurs :
        // lui accorder la meme patience qu'a un simple start le ferait declarer
        // en echec alors qu'il est en train de reussir.
        val attempts = if (action == StackAction.REDEPLOY) 40 else 6
        val interval = if (action == StackAction.REDEPLOY) 1_500L else 900L

        repeat(attempts) { attempt ->
            delay(if (attempt == 0) 700L else interval)
            val fresh = reload(client, stack)
            if (fresh != null) {
                last = fresh
                if (fresh.runState == target) return Outcome.Done(fresh.runState)
            }
        }

        val settled = last
        return when {
            settled == null -> Outcome.Failed("État illisible après l'action.")
            settled.runState == target -> Outcome.Done(settled.runState)
            else -> Outcome.Mismatch(settled.runState)
        }
    }

    private suspend fun reload(client: PortainerClient, stack: StackView): StackView? {
        val containers = client.containers(stack.envId).valueOrNull() ?: return null
        val managed = client.stacks().valueOr(emptyList()).filter { it.endpointId == stack.envId }
        val env = PortainerEndpoint(id = stack.envId, name = stack.envName, type = 1)
        return merge(env, managed, containers).firstOrNull { it.key == stack.key }
    }

    private suspend fun awaitManagedStatus(
        client: PortainerClient,
        stackId: Int,
        wanted: Int,
        tries: Int = 8,
    ) {
        repeat(tries) {
            if (client.stack(stackId).valueOrNull()?.status == wanted) return
            delay(600)
        }
    }
}
