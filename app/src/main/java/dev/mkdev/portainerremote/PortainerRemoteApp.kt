package dev.mkdev.portainerremote

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.mkdev.portainerremote.data.PortainerRepository
import dev.mkdev.portainerremote.data.UpdateManager
import dev.mkdev.portainerremote.data.WidgetSync
import dev.mkdev.portainerremote.data.store.FavoritesStore
import dev.mkdev.portainerremote.data.store.ServerStore
import dev.mkdev.portainerremote.data.net.UpdateChecker

/** Injection manuelle : l'app est trop petite pour justifier un conteneur. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val serverStore = ServerStore(appContext)
    val favoritesStore = FavoritesStore(appContext)
    val repository = PortainerRepository(serverStore)
    val widgetSync = WidgetSync(appContext, serverStore, favoritesStore, repository)
    val updateChecker = UpdateChecker()
    val updateManager = UpdateManager(appContext)
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
