package dev.mkdev.portainerremote.data.backup

import android.content.Context
import android.net.Uri
import dev.mkdev.portainerremote.BuildConfig
import dev.mkdev.portainerremote.data.store.FavoriteStack
import dev.mkdev.portainerremote.data.store.FavoritesStore
import dev.mkdev.portainerremote.data.store.PrefsStore
import dev.mkdev.portainerremote.data.store.ServerStore
import dev.mkdev.portainerremote.domain.AuthMode
import dev.mkdev.portainerremote.domain.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ImportResult(val servers: Int, val favorites: Int)

/**
 * Export et import de la configuration.
 *
 * Une desinstallation efface le DataStore *et* la cle du Keystore qui scelle les
 * jetons : sans sauvegarde, chaque reinstallation impose de tout ressaisir. Le
 * fichier produit contient les jetons, donc il n'existe que chiffre par une
 * phrase de passe — voir [BackupCrypto].
 *
 * L'ecriture passe par le Storage Access Framework : l'utilisateur choisit
 * l'emplacement, et l'application n'a besoin d'aucune permission de stockage.
 */
class BackupManager(
    private val context: Context,
    private val serverStore: ServerStore,
    private val favoritesStore: FavoritesStore,
    private val prefsStore: PrefsStore,
) {

    /** 0 si aucune sauvegarde n'a ete exportee depuis cette installation. */
    suspend fun lastExportAt(): Long = prefsStore.lastExportAt()

    fun suggestedFileName(): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "portainer-remote-$day.backup.json"
    }

    private suspend fun collect(): BackupPayload {
        val servers = serverStore.servers.first().map { server ->
            BackupServer(
                id = server.id,
                label = server.label,
                baseUrl = server.baseUrl,
                authMode = server.authMode.name,
                username = server.username,
                // Un secret que le Keystore ne sait plus lire n'est pas une
                // erreur bloquante : le reste de la configuration vaut d'etre
                // sauve, et l'ecran d'import le signale.
                secret = serverStore.secretOf(server.id).orEmpty(),
            )
        }

        val favorites = favoritesStore.current().map {
            BackupFavorite(it.serverId, it.serverLabel, it.stackKey, it.name, it.envId)
        }

        return BackupPayload(
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            servers = servers,
            favorites = favorites,
            pinnedPorts = prefsStore.currentPinnedPorts(),
        )
    }

    /** @return le nombre de serveurs ecrits. */
    suspend fun export(target: Uri, passphrase: String): Int = withContext(Dispatchers.IO) {
        val payload = collect()
        val sealed = BackupCrypto.encrypt(payload, passphrase)

        val stream = context.contentResolver.openOutputStream(target, "wt")
            ?: error("Impossible d'écrire dans ce fichier.")
        stream.use { it.write(sealed.toByteArray(Charsets.UTF_8)) }
        prefsStore.setLastExportAt(System.currentTimeMillis())

        payload.servers.size
    }

    /**
     * Restaure par-dessus la configuration en place. Les identifiants sont
     * conserves, donc reimporter deux fois la meme sauvegarde ne cree pas de
     * doublons : chaque serveur ecrase son homologue.
     */
    suspend fun import(source: Uri, passphrase: String): ImportResult =
        withContext(Dispatchers.IO) {
            val content = context.contentResolver.openInputStream(source)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Impossible de lire ce fichier.")

            val payload = BackupCrypto.decrypt(content, passphrase)

            payload.servers.forEach { stored ->
                serverStore.upsert(
                    Server(
                        id = stored.id,
                        label = stored.label,
                        baseUrl = stored.baseUrl,
                        authMode = runCatching { AuthMode.valueOf(stored.authMode) }
                            .getOrDefault(AuthMode.API_KEY),
                        username = stored.username,
                    ),
                    // Vide veut dire « garder ce qui est en place » cote store ;
                    // c'est le bon comportement pour un secret non exportable.
                    plainSecret = stored.secret.takeIf { it.isNotEmpty() },
                )
            }

            favoritesStore.addAll(
                payload.favorites.map {
                    FavoriteStack(it.serverId, it.serverLabel, it.stackKey, it.name, it.envId)
                },
            )

            prefsStore.addPinnedPorts(payload.pinnedPorts)

            ImportResult(payload.servers.size, payload.favorites.size)
        }
}
