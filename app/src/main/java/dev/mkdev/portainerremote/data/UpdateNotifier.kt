package dev.mkdev.portainerremote.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.mkdev.portainerremote.MainActivity
import dev.mkdev.portainerremote.R
import dev.mkdev.portainerremote.data.net.ReleaseInfo
import dev.mkdev.portainerremote.data.store.PrefsStore

/**
 * Notifie qu'une version plus recente existe.
 *
 * Une meme version n'est annoncee qu'une fois : sans cette memoire, la
 * verification quotidienne reposterait la meme notification chaque jour jusqu'a
 * l'installation, ce qui apprend a l'ignorer.
 */
class UpdateNotifier(
    private val context: Context,
    private val prefs: PrefsStore,
) {

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mises à jour",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Annonce une nouvelle version publiée de l'application."
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun canNotify(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    /** @return vrai si une notification a effectivement ete postee. */
    suspend fun notifyIfNew(release: ReleaseInfo): Boolean {
        if (prefs.notifiedVersion() == release.version) return false
        if (!canNotify()) return false

        ensureChannel()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_UPDATES, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_update)
            .setContentTitle("Portainer Remote ${release.version}")
            .setContentText(release.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(release.notes.take(400)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        prefs.setNotifiedVersion(release.version)
        return true
    }

    private companion object {
        const val CHANNEL_ID = "updates"
        const val NOTIFICATION_ID = 4201
    }
}
