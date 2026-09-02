package dev.mkdev.portainerremote.data.net

import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.data.model.AuthPayload
import dev.mkdev.portainerremote.data.model.AuthResponse
import dev.mkdev.portainerremote.data.model.DockerContainer
import dev.mkdev.portainerremote.data.model.DockerContainerDetail
import dev.mkdev.portainerremote.data.model.DockerImage
import dev.mkdev.portainerremote.data.model.PortainerEndpoint
import dev.mkdev.portainerremote.data.model.PortainerStack
import dev.mkdev.portainerremote.data.model.PortainerSystemStatus
import dev.mkdev.portainerremote.data.model.StackFile
import dev.mkdev.portainerremote.data.model.StackUpdatePayload
import dev.mkdev.portainerremote.domain.AuthMode
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Client HTTP d'une instance Portainer.
 *
 * Aucun identifiant n'y est ecrit en dur : l'URL de base vient de la
 * configuration, les endpointId et les identifiants de stack sont decouverts
 * par les appels.
 */
class PortainerClient(
    baseUrl: String,
    private val authMode: AuthMode,
    private val username: String,
    private val secret: String,
) {

    private val root = baseUrl.trimEnd('/')

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            // Court volontairement : hors VPN, mieux vaut echouer vite et le dire
            // que faire tourner un spinner pendant trente secondes.
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
    }

    /** Le JWT ne vit qu'en memoire et disparait avec le processus. */
    @Volatile
    private var jwt: String? = null

    private fun url(path: String) = "$root$path"

    private suspend fun ensureJwt(): String? {
        jwt?.let { return it }
        if (username.isBlank()) return null
        val response = http.post(url("/api/auth")) {
            contentType(ContentType.Application.Json)
            setBody(AuthPayload(username, secret))
        }
        if (!response.status.isSuccess()) return null
        val token = runCatching { response.body<AuthResponse>().jwt }.getOrNull()
        jwt = token?.takeIf { it.isNotBlank() }
        return jwt
    }

    private suspend fun authHeader(): Pair<String, String>? = when (authMode) {
        AuthMode.API_KEY -> if (secret.isBlank()) null else "X-API-Key" to secret
        AuthMode.PASSWORD -> ensureJwt()?.let { "Authorization" to "Bearer $it" }
    }

    private suspend fun send(
        method: HttpMethod,
        path: String,
        retryOn401: Boolean = true,
    ): HttpResponse {
        val auth = authHeader()
        val response = http.request(url(path)) {
            this.method = method
            auth?.let { header(it.first, it.second) }
        }
        // Un JWT expire se rejoue une fois, sans deranger l'utilisateur.
        if (response.status.value == 401 && authMode == AuthMode.PASSWORD && retryOn401) {
            jwt = null
            return send(method, path, retryOn401 = false)
        }
        return response
    }

    private suspend inline fun <reified T> HttpResponse.decode(): ApiResult<T> = when {
        status.isSuccess() -> ApiResult.Ok(body<T>())
        status.value == 404 || status.value == 501 -> ApiResult.Unsupported
        else -> ApiResult.HttpError(status.value)
    }

    /**
     * Pour les routes d'action, qui ne renvoient pas de corps exploitable.
     * Le 304 est un succes : Docker s'en sert pour dire "deja dans cet etat".
     */
    private fun HttpResponse.actionOutcome(): ApiResult<Int> = when {
        status.isSuccess() -> ApiResult.Ok(status.value)
        status.value == 304 -> ApiResult.Ok(status.value)
        status.value == 404 || status.value == 501 -> ApiResult.Unsupported
        else -> ApiResult.HttpError(status.value)
    }

    private suspend fun <T> guard(block: suspend () -> ApiResult<T>): ApiResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.NetworkError(e.message ?: e::class.simpleName ?: "erreur reseau")
        }

    // ---------------------------------------------------------------- lecture

    /** Sonde la route de version, avec repli sur l'ancienne. Sert aussi de test de connexion. */
    suspend fun version(): ApiResult<String> = guard {
        var status = send(HttpMethod.Get, "/api/system/status").decode<PortainerSystemStatus>()
        if (status is ApiResult.Unsupported) {
            status = send(HttpMethod.Get, "/api/status").decode<PortainerSystemStatus>()
        }
        when (status) {
            is ApiResult.Ok -> ApiResult.Ok(status.value.version)
            is ApiResult.Unsupported -> ApiResult.Unsupported
            is ApiResult.HttpError -> ApiResult.HttpError(status.code)
            is ApiResult.NetworkError -> ApiResult.NetworkError(status.reason)
        }
    }

    /**
     * excludeSnapshots n'est pas une optimisation de confort : sans lui, la route
     * embarque un instantane complet et pese 938 Ko la ou elle en pese 3,5.
     * Une version qui ignore le parametre renvoie simplement la charge complete.
     */
    suspend fun endpoints(): ApiResult<List<PortainerEndpoint>> = guard {
        send(HttpMethod.Get, "/api/endpoints?excludeSnapshots=true")
            .decode<List<PortainerEndpoint>>()
    }

    /** Ne renvoie que les stacks crees dans Portainer. Les autres se deduisent des conteneurs. */
    suspend fun stacks(): ApiResult<List<PortainerStack>> = guard {
        send(HttpMethod.Get, "/api/stacks").decode<List<PortainerStack>>()
    }

    suspend fun stack(id: Int): ApiResult<PortainerStack> = guard {
        send(HttpMethod.Get, "/api/stacks/$id").decode<PortainerStack>()
    }

    /** all=true, sinon les conteneurs arretes - ceux qu'on veut relancer - sont invisibles. */
    suspend fun containers(envId: Int): ApiResult<List<DockerContainer>> = guard {
        send(HttpMethod.Get, "/api/endpoints/$envId/docker/containers/json?all=true")
            .decode<List<DockerContainer>>()
    }

    /**
     * Un appel par conteneur : reserve aux conteneurs dont la liste des ports
     * est vide alors que leur mode reseau explique pourquoi. Les appeler tous
     * couterait autant de requetes que de conteneurs, pour rien.
     */
    suspend fun inspect(envId: Int, containerId: String): ApiResult<DockerContainerDetail> = guard {
        send(HttpMethod.Get, "/api/endpoints/$envId/docker/containers/$containerId/json")
            .decode<DockerContainerDetail>()
    }

    suspend fun logs(
        envId: Int,
        containerId: String,
        tail: Int = 200,
        timestamps: Boolean = true,
    ): ApiResult<ByteArray> = guard {
        val response = send(
            HttpMethod.Get,
            "/api/endpoints/$envId/docker/containers/$containerId/logs" +
                "?stdout=1&stderr=1&timestamps=${if (timestamps) 1 else 0}&tail=$tail",
        )
        when {
            response.status.isSuccess() -> ApiResult.Ok(response.body<ByteArray>())
            response.status.value == 404 || response.status.value == 501 -> ApiResult.Unsupported
            else -> ApiResult.HttpError(response.status.value)
        }
    }

    // ----------------------------------------------------------------- images

    suspend fun images(envId: Int): ApiResult<List<DockerImage>> = guard {
        send(HttpMethod.Get, "/api/endpoints/$envId/docker/images/json")
            .decode<List<DockerImage>>()
    }

    /**
     * force=false volontairement : Docker refuse alors de supprimer une image
     * encore referencee, ce qui evite de casser un conteneur arrete par megarde.
     */
    suspend fun deleteImage(envId: Int, imageId: String): ApiResult<Int> = guard {
        send(HttpMethod.Delete, "/api/endpoints/$envId/docker/images/$imageId?force=false&noprune=false")
            .actionOutcome()
    }

    /** Telecharge une image. La reponse est un flux de progression que l'on consomme sans le lire. */
    suspend fun pullImage(envId: Int, imageRef: String): ApiResult<Int> = guard {
        val encoded = imageRef.replace(":", "%3A").replace("/", "%2F")
        val response = send(
            HttpMethod.Post,
            "/api/endpoints/$envId/docker/images/create?fromImage=$encoded",
        )
        if (response.status.isSuccess()) {
            runCatching { response.body<ByteArray>() }
            ApiResult.Ok(response.status.value)
        } else {
            response.actionOutcome()
        }
    }

    // ---------------------------------------------------------------- actions

    suspend fun startStack(stackId: Int, envId: Int): ApiResult<Int> = guard {
        send(HttpMethod.Post, "/api/stacks/$stackId/start?endpointId=$envId").actionOutcome()
    }

    suspend fun stopStack(stackId: Int, envId: Int): ApiResult<Int> = guard {
        send(HttpMethod.Post, "/api/stacks/$stackId/stop?endpointId=$envId").actionOutcome()
    }

    /** Le compose d'origine, indispensable : le PUT de redeploiement exige son contenu complet. */
    suspend fun stackFile(stackId: Int): ApiResult<String> = guard {
        when (val file = send(HttpMethod.Get, "/api/stacks/$stackId/file").decode<StackFile>()) {
            is ApiResult.Ok -> ApiResult.Ok(file.value.content)
            is ApiResult.Unsupported -> ApiResult.Unsupported
            is ApiResult.HttpError -> ApiResult.HttpError(file.code)
            is ApiResult.NetworkError -> ApiResult.NetworkError(file.reason)
        }
    }

    /**
     * Redeploiement. pullImage a vrai retelecharge les images avant de recreer
     * les conteneurs : c'est la difference entre relancer et mettre a jour.
     */
    suspend fun updateStack(
        stackId: Int,
        envId: Int,
        payload: StackUpdatePayload,
    ): ApiResult<Int> = guard {
        val auth = authHeader()
        val response = http.put(url("/api/stacks/$stackId?endpointId=$envId")) {
            auth?.let { header(it.first, it.second) }
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        response.actionOutcome()
    }

    /** action vaut start, stop ou restart. */
    suspend fun containerAction(envId: Int, containerId: String, action: String): ApiResult<Int> = guard {
        send(HttpMethod.Post, "/api/endpoints/$envId/docker/containers/$containerId/$action")
            .actionOutcome()
    }

    fun close() = http.close()
}
