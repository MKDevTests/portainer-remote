package dev.mkdev.portainerremote.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.data.net.ReleaseInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Telecharge l'APK d'une release et le passe a l'installateur du systeme.
 *
 * L'application n'installe rien elle-meme et ne le peut pas : elle depose un
 * fichier et ouvre l'ecran d'installation d'Android, qui demande sa propre
 * confirmation. Android refuse par ailleurs tout APK signe par une autre cle
 * que celle de la version deja installee — c'est cette verification, et non
 * l'application, qui protege contre un binaire substitue.
 */
class UpdateManager(private val context: Context) {

    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            // Volontairement aucun requestTimeoutMillis : un APK de 16 Mo sur un
            // reseau lent depasse largement le delai des appels d'API. C'est le
            // delai de socket qui protege, en coupant un flux vraiment mort.
            socketTimeoutMillis = 30_000
        }
    }

    private val directory: File get() = File(context.cacheDir, "updates")

    /**
     * @param onProgress fraction entre 0 et 1, ou null tant que la taille
     *   annoncee est inconnue.
     */
    suspend fun download(
        release: ReleaseInfo,
        onProgress: (Float?) -> Unit,
    ): ApiResult<File> = withContext(Dispatchers.IO) {
        try {
            // Un APK d'une version precedente n'a plus aucune utilite, et 16 Mo
            // de cache oublie se voient dans les reglages de l'appareil.
            directory.deleteRecursively()
            directory.mkdirs()

            val target = File(directory, "portainer-remote-${release.version}.apk")

            val response = http.get(release.downloadUrl) {
                header("User-Agent", "portainer-remote")
                onDownload { received, total ->
                    onProgress(if (total != null && total > 0) received.toFloat() / total else null)
                }
            }

            if (!response.status.isSuccess()) {
                Log.w(TAG, "telechargement refuse : ${response.status.value}")
                return@withContext ApiResult.HttpError(response.status.value)
            }

            target.outputStream().use { output ->
                response.bodyAsChannel().copyTo(output)
            }

            if (target.length() == 0L) {
                return@withContext ApiResult.NetworkError("Fichier vide.")
            }

            Log.d(TAG, "telecharge : ${target.name}, ${target.length()} octets")
            ApiResult.Ok(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "telechargement impossible : ${e::class.simpleName} ${e.message}")
            ApiResult.NetworkError(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /**
     * L'autorisation d'installer depuis cette source est un reglage systeme,
     * distinct de la permission du manifeste, et revocable a tout moment.
     */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Ouvre le reglage Android ou l'utilisateur accorde cette autorisation. */
    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Remet l'APK au systeme, par une session d'installation.
     *
     * L'ancien chemin ouvrait une intention ACTION_VIEW sur le fichier. Quand
     * Android ne l'honorait pas - ce qui arrive selon la version et le
     * constructeur - il ne se passait rien : aucune fenetre, aucune erreur, et
     * l'ecran continuait d'annoncer une mise a jour qui ne s'installait jamais.
     *
     * Une session rend toujours un verdict. Android affiche sa propre
     * confirmation, comme avant - l'application n'installe rien elle-meme et ne
     * le peut pas - mais cette fois le refus revient et peut etre affiche.
     */
    suspend fun install(apk: File): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
                runCatching { setSize(apk.length()) }
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(NAME, 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }

                // FLAG_MUTABLE est obligatoire : c'est le systeme qui remplit
                // cette intention avec le statut et, au besoin, son ecran de
                // confirmation. Immuable, elle reviendrait vide.
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(InstallReceiver.ACTION).setPackage(context.packageName)
                        .setClass(context, InstallReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(callback.intentSender)
            }
            Log.d(TAG, "session $sessionId remise au systeme")
            ApiResult.Ok(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "installation impossible : ${e::class.simpleName} ${e.message}")
            ApiResult.NetworkError(
                e.message ?: e::class.simpleName ?: "installation refusée par le système",
            )
        }
    }

    private companion object {
        const val TAG = "UpdateManager"

        /** Nom du flux dans la session. Sans importance pour le systeme. */
        const val NAME = "portainer-remote"
    }
}
