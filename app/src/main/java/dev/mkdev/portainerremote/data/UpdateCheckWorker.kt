package dev.mkdev.portainerremote.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mkdev.portainerremote.PortainerRemoteApp
import dev.mkdev.portainerremote.core.ApiResult
import java.util.concurrent.TimeUnit

/**
 * Verification quotidienne des releases.
 *
 * WorkManager ne garantit pas l'heure exacte : il regroupe les travaux pour
 * economiser la batterie. C'est le bon compromis ici — une mise a jour
 * d'application ne se joue pas a quelques heures pres.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PortainerRemoteApp).container

        return when (val result = container.updateChecker.check()) {
            is ApiResult.Ok -> {
                val release = result.value
                if (release != null) {
                    val posted = container.updateNotifier.notifyIfNew(release)
                    Log.d(TAG, "release ${release.version} trouvee, notifiee=$posted")
                }
                Result.success()
            }
            // Reseau absent ou VPN coupe : reessayer plus tard vaut mieux
            // qu'attendre la prochaine periode complete.
            else -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val WORK_NAME = "verification-mises-a-jour"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            // KEEP : replanifier a chaque demarrage repousserait indefiniment la
            // premiere execution sur un appareil ouvert plusieurs fois par jour.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
