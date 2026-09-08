package dev.mkdev.portainerremote.data

import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.data.net.ZimaClient
import dev.mkdev.portainerremote.data.store.HostStore
import dev.mkdev.portainerremote.domain.HostApp
import dev.mkdev.portainerremote.domain.HostAppAction
import dev.mkdev.portainerremote.domain.HostConfig
import dev.mkdev.portainerremote.domain.HostPower
import dev.mkdev.portainerremote.domain.HostUsage
import dev.mkdev.portainerremote.domain.ScheduledOff
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/**
 * L'acces a l'hote ZimaOS, s'il y en a un.
 *
 * Tout ici est optionnel par construction : sans configuration, chaque methode
 * repond [ApiResult.Unsupported] et l'interface n'affiche rien. C'est ce qui
 * garantit que l'app reste utilisable avec un Portainer quelconque, sur une
 * machine qui n'a jamais entendu parler de ZimaOS.
 */
class HostRepository(private val store: HostStore) {

    /**
     * Un client par serveur, garde entre les appels pour ne pas se reconnecter
     * a chaque action : le jeton vit dans le client.
     */
    private val clients = mutableMapOf<String, Pair<HostConfig, ZimaClient>>()

    /**
     * L'ecran lance ses lectures en parallele. Sans verrou, deux d'entre elles
     * peuvent construire chacune leur client et fermer celui de l'autre en
     * plein appel : le symptome est une lecture qui echoue au hasard.
     */
    private val lock = Mutex()

    private suspend fun client(serverId: String): ZimaClient? = lock.withLock {
        val config = store.get(serverId)
        if (!config.configured) {
            clients.remove(serverId)?.second?.close()
            return@withLock null
        }
        clients[serverId]?.let { (cached, client) -> if (cached == config) return@withLock client }
        clients.remove(serverId)?.second?.close()
        val password = store.passwordOf(serverId).orEmpty()
        val client = ZimaClient(config.baseUrl, config.username, password)
        clients[serverId] = config to client
        client
    }

    /** A appeler apres une ecriture : le client suivant repartira du nouveau reglage. */
    suspend fun invalidate(serverId: String) = lock.withLock {
        clients.remove(serverId)?.second?.close()
        Unit
    }

    private suspend fun <T> withClient(
        serverId: String,
        block: suspend (ZimaClient) -> ApiResult<T>,
    ): ApiResult<T> = client(serverId)?.let { block(it) } ?: ApiResult.Unsupported

    // --------------------------------------------------------------- reglage

    suspend fun config(serverId: String): HostConfig = store.get(serverId)

    suspend fun save(serverId: String, config: HostConfig, password: String?) {
        store.save(serverId, config, password)
        invalidate(serverId)
    }

    suspend fun forget(serverId: String) {
        store.forget(serverId)
        invalidate(serverId)
    }

    /**
     * Teste une adresse et des identifiants avant de les enregistrer.
     * On separe volontairement les deux echecs : une adresse qui ne repond pas
     * et un mot de passe refuse ne se corrigent pas de la meme facon.
     */
    suspend fun test(baseUrl: String, username: String, password: String): ApiResult<Boolean> {
        val client = ZimaClient(baseUrl, username, password)
        return try {
            if (!client.detect()) ApiResult.Unsupported else client.signIn()
        } finally {
            client.close()
        }
    }

    /**
     * L'adresse probable de l'interface ZimaOS, deduite de celle de Portainer.
     *
     * Meme machine, port par defaut du web : c'est vrai dans le cas courant et
     * faux dans les autres, donc c'est une proposition pre-remplie, jamais un
     * reglage impose. L'utilisateur la corrige si son installation differe.
     */
    fun guessBaseUrl(portainerBaseUrl: String): String {
        val host = runCatching { URI(portainerBaseUrl).host }.getOrNull()
            ?: return portainerBaseUrl
        return "http://$host"
    }

    // --------------------------------------------------------------- actions

    suspend fun apps(serverId: String): ApiResult<List<HostApp>> =
        withClient(serverId) { it.apps() }

    suspend fun setAppStatus(
        serverId: String,
        appId: String,
        action: HostAppAction,
    ): ApiResult<Int> = withClient(serverId) { it.setAppStatus(appId, action) }

    /** Relance l'application qui heberge Portainer, choisie une fois dans les reglages. */
    suspend fun startPortainer(serverId: String): ApiResult<Int> {
        val appId = store.get(serverId).portainerAppId
        if (appId.isBlank()) return ApiResult.Unsupported
        return setAppStatus(serverId, appId, HostAppAction.START)
    }

    suspend fun usage(serverId: String): ApiResult<HostUsage> =
        withClient(serverId) { it.usage() }

    suspend fun scheduledOff(serverId: String): ApiResult<ScheduledOff> =
        withClient(serverId) { it.scheduledOff() }

    suspend fun power(serverId: String, action: HostPower): ApiResult<Int> =
        withClient(serverId) { it.power(action) }
}
