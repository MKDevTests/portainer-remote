package dev.mkdev.portainerremote

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.mkdev.portainerremote.data.PortainerRepository
import dev.mkdev.portainerremote.data.store.ServerStore

/** Injection manuelle : l'app est trop petite pour justifier un conteneur. */
class AppContainer(context: Context) {
    val serverStore = ServerStore(context)
    val repository = PortainerRepository(serverStore)
}

class PortainerRemoteApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

@Composable
fun rememberAppContainer(): AppContainer =
    (LocalContext.current.applicationContext as PortainerRemoteApp).container
