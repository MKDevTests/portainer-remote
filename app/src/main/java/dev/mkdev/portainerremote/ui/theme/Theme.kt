package dev.mkdev.portainerremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0B6E77)
private val TealLight = Color(0xFF3FB3BC)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1F0F1),
    onPrimaryContainer = Color(0xFF04353A),
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF14202B),
    surface = Color.White,
    onSurface = Color(0xFF14202B),
    surfaceVariant = Color(0xFFEDF1F4),
    onSurfaceVariant = Color(0xFF4E5F6E),
    outline = Color(0xFFB9C6D1),
    error = Color(0xFFA8232B),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF04262A),
    primaryContainer = Color(0xFF102C30),
    onPrimaryContainer = Color(0xFFBFE9EC),
    background = Color(0xFF0D141A),
    onBackground = Color(0xFFE3EAF0),
    surface = Color(0xFF141E26),
    onSurface = Color(0xFFE3EAF0),
    surfaceVariant = Color(0xFF1B2831),
    onSurfaceVariant = Color(0xFF9DAEBB),
    outline = Color(0xFF354652),
    error = Color(0xFFE4747C),
)

/** Couleurs semantiques d'etat, distinctes de la couleur d'accent. */
object StateColors {
    val running: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF4FBE81) else Color(0xFF1D7A47)
    val runningBg: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF10271B) else Color(0xFFE2F2E8)
    val partial: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFD69A3C) else Color(0xFF9A5E00)
    val partialBg: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2A2013) else Color(0xFFF8EEDC)
    val stopped: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF8698A7) else Color(0xFF67788A)
    val stoppedBg: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1B2831) else Color(0xFFEDF1F4)
}

@Composable
fun PortainerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
