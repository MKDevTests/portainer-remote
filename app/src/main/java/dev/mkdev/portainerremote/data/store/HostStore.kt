package dev.mkdev.portainerremote.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mkdev.portainerremote.domain.HostConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.hostDataStore by preferencesDataStore(name = "portainer_host")

@Serializable
private data class StoredHost(
    val serverId: String,
    val baseUrl: String = "",
    val username: String = "",
    /** Chiffre par [SecretCrypto], comme le secret Portainer. */
    val secret: String = "",
    val portainerAppId: String = "",
)

/**
 * L'hote ZimaOS attache a un serveur Portainer, quand il y en a un.
 *
 * Le lien est fait par identifiant de serveur, pas par adresse : deux serveurs
 * peuvent viser la meme machine, et une machine peut changer d'adresse sans
 * cesser d'etre la meme. Supprimer un serveur ne supprime pas son hote ici -
 * l'entree devient simplement inatteignable, et sera ecrasee si l'identifiant
 * reapparait.
 */
class HostStore(context: Context) {

    private val appContext = context.applicationContext
    private val key = stringPreferencesKey("hosts_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decode(raw: String?): List<StoredHost> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<StoredHost>>(raw) }.getOrDefault(emptyList())

    fun config(serverId: String): Flow<HostConfig> = appContext.hostDataStore.data.map { prefs ->
        decode(prefs[key]).firstOrNull { it.serverId == serverId }
            ?.let { HostConfig(it.baseUrl, it.username, it.portainerAppId) }
            ?: HostConfig()
    }

    private suspend fun raw(): List<StoredHost> = decode(appContext.hostDataStore.data.first()[key])

    suspend fun get(serverId: String): HostConfig =
        raw().firstOrNull { it.serverId == serverId }
            ?.let { HostConfig(it.baseUrl, it.username, it.portainerAppId) }
            ?: HostConfig()

    /** Mot de passe en clair, dechiffre a la demande. Null si le Keystore ne le lit plus. */
    suspend fun passwordOf(serverId: String): String? =
        raw().firstOrNull { it.serverId == serverId }?.secret
            ?.takeIf { it.isNotEmpty() }?.let(SecretCrypto::decrypt)

    /**
     * [plainPassword] a null conserve le mot de passe existant : on peut ainsi
     * changer l'application choisie sans avoir a le ressaisir.
     */
    suspend fun save(serverId: String, config: HostConfig, plainPassword: String?) {
        appContext.hostDataStore.edit { prefs ->
            val current = decode(prefs[key]).toMutableList()
            val index = current.indexOfFirst { it.serverId == serverId }
            val kept = current.getOrNull(index)?.secret.orEmpty()
            val entry = StoredHost(
                serverId = serverId,
                baseUrl = config.baseUrl.trimEnd('/'),
                username = config.username,
                secret = plainPassword?.let(SecretCrypto::encrypt) ?: kept,
                portainerAppId = config.portainerAppId,
            )
            if (index >= 0) current[index] = entry else current.add(entry)
            prefs[key] = json.encodeToString(current.toList())
        }
    }

    suspend fun forget(serverId: String) {
        appContext.hostDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(decode(prefs[key]).filterNot { it.serverId == serverId })
        }
    }
}
