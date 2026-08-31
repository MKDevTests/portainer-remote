package dev.mkdev.portainerremote.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.widgetDataStore by preferencesDataStore(name = "portainer_widget")

/** Un stack epingle. Porte assez d'information pour etre affiche sans appel reseau. */
@Serializable
data class FavoriteStack(
    val serverId: String,
    val serverLabel: String,
    val stackKey: String,
    val name: String,
    val envId: Int,
)

/**
 * Dernier etat connu des favoris.
 *
 * Le widget doit dessiner quelque chose immediatement, avant tout appel reseau
 * et meme VPN coupe. Il lit cet instantane ; le rafraichissement le remplace.
 */
@Serializable
data class WidgetEntry(
    val serverId: String,
    val stackKey: String,
    val name: String,
    val serverLabel: String = "",
    /** RUNNING, PARTIAL, STOPPED ou UNKNOWN. */
    val state: String = "UNKNOWN",
)

@Serializable
data class WidgetSnapshot(
    val entries: List<WidgetEntry> = emptyList(),
    val updatedAt: Long = 0,
    val stale: Boolean = false,
)

class FavoritesStore(context: Context) {

    private val appContext = context.applicationContext
    private val favoritesKey = stringPreferencesKey("favorites_json")
    private val snapshotKey = stringPreferencesKey("snapshot_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val favorites: Flow<List<FavoriteStack>> = appContext.widgetDataStore.data.map { prefs ->
        decodeFavorites(prefs[favoritesKey])
    }

    private fun decodeFavorites(raw: String?): List<FavoriteStack> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<FavoriteStack>>(raw) }.getOrDefault(emptyList())

    suspend fun current(): List<FavoriteStack> =
        decodeFavorites(appContext.widgetDataStore.data.first()[favoritesKey])

    suspend fun isFavorite(serverId: String, stackKey: String): Boolean =
        current().any { it.serverId == serverId && it.stackKey == stackKey }

    suspend fun toggle(favorite: FavoriteStack): Boolean {
        var nowFavorite = false
        appContext.widgetDataStore.edit { prefs ->
            val list = decodeFavorites(prefs[favoritesKey]).toMutableList()
            val index = list.indexOfFirst {
                it.serverId == favorite.serverId && it.stackKey == favorite.stackKey
            }
            if (index >= 0) {
                list.removeAt(index)
            } else {
                list.add(favorite)
                nowFavorite = true
            }
            prefs[favoritesKey] = json.encodeToString(list.toList())
        }
        return nowFavorite
    }

    /** Retire les favoris d'un serveur supprime, sinon le widget garde des fantomes. */
    suspend fun forgetServer(serverId: String) {
        appContext.widgetDataStore.edit { prefs ->
            val kept = decodeFavorites(prefs[favoritesKey]).filterNot { it.serverId == serverId }
            prefs[favoritesKey] = json.encodeToString(kept)
        }
    }

    suspend fun snapshot(): WidgetSnapshot {
        val raw = appContext.widgetDataStore.data.first()[snapshotKey] ?: return WidgetSnapshot()
        return runCatching { json.decodeFromString<WidgetSnapshot>(raw) }.getOrDefault(WidgetSnapshot())
    }

    suspend fun saveSnapshot(snapshot: WidgetSnapshot) {
        appContext.widgetDataStore.edit { prefs ->
            prefs[snapshotKey] = json.encodeToString(snapshot)
        }
    }
}
