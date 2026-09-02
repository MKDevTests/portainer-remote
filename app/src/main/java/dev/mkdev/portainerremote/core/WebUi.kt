package dev.mkdev.portainerremote.core

/**
 * Batit le lien vers un service publie par un conteneur.
 *
 * Toute la difficulte tient en une phrase : l'hote de Portainer n'est pas
 * forcement l'hote de Docker. Un port publie appartient a la machine ou tourne
 * le demon, pas a celle ou tourne l'interface web. Trois sources repondent, de
 * la plus explicite a la plus faible, et l'ordre entre elles est tout.
 */
object WebUi {

    /**
     * Ports ou l'on suppose TLS. La liste est volontairement courte : deviner
     * https a tort donne une erreur de certificat illisible, alors que deviner
     * http a tort donne une redirection que le navigateur suit tout seul.
     */
    private val HTTPS_PORTS = setOf(443, 8443, 9443)

    /**
     * @param serverBaseUrl adresse du Portainer configuree dans l'app.
     * @param endpointPublicUrl champ PublicURL de l'environnement, quand l'administrateur l'a rempli.
     * @param endpointUrl champ URL de l'environnement : le demon Docker vise.
     * @return l'hote a joindre, ou une chaine vide si rien de fiable ne se deduit.
     */
    fun resolveHost(
        serverBaseUrl: String,
        endpointPublicUrl: String = "",
        endpointUrl: String = "",
    ): String {
        val fallback = hostOf(serverBaseUrl)

        // 1. La reponse explicite de l'administrateur. Rien ne la surpasse.
        hostOf(endpointPublicUrl).takeIf { it.isNotBlank() }?.let { return it }

        // 2. L'adresse du demon, quand il est joignable par le reseau. Un socket
        //    unix ne nomme aucune machine : il dit seulement "ici", c'est-a-dire
        //    l'hote de Portainer, que le repli couvre deja.
        val daemon = hostOf(endpointUrl)
        if (daemon.isNotBlank() && !endpointUrl.startsWith("unix:") && !isLocal(daemon)) {
            return daemon
        }

        // 3. Repli : l'hote de Portainer. Juste des que Docker tourne sur la
        //    meme machine, ce qui est le cas de tous les environnements sondes.
        return fallback
    }

    /**
     * Lien vers un port publie. Null quand aucun hote n'a pu etre resolu :
     * mieux vaut afficher le port sans lien qu'un lien qui ne mene nulle part.
     */
    fun url(host: String, publicPort: Int): String? {
        if (host.isBlank() || publicPort <= 0) return null
        val scheme = if (publicPort in HTTPS_PORTS) "https" else "http"
        return "$scheme://$host:$publicPort"
    }

    /**
     * Hote nu d'une adresse, sans protocole, sans port, sans chemin.
     *
     * Un IPv6 litteral garde ses crochets : ils font partie de l'ecriture d'une
     * URL, et c'est une URL qu'on rebatit ensuite.
     */
    internal fun hostOf(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""

        val scheme = value.indexOf("://")
        if (scheme >= 0) value = value.substring(scheme + 3)

        value = value.substringBefore('/').substringAfter('@')
        if (value.isEmpty()) return ""

        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            return if (close > 0) value.substring(0, close + 1) else ""
        }
        return value.substringBefore(':')
    }

    /**
     * Une boucle locale vue par Portainer designe la machine de Portainer, pas
     * celle du telephone. Elle ne peut donc jamais servir de cible.
     */
    private fun isLocal(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
}
