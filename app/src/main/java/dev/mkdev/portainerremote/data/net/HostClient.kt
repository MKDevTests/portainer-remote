package dev.mkdev.portainerremote.data.net

import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.domain.Host
import dev.mkdev.portainerremote.domain.DiskSleep
import dev.mkdev.portainerremote.domain.HostApp
import dev.mkdev.portainerremote.domain.HostAppAction
import dev.mkdev.portainerremote.domain.HostDisk
import dev.mkdev.portainerremote.domain.HostJournal
import dev.mkdev.portainerremote.domain.HostKind
import dev.mkdev.portainerremote.domain.HostMachine
import dev.mkdev.portainerremote.domain.HostPower
import dev.mkdev.portainerremote.domain.HostUsage
import dev.mkdev.portainerremote.domain.LogLevel
import dev.mkdev.portainerremote.domain.ScheduledOff
import dev.mkdev.portainerremote.domain.SignIn

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

    /**
     * Ouvre une session.
     *
     * [otp] est le code de verification en deux etapes, quand l'hote en
     * reclame un. Il n'est ni conserve ni reutilisable : ce qui est garde, si
     * l'hote en rend un, c'est le jeton d'appareil rendu par [deviceToken].
     */
    suspend fun signIn(otp: String? = null): ApiResult<SignIn>

    /**
     * Le jeton d'appareil obtenu lors de la derniere connexion avec un code.
     *
     * Null quand l'hote n'en delivre pas, ou quand aucun code n'a ete fourni.
     * L'appelant le range pour ne plus redemander de code.
     */
    fun deviceToken(): String? = null

    suspend fun apps(): ApiResult<List<HostApp>>

    suspend fun setAppStatus(appId: String, action: HostAppAction): ApiResult<Int>

    /** Les identifiants des applications pour lesquelles l'hote annonce une mise a jour. */
    suspend fun upgradable(): ApiResult<Set<String>>

    suspend fun upgrade(appId: String, appType: String): ApiResult<Int>

    suspend fun usage(): ApiResult<HostUsage>

    /** Ce que la machine est : modele, systeme, processeur, memoire. */
    suspend fun machine(): ApiResult<HostMachine>

    /** Les disques physiques, un par un. Vide quand l'hote n'en dit rien. */
    suspend fun disks(): ApiResult<List<HostDisk>>

    /** Le delai avant mise en veille des disques, tel que l'hote le publie. */
    suspend fun diskSleep(): ApiResult<DiskSleep>

    suspend fun scheduledOff(): ApiResult<ScheduledOff>

    suspend fun setScheduledOff(schedule: ScheduledOff): ApiResult<Int>

    /**
     * Le journal de l'hote : connexions, utilisateurs, acces.
     *
     * Aucune autre source ne repond a cette question. Docker ne garde que ses
     * 256 derniers evenements en memoire, ZimaOS n'expose aucune route de
     * journal, et l'historique d'authentification de Portainer est reserve a
     * son edition payante. Un hote qui ne sait pas repond Unsupported, et
     * l'ecran disparait plutot que de montrer une page vide.
     */
    suspend fun journal(level: LogLevel?, limit: Int): ApiResult<HostJournal>

    suspend fun power(action: HostPower): ApiResult<Int>

    fun close()

    companion object {
        /**
         * Le client du systeme declare. Rien n'est devine ici : le type vient de
         * ce que l'utilisateur a choisi, et un type non gere donne un client qui
         * le dit au lieu de tenter sa chance sur des routes inconnues.
         */
        fun of(host: Host, password: String, deviceId: String? = null): HostClient = when (host.kind) {
            HostKind.ZIMA -> ZimaClient(host.baseUrl, host.username, password)
            HostKind.SYNOLOGY -> SynologyClient(host.baseUrl, host.username, password, deviceId)
            HostKind.QNAP -> UnsupportedHostClient
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
    override suspend fun signIn(otp: String?): ApiResult<SignIn> = ApiResult.Unsupported
    override suspend fun apps(): ApiResult<List<HostApp>> = ApiResult.Unsupported
    override suspend fun setAppStatus(appId: String, action: HostAppAction): ApiResult<Int> =
        ApiResult.Unsupported

    override suspend fun upgradable(): ApiResult<Set<String>> = ApiResult.Unsupported
    override suspend fun upgrade(appId: String, appType: String): ApiResult<Int> =
        ApiResult.Unsupported

    override suspend fun usage(): ApiResult<HostUsage> = ApiResult.Unsupported
    override suspend fun machine(): ApiResult<HostMachine> = ApiResult.Unsupported
    override suspend fun disks(): ApiResult<List<HostDisk>> = ApiResult.Unsupported
    override suspend fun journal(level: LogLevel?, limit: Int): ApiResult<HostJournal> =
        ApiResult.Unsupported
    override suspend fun diskSleep(): ApiResult<DiskSleep> = ApiResult.Unsupported
    override suspend fun scheduledOff(): ApiResult<ScheduledOff> = ApiResult.Unsupported
    override suspend fun setScheduledOff(schedule: ScheduledOff): ApiResult<Int> =
        ApiResult.Unsupported
    override suspend fun power(action: HostPower): ApiResult<Int> = ApiResult.Unsupported
    override fun close() = Unit
}
