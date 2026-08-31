package dev.mkdev.portainerremote.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mkdev.portainerremote.domain.AuthMode
import dev.mkdev.portainerremote.domain.Server
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.serverDataStore by preferencesDataStore(name = "portainer_servers")

@Serializable
private data class StoredServer(
    val id: String,
    val label: String,
    val baseUrl: String,
    val authMode: String,
    val username: String = "",
    /** Chiffre par [SecretCrypto]. Jamais en clair sur le disque. */
    val secret: String = "",
)

/**
 * Persistance des serveurs configures.
 *
 * Le JWT du mode mot de passe n'est volontairement pas persiste : il vit en
 * memoire dans le client HTTP et disparait avec le processus.
 */
class ServerStore(context: Context) {

    private val appContext = context.applicationContext
    private val key = stringPreferencesKey("servers_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decode(raw: String?): List<StoredServer> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<StoredServer>>(raw) }.getOrDefault(emptyList())

    val servers: Flow<List<Server>> = appContext.serverDataStore.data.map { prefs ->
        decode(prefs[key]).map { stored ->
            Server(
                id = stored.id,
                label = stored.label,
                baseUrl = stored.baseUrl,
                authMode = runCatching { AuthMode.valueOf(stored.authMode) }.getOrDefault(AuthMode.API_KEY),
                username = stored.username,
            )
        }
    }

    private suspend fun raw(): List<StoredServer> =
        decode(appContext.serverDataStore.data.first()[key])

    suspend fun get(id: String): Server? = servers.first().firstOrNull { it.id == id }

    /** Secret en clair, dechiffre a la demande. Null si le Keystore ne peut plus le lire. */
    suspend fun secretOf(id: String): String? =
        raw().firstOrNull { it.id == id }?.secret?.takeIf { it.isNotEmpty() }?.let(SecretCrypto::decrypt)

    /**
     * Cree ou met a jour un serveur.
     * [plainSecret] a null conserve le secret existant : l'ecran d'edition n'a
     * ainsi pas besoin de reafficher le jeton pour permettre de renommer.
     */
    suspend fun upsert(server: Server, plainSecret: String?): String {
        val id = server.id.ifBlank { UUID.randomUUID().toString() }
        appContext.serverDataStore.edit { prefs ->
            val current = decode(prefs[key]).toMutableList()
            val index = current.indexOfFirst { it.id == id }
            val keptSecret = current.getOrNull(index)?.secret.orEmpty()
            val entry = StoredServer(
                id = id,
                label = server.label.ifBlank { server.baseUrl },
                baseUrl = server.baseUrl.trimEnd('/'),
                authMode = server.authMode.name,
                username = server.username,
                secret = plainSecret?.let(SecretCrypto::encrypt) ?: keptSecret,
            )
            if (index >= 0) current[index] = entry else current.add(entry)
            prefs[key] = json.encodeToString(current.toList())
        }
        return id
    }

    suspend fun delete(id: String) {
        appContext.serverDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(decode(prefs[key]).filterNot { it.id == id })
        }
    }
}
