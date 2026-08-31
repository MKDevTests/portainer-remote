package dev.mkdev.portainerremote.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mkdev.portainerremote.PortainerRemoteApp
import dev.mkdev.portainerremote.domain.RunState
import dev.mkdev.portainerremote.domain.StackAction

/**
 * Execute l'action demandee depuis le widget ou la tuile.
 *
 * Un widget ne peut pas appeler le reseau : il ne vit que quelques
 * millisecondes. Le tap declenche donc ce travail, qui agit puis redessine.
 */
class StackActionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PortainerRemoteApp).container

        val serverId = inputData.getString(KEY_SERVER_ID)
        val stackKey = inputData.getString(KEY_STACK_KEY)

        // Sans cible, c'est un simple rafraichissement.
        if (serverId.isNullOrBlank() || stackKey.isNullOrBlank()) {
            container.widgetSync.refresh()
            return Result.success()
        }

        val server = container.serverStore.get(serverId) ?: return Result.success()
        val favorite = container.favoritesStore.current()
            .firstOrNull { it.serverId == serverId && it.stackKey == stackKey }
            ?: return Result.success()

        val stack = container.widgetSync.resolve(favorite)
        if (stack == null) {
            // Injoignable : on redessine pour afficher l'etat perime plutot que
            // de laisser croire que l'action a abouti.
            container.widgetSync.refresh()
            return Result.retry()
        }

        // Un tap, une intention : relancer ce qui tourne, demarrer ce qui est arrete.
        val action = when (stack.runState) {
            RunState.RUNNING -> StackAction.RESTART
            else -> StackAction.START
        }

        container.repository.act(server, stack, action)
        container.widgetSync.refresh()
        return Result.success()
    }

    companion object {
        const val KEY_SERVER_ID = "serverId"
        const val KEY_STACK_KEY = "stackKey"

        private const val WORK_NAME = "portainer-widget-action"

        fun enqueueAction(context: Context, serverId: String, stackKey: String) {
            enqueue(
                context,
                Data.Builder()
                    .putString(KEY_SERVER_ID, serverId)
                    .putString(KEY_STACK_KEY, stackKey)
                    .build(),
            )
        }

        fun enqueueRefresh(context: Context) = enqueue(context, Data.EMPTY)

        private fun enqueue(context: Context, data: Data) {
            val request = OneTimeWorkRequestBuilder<StackActionWorker>()
                .setInputData(data)
                .build()
            // APPEND_OR_REPLACE : deux taps rapproches doivent s'enchainer, pas
            // s'annuler ni se marcher dessus.
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
