package dev.mkdev.portainerremote.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mkdev.portainerremote.domain.Host
import dev.mkdev.portainerremote.domain.HostKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.hostDataStore by preferencesDataStore(name = "portainer_host")

@Serializable
private data class StoredHost(
    /**
     * Vide dans les enregistrements d'avant : l'hote y etait range sous
     * l'identifiant de son serveur Portainer. Voir [HostStore.migrate].
     */
    val id: String = "",
    val kind: String = HostKind.ZIMA.name,
    val label: String = "",
    val baseUrl: String = "",
    val username: String = "",
    /** Chiffre par [SecretCrypto], comme le secret Portainer. */
    val secret: String = "",
    /** Lien optionnel vers un serveur Portainer. */
    val serverId: String = "",
    val portainerAppId: String = "",
)

/**
 * Les NAS connus de l'application.
 *
 * Chacun a son identifiant propre. Le ranger sous celui d'un serveur Portainer,
 * comme c'etait le cas au depart, revenait a dire qu'une machine appartient a
 * l'un de ses logiciels : deux serveurs vers le meme NAS en faisaient deux
 * configurations, et supprimer le serveur oubliait la machine.
 */
class HostStore(context: Context) {

    private val appContext = context.applicationContext
    private val key = stringPreferencesKey("hosts_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decode(raw: String?): List<StoredHost> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<StoredHost>>(raw) }
            .getOrDefault(emptyList())
            .map(::migrate)

    /**
     * Un enregistrement d'avant n'a pas d'identifiant propre : il en recoit un,
     * celui du serveur auquel il etait attache, ce qui le rend stable et evite
     * de creer un doublon a chaque lecture. Son type est ZimaOS, puisque c'est
     * le seul qui existait alors. Le secret scelle, lui, ne bouge pas : il
     * reste lisible par la meme cle du Keystore.
     */
    private fun migrate(stored: StoredHost): StoredHost =
        if (stored.id.isNotBlank()) stored else stored.copy(id = stored.serverId)

    private fun StoredHost.toHost() = Host(
        id = id,
        kind = runCatching { HostKind.valueOf(kind) }.getOrDefault(HostKind.ZIMA),
        label = label,
        baseUrl = baseUrl,
        username = username,
        serverId = serverId,
        portainerAppId = portainerAppId,
    )

    val hosts: Flow<List<Host>> = appContext.hostDataStore.data.map { prefs ->
        decode(prefs[key]).filter { it.id.isNotBlank() }.map { it.toHost() }
    }

    private suspend fun raw(): List<StoredHost> = decode(appContext.hostDataStore.data.first()[key])

    suspend fun current(): List<Host> = raw().filter { it.id.isNotBlank() }.map { it.toHost() }

    suspend fun get(id: String): Host? = raw().firstOrNull { it.id == id }?.toHost()

    /** Mot de passe en clair, dechiffre a la demande. Null si le Keystore ne le lit plus. */
    suspend fun passwordOf(id: String): String? =
        raw().firstOrNull { it.id == id }?.secret
            ?.takeIf { it.isNotEmpty() }?.let(SecretCrypto::decrypt)

    /**
     * Cree ou met a jour un hote, et renvoie son identifiant.
     * [plainPassword] a null conserve le mot de passe existant : on peut ainsi
     * renommer un NAS ou changer l'application choisie sans le ressaisir.
     */
    suspend fun save(host: Host, plainPassword: String?): String {
        val id = host.id.ifBlank { UUID.randomUUID().toString() }
        appContext.hostDataStore.edit { prefs ->
            val current = decode(prefs[key]).toMutableList()
            val index = current.indexOfFirst { it.id == id }
            val kept = current.getOrNull(index)?.secret.orEmpty()
            val entry = StoredHost(
                id = id,
                kind = host.kind.name,
                label = host.label,
                baseUrl = host.baseUrl.trimEnd('/'),
                username = host.username,
                secret = plainPassword?.let(SecretCrypto::encrypt) ?: kept,
                serverId = host.serverId,
                portainerAppId = host.portainerAppId,
            )
            if (index >= 0) current[index] = entry else current.add(entry)
            prefs[key] = json.encodeToString(current.toList())
        }
        return id
    }

    suspend fun forget(id: String) {
        appContext.hostDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(decode(prefs[key]).filterNot { it.id == id })
        }
    }
}
