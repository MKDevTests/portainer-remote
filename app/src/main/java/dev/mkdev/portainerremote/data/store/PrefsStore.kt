package dev.mkdev.portainerremote.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.prefsDataStore by preferencesDataStore(name = "portainer_prefs")

/** Petits reglages qui n'appartiennent ni aux serveurs ni aux favoris. */
class PrefsStore(context: Context) {

    private val appContext = context.applicationContext
    private val notifiedVersionKey = stringPreferencesKey("notified_version")
    private val lastExportKey = longPreferencesKey("last_export_at")

    /**
     * Derniere version deja annoncee. Sans elle, la verification quotidienne
     * renotifierait la meme version tous les jours jusqu'a l'installation.
     */
    suspend fun notifiedVersion(): String =
        appContext.prefsDataStore.data.first()[notifiedVersionKey].orEmpty()

    suspend fun setNotifiedVersion(version: String) {
        appContext.prefsDataStore.edit { it[notifiedVersionKey] = version }
    }

    /** 0 si aucune sauvegarde n'a jamais ete exportee depuis cette installation. */
    suspend fun lastExportAt(): Long =
        appContext.prefsDataStore.data.first()[lastExportKey] ?: 0L

    suspend fun setLastExportAt(instant: Long) {
        appContext.prefsDataStore.edit { it[lastExportKey] = instant }
    }
}
