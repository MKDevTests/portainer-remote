package dev.mkdev.portainerremote.data

import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.data.net.HostClient
import dev.mkdev.portainerremote.data.store.HostStore
import dev.mkdev.portainerremote.domain.Host
import dev.mkdev.portainerremote.domain.HostApp
import dev.mkdev.portainerremote.domain.HostAppAction
import dev.mkdev.portainerremote.domain.HostPower
import dev.mkdev.portainerremote.domain.HostUsage
import dev.mkdev.portainerremote.domain.ScheduledOff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/**
 * L'acces aux NAS declares, s'il y en a.
 *
 * Tout ici est optionnel par construction : sans hote configure, chaque methode
 * repond [ApiResult.Unsupported] et l'interface n'affiche rien. C'est ce qui
 * garantit que l'application reste utilisable avec un Portainer quelconque, sur
 * une machine dont on ne sait rien.
 *
 * Le systeme du NAS n'apparait nulle part ici : c'est [HostClient] qui le sait.
 */
class HostRepository(private val store: HostStore) {

    /**
     * Un client par hote, garde entre les appels pour ne pas se reconnecter a
     * chaque action : le jeton vit dans le client.
     */
    private val clients = mutableMapOf<String, Pair<Host, HostClient>>()

    /**
     * L'ecran lance ses lectures en parallele. Sans verrou, deux d'entre elles
     * peuvent construire chacune leur client et fermer celui de l'autre en
     * plein appel : le symptome est une lecture qui echoue au hasard.
     */
    private val lock = Mutex()

    val hosts: Flow<List<Host>> = store.hosts

    private suspend fun client(hostId: String): HostClient? = lock.withLock {
        val host = store.get(hostId)
        if (host == null || !host.configured) {
            clients.remove(hostId)?.second?.close()
            return@withLock null
        }
        clients[hostId]?.let { (cached, client) -> if (cached == host) return@withLock client }
        clients.remove(hostId)?.second?.close()
        val password = store.passwordOf(hostId).orEmpty()
        val client = HostClient.of(host, password)
        clients[hostId] = host to client
        client
    }

    /** A appeler apres une ecriture : le client suivant repartira du nouveau reglage. */
    suspend fun invalidate(hostId: String) = lock.withLock {
        clients.remove(hostId)?.second?.close()
        Unit
    }

    private suspend fun <T> withClient(
        hostId: String,
        block: suspend (HostClient) -> ApiResult<T>,
    ): ApiResult<T> = client(hostId)?.let { block(it) } ?: ApiResult.Unsupported

    // --------------------------------------------------------------- reglage

    suspend fun current(): List<Host> = store.current()

    suspend fun get(hostId: String): Host? = store.get(hostId)

    suspend fun save(host: Host, password: String?): String {
        val id = store.save(host, password)
        invalidate(id)
        return id
    }

    suspend fun forget(hostId: String) {
        store.forget(hostId)
        invalidate(hostId)
    }

    /**
     * Teste une adresse et des identifiants avant de les enregistrer.
     * On separe volontairement les deux echecs : une adresse qui ne repond pas
     * et un mot de passe refuse ne se corrigent pas de la meme facon.
     */
    suspend fun test(host: Host, password: String): ApiResult<Boolean> {
        val client = HostClient.of(host, password)
        return try {
            if (!client.detect()) ApiResult.Unsupported else client.signIn()
        } finally {
            client.close()
        }
    }

    /**
     * L'adresse probable de l'interface du NAS, deduite de celle de Portainer.
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

    suspend fun apps(hostId: String): ApiResult<List<HostApp>> =
        withClient(hostId) { it.apps() }

    suspend fun setAppStatus(
        hostId: String,
        appId: String,
        action: HostAppAction,
    ): ApiResult<Int> = withClient(hostId) { it.setAppStatus(appId, action) }

    /** Relance l'application qui heberge Portainer, choisie une fois dans les reglages. */
    suspend fun startPortainer(hostId: String): ApiResult<Int> {
        val appId = store.get(hostId)?.portainerAppId.orEmpty()
        if (appId.isBlank()) return ApiResult.Unsupported
        return setAppStatus(hostId, appId, HostAppAction.START)
    }

    suspend fun usage(hostId: String): ApiResult<HostUsage> =
        withClient(hostId) { it.usage() }

    suspend fun scheduledOff(hostId: String): ApiResult<ScheduledOff> =
        withClient(hostId) { it.scheduledOff() }

    suspend fun power(hostId: String, action: HostPower): ApiResult<Int> =
        withClient(hostId) { it.power(action) }
}
