package dev.mkdev.portainerremote.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.net.Uri
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.mkdev.portainerremote.rememberAppContainer
import dev.mkdev.portainerremote.ui.backup.BackupScreen
import dev.mkdev.portainerremote.ui.backup.BackupViewModel
import dev.mkdev.portainerremote.ui.images.ImagesScreen
import dev.mkdev.portainerremote.ui.images.ImagesViewModel
import dev.mkdev.portainerremote.ui.logs.LogsScreen
import dev.mkdev.portainerremote.ui.logs.LogsViewModel
import dev.mkdev.portainerremote.ui.servers.ServerEditScreen
import dev.mkdev.portainerremote.ui.servers.ServersScreen
import dev.mkdev.portainerremote.ui.servers.ServersViewModel
import dev.mkdev.portainerremote.ui.stacks.StacksScreen
import dev.mkdev.portainerremote.ui.stacks.StacksViewModel
import dev.mkdev.portainerremote.ui.updates.UpdatesScreen
import dev.mkdev.portainerremote.ui.updates.UpdatesViewModel

private const val ROUTE_SERVERS = "servers"
private const val ROUTE_SERVER_NEW = "server/new"
private const val ROUTE_SERVER_EDIT = "server/edit/{id}"
private const val ROUTE_STACKS = "stacks/{serverId}"
private const val ROUTE_IMAGES = "images/{serverId}"
private const val ROUTE_UPDATES = "updates"
private const val ROUTE_BACKUP = "backup"
private const val ROUTE_LOGS = "logs/{serverId}/{envId}/{containerId}/{name}"

@Composable
fun App(openUpdatesAtStart: Boolean = false) {
    val navController = rememberNavController()
    val container = rememberAppContainer()

    val serversFactory = viewModelFactory {
        initializer {
            ServersViewModel(
                container.serverStore,
                container.repository,
                container.favoritesStore,
                container.widgetSync,
                container.updateChecker,
                container.updateNotifier,
            )
        }
    }

    // La notification ouvre l'ecran des mises a jour, mais par-dessus la liste
    // des serveurs : le retour arriere doit ramener a l'application, pas la
    // fermer.
    LaunchedEffect(openUpdatesAtStart) {
        if (openUpdatesAtStart) navController.navigate(ROUTE_UPDATES)
    }

    NavHost(navController = navController, startDestination = ROUTE_SERVERS) {

        composable(ROUTE_SERVERS) {
            ServersScreen(
                viewModel = viewModel(factory = serversFactory),
                onOpen = { id -> navController.navigate("stacks/$id") },
                onEdit = { id -> navController.navigate("server/edit/$id") },
                onAdd = { navController.navigate(ROUTE_SERVER_NEW) },
                onOpenUpdates = { navController.navigate(ROUTE_UPDATES) },
                onOpenBackup = { navController.navigate(ROUTE_BACKUP) },
            )
        }

        composable(ROUTE_SERVER_NEW) {
            ServerEditScreen(
                viewModel = viewModel(factory = serversFactory),
                serverId = null,
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = ROUTE_SERVER_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            ServerEditScreen(
                viewModel = viewModel(factory = serversFactory),
                serverId = entry.arguments?.getString("id"),
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = ROUTE_STACKS,
            arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
        ) { entry ->
            val serverId = entry.arguments?.getString("serverId").orEmpty()
            StacksScreen(
                viewModel = viewModel(
                    key = "stacks-$serverId",
                    factory = viewModelFactory {
                        initializer {
                            StacksViewModel(
                                serverId,
                                container.serverStore,
                                container.repository,
                                container.favoritesStore,
                                container.widgetSync,
                                container.prefsStore,
                            )
                        }
                    },
                ),
                onBack = { navController.popBackStack() },
                onOpenImages = { navController.navigate("images/$serverId") },
                onOpenLogs = { envId, containerId, name ->
                    // Un nom de conteneur peut contenir des caracteres a echapper.
                    val safeName = Uri.encode(name)
                    navController.navigate("logs/$serverId/$envId/$containerId/$safeName")
                },
            )
        }

        composable(
            route = ROUTE_LOGS,
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("envId") { type = NavType.IntType },
                navArgument("containerId") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
            ),
        ) { entry ->
            val serverId = entry.arguments?.getString("serverId").orEmpty()
            val envId = entry.arguments?.getInt("envId") ?: 0
            val containerId = entry.arguments?.getString("containerId").orEmpty()
            val name = entry.arguments?.getString("name").orEmpty()

            LogsScreen(
                viewModel = viewModel(
                    key = "logs-$containerId",
                    factory = viewModelFactory {
                        initializer {
                            LogsViewModel(
                                serverId,
                                envId,
                                containerId,
                                name,
                                container.serverStore,
                                container.repository,
                            )
                        }
                    },
                ),
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_BACKUP) {
            BackupScreen(
                viewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            BackupViewModel(container.backupManager, container.widgetSync)
                        }
                    },
                ),
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_UPDATES) {
            UpdatesScreen(
                viewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            UpdatesViewModel(container.updateChecker, container.updateManager)
                        }
                    },
                ),
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = ROUTE_IMAGES,
            arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
        ) { entry ->
            val serverId = entry.arguments?.getString("serverId").orEmpty()
            ImagesScreen(
                viewModel = viewModel(
                    key = "images-$serverId",
                    factory = viewModelFactory {
                        initializer {
                            ImagesViewModel(serverId, container.serverStore, container.repository)
                        }
                    },
                ),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
