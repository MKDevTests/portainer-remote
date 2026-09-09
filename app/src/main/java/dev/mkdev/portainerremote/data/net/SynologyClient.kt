package dev.mkdev.portainerremote.data.net

import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.domain.DiskRole
import dev.mkdev.portainerremote.domain.DiskSleep
import dev.mkdev.portainerremote.domain.HostApp
import dev.mkdev.portainerremote.domain.HostAppAction
import dev.mkdev.portainerremote.domain.HostDisk
import dev.mkdev.portainerremote.domain.HostJournal
import dev.mkdev.portainerremote.domain.HostMachine
import dev.mkdev.portainerremote.domain.HostPower
import dev.mkdev.portainerremote.domain.HostUpdate
import dev.mkdev.portainerremote.domain.HostUsage
import dev.mkdev.portainerremote.domain.LogEntry
import dev.mkdev.portainerremote.domain.LogLevel
import dev.mkdev.portainerremote.domain.LogSession
import dev.mkdev.portainerremote.domain.NetCounters
import dev.mkdev.portainerremote.domain.ScheduledOff
import dev.mkdev.portainerremote.domain.SignIn
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Client d'un NAS Synology sous DSM 7.
 *
 * DSM ne se devine pas : il publie lui-meme son catalogue d'API - nom, chemin,
 * versions - sur une route qui ne demande aucun identifiant. Ce client le lit
 * une fois, puis n'appelle que ce que cette machine a declare, dans la version
 * qu'elle declare. Ecrire un nom d'API en dur reviendrait a casser a la
 * premiere mise a jour de DSM.
 *
 * Cette version ne fait que lire. Aucune methode appelee ici ne modifie quoi
 * que ce soit : ni extinction, ni redemarrage, ni reglage. Les questions
 * d'ecriture repondent Unsupported, et l'interface les masque.
 */
class SynologyClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    /**
     * Le jeton d'appareil obtenu lors d'une precedente double authentification.
     * Tant qu'il est valide, DSM ne redemande pas de code.
     */
    private val deviceId: String? = null,
) : HostClient {

    private val root = baseUrl.trimEnd('/')

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    /** Le catalogue, lu une fois par session. Nom d'API vers chemin et version. */
    private data class ApiEntry(val path: String, val maxVersion: Int)

    @Volatile
    private var catalogue: Map<String, ApiEntry>? = null

    /** L'identifiant de session. Il ne vit qu'en memoire et meurt avec le processus. */
    @Volatile
    private var sid: String? = null

    /**
     * Depuis quand un refus vaut abstention.
     *
     * DSM bloque une adresse apres quelques echecs - c'est meme un de ses
     * reglages. Reessayer en boucle avec un mot de passe devenu faux ferait
     * donc bannir le telephone du reseau. Apres un refus, on attend.
     */
    @Volatile
    private var rejectedAt: Long = 0

    private val loginLock = Mutex()

    // ----------------------------------------------------------- catalogue

    private suspend fun catalogue(): Map<String, ApiEntry>? {
        catalogue?.let { return it }
        val response = runCatching {
            http.get(
                "$root/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=all",
            )
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null

        val body = parse(response.bodyAsText()) ?: return null
        val data = body["data"] as? JsonObject ?: return null
        val built = data.mapNotNull { (name, node) ->
            val entry = node as? JsonObject ?: return@mapNotNull null
            val path = entry.string("path") ?: return@mapNotNull null
            name to ApiEntry(path, entry.int("maxVersion") ?: 1)
        }.toMap()

        return built.also { catalogue = it }
    }

    /**
     * Reconnait un DSM sans envoyer le moindre identifiant.
     *
     * Le catalogue suffit : une machine qui declare SYNO.API.Auth est un DSM.
     * Aucune autre ne repond a cette adresse avec cette forme, et le mot de
     * passe n'est donc jamais propose a un inconnu.
     */
    override suspend fun detect(): Boolean = catalogue()?.containsKey("SYNO.API.Auth") == true

    // ------------------------------------------------------------- session

    /**
     * Le jeton d'appareil rendu par DSM lors d'une connexion avec code.
     * L'appelant le range pour ne plus redemander de code ensuite.
     */
    @Volatile
    private var freshDeviceId: String? = null

    /**
     * Vrai quand l'hote reclame un code que personne n'a saisi.
     *
     * Un jeton d'appareil finit par etre revoque - l'utilisateur change son mot
     * de passe, ou retire l'appareil de confiance dans DSM. Sans ce drapeau,
     * l'ecran se contenterait de rester vide, ce qui ressemble a une panne
     * reseau alors qu'il suffit de six chiffres.
     */
    @Volatile
    private var otpPending: Boolean = false

    override fun deviceToken(): String? = freshDeviceId

    override suspend fun signIn(otp: String?): ApiResult<SignIn> = attempt {
        ApiResult.Ok(login(otp))
    }

    /**
     * Ouvre une session, une seule a la fois.
     *
     * Le mot de passe part dans le corps d'une requete POST. Place dans
     * l'adresse, il serait recopie tel quel dans le journal de connexion de
     * DSM - celui-la meme que l'application affiche par ailleurs.
     */
    private suspend fun ensureSession(): Boolean = login(null) == SignIn.OK

    /**
     * Ouvre une session, une seule a la fois.
     *
     * La double authentification se passe en deux temps, et c'est DSM qui les
     * impose : sans code, il repond 403 « code non fourni ». On lui en donne un,
     * en demandant en meme temps un jeton d'appareil - c'est exactement ce que
     * fait « faire confiance a cet appareil » dans son interface. Ce jeton
     * remplace le code aux connexions suivantes, et c'est lui qui evite de
     * reclamer six chiffres a chaque ouverture de l'application.
     */
    private suspend fun login(otp: String?): SignIn {
        sid?.let { return SignIn.OK }
        if (username.isBlank() || password.isEmpty()) return SignIn.REFUSED
        // Un code fourni a la main vaut une intention neuve : il annule
        // l'abstention posee par le refus precedent.
        if (otp.isNullOrBlank() && System.currentTimeMillis() - rejectedAt < REJECT_PAUSE_MS) {
            return SignIn.REFUSED
        }

        return loginLock.withLock {
            sid?.let { return@withLock SignIn.OK }

            val api = catalogue()?.get("SYNO.API.Auth") ?: return@withLock SignIn.REFUSED
            val response = runCatching {
                http.submitForm(
                    url = "$root/webapi/${api.path}",
                    formParameters = Parameters.build {
                        append("api", "SYNO.API.Auth")
                        append("version", api.maxVersion.toString())
                        append("method", "login")
                        append("account", username)
                        append("passwd", password)
                        append("session", "PortainerRemote")
                        append("format", "sid")
                        if (!otp.isNullOrBlank()) {
                            append("otp_code", otp)
                            // Demande le jeton d'appareil en meme temps : sans
                            // lui, chaque connexion reclamerait un code.
                            append("enable_device_token", "yes")
                            append("device_name", "Portainer Remote")
                        } else if (!deviceId.isNullOrBlank()) {
                            append("device_id", deviceId)
                        }
                    },
                )
            }.getOrNull() ?: return@withLock SignIn.REFUSED

            val body = parse(response.bodyAsText())
            val data = body?.get("data") as? JsonObject
            val token = (data?.get("sid") as? JsonPrimitive)?.contentOrNull

            if (body?.succeeded() == true && !token.isNullOrBlank()) {
                sid = token
                rejectedAt = 0
                otpPending = false
                // DSM le nomme « did », mais pas dans toutes les versions de
                // son API d'authentification. Perdre le jeton sur une question
                // d'orthographe reviendrait a reclamer un code a vie.
                listOf("did", "device_id", "deviceId")
                    .firstNotNullOfOrNull { (data[it] as? JsonPrimitive)?.contentOrNull }
                    ?.takeIf { it.isNotBlank() }
                    ?.let { freshDeviceId = it }
                return@withLock SignIn.OK
            }

            // 403 : DSM veut un code. 404 : celui qu'on a donne ne convient
            // pas. Aucun des deux ne se corrige en ressaisissant le mot de
            // passe, et les confondre enverrait chercher au mauvais endroit.
            val code = ((body?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)
                ?.doubleOrNull?.toInt()

            when (code) {
                403, 406 -> {
                    otpPending = true
                    SignIn.OTP_REQUIRED
                }

                404 -> {
                    otpPending = true
                    SignIn.OTP_REFUSED
                }
                else -> {
                    // Insister avec un mot de passe devenu faux ferait bannir
                    // l'adresse par DSM lui-meme : apres un refus, on attend.
                    rejectedAt = System.currentTimeMillis()
                    sid = null
                    SignIn.REFUSED
                }
            }
        }
    }

    /**
     * Un appel de lecture, et un seul reessai.
     *
     * Une session DSM expire. Le premier refus vaut donc « ouvre-la de
     * nouveau », pas « le mot de passe est faux » : on refait la session une
     * fois, puis on abandonne.
     */
    private suspend fun call(
        apiName: String,
        method: String,
        extra: String = "",
    ): JsonObject? {
        if (!ensureSession()) return null
        val api = catalogue()?.get(apiName) ?: return null

        repeat(2) { essai ->
            val token = sid ?: return null
            val url = buildString {
                append(root).append("/webapi/").append(api.path)
                append("?api=").append(apiName)
                append("&version=").append(api.maxVersion)
                append("&method=").append(method)
                if (extra.isNotBlank()) append("&").append(extra)
                append("&_sid=").append(token.encodeURLParameter())
            }

            val response = runCatching { http.get(url) }.getOrNull() ?: return null
            val body = parse(response.bodyAsText())
            if (body?.succeeded() == true) return body

            // La session a expire : on l'oublie et on recommence, une fois.
            if (essai == 0) sid = null else return null
        }
        return null
    }

    // -------------------------------------------------------------- lecture

    override suspend fun machine(): ApiResult<HostMachine> = attempt {
        val data = call("SYNO.Core.System", "info")?.data() ?: return@attempt refused()
        ApiResult.Ok(
            HostMachine(
                model = data.string("model").orEmpty(),
                name = data.string("model").orEmpty(),
                osVersion = data.string("firmware_ver").orEmpty(),
                cpuModel = listOfNotNull(
                    data.string("cpu_vendor")?.takeIf { it.isNotBlank() },
                    data.string("cpu_family")?.takeIf { it.isNotBlank() },
                    data.string("cpu_series")?.takeIf { it.isNotBlank() },
                ).joinToString(" "),
                cpuCores = data.int("cpu_cores") ?: 0,
                // DSM compte la memoire en mebi-octets, pas en octets.
                memoryTotalBytes = (data.long("ram_size") ?: -1L).let {
                    if (it > 0) it * 1024 * 1024 else -1L
                },
            ),
        )
    }

    /**
     * La charge.
     *
     * DSM rend ses pourcentages en trois morceaux - utilisateur, systeme, le
     * reste - qu'il faut additionner : lire « user_load » seul afficherait un
     * processeur au repos pendant qu'il travaille. Et les chiffres arrivent en
     * chaines de caracteres, pas en nombres.
     *
     * Le reseau, lui, est deja un debit : DSM publie des octets par seconde. La
     * ou ZimaOS demandait deux mesures, une seule suffit ici.
     */
    override suspend fun usage(): ApiResult<HostUsage> = attempt {
        val data = call("SYNO.Core.System.Utilization", "get")?.data()
            ?: return@attempt refused()

        val cpu = data["cpu"] as? JsonObject
        val processeur = listOf("user_load", "system_load", "other_load")
            .sumOf { cpu?.int(it) ?: 0 }

        val memory = data["memory"] as? JsonObject
        val memoryTotalKb = memory?.long("memory_size") ?: -1L
        // « real_usage » est le chiffre que DSM affiche lui-meme. Recalculer
        // l'occupation depuis « avail_real » donnerait 93 % la ou la jauge dit
        // 67 % : ce champ exclut le cache, que le systeme rendra a la demande.
        val memoryPercent = memory?.int("real_usage") ?: -1

        ApiResult.Ok(
            HostUsage(
                cpuPercent = if (cpu != null) processeur.coerceIn(0, 100) else -1,
                memoryPercent = memoryPercent,
                memoryTotalBytes = if (memoryTotalKb > 0) memoryTotalKb * 1024 else -1L,
                memoryUsedBytes = if (memoryTotalKb > 0 && memoryPercent >= 0) {
                    memoryTotalKb * 1024 * memoryPercent / 100
                } else {
                    -1L
                },
                network = readNetwork(data["network"]),
                networkIsRate = true,
                takenAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Les interfaces reseau.
     *
     * DSM nomme « total » la somme de toutes les interfaces : la garder ferait
     * compter deux fois. Les valeurs sont deja des debits, elles voyagent donc
     * dans les compteurs sans etre transformees, et l'ecran s'en sert tel quel
     * puisqu'une seule mesure suffit.
     */
    private fun readNetwork(node: kotlinx.serialization.json.JsonElement?): List<NetCounters> {
        val array = node as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.string("device") ?: return@mapNotNull null
            if (name.equals("total", ignoreCase = true)) return@mapNotNull null
            NetCounters(name, obj.long("tx") ?: 0L, obj.long("rx") ?: 0L)
        }
    }

    /**
     * Les volumes et les disques.
     *
     * Un Synology en RAID ne fait pas correspondre un disque a un espace : ses
     * trois disques vivent sous un seul volume. Les deux sont donc rendus, mais
     * separement - le volume porte le remplissage, le disque porte sa
     * temperature et sa sante. Les additionner donnerait des chiffres faux.
     */
    override suspend fun disks(): ApiResult<List<HostDisk>> = attempt {
        val data = call("SYNO.Storage.CGI.Storage", "load_info")
            ?: return@attempt refused()

        val body = data.data() ?: return@attempt refused()
        val result = mutableListOf<HostDisk>()

        (body["volumes"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { volume ->
            val size = volume["size"] as? JsonObject
            result += HostDisk(
                name = volume.string("id").orEmpty(),
                model = volume.string("vol_desc").orEmpty()
                    .ifBlank { volume.string("vol_path").orEmpty() },
                role = DiskRole.VOLUME,
                kind = listOfNotNull(
                    volume.string("fs_type")?.takeIf { it.isNotBlank() },
                    volume.string("device_type")?.replace("_", " ")?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                sizeBytes = size?.long("total") ?: -1L,
                usedBytes = size?.long("used") ?: -1L,
                healthy = volume.string("status")?.equals("normal", true),
            )
        }

        (body["disks"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { disk ->
            val temperature = disk.int("temp") ?: 0
            result += HostDisk(
                name = disk.string("longName") ?: disk.string("id").orEmpty(),
                model = disk.string("model").orEmpty(),
                role = DiskRole.DRIVE,
                kind = if (disk.bool("isSsd") == true) "SSD" else disk.string("diskType").orEmpty(),
                sizeBytes = disk.long("size_total") ?: -1L,
                // Un disque de la baie ne porte pas d'espace occupe : celui-ci
                // appartient au volume qui s'etend dessus.
                usedBytes = -1L,
                temperature = if (temperature > 0) temperature else -1,
                // « smart_status » est l'avis du disque sur lui-meme ; « status »
                // celui de DSM. Il faut les deux pour dire « bonne sante ».
                healthy = disk.string("smart_status")?.equals("normal", true) == true &&
                    disk.string("status")?.equals("normal", true) == true,
            )
        }

        ApiResult.Ok(result)
    }

    /**
     * Ce que DSM sait d'une mise a jour de lui-meme.
     *
     * « check » est ce que fait le bouton « Verifier les mises a jour » de son
     * interface : il interroge le serveur de Synology et rend un verdict. Il
     * n'installe rien, et rien ici ne l'installera - poser une version de DSM
     * redemarre la machine et coupe tous les conteneurs.
     *
     * La version installee vient de l'autre bout, « Core.System/info », parce
     * que la reponse de « check » ne parle que de celle qui est disponible.
     */
    override suspend fun systemUpdate(): ApiResult<HostUpdate> = attempt {
        val data = call("SYNO.Core.Upgrade.Server", "check")?.data()
            ?: return@attempt refused()
        val update = data["update"] as? JsonObject ?: return@attempt ApiResult.Ok(HostUpdate(known = true))

        val details = update["version_details"] as? JsonObject
        val installee = call("SYNO.Core.System", "info")?.data()?.string("firmware_ver").orEmpty()

        ApiResult.Ok(
            HostUpdate(
                known = true,
                available = update.bool("available") == true,
                currentVersion = installee,
                latestVersion = update.string("version").orEmpty(),
                important = details?.bool("isSecurityVersion") == true,
            ),
        )
    }

    /** DSM compte en minutes d'inactivite : il n'y a rien a interpreter. */
    override suspend fun diskSleep(): ApiResult<DiskSleep> = attempt {
        val data = call("SYNO.Core.Hardware.Hibernation", "get")?.data()
            ?: return@attempt refused()
        val minutes = data.int("internal_hd_idletime") ?: return@attempt refused()
        ApiResult.Ok(DiskSleep.fromMinutes(minutes))
    }

    /**
     * L'extinction programmee.
     *
     * DSM tient une liste de taches, avec des jours en chiffres, la ou ZimaOS
     * tient un seul horaire avec des jours en lettres. On rend la premiere
     * tache d'extinction active : afficher un horaire faux serait pire que de
     * n'en afficher aucun.
     *
     * DSM sait aussi programmer un allumage. L'application ne le montre pas
     * encore : ce contrat n'a qu'une extinction, et lui en ajouter une moitie
     * sans l'autre embrouillerait plus que ca n'aiderait.
     */
    override suspend fun scheduledOff(): ApiResult<ScheduledOff> = attempt {
        val data = call("SYNO.Core.Hardware.PowerSchedule", "load")?.data()
            ?: return@attempt refused()

        val tasks = (data["poweroff_tasks"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val task = tasks.firstOrNull { it.bool("enabled") != false }
            ?: return@attempt ApiResult.Ok(ScheduledOff(0, 0, emptyList()))

        ApiResult.Ok(
            ScheduledOff(
                hour = task.int("hour") ?: 0,
                minute = task.int("min") ?: task.int("minute") ?: 0,
                weekdays = readWeekdays(task.string("weekdays")),
            ),
        )
    }

    /**
     * Les jours, tels que DSM les ecrit : des chiffres separes par des virgules,
     * dimanche valant zero. La semaine du contrat commence le lundi.
     */
    private fun readWeekdays(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val ordre = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
        val jours = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
            .mapNotNull { ordre.getOrNull(it) }
            .toSet()
        return ScheduledOff.WEEK.filter { it in jours }
    }

    // -------------------------------------------------------------- journal

    /**
     * Le journal systeme, et qui est connecte en ce moment.
     *
     * C'est la seule des trois machines de ce projet qui publie cela : ZimaOS
     * n'expose aucune route de journal, Docker ne garde que ses 256 derniers
     * evenements en memoire, et l'historique d'authentification de Portainer
     * appartient a son edition payante.
     *
     * Le filtre par niveau est envoye a DSM plutot qu'applique ici : filtrer
     * apres coup ne montrerait que les erreurs des vingt dernieres lignes lues,
     * pas les vingt dernieres erreurs.
     */
    override suspend fun journal(level: LogLevel?, limit: Int): ApiResult<HostJournal> = attempt {
        val borne = limit.coerceIn(1, 200)
        val filtre = when (level) {
            LogLevel.ERROR -> "&level=1"
            LogLevel.WARNING -> "&level=2"
            LogLevel.INFO -> "&level=3"
            null -> ""
        }

        val logs = call("SYNO.Core.SyslogClient.Log", "list", "start=0&limit=$borne$filtre")
        val sessions = call("SYNO.Core.CurrentConnection", "list", "start=0&limit=50")

        if (logs == null && sessions == null) return@attempt refused()

        val body = logs?.data()
        val entries = (body?.get("items") as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .map { item ->
                LogEntry(
                    time = item.string("time").orEmpty(),
                    level = readLevel(item.string("level")),
                    category = item.string("logtype") ?: item.string("orginalLogType").orEmpty(),
                    message = item.string("descr").orEmpty(),
                    who = item.string("who").orEmpty(),
                )
            }

        val ouvertes = (sessions?.data()?.get("items") as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .map { item ->
                LogSession(
                    who = item.string("who").orEmpty(),
                    from = item.string("from").orEmpty(),
                    protocol = item.string("type") ?: item.string("protocol").orEmpty(),
                    since = item.string("first_login_time") ?: item.string("time").orEmpty(),
                    current = item.bool("is_current_connected") == true,
                )
            }

        ApiResult.Ok(
            HostJournal(entries = entries, sessions = ouvertes, total = body?.int("total") ?: entries.size),
        )
    }

    /**
     * DSM ecrit son niveau en toutes lettres, et pas toujours les memes selon
     * la langue de l'interface. On reconnait ce qu'on reconnait, et le reste
     * est une information : se tromper de gravite serait pire que de rester
     * neutre.
     */
    private fun readLevel(raw: String?): LogLevel = when {
        raw == null -> LogLevel.INFO
        raw.startsWith("err", true) || raw.startsWith("crit", true) -> LogLevel.ERROR
        raw.startsWith("warn", true) -> LogLevel.WARNING
        else -> LogLevel.INFO
    }

    // ------------------------------------------------------- pas encore fait

    /**
     * La liste des conteneurs geres par DSM.
     *
     * Mesuree, et refusee : SYNO.Docker.Container/list rend l'erreur 114,
     * « parametres manquants », sans dire lesquels. Tant que ce n'est pas
     * mesure, cette question reste sans reponse plutot que d'envoyer une
     * requete au hasard - et Portainer, lui, sait deja lister ces conteneurs.
     */
    override suspend fun apps(): ApiResult<List<HostApp>> = ApiResult.Unsupported

    /** Cette version ne fait que lire. */
    override suspend fun setAppStatus(appId: String, action: HostAppAction): ApiResult<Int> =
        ApiResult.Unsupported

    override suspend fun upgradable(): ApiResult<Set<String>> = ApiResult.Unsupported

    override suspend fun upgrade(appId: String, appType: String): ApiResult<Int> =
        ApiResult.Unsupported

    override suspend fun setScheduledOff(schedule: ScheduledOff): ApiResult<Int> =
        ApiResult.Unsupported

    /**
     * Eteindre et redemarrer existent chez DSM, et ne sont pas branches.
     *
     * Les methodes d'ecriture n'ont pas ete mesurees : les essayer, c'est
     * risquer d'eteindre un NAS pour verifier qu'on sait l'eteindre. Elles
     * viendront quand elles auront ete sondees.
     */
    override suspend fun power(action: HostPower): ApiResult<Int> = ApiResult.Unsupported

    override fun close() {
        sid = null
        http.close()
    }

    // ---------------------------------------------------------------- outils

    /**
     * Ce qu'on repond quand une lecture n'a pas abouti.
     *
     * « Non gere » et « il manque un code » se ressemblent a l'ecran - rien ne
     * s'affiche - mais l'un se corrige en attendant une prochaine version et
     * l'autre en saisissant six chiffres.
     */
    private fun <T> refused(): ApiResult<T> =
        if (otpPending) ApiResult.HttpError(403) else ApiResult.Unsupported

    private fun parse(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun JsonObject.succeeded(): Boolean =
        (this["success"] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.data(): JsonObject? = this["data"] as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** DSM rend ses nombres tantot en nombres, tantot en chaines. */
    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.doubleOrNull?.toLong()

    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private inline fun <T> attempt(block: () -> ApiResult<T>): ApiResult<T> = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        ApiResult.NetworkError(error.message ?: "Serveur injoignable")
    }

    private companion object {
        const val REJECT_PAUSE_MS = 60_000L
    }
}
