package dev.mkdev.portainerremote.ui.stacks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mkdev.portainerremote.domain.ContainerView
import dev.mkdev.portainerremote.domain.EnvGroup
import dev.mkdev.portainerremote.domain.RunState
import dev.mkdev.portainerremote.domain.StackAction
import dev.mkdev.portainerremote.domain.StackOrigin
import dev.mkdev.portainerremote.domain.StackView
import dev.mkdev.portainerremote.ui.components.OriginChip
import dev.mkdev.portainerremote.ui.components.StateChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StacksScreen(
    viewModel: StacksViewModel,
    onBack: () -> Unit,
    onOpenImages: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.server?.label ?: "Stacks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenImages) {
                        Icon(Icons.Default.Layers, contentDescription = "Images")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rafraîchir")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            val error = ui.error
            if (error != null && ui.groups.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                    Text("Rien à afficher", style = MaterialTheme.typography.titleMedium)
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                return@Column
            }

            LazyColumn(
                // Meme borne que la liste des serveurs : au-dela, le nom du stack
                // et ses boutons se retrouvent aux deux extremites de l'ecran.
                modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ui.groups.forEach { group ->
                    item(key = "env-${group.envId}") { EnvHeader(group) }

                    if (!group.dockerCapable) {
                        item(key = "env-${group.envId}-unsupported") {
                            Text(
                                "Environnement ${group.kindLabel} : cette application ne pilote " +
                                    "que les environnements Docker.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    items(group.stacks, key = { it.key }) { stack ->
                        StackCard(
                            stack = stack,
                            busy = ui.busy,
                            expanded = expanded[stack.key] == true,
                            onToggle = { expanded[stack.key] = expanded[stack.key] != true },
                            onAction = { action -> viewModel.act(stack, action) },
                            onContainerAction = { container, action ->
                                viewModel.actOnContainer(stack, container, action)
                            },
                        )
                    }

                    if (group.dockerCapable && group.stacks.isEmpty()) {
                        item(key = "env-${group.envId}-empty") {
                            Text(
                                "Aucun conteneur sur cet environnement.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvHeader(group: EnvGroup) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            group.envName.ifBlank { "Environnement ${group.envId}" },
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.weight(1f))
        Text(
            group.kindLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StackCard(
    stack: StackView,
    busy: Set<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAction: (StackAction) -> Unit,
    onContainerAction: (ContainerView, StackAction) -> Unit,
) {
    val working = stack.key in busy
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stack.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StateChip(stack.runState, stack.runningCount, stack.containers.size)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OriginChip(stack.origin)
                if (stack.containers.isNotEmpty()) {
                    Text(
                        "${stack.containers.size} conteneur" +
                            if (stack.containers.size > 1) "s" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onToggle() },
                    )
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Replier" else "Déplier",
                        modifier = Modifier.size(18.dp).clickable { onToggle() },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.weight(1f))

                if (working) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                } else if (stack.actionable) {
                    if (stack.runState != RunState.RUNNING) {
                        IconButton(onClick = { onAction(StackAction.START) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Démarrer")
                        }
                    }
                    if (stack.runState != RunState.STOPPED) {
                        IconButton(onClick = { onAction(StackAction.STOP) }) {
                            Icon(Icons.Default.Stop, contentDescription = "Arrêter")
                        }
                    }

                    // Deux facons de relancer, dont une qui peut changer ce qui
                    // tourne : elles ne peuvent pas partager un meme bouton.
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Autres actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Relancer") },
                                onClick = {
                                    menuOpen = false
                                    onAction(StackAction.RESTART)
                                },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Relancer avec images à jour") },
                                enabled = stack.managedId != null,
                                onClick = {
                                    menuOpen = false
                                    onAction(StackAction.REDEPLOY)
                                },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                            )
                            if (stack.managedId == null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Stack déduit : Portainer ne peut pas le redéployer.",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                    enabled = false,
                                    onClick = {},
                                )
                            }
                        }
                    }
                }
            }

            if (stack.origin == StackOrigin.MANAGED && stack.containers.isEmpty()) {
                Text(
                    "Stack arrêté : ses conteneurs ne sont plus listés.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                stack.containers.forEach { container ->
                    ContainerRow(
                        container = container,
                        busy = container.id in busy,
                        onAction = { action -> onContainerAction(container, action) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContainerRow(
    container: ContainerView,
    busy: Boolean,
    onAction: (StackAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                container.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                container.statusText.ifBlank { container.state },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (busy) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        } else if (container.running) {
            IconButton(onClick = { onAction(StackAction.STOP) }) {
                Icon(Icons.Default.Stop, contentDescription = "Arrêter ${container.name}")
            }
            IconButton(onClick = { onAction(StackAction.RESTART) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Relancer ${container.name}")
            }
        } else {
            IconButton(onClick = { onAction(StackAction.START) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Démarrer ${container.name}")
            }
        }
    }
}
