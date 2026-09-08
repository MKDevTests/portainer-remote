package dev.mkdev.portainerremote.domain

/**
 * Le NAS lui-meme, quand il tourne sous ZimaOS ou CasaOS.
 *
 * Cette couche est optionnelle et detectee : l'app fonctionne entierement sans
 * elle, avec n'importe quel Portainer. Elle n'existe que pour les deux choses
 * que Portainer ne sait pas faire, parce qu'elles se situent au-dessous de lui :
 * demarrer Portainer quand il est arrete, et eteindre la machine.
 */
data class HostConfig(
    val baseUrl: String = "",
    val username: String = "",
    /** L'application qui heberge Portainer, choisie une fois par l'utilisateur. */
    val portainerAppId: String = "",
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && username.isNotBlank()
}

/** Une application connue de ZimaOS. Sur ce NAS, Portainer en est une. */
data class HostApp(
    val id: String,
    val name: String,
    val running: Boolean,
)

/** Charge de la machine, telle que ZimaOS la mesure lui-meme. */
data class HostUsage(
    val cpuPercent: Int = -1,
    val memoryPercent: Int = -1,
    val diskPercent: Int = -1,
    val uptimeSeconds: Long = -1,
) {
    val known: Boolean get() = cpuPercent >= 0 || memoryPercent >= 0 || diskPercent >= 0
}

/** L'extinction programmee de ZimaOS. Les jours sont ceux de son API. */
data class ScheduledOff(
    val enabled: Boolean = false,
    val hour: Int = 0,
    val minute: Int = 0,
    val weekdays: List<String> = emptyList(),
)

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
