package dev.mkdev.portainerremote.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.mkdev.portainerremote.rememberAppContainer
import dev.mkdev.portainerremote.ui.images.ImagesScreen
import dev.mkdev.portainerremote.ui.images.ImagesViewModel
import dev.mkdev.portainerremote.ui.servers.ServerEditScreen
import dev.mkdev.portainerremote.ui.servers.ServersScreen
import dev.mkdev.portainerremote.ui.servers.ServersViewModel
import dev.mkdev.portainerremote.ui.stacks.StacksScreen
import dev.mkdev.portainerremote.ui.stacks.StacksViewModel

private const val ROUTE_SERVERS = "servers"
private const val ROUTE_SERVER_NEW = "server/new"
private const val ROUTE_SERVER_EDIT = "server/edit/{id}"
private const val ROUTE_STACKS = "stacks/{serverId}"
private const val ROUTE_IMAGES = "images/{serverId}"

@Composable
fun App() {
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
            )
        }
    }

    NavHost(navController = navController, startDestination = ROUTE_SERVERS) {

        composable(ROUTE_SERVERS) {
            ServersScreen(
                viewModel = viewModel(factory = serversFactory),
                onOpen = { id -> navController.navigate("stacks/$id") },
                onEdit = { id -> navController.navigate("server/edit/$id") },
                onAdd = { navController.navigate(ROUTE_SERVER_NEW) },
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
                            )
                        }
                    },
                ),
                onBack = { navController.popBackStack() },
                onOpenImages = { navController.navigate("images/$serverId") },
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
