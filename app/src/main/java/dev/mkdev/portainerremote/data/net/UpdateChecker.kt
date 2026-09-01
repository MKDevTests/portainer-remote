package dev.mkdev.portainerremote.data.net

import android.util.Log
import dev.mkdev.portainerremote.BuildConfig
import dev.mkdev.portainerremote.core.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GithubAsset(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    @SerialName("size") val size: Long = 0,
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tag: String = "",
    @SerialName("name") val title: String? = null,
    @SerialName("html_url") val pageUrl: String = "",
    @SerialName("body") val notes: String? = null,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("assets") val assets: List<GithubAsset> = emptyList(),
)

/** Ce que l'ecran affiche : une version plus recente et ou la telecharger. */
data class ReleaseInfo(
    val version: String,
    val title: String,
    val notes: String,
    /**
     * L'APK publie s'il existe, sinon la page de la release. Une release sans
     * APK reste utile : elle dit qu'une version existe.
     */
    val downloadUrl: String,
    val hasApk: Boolean,
)

/**
 * Regarde si une release GitHub est plus recente que la version installee.
 *
 * L'application ne s'installe pas elle-meme : cela demanderait la permission
 * REQUEST_INSTALL_PACKAGES, qui est exactement celle qu'on ne veut pas accorder
 * a une app de pilotage d'infrastructure. Elle ouvre le lien, l'utilisateur
 * decide.
 */
class UpdateChecker(
    private val repo: String = BuildConfig.UPDATE_REPO,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val apiRoot: String = BuildConfig.UPDATE_API,
) {

    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
        }
    }

    /** Ok(null) veut dire « a jour », pas « echec ». */
    suspend fun check(): ApiResult<ReleaseInfo?> = try {
        // Trace volontaire : l'echec est silencieux pour l'utilisateur, il faut
        // donc qu'il reste diagnosticable au logcat.
        Log.d(TAG, "verification des releases de $repo, version installee $currentVersion")
        val response = http.get("$apiRoot/repos/$repo/releases/latest") {
            // GitHub refuse les requetes sans User-Agent.
            header("User-Agent", "portainer-remote/$currentVersion")
            header("Accept", "application/vnd.github+json")
        }

        when {
            // 404 : depot prive, renomme, ou aucune release publiee. Aucun de ces
            // cas n'est une erreur a montrer a l'utilisateur.
            response.status.value == 404 -> {
                Log.d(TAG, "aucune release publiee, ou depot inaccessible")
                ApiResult.Ok(null)
            }
            !response.status.isSuccess() -> {
                Log.w(TAG, "reponse ${response.status.value}")
                ApiResult.HttpError(response.status.value)
            }
            else -> {
                val release: GithubRelease = response.body()
                val info = release.toInfoIfNewer(currentVersion)
                Log.d(TAG, "derniere release ${release.tag}, plus recente=${info != null}")
                ApiResult.Ok(info)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "verification impossible : ${e::class.simpleName} ${e.message}")
        ApiResult.NetworkError(e.message ?: e::class.simpleName.orEmpty())
    }

    private companion object {
        const val TAG = "UpdateChecker"
    }
}

private fun GithubRelease.toInfoIfNewer(current: String): ReleaseInfo? {
    if (draft || !isNewerVersion(tag, current)) return null

    val apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    return ReleaseInfo(
        version = tag,
        title = title?.takeIf { it.isNotBlank() } ?: tag,
        notes = notes.orEmpty().trim(),
        downloadUrl = apk?.downloadUrl?.takeIf { it.isNotBlank() } ?: pageUrl,
        hasApk = apk != null,
    )
}

/**
 * Compare deux versions numeriques, en tolerant le prefixe « v » et un suffixe
 * de pre-release. Un tag non numerique ne declenche jamais de proposition :
 * mieux vaut rater une mise a jour que d'en annoncer une qui n'existe pas.
 */
internal fun isNewerVersion(tag: String, current: String): Boolean {
    val a = versionParts(tag)
    val b = versionParts(current)
    if (a.isEmpty() || b.isEmpty()) return false

    for (i in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(i) { 0 }
        val right = b.getOrElse(i) { 0 }
        if (left != right) return left > right
    }
    return false
}

private fun versionParts(value: String): List<Int> {
    val parts = value.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { it.toIntOrNull() }

    // Un seul segment illisible invalide tout le tag : « 1.x.0 » ne se compare pas.
    return if (parts.any { it == null }) emptyList() else parts.filterNotNull()
}
