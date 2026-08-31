package dev.mkdev.portainerremote.data.net

/**
 * Demultiplexeur du flux de logs Docker.
 *
 * Mesure sur une instance reelle, premiers octets recus :
 *
 *     01 00 00 00 00 00 00 71 32 30 32 36 2D 30 38 2D 33 31 ...
 *     |  <- flux           |  <- taille   |  <- "2026-08-31..."
 *
 * Soit un entete de 8 octets par trame : un octet de flux (0 stdin, 1 stdout,
 * 2 stderr), trois octets nuls, puis la taille de la charge en gros-boutiste.
 * Affiche sans traitement, chaque ligne commence par des caracteres parasites.
 *
 * Exception a gerer : un conteneur lance avec un TTY renvoie du texte brut,
 * sans aucun entete.
 */
object DockerLogStream {

    private const val HEADER = 8

    fun decode(raw: ByteArray): String {
        if (raw.isEmpty()) return ""
        if (!looksMultiplexed(raw)) return String(raw, Charsets.UTF_8)

        val out = StringBuilder(raw.size)
        var offset = 0
        while (offset + HEADER <= raw.size) {
            val size = ((raw[offset + 4].toInt() and 0xFF) shl 24) or
                ((raw[offset + 5].toInt() and 0xFF) shl 16) or
                ((raw[offset + 6].toInt() and 0xFF) shl 8) or
                (raw[offset + 7].toInt() and 0xFF)

            val start = offset + HEADER
            if (size <= 0 || start >= raw.size) break
            val end = minOf(start + size, raw.size)
            out.append(String(raw, start, end - start, Charsets.UTF_8))
            offset = end
        }
        return out.toString()
    }

    /** Un entete valide commence par un octet de flux 0..2 suivi de trois octets nuls. */
    private fun looksMultiplexed(raw: ByteArray): Boolean =
        raw.size >= HEADER &&
            raw[0].toInt() in 0..2 &&
            raw[1].toInt() == 0 &&
            raw[2].toInt() == 0 &&
            raw[3].toInt() == 0
}
