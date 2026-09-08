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
) {
    val known: Boolean get() = cpuPercent >= 0 || memoryPercent >= 0 || diskPercent >= 0
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
