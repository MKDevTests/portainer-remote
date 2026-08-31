package dev.mkdev.portainerremote.core

/**
 * Normalise l'adresse saisie.
 *
 * Le port par defaut depend du protocole, pas d'une constante : Portainer ecoute
 * en 9000 sur HTTP et en 9443 sur HTTPS. Completer systematiquement en 9000
 * casserait toute instance servie en HTTPS.
 */
object ServerUrl {

    private const val HTTP_PORT = 9000
    private const val HTTPS_PORT = 9443

    fun normalize(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.isEmpty()) return ""

        // Sans protocole, on suppose HTTPS : le defaut sur doit etre le defaut tout court.
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        val secure = url.startsWith("https://")
        val scheme = if (secure) "https://" else "http://"
        val rest = url.removePrefix(scheme)
        if (rest.isEmpty()) return ""

        val host = rest.substringBefore('/')
        val path = rest.removePrefix(host)

        // Un IPv6 litteral s'ecrit [::1]:9000 : le port est ce qui suit le crochet.
        val portZone = host.substringAfterLast(']', host)
        if (portZone.contains(':')) return url

        val port = if (secure) HTTPS_PORT else HTTP_PORT
        return "$scheme$host:$port$path"
    }

    /** Vrai si la normalisation changerait la saisie, pour l'annoncer avant enregistrement. */
    fun willChange(raw: String): Boolean {
        val normalized = normalize(raw)
        return normalized.isNotEmpty() && normalized != raw.trim().trimEnd('/')
    }
}
