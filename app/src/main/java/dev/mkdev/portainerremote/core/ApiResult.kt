package dev.mkdev.portainerremote.core

/**
 * Resultat d'un appel a Portainer.
 *
 * [Unsupported] existe pour la regle 2 de l'etude : on ne branche jamais sur un
 * numero de version, on tente la route et on interprete sa reponse. Un 404 ou un
 * 501 signifie "cette instance ne sait pas faire", pas "erreur".
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class HttpError(val code: Int) : ApiResult<Nothing>
    data class NetworkError(val reason: String) : ApiResult<Nothing>
    data object Unsupported : ApiResult<Nothing>
}

fun <T> ApiResult<T>.valueOrNull(): T? = (this as? ApiResult.Ok)?.value

fun <T> ApiResult<T>.valueOr(fallback: T): T = valueOrNull() ?: fallback

/** Message affichable, en francais, sans jargon HTTP inutile. */
fun ApiResult<*>.errorText(): String = when (this) {
    is ApiResult.Ok -> ""
    is ApiResult.Unsupported -> "Cette instance Portainer ne propose pas cette action."
    is ApiResult.HttpError -> when (code) {
        401, 403 -> "Identifiants refusés. Vérifie le jeton ou le mot de passe."
        404 -> "Introuvable sur ce serveur."
        else -> "Le serveur a répondu $code."
    }
    is ApiResult.NetworkError -> "Serveur injoignable. Le VPN est-il actif ?"
}
