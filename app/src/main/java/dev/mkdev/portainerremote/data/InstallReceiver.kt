package dev.mkdev.portainerremote.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ce que le systeme repond a une demande d'installation.
 *
 * L'ancien chemin - une intention ACTION_VIEW sur le fichier - n'avait aucun
 * retour : quand Android ne l'honorait pas, il ne se passait rien du tout et
 * l'application restait a affirmer qu'une mise a jour attendait. Une session
 * d'installation, elle, rend toujours un verdict, y compris quand il est
 * mauvais. C'est la seule raison de cette classe.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        Log.d(TAG, "statut $status ${detail.take(120)}")

        when (status) {
            // Android veut sa propre confirmation, et il a raison : installer
            // une application se decide devant un ecran du systeme, pas dans le
            // notre. On se contente d'ouvrir cet ecran.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    publish("Android n'a pas fourni son écran de confirmation.")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure {
                    publish("Impossible d'ouvrir l'écran d'installation : ${it.message}")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> publish("Mise à jour installée.")

            // Annuler n'est pas une panne : le dire comme une erreur ferait
            // chercher un probleme qui n'existe pas.
            PackageInstaller.STATUS_FAILURE_ABORTED -> publish("Installation annulée.")

            PackageInstaller.STATUS_FAILURE_CONFLICT -> publish(
                "Installation refusée : cette version est signée par une autre clé, " +
                    "ou une version plus récente est déjà installée.",
            )

            PackageInstaller.STATUS_FAILURE_STORAGE -> publish(
                "Installation refusée : pas assez d'espace sur l'appareil.",
            )

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> publish(
                "Installation refusée : ce paquet n'est pas compatible avec cet appareil.",
            )

            PackageInstaller.STATUS_FAILURE_INVALID -> publish(
                "Installation refusée : le fichier téléchargé est illisible.",
            )

            PackageInstaller.STATUS_FAILURE_BLOCKED -> publish(
                "Installation bloquée par l'appareil : ${detail.ifBlank { "aucun motif donné" }}",
            )

            else -> publish(
                "Installation échouée (code $status)" +
                    if (detail.isBlank()) "." else " : $detail",
            )
        }
    }

    companion object {
        private const val TAG = "InstallReceiver"

        private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

        /** Ce que le systeme a repondu, a afficher tel quel. */
        val messages: SharedFlow<String> = _messages.asSharedFlow()

        private val _installed = MutableStateFlow(false)

        /** Vrai une fois l'installation reussie : l'ecran cesse de la proposer. */
        val installed: StateFlow<Boolean> = _installed.asStateFlow()

        private fun publish(message: String) {
            if (message == "Mise à jour installée.") _installed.value = true
            _messages.tryEmit(message)
        }

        const val ACTION = "dev.mkdev.portainerremote.INSTALL_STATUS"
    }
}
