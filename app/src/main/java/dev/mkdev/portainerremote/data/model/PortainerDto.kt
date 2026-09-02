package dev.mkdev.portainerremote.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Regle 3 de l'etude : parsing tolerant.
 *
 * Tout champ hors du noyau porte une valeur par defaut. Le sondage a montre que
 * cinq champs disparaissent entre 2.19.4 et 2.31.3 (IsComposeFormat, UserTrusted,
 * IsEdgeDevice, EnableGPUManagement, DemoEnvironment) et que trois apparaissent
 * (ContainerEngine, IsPodman, ContainerCount). Aucun n'est modelise ici : seul le
 * noyau Id / Name / Type / Status / EndpointId l'est, et lui n'a pas bouge.
 */

@Serializable
data class PortainerEndpoint(
    @SerialName("Id") val id: Int,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: Int = 0,
    @SerialName("Status") val status: Int = 0,
    /**
     * Adresse publique des conteneurs, saisie par l'administrateur dans
     * Portainer. C'est le champ prevu pour batir un lien vers un service publie.
     * Mesure : vide sur les trois instances sondees, personne ne le remplit.
     */
    @SerialName("PublicURL") val publicUrl: String = "",
    /**
     * Adresse du demon Docker. unix:///var/run/docker.sock veut dire que Docker
     * tourne sur la machine de Portainer ; tcp://hote:2376 designe une autre
     * machine, et c'est alors elle qui porte les ports publies.
     */
    @SerialName("URL") val url: String = "",
) {
    /** 1 Docker local, 2 agent, 4 agent Edge. Le reste (Azure, Kubernetes) n'expose pas l'API Docker. */
    val dockerCapable: Boolean get() = type == 1 || type == 2 || type == 4

    val kindLabel: String
        get() = when (type) {
            1 -> "Docker"
            2 -> "Agent"
            3 -> "Azure"
            4 -> "Edge"
            5, 6, 7 -> "Kubernetes"
            else -> "Type $type"
        }
}

@Serializable
data class StackEnvVar(val name: String = "", val value: String = "")

@Serializable
data class PortainerStack(
    @SerialName("Id") val id: Int,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: Int = 0,
    @SerialName("EndpointId") val endpointId: Int = 0,
    /** Mesure : 1 actif, 2 inactif. */
    @SerialName("Status") val status: Int = 0,
    /** A reinjecter tel quel lors d'un redeploiement, sinon les variables sont perdues. */
    @SerialName("Env") val env: List<StackEnvVar> = emptyList(),
)

/** Reponse de /api/stacks/{id}/file : le compose d'origine, requis pour redeployer. */
@Serializable
data class StackFile(
    @SerialName("StackFileContent") val content: String = "",
)

/**
 * Corps du PUT /api/stacks/{id}. pullImage a vrai est ce qui distingue un
 * redeploiement avec images a jour d'un simple redemarrage.
 */
@Serializable
data class StackUpdatePayload(
    val stackFileContent: String,
    val env: List<StackEnvVar> = emptyList(),
    val prune: Boolean = false,
    val pullImage: Boolean = true,
)

@Serializable
data class DockerImage(
    @SerialName("Id") val id: String,
    @SerialName("RepoTags") val repoTags: List<String> = emptyList(),
    @SerialName("Size") val size: Long = 0,
    @SerialName("Created") val created: Long = 0,
)

@Serializable
data class DockerContainer(
    @SerialName("Id") val id: String,
    @SerialName("Names") val names: List<String> = emptyList(),
    @SerialName("Image") val image: String = "",
    /** Identifiant resolu de l'image : c'est lui qui dit si une image est utilisee. */
    @SerialName("ImageID") val imageId: String = "",
    @SerialName("State") val state: String = "",
    @SerialName("Status") val status: String = "",
    @SerialName("Labels") val labels: Map<String, String> = emptyMap(),
    /**
     * Mesure : la liste arrive parfois a null plutot qu'a vide (deux conteneurs
     * en marche sur un Docker local). coerceInputValues, actif dans le client,
     * ramene ce null a la valeur par defaut au lieu de faire echouer le parsing.
     */
    @SerialName("Ports") val ports: List<DockerPort> = emptyList(),
    @SerialName("HostConfig") val hostConfig: DockerHostConfig = DockerHostConfig(),
) {
    val displayName: String get() = names.firstOrNull()?.removePrefix("/") ?: id.take(12)
}

/**
 * Un port tel que Docker le rapporte.
 *
 * publicPort absent veut dire "expose, pas publie" : le port existe dans le
 * reseau de Docker, rien ne l'atteint depuis l'exterieur. Sur l'instance
 * sondee, 19 entrees sur 115 sont dans ce cas.
 */
@Serializable
data class DockerPort(
    /** Interface d'ecoute cote hote : 0.0.0.0 et :: valent "toutes". */
    @SerialName("IP") val ip: String = "",
    @SerialName("PrivatePort") val privatePort: Int = 0,
    @SerialName("PublicPort") val publicPort: Int = 0,
    @SerialName("Type") val type: String = "tcp",
)

/**
 * Seul NetworkMode est modelise : il explique les conteneurs qui n'affichent
 * aucun port. Mesure sur 26 conteneurs muets : 7 en reseau host, 8 partageant
 * la pile d'un autre conteneur.
 */
@Serializable
data class DockerHostConfig(
    @SerialName("NetworkMode") val networkMode: String = "",
)

@Serializable
data class PortainerSystemStatus(
    @SerialName("Version") val version: String = "",
)

@Serializable
data class AuthPayload(val username: String, val password: String)

@Serializable
data class AuthResponse(val jwt: String = "")
