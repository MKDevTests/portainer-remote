package dev.mkdev.portainerremote.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
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

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private companion object {
        const val TAG = "UpdateManager"
    }
}
