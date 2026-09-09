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
enum class HostKind(val label: String, val supported: Boolean) {
    ZIMA("ZimaOS · CasaOS", true),
    SYNOLOGY("Synology DSM", false),
    QNAP("QNAP QTS", false),
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
data class HostDisk(
    val name: String,
    val model: String,
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
 * L'hote le publie sous forme d'un niveau ATA, la meme echelle que hdparm : de
 * 1 a 240, des pas de cinq secondes ; de 241 a 251, des pas de trente minutes.
 * L'interpretation est donnee comme telle - c'est une lecture du standard, pas
 * une mesure faite sur la machine.
 */
@JvmInline
value class DiskSleep(val level: Int) {

    val minutes: Int?
        get() = when (level) {
            in 1..240 -> (level * 5) / 60
            in 241..251 -> (level - 240) * 30
            else -> null
        }

    val label: String
        get() = when {
            level <= 0 -> "jamais"
            minutes == null -> "niveau $level"
            minutes == 0 -> "moins d'une minute"
            minutes!! < 60 -> "${minutes} min"
            minutes!! % 60 == 0 -> "${minutes!! / 60} h"
            else -> "${minutes!! / 60} h ${minutes!! % 60} min"
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
