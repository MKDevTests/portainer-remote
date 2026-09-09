package dev.mkdev.portainerremote.domain

/**
 * Le systeme qui fait tourner le NAS.
 *
 * Il est declare par l'utilisateur, jamais devine. Une application qui part
 * sonder des routes systeme sans qu'on le lui ait demande n'est pas discrete,
 * et un NAS qui ne repond pas comme prevu ne merite pas qu'on insiste.
 *
 * Les entrees non gerees existent quand meme : une absence annoncee se lit,
 * une absence silencieuse laisse chercher.
 */
enum class HostKind(
    val label: String,
    val supported: Boolean,
    /**
     * Ce que ce systeme sait faire, tel qu'il a ete mesure - pas tel qu'on
     * l'espere. Une capacite absente fait disparaitre sa carte, plutot que de
     * laisser un bouton qui echouera.
     */
    val canApps: Boolean = false,
    /**
     * Lire la liste n'est pas la piloter. DSM rend ses conteneurs mais ses
     * methodes d'ecriture n'ont pas ete sondees : la liste s'affiche, sans les
     * boutons qui echoueraient.
     */
    val canSeeApps: Boolean = canApps,
    val canPower: Boolean = false,
    val canWriteSchedule: Boolean = false,
    val hasJournal: Boolean = false,
) {
    ZIMA(
        label = "ZimaOS · CasaOS",
        supported = true,
        canApps = true,
        canPower = true,
        canWriteSchedule = true,
    ),

    /**
     * DSM 7. Lecture seule pour l'instant : ses methodes d'ecriture n'ont pas
     * ete sondees, et les essayer reviendrait a eteindre un NAS pour verifier
     * qu'on sait l'eteindre. En echange, il est le seul a publier un journal.
     */
    SYNOLOGY(
        label = "Synology DSM",
        supported = true,
        canSeeApps = true,
        hasJournal = true,
    ),

    QNAP(label = "QNAP QTS", supported = false),
}

/**
 * Un NAS, tel que l'application le connait.
 *
 * C'est une entite a part entiere, et non une propriete d'un serveur Portainer :
 * deux serveurs peuvent viser la meme machine, et supprimer un serveur ne doit
 * pas faire oublier la machine. Le lien vers un Portainer existe, mais il est
 * optionnel - il sert a proposer une adresse, et a savoir quel Portainer on
 * ressuscite quand on relance son application.
 */
data class Host(
    val id: String = "",
    val kind: HostKind = HostKind.ZIMA,
    val label: String = "",
    val baseUrl: String = "",
    val username: String = "",
    /** Serveur Portainer associe, s'il y en a un. */
    val serverId: String = "",
    /** L'application de l'hote qui heberge Portainer. */
    val portainerAppId: String = "",
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && username.isNotBlank()

    /**
     * Reconnait l'application qui heberge Portainer.
     *
     * L'hote a change la forme de ses identifiants d'une version a l'autre :
     * ce qu'il appelait « portainer » s'appelle desormais
     * « zimaapp://v2app/portainer ». Un choix fait avant ce changement reste un
     * choix : le dernier segment le retrouve, et le rafraichissement le reecrit
     * ensuite sous sa forme actuelle.
     */
    fun isPortainerApp(appId: String): Boolean =
        portainerAppId.isNotBlank() &&
            (
                appId == portainerAppId ||
                    appId.substringAfterLast('/') == portainerAppId.substringAfterLast('/')
                )

    /** Ce qu'on affiche : le nom donne, sinon l'adresse, qui identifie toujours. */
    val title: String get() = label.ifBlank { baseUrl.removePrefix("http://").removePrefix("https://") }
}

/** Une application connue de l'hote. Sur un ZimaOS, Portainer en est une. */
data class HostApp(
    val id: String,
    val name: String,
    val running: Boolean,
    /**
     * Le type que l'hote donne a l'application. La route de mise a jour le
     * reclame, et on le lui rend tel quel plutot que d'ecrire une constante :
     * c'est lui qui sait, et la valeur peut differer d'un systeme a l'autre.
     */
    val appType: String = "",
    /**
     * Ce que l'hote dit de son etat, dans ses mots.
     *
     * DSM ecrit « Up 6 days (healthy) » ou « Exited (137) 19 months ago » : une
     * phrase deja composee, qui en dit plus que « en marche ». On la rend telle
     * quelle plutot que de la reconstruire - la reconstruire, c'est inventer
     * une duree que l'hote a deja calculee.
     */
    val detail: String = "",
)

/**
 * Charge de la machine, telle que l'hote la mesure lui-meme.
 *
 * Pas de duree d'allumage : l'hote ne la publie pas avec sa charge. L'y chercher
 * donnait un champ toujours absent, donc une ligne qui ne s'affichait jamais.
 */
