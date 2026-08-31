package dev.mkdev.portainerremote.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.mkdev.portainerremote.MainActivity
import dev.mkdev.portainerremote.PortainerRemoteApp
import dev.mkdev.portainerremote.R
import dev.mkdev.portainerremote.data.store.WidgetEntry
import dev.mkdev.portainerremote.data.store.WidgetSnapshot

private val Running = Color(0xFF4FBE81)
private val Partial = Color(0xFFD69A3C)
private val Stopped = Color(0xFF8698A7)

/**
 * Widget d'ecran d'accueil.
 *
 * C'est le livrable qui justifie l'application : relancer un stack en un tap,
 * sans rien ouvrir. Il ne fait aucun appel reseau et se contente de lire le
 * dernier instantane connu ; les actions partent vers [StackActionWorker].
 */
class StackWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as PortainerRemoteApp).container
        val snapshot = container.favoritesStore.snapshot()

        provideContent {
            GlanceTheme {
                WidgetBody(snapshot)
            }
        }
    }

    companion object {
        /** Nommé refreshAll et non updateAll, pour ne pas masquer l'extension de Glance. */
        suspend fun refreshAll(context: Context) {
            StackWidget().updateAll(context.applicationContext)
        }
    }
}

/*
 * Attention : ne pas introduire de Spacer sans dimension ici. Un
 * Spacer(GlanceModifier.padding(...)) sans width ni height fait disparaitre
 * tout ce qui le suit dans la colonne, sans lever la moindre exception.
 * L'espacement se met en padding sur l'element concerne.
 */
@Composable
private fun WidgetBody(snapshot: WidgetSnapshot) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (snapshot.stale) "Portainer · hors ligne" else "Portainer",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            CircleIconButton(
                imageProvider = ImageProvider(R.drawable.ic_widget_refresh),
                contentDescription = "Rafraîchir",
                backgroundColor = null,
                onClick = actionRunCallback<RefreshAction>(),
            )
        }

        if (snapshot.entries.isEmpty()) {
            Text(
                text = "Aucun favori. Épingle un stack depuis l'application avec l'étoile.",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                modifier = GlanceModifier.padding(top = 6.dp),
            )
        } else {
            snapshot.entries.forEach { entry -> StackRow(entry, snapshot.stale) }
        }
    }
}

@Composable
private fun StackRow(entry: WidgetEntry, stale: Boolean) {
    val color = when (entry.state) {
        "RUNNING" -> Running
        "PARTIAL" -> Partial
        else -> Stopped
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            // Le nom ouvre l'application ; seul le bouton agit. Un widget ne doit
            // jamais declencher une action destructive par mégarde.
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "●",
            style = TextStyle(
                color = ColorProvider(if (stale) Stopped else color),
                fontSize = 14.sp,
            ),
            modifier = GlanceModifier.padding(end = 8.dp),
        )
        Text(
            text = entry.name,
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = GlanceModifier.defaultWeight(),
        )
        CircleIconButton(
            imageProvider = ImageProvider(
                if (entry.state == "RUNNING") R.drawable.ic_widget_restart
                else R.drawable.ic_widget_play,
            ),
            contentDescription = if (entry.state == "RUNNING") {
                "Relancer ${entry.name}"
            } else {
                "Démarrer ${entry.name}"
            },
            backgroundColor = null,
            onClick = actionRunCallback<StackTapAction>(
                actionParametersOf(
                    serverIdKey to entry.serverId,
                    stackKeyKey to entry.stackKey,
                ),
            ),
        )
    }
}

private val serverIdKey = ActionParameters.Key<String>(StackActionWorker.KEY_SERVER_ID)
private val stackKeyKey = ActionParameters.Key<String>(StackActionWorker.KEY_STACK_KEY)

class StackTapAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val serverId = parameters[serverIdKey] ?: return
        val stackKey = parameters[stackKeyKey] ?: return
        StackActionWorker.enqueueAction(context, serverId, stackKey)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        StackActionWorker.enqueueRefresh(context)
    }
}

class StackWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StackWidget()
}
