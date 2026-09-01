package dev.mkdev.portainerremote

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.mkdev.portainerremote.ui.App
import dev.mkdev.portainerremote.ui.theme.PortainerTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Refusée, l'application se contente de la bannière. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Depuis Android 13 la permission se demande à l'exécution. La demander
        // au premier lancement plutôt qu'au moment d'une mise à jour : à ce
        // moment-là, la notification serait perdue le temps de répondre.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val openUpdates = intent?.getBooleanExtra(EXTRA_OPEN_UPDATES, false) == true

        setContent {
            PortainerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App(openUpdatesAtStart = openUpdates)
                }
            }
        }
    }

    companion object {
        /** Posé par la notification de mise à jour, pour ouvrir l'écran directement. */
        const val EXTRA_OPEN_UPDATES = "open_updates"
    }
}