data class HostUsage(
    val cpuPercent: Int = -1,
    val memoryPercent: Int = -1,
    val diskPercent: Int = -1,
    /** En degres Celsius. -1 quand l'hote ne la publie pas. */
    val cpuTemperature: Int = -1,
    /** Null quand l'hote ne se prononce pas sur la sante du disque systeme. */
    val diskHealthy: Boolean? = null,
    val memoryUsedBytes: Long = -1,
    val memoryTotalBytes: Long = -1,
    val diskUsedBytes: Long = -1,
    val diskTotalBytes: Long = -1,
    val network: List<NetCounters> = emptyList(),
    /**
     * Vrai quand l'hote publie deja des debits plutot que des compteurs.
     *
     * DSM le fait, ZimaOS non. Soustraire deux debits donnerait zero, et
     * l'ecran annoncerait un reseau au repos sur une machine qui transfere.
     */
    val networkIsRate: Boolean = false,
    /** Horodatage local de la mesure, pour calculer un debit entre deux lectures. */
    val takenAt: Long = 0,
) {
    val known: Boolean get() = cpuPercent >= 0 || memoryPercent >= 0 || diskPercent >= 0
}

/**
 * Les compteurs d'une interface reseau.
 *
 * Ce sont des totaux cumules depuis le demarrage, pas des debits. Un debit se
 * deduit de deux mesures, et l'horloge utilisee est celle de l'appareil : le
 * champ de temps de l'hote existe, mais son unite n'a pas ete mesuree, et un
 * debit calcule sur une unite supposee serait faux sans le dire.
 */
/**
 * Un disque physique, tel que l'hote le decrit.
 *
 * La place occupee ne vient pas du disque mais de ses partitions montees : le
 * champ que l'hote nomme « percentage_used » vaut 0 sur un disque plein a 94 %
 * et 10 sur un SSD rempli au quart - c'est l'usure de la memoire flash, pas le
 * remplissage. Additionner ce que les systemes de fichiers declarent est la
 * seule mesure qui corresponde a ce qu'on voit.
 */
/**
 * L'issue d'une tentative de connexion.
 *
 * Un mot de passe refuse et un code de verification manquant ne se corrigent
 * pas de la meme facon : les confondre ferait ressaisir un mot de passe juste.
 */
enum class SignIn {
    OK,

    /** L'hote exige un code de verification en deux etapes. */
    OTP_REQUIRED,

    /** Le code fourni a ete refuse : il a expire, ou il a ete mal recopie. */
    OTP_REFUSED,

    REFUSED,
}

/**
 * L'etat des mises a jour du systeme de l'hote.
 *
 * L'application se contente de dire ce que la machine annonce. Elle
 * n'installe rien : une mise a jour de systeme redemarre le NAS, coupe tous
 * les conteneurs et peut echouer - cela se decide devant l'interface du NAS,
 * pas au bout d'un doigt sur un telephone.
 */
data class HostUpdate(
    /** Faux tant que l'hote ne s'est pas prononce : la carte n'apparait pas. */
    val known: Boolean = false,
    val available: Boolean = false,
    val currentVersion: String = "",
    val latestVersion: String = "",
    /** L'hote signale une version de securite ou marquee importante. */
    val important: Boolean = false,
)

/** Ce qu'une ligne de la carte decrit vraiment. */
enum class DiskRole { VOLUME, DRIVE }

data class HostDisk(
    val name: String,
    val model: String,
    /**
     * Un volume porte l'espace occupe, un disque porte la temperature et la
     * sante. Sur ZimaOS les deux se confondent - un disque, une partition -
     * mais un Synology en RAID publie trois disques sous un seul volume :
     * les melanger afficherait des chiffres qui ne s'additionnent pas.
     */
    val role: DiskRole = DiskRole.DRIVE,
    /** HDD, SSD, MMC... tel quel : l'hote le donne deja en toutes lettres. */
    val kind: String = "",
    val sizeBytes: Long = -1,
    /** -1 quand aucune partition montee n'a pu etre additionnee. */
    val usedBytes: Long = -1,
    /** En degres Celsius. -1 quand l'hote ne la publie pas ou renvoie 0. */
    val temperature: Int = -1,
    val healthy: Boolean? = null,
    /** Heures de fonctionnement cumulees. 0 quand l'hote se tait. */
    val powerOnHours: Long = 0,
) {
    /** Le titre affiche : le modele s'il existe, sinon le nom du peripherique. */
    val title get() = model.ifBlank { name }

    /** Le remplissage en pourcentage, ou -1 quand il ne se calcule pas. */
    val percent: Int
        get() = if (sizeBytes > 0 && usedBytes >= 0) {
            ((usedBytes * 100) / sizeBytes).toInt().coerceIn(0, 100)
        } else {
            -1
        }
}

/** La nature d'une entree de journal, telle que l'hote la classe. */
enum class LogLevel(val label: String) {
    INFO("Information"),
    WARNING("Avertissement"),
    ERROR("Erreur"),
}

