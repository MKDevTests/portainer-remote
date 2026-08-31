package dev.mkdev.portainerremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mkdev.portainerremote.domain.RunState
import dev.mkdev.portainerremote.domain.StackOrigin
import dev.mkdev.portainerremote.ui.theme.StateColors

@Composable
private fun Chip(text: String, fg: Color, bg: Color, dot: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        if (dot) {
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(6.dp)
                    .background(fg, CircleShape),
            )
        }
        Text(
            text = text,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** L'état encodé dans la forme autant que dans le texte, pour être lisible d'un coup d'œil. */
@Composable
fun StateChip(state: RunState, running: Int, total: Int) {
    when (state) {
        RunState.RUNNING -> Chip("En marche", StateColors.running, StateColors.runningBg, dot = true)
        RunState.PARTIAL -> Chip("$running / $total", StateColors.partial, StateColors.partialBg, dot = true)
        RunState.STOPPED -> Chip("Arrêté", StateColors.stopped, StateColors.stoppedBg, dot = true)
        RunState.UNKNOWN -> Chip("État inconnu", StateColors.stopped, StateColors.stoppedBg)
    }
}

/** État d'une image : ce qui décide si elle peut partir. */
@Composable
fun UsageChip(inUse: Boolean, dangling: Boolean) {
    when {
        inUse -> Chip("Utilisée", StateColors.running, StateColors.runningBg, dot = true)
        dangling -> Chip("Sans étiquette", StateColors.partial, StateColors.partialBg, dot = true)
        else -> Chip("Inutilisée", StateColors.stopped, StateColors.stoppedBg, dot = true)
    }
}

/**
 * D'où vient le stack. L'information compte : un stack déduit n'a pas de route
 * native, ses actions passent conteneur par conteneur.
 */
@Composable
fun OriginChip(origin: StackOrigin) {
    val scheme = MaterialTheme.colorScheme
    when (origin) {
        StackOrigin.MANAGED -> Chip("Portainer", scheme.onPrimaryContainer, scheme.primaryContainer)
        StackOrigin.COMPOSE -> Chip("Compose", scheme.onSurfaceVariant, scheme.surfaceVariant)
        StackOrigin.SWARM -> Chip("Swarm", scheme.onSurfaceVariant, scheme.surfaceVariant)
        StackOrigin.LOOSE -> Chip("Isolés", scheme.onSurfaceVariant, scheme.surfaceVariant)
    }
}
