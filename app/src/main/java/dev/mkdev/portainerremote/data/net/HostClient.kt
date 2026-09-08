package dev.mkdev.portainerremote.data.net

import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.domain.Host
import dev.mkdev.portainerremote.domain.HostApp
import dev.mkdev.portainerremote.domain.HostAppAction
import dev.mkdev.portainerremote.domain.HostKind
import dev.mkdev.portainerremote.domain.HostPower
import dev.mkdev.portainerremote.domain.HostUsage
import dev.mkdev.portainerremote.domain.ScheduledOff

/**
 * Ce que l'application demande a un NAS, quel qu'il soit.
 *
 * Le contrat est volontairement court : il ne contient que ce que Portainer ne
 * sait pas faire depuis l'interieur d'un conteneur. Tout ce qui se double entre
 * les deux se tranche en faveur de Portainer, qui est generique et deja ecrit -
 * c'est ce qui evite qu'ajouter un systeme revienne a reecrire l'application.
 *
 * Un systeme qui ne sait pas repondre a une question repond [ApiResult.Unsupported].
 * L'interface ne suppose donc pas que tous savent tout faire.
 */
interface HostClient {

    /** Reconnait l'hote sans identifiants. Faux : on n'envoie pas le mot de passe. */
    suspend fun detect(): Boolean

    suspend fun signIn(): ApiResult<Boolean>

    suspend fun apps(): ApiResult<List<HostApp>>

    suspend fun setAppStatus(appId: String, action: HostAppAction): ApiResult<Int>

    suspend fun usage(): ApiResult<HostUsage>

    suspend fun scheduledOff(): ApiResult<ScheduledOff>

    suspend fun power(action: HostPower): ApiResult<Int>

    fun close()

    companion object {
        /**
         * Le client du systeme declare. Rien n'est devine ici : le type vient de
         * ce que l'utilisateur a choisi, et un type non gere donne un client qui
         * le dit au lieu de tenter sa chance sur des routes inconnues.
         */
        fun of(host: Host, password: String): HostClient = when (host.kind) {
            HostKind.ZIMA -> ZimaClient(host.baseUrl, host.username, password)
            HostKind.SYNOLOGY, HostKind.QNAP -> UnsupportedHostClient
        }
    }
}

/**
 * Le client des systemes annonces mais pas encore ecrits.
 *
 * Il existe pour que l'absence soit une reponse, et non une panne : l'ecran
 * affiche « pas encore gere » plutot qu'une erreur reseau, et le jour ou le
 * client arrive, c'est une ligne a changer dans [HostClient.of].
 */
object UnsupportedHostClient : HostClient {
    override suspend fun detect(): Boolean = false
    override suspend fun signIn(): ApiResult<Boolean> = ApiResult.Unsupported
    override suspend fun apps(): ApiResult<List<HostApp>> = ApiResult.Unsupported
    override suspend fun setAppStatus(appId: String, action: HostAppAction): ApiResult<Int> =
        ApiResult.Unsupported

    override suspend fun usage(): ApiResult<HostUsage> = ApiResult.Unsupported
    override suspend fun scheduledOff(): ApiResult<ScheduledOff> = ApiResult.Unsupported
    override suspend fun power(action: HostPower): ApiResult<Int> = ApiResult.Unsupported
    override fun close() = Unit
}
