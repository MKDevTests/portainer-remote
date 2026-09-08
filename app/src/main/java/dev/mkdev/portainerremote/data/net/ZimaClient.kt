package dev.mkdev.portainerremote.data.net

import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.domain.HostApp
import dev.mkdev.portainerremote.domain.HostAppAction
import dev.mkdev.portainerremote.domain.HostPower
import dev.mkdev.portainerremote.domain.HostUsage
import dev.mkdev.portainerremote.domain.ScheduledOff
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Client d'un hote ZimaOS (ou CasaOS, dont ZimaOS derive).
 *
 * Les routes ne sont pas devinees : l'interface de ZimaOS est servie par des
 * clients d'API generes, ou chaque chemin et chaque verbe figurent en clair.
 * Elles sont donc citees ici telles qu'elles y sont ecrites, avec leur base.
 *
 * Les reponses ne sont pas typees. Un hote peut etre en 1.4 comme en 1.7, et la
 * forme des charges utiles bouge entre les versions : on lit les champs qu'on
 * comprend et on ignore le reste, plutot que d'echouer sur un champ absent.
 */
class ZimaClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
) {

    private val root = baseUrl.trimEnd('/')

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
    }

    /** Le jeton ne vit qu'en memoire et disparait avec le processus. */
    @Volatile
    private var token: String? = null

    /**
     * Les deux conventions coexistent selon les versions : le jeton brut, ou
     * prefixe. On retient celle qui a repondu plutot que de la redecouvrir a
     * chaque appel.
     */
    @Volatile
    private var bearer = false

    /**
     * Une seule connexion a la fois. L'ecran lance trois lectures en parallele :
     * sans ce verrou, elles envoient trois fois le mot de passe pour obtenir
     * trois jetons dont deux seront jetes.
     */
    private val loginLock = Mutex()

    /**
     * Instant du dernier refus d'identifiants.
     *
     * Un mot de passe refuse ne devient pas correct parce qu'on le renvoie. Le
     * reproposer a chaque rafraichissement, c'est faire compter les echecs a
     * l'hote - et sur un NAS, une suite d'echecs finit par bloquer le compte.
     * On s'abstient donc pendant un moment, et l'ecran de reglages, lui, part
     * d'un client neuf : reessayer a la main reste immediat.
     */
    @Volatile
    private var rejectedAt = 0L

    // ------------------------------------------------------------- transport

    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: JsonElement?,
        jwt: String?,
    ): HttpResponse = http.request(root + path) {
        this.method = method
        jwt?.let { header("Authorization", if (bearer) "Bearer $it" else it) }
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    }

    /**
     * Un appel, et au plus deux rejeux : la convention d'en-tete, puis le jeton.
     *
     * Le nombre est borne volontairement. Une boucle qui se reconnecte a chaque
     * 401 transforme un mot de passe change en rafale de tentatives.
     */
    private suspend fun call(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
    ): HttpResponse {
        val jwt = ensureToken()
        var response = send(method, path, body, jwt)
        if (response.status.value != 401 || jwt == null) return response

        // 1. Certaines versions veulent le jeton prefixe. On bascule une fois.
        if (!bearer) {
            bearer = true
            response = send(method, path, body, jwt)
            if (response.status.value != 401) return response
        }

        // 2. Jeton expire. On n'invalide que celui qu'on vient d'utiliser : un
        //    appel voisin a pu en obtenir un neuf entre-temps.
        loginLock.withLock { if (token == jwt) token = null }
        val fresh = ensureToken() ?: return response
        if (fresh == jwt) return response
        return send(method, path, body, fresh)
    }

    private suspend fun ensureToken(): String? = loginLock.withLock {
        token?.let { return@withLock it }
        // Un identifiant ou un mot de passe vide n'ouvre aucune session : autant
        // ne pas l'envoyer sur le reseau.
        if (username.isBlank() || password.isEmpty()) return@withLock null
        if (System.currentTimeMillis() - rejectedAt < REJECT_PAUSE_MS) return@withLock null

        val response = http.request("$root/v1/users/login") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("username", username)
                    put("password", password)
                }.toString(),
            )
        }
        if (response.status.value == 401 || response.status.value == 403) {
            rejectedAt = System.currentTimeMillis()
            return@withLock null
        }
        if (!response.status.isSuccess()) return@withLock null

        val payload = parse(response.bodyAsText()) ?: return@withLock null
        token = findString(payload, setOf("access_token", "token"))?.takeIf { it.length > 8 }
        if (token == null) rejectedAt = System.currentTimeMillis()
        token
    }

    private fun parse(text: String): JsonElement? =
        runCatching { json.parseToJsonElement(text) }.getOrNull()

    /**
     * Cherche une chaine par nom de champ, a n'importe quelle profondeur.
     * CasaOS enveloppe ses reponses dans data, ZimaOS parfois dans data.token :
     * suivre la forme exacte reviendrait a coder une version precise.
     */
    private fun findString(element: JsonElement, names: Set<String>, depth: Int = 0): String? {
        if (depth > 6) return null
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (key in names) {
                        (value as? JsonPrimitive)?.contentOrNull
                            ?.let { if (it.isNotBlank()) return it }
                    }
                }
                for ((_, value) in element) findString(value, names, depth + 1)?.let { return it }
            }

            is JsonArray -> for (value in element) findString(value, names, depth + 1)?.let { return it }
            else -> Unit
        }
        return null
    }

    private suspend fun <T> attempt(block: suspend () -> ApiResult<T>): ApiResult<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiResult.NetworkError(e.message ?: e::class.simpleName ?: "erreur reseau")
    }

    private fun HttpResponse.outcome(): ApiResult<Int> = when {
        status.isSuccess() -> ApiResult.Ok(status.value)
        status.value == 404 || status.value == 501 -> ApiResult.Unsupported
        else -> ApiResult.HttpError(status.value)
    }

    // ------------------------------------------------------------- detection

    /**
     * Est-ce bien un hote ZimaOS ?
     *
     * La question se pose sans identifiants : une route authentifiee qui repond
     * 401 prouve deja qu'elle existe, donc que le service est la. Une absence de
     * reponse, ou un 404, veut dire que non - et l'app continue sans hote.
     */
    suspend fun detect(): Boolean = runCatching {
        val response = http.request("$root/v1/sys/utilization") { method = HttpMethod.Get }
        when (response.status.value) {
            // Une route protegee qui refuse l'acces prouve qu'elle existe.
            401, 403 -> true
            // Un 200 ne suffit pas : n'importe quel serveur web repond 200 a
            // n'importe quoi. On exige une charge utile de la forme attendue,
            // sans quoi on refuserait d'aller plus loin - et on eviterait donc
            // d'envoyer le mot de passe a un hote qui n'est pas celui-la.
            200 -> (parse(response.bodyAsText()) as? JsonObject)
                ?.let { "data" in it || "success" in it } == true

            else -> false
        }
    }.getOrDefault(false)

    /** Teste les identifiants. Le jeton obtenu reste en memoire. */
    suspend fun signIn(): ApiResult<Boolean> = attempt {
        if (ensureToken() == null) ApiResult.HttpError(401) else ApiResult.Ok(true)
    }

    // --------------------------------------------------------- applications

    /**
     * Les applications que ZimaOS gere lui-meme. Sur un tel NAS, Portainer en
     * fait partie : c'est ce qui permet de le relancer quand il est arrete,
     * donc quand le reste de l'app ne repond plus.
     *
     * On lit /installed/list et non /compose, bien que les deux portent l'etat.
     * /compose renvoie les fichiers compose complets - donc les blocs
     * environment, donc des mots de passe et des cles d'API en clair, pour
     * afficher un point vert. Une application n'a pas a telecharger ce dont
     * elle n'a pas besoin, et cela vaut d'abord pour les secrets des autres.
     */
    suspend fun apps(): ApiResult<List<HostApp>> = attempt {
        val response = call(HttpMethod.Get, "/v2/app_management/installed/list")
        if (!response.status.isSuccess()) return@attempt response.outcome().asFailure()
        val body = parse(response.bodyAsText()) ?: return@attempt ApiResult.Ok(emptyList())
        ApiResult.Ok(readApps(body))
    }

    /**
     * La liste arrive en tableau sur ZimaOS, et en dictionnaire indexe par
     * identifiant sur des versions plus anciennes. Les deux disent la meme
     * chose : on accepte les deux plutot que de coder un numero de version.
     */
    private fun readApps(body: JsonElement): List<HostApp> {
        val data = (body as? JsonObject)?.get("data") ?: body
        val entries: List<Pair<String, JsonObject>> = when (data) {
            is JsonArray -> data.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.string("id") ?: obj.string("name") ?: return@mapNotNull null
                id to obj
            }

            is JsonObject -> data.mapNotNull { (key, value) ->
                (value as? JsonObject)?.let { key to it }
            }

            else -> emptyList()
        }
        return entries
            .map { (id, obj) ->
                val status = obj.string("status") ?: obj.string("state") ?: ""
                HostApp(
                    id = id,
                    name = obj.title() ?: id,
                    running = status.equals("running", true) || status.equals("started", true),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Le nom affichable. ZimaOS garde deja un titre personnalise a cote du
     * titre d'origine : le preferer, c'est respecter un choix que l'utilisateur
     * a deja fait ailleurs plutot que lui demander de le refaire ici.
     */
    private fun JsonObject.title(): String? {
        val title = this["title"] as? JsonObject
        return title?.string("custom")
            ?: title?.string("en_us")
            ?: title?.string("en_US")
            ?: string("title")
            ?: string("name")
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /**
     * Demarre, arrete ou relance une application ZimaOS.
     *
     * Le corps est une chaine JSON nue - "start" - et non un objet : c'est la
     * forme que decrit le contrat de la route, verifiee dans la specification
     * publique de CasaOS-AppManagement.
     */
    suspend fun setAppStatus(appId: String, action: HostAppAction): ApiResult<Int> = attempt {
        call(
            HttpMethod.Put,
            "/v2/app_management/compose/$appId/status",
            JsonPrimitive(action.value),
        ).outcome()
    }

    // -------------------------------------------------------------- machine

    suspend fun usage(): ApiResult<HostUsage> = attempt {
        val response = call(HttpMethod.Get, "/v1/sys/utilization")
        if (!response.status.isSuccess()) return@attempt response.outcome().asFailure()
        val body = parse(response.bodyAsText()) as? JsonObject
            ?: return@attempt ApiResult.Ok(HostUsage())
        val data = body["data"] as? JsonObject ?: body
        ApiResult.Ok(
            HostUsage(
                cpuPercent = data.percent("cpu"),
                memoryPercent = data.percent("mem", "memory"),
                // Le disque systeme s'appelle sys_disk et compte en used sur
                // size : viser disk/total, c'est une jauge vide qui ne dit
                // jamais pourquoi. Les autres noms restent acceptes.
                diskPercent = data.percent("sys_disk", "disk", "storage"),
            ),
        )
    }

    /**
     * Le taux arrive selon les champs en pourcentage direct, ou en couple
     * consomme sur total. On accepte les deux, et les deux orthographes de
     * chaque nom : une seule forme ferait dependre l'affichage d'une version.
     */
    private fun JsonObject.percent(vararg keys: String): Int {
        for (key in keys) {
            val node = this[key] ?: continue
            (node as? JsonPrimitive)?.doubleOrNull?.let { return it.toInt().coerceIn(0, 100) }
            val obj = node as? JsonObject ?: continue

            for (direct in arrayOf("percent", "usedPercent", "used_percent")) {
                (obj[direct] as? JsonPrimitive)?.doubleOrNull
                    ?.let { return it.toInt().coerceIn(0, 100) }
            }

            val used = (obj["used"] as? JsonPrimitive)?.doubleOrNull
            val total = arrayOf("total", "size")
                .firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.doubleOrNull }
            if (used != null && total != null && total > 0) {
                return ((used / total) * 100).toInt().coerceIn(0, 100)
            }
        }
        return -1
    }

    suspend fun scheduledOff(): ApiResult<ScheduledOff> = attempt {
        val response = call(HttpMethod.Get, "/v2/zimaos/scheduledoff")
        if (!response.status.isSuccess()) return@attempt response.outcome().asFailure()
        val body = parse(response.bodyAsText()) as? JsonObject
            ?: return@attempt ApiResult.Ok(ScheduledOff())
        val data = body["data"] as? JsonObject ?: body
        ApiResult.Ok(
            ScheduledOff(
                hour = (data["hour"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0,
                minute = (data["minute"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0,
                weekdays = (data["weekdays"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            ),
        )
    }

    /**
     * Redemarrage ou extinction. L'etat est un parametre de chemin, sans corps :
     * PUT /v1/sys/state/off. Rien ici ne confirme a la place de l'utilisateur -
     * la confirmation appartient a l'interface.
     */
    suspend fun power(action: HostPower): ApiResult<Int> = attempt {
        call(HttpMethod.Put, "/v1/sys/state/${action.state}").outcome()
    }

    private fun <T> ApiResult<Int>.asFailure(): ApiResult<T> = when (this) {
        is ApiResult.Ok -> ApiResult.HttpError(value)
        is ApiResult.HttpError -> ApiResult.HttpError(code)
        is ApiResult.NetworkError -> ApiResult.NetworkError(reason)
        ApiResult.Unsupported -> ApiResult.Unsupported
    }

    fun close() {
        runCatching { http.close() }
    }

    private companion object {
        /** Assez long pour ne pas marteler l'hote, assez court pour un mot de passe corrige. */
        const val REJECT_PAUSE_MS = 60_000L
    }
}