/**
 * Une entree du journal systeme de l'hote.
 *
 * Elle vient de la machine, pas de l'application : c'est elle qui sait qui
 * s'est connecte et quand. L'entree est donc rendue telle qu'elle est publiee -
 * on ne traduit pas son texte, on ne devine pas ce qu'il veut dire.
 */
data class LogEntry(
    val time: String,
    val level: LogLevel = LogLevel.INFO,
    /** La categorie de l'hote : « System », « Connexion »... Vide si absente. */
    val category: String = "",
    val message: String,
    /** L'utilisateur concerne, quand l'hote le nomme. */
    val who: String = "",
)

/**
 * Une session ouverte en ce moment sur l'hote.
 *
 * C'est la reponse a « qui est connecte la, maintenant » - une question qu'un
 * journal, qui regarde le passe, ne repond pas.
 */
data class LogSession(
    val who: String,
    val from: String,
    val protocol: String = "",
    val since: String = "",
    val current: Boolean = false,
)

/** Ce qu'un hote sait raconter de lui-meme. Vide quand il ne raconte rien. */
data class HostJournal(
    val entries: List<LogEntry> = emptyList(),
    val sessions: List<LogSession> = emptyList(),
    /** Total annonce par l'hote, qui depasse souvent ce qui a ete lu. */
    val total: Int = 0,
)

data class NetCounters(
    val name: String,
    val sentBytes: Long,
    val receivedBytes: Long,
)

/** Le debit d'une interface, deduit de deux mesures successives. */
data class NetRate(
    val name: String,
    val sentPerSecond: Long,
    val receivedPerSecond: Long,
)

/** Ce que la machine est, par opposition a ce qu'elle fait. */
data class HostMachine(
    val model: String = "",
    val name: String = "",
    val osVersion: String = "",
    val cpuModel: String = "",
    val cpuCores: Int = 0,
    val memoryTotalBytes: Long = -1,
    val memoryType: String = "",
) {
    val known: Boolean
        get() = model.isNotBlank() || osVersion.isNotBlank() || cpuModel.isNotBlank()
}

/**
 * Le delai avant mise en veille des disques.
 *
 * Les deux systemes ne comptent pas pareil : ZimaOS publie un niveau ATA, la
 * meme echelle que hdparm, tandis que DSM publie des minutes. La classe garde
 * donc des minutes et laisse chaque client dire d'ou elles viennent.
 */
class DiskSleep private constructor(val minutes: Int?, private val raw: String) {

    val label: String
        get() = when {
            minutes == null -> raw
            minutes <= 0 -> "jamais"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }

    companion object {
        /**
         * L'echelle ATA, celle que ZimaOS publie telle quelle.
         *
         * Le standard la definit en deux tranches : de 1 a 240, chaque cran
         * vaut cinq secondes ; de 241 a 251, chaque cran vaut trente minutes.
         * Cette lecture vient de la norme, pas d'une mesure sur la machine :
         * un niveau hors de ces tranches est donc affiche tel quel plutot
         * qu'interprete de travers.
         */
        fun fromAtaLevel(level: Int): DiskSleep = when (level) {
            0 -> DiskSleep(0, "jamais")
            in 1..240 -> DiskSleep((level * 5) / 60, "")
            in 241..251 -> DiskSleep((level - 240) * 30, "")
            else -> DiskSleep(null, "niveau $level")
        }

        /** DSM compte directement en minutes : rien a interpreter. */
        fun fromMinutes(minutes: Int): DiskSleep = DiskSleep(minutes, "")
    }
}

/**
 * L'extinction programmee de l'hote.
 *
 * Elle n'a pas d'interrupteur : une liste de jours vide *est* l'etat desactive.
 * Chercher un champ « active » revenait a en inventer un, et a afficher
 * « desactivee » quelle que soit la realite.
 */
data class ScheduledOff(
    val hour: Int = 0,
    val minute: Int = 0,
    val weekdays: List<String> = emptyList(),
) {
    val active: Boolean get() = weekdays.isNotEmpty()

    companion object {
        /**
         * Les codes de jours attendus par l'hote, dans l'ordre ou on les lit
         * en francais - la semaine commence lundi, pas dimanche.
         */
        val WEEK = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

        fun shortLabel(code: String): String = when (code) {
            "MON" -> "Lun"
            "TUE" -> "Mar"
            "WED" -> "Mer"
            "THU" -> "Jeu"
            "FRI" -> "Ven"
            "SAT" -> "Sam"
            "SUN" -> "Dim"
            else -> code
        }
    }
}

/** Ce qu'on peut demander a la machine. Une action, un chemin, rien de plus. */
enum class HostPower(val state: String, val label: String) {
    RESTART("restart", "Redémarrer"),
    OFF("off", "Éteindre"),
}

enum class HostAppAction(val value: String) {
    START("start"),
    STOP("stop"),
    RESTART("restart"),
}
