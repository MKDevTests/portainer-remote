package dev.mkdev.portainerremote.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.prefsDataStore by preferencesDataStore(name = "portainer_prefs")

/**
 * Clef d'un port epingle.
 *
 * Volontairement fondee sur le **nom** du conteneur et non sur son identifiant :
 * un `compose up` recree le conteneur avec un nouvel identifiant mais garde son
 * nom. Une clef par identifiant serait perdue au premier redeploiement, c'est-a-dire
 * exactement quand le reglage doit survivre.
 */
fun portPinKey(serverId: String, envId: Int, containerName: String): String =
    "$serverId|$envId|$containerName"

/**
 * Le nom du conteneur tel qu'il est inscrit dans une clef.
 *
 * Sert aux favoris dont le conteneur a disparu : sans lui, un favori devenu
 * introuvable n'aurait plus rien a afficher, donc plus rien a cliquer pour
 * etre retire.
 */
fun containerNameOfKey(key: String): String = key.split('|', limit = 3).getOrElse(2) { key }

fun envIdOfKey(key: String): Int = key.split('|', limit = 3).getOrNull(1)?.toIntOrNull() ?: 0

fun serverIdOfKey(key: String): String = key.substringBefore('|')

/** Petits reglages qui n'appartiennent ni aux serveurs ni aux favoris. */
class PrefsStore(context: Context) {

    private val appContext = context.applicationContext
    private val notifiedVersionKey = stringPreferencesKey("notified_version")
    private val lastExportKey = longPreferencesKey("last_export_at")
    private val pinnedPortsKey = stringPreferencesKey("pinned_ports_json")
    private val favoriteContainersKey = stringPreferencesKey("favorite_containers_json")
    private val favoritesViewKey = stringPreferencesKey("favorites_view")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Derniere version deja annoncee. Sans elle, la verification quotidienne
     * renotifierait la meme version tous les jours jusqu'a l'installation.
     */
    suspend fun notifiedVersion(): String =
        appContext.prefsDataStore.data.first()[notifiedVersionKey].orEmpty()

    suspend fun setNotifiedVersion(version: String) {
        appContext.prefsDataStore.edit { it[notifiedVersionKey] = version }
    }

    /**
     * Port choisi a la main pour le raccourci d'un conteneur, par clef.
     *
     * Aucune heuristique ne peut deviner lequel des ports publies porte
     * l'interface web : un client BitTorrent en publie plusieurs et rien ne les
     * distingue. C'est donc a l'utilisateur de trancher, une fois.
     */
    val pinnedPorts: Flow<Map<String, Int>> =
        appContext.prefsDataStore.data.map { decodePins(it[pinnedPortsKey]) }

    suspend fun currentPinnedPorts(): Map<String, Int> =
        decodePins(appContext.prefsDataStore.data.first()[pinnedPortsKey])

    /** Un port nul efface le reglage : c'est ainsi qu'on revient au comportement automatique. */
    suspend fun setPinnedPort(key: String, port: Int?) {
        appContext.prefsDataStore.edit { prefs ->
            val pins = decodePins(prefs[pinnedPortsKey]).toMutableMap()
            if (port == null) pins.remove(key) else pins[key] = port
            prefs[pinnedPortsKey] = json.encodeToString(pins.toMap())
        }
    }

    /** Fusionne sans rien retirer, pour la restauration d'une sauvegarde. */
    suspend fun addPinnedPorts(pins: Map<String, Int>) {
        if (pins.isEmpty()) return
        appContext.prefsDataStore.edit { prefs ->
            val merged = decodePins(prefs[pinnedPortsKey]) + pins
            prefs[pinnedPortsKey] = json.encodeToString(merged)
        }
    }

    /**
     * Conteneurs mis en favori, par clef.
     *
     * Deliberement ici et non dans FavoritesStore : ce dernier alimente le
     * widget, la tuile et StackActionWorker, qui retrouvent tous un favori par
     * son stackKey. Un conteneur range la-bas n'aurait aucun stack correspondant
     * et casserait le widget sans bruit.
     */
    val favoriteContainers: Flow<Set<String>> =
        appContext.prefsDataStore.data.map { decodeKeys(it[favoriteContainersKey]) }

    suspend fun currentFavoriteContainers(): Set<String> =
        decodeKeys(appContext.prefsDataStore.data.first()[favoriteContainersKey])

    /** @return vrai si le conteneur est desormais favori. */
    suspend fun toggleFavoriteContainer(key: String): Boolean {
        var nowFavorite = false
        appContext.prefsDataStore.edit { prefs ->
            val keys = decodeKeys(prefs[favoriteContainersKey]).toMutableSet()
            nowFavorite = keys.add(key)
            if (!nowFavorite) keys.remove(key)
            prefs[favoriteContainersKey] = json.encodeToString(keys.toList())
        }
        return nowFavorite
    }

    /** Fusionne sans rien retirer, pour la restauration d'une sauvegarde. */
    suspend fun addFavoriteContainers(keys: Collection<String>) {
        if (keys.isEmpty()) return
        appContext.prefsDataStore.edit { prefs ->
            val merged = decodeKeys(prefs[favoriteContainersKey]) + keys
            prefs[favoriteContainersKey] = json.encodeToString(merged.toList())
        }
    }

    /** Vide tant que rien n'a ete choisi : l'appelant decide du defaut. */
    suspend fun favoritesView(): String =
        appContext.prefsDataStore.data.first()[favoritesViewKey].orEmpty()

    suspend fun setFavoritesView(mode: String) {
        appContext.prefsDataStore.edit { it[favoritesViewKey] = mode }
    }

    private fun decodeKeys(raw: String?): Set<String> =
        if (raw.isNullOrBlank()) emptySet()
        else runCatching { json.decodeFromString<List<String>>(raw).toSet() }.getOrDefault(emptySet())

    private fun decodePins(raw: String?): Map<String, Int> =
        if (raw.isNullOrBlank()) emptyMap()
        else runCatching { json.decodeFromString<Map<String, Int>>(raw) }.getOrDefault(emptyMap())

    /** 0 si aucune sauvegarde n'a jamais ete exportee depuis cette installation. */
    suspend fun lastExportAt(): Long =
        appContext.prefsDataStore.data.first()[lastExportKey] ?: 0L

    suspend fun setLastExportAt(instant: Long) {
        appContext.prefsDataStore.edit { it[lastExportKey] = instant }
    }
}
