package dev.mkdev.portainerremote.ui.images

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mkdev.portainerremote.domain.ImageGroup
import dev.mkdev.portainerremote.domain.ImageView
import dev.mkdev.portainerremote.ui.components.UsageChip

/** Tailles en unites decimales, comme Docker et Portainer les affichent. */
private fun formatSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes o"
    val units = listOf("ko", "Mo", "Go", "To")
    var value = bytes.toDouble() / 1000
    var index = 0
    while (value >= 1000 && index < units.lastIndex) {
        value /= 1000
        index++
    }
    return if (value >= 100) "${value.toInt()} ${units[index]}"
    else String.format("%.1f %s", value, units[index])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesScreen(
    viewModel: ImagesViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    ui.pendingDelete?.let { (_, image) ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text("Supprimer cette image ?") },
            text = {
                Text(
                    "${image.displayName}\n${formatSize(image.sizeBytes)} seront libérés. " +
                        "L'image sera retéléchargée au prochain démarrage d'un conteneur qui l'utilise.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) { Text("Annuler") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Images") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rafraîchir")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            val error = ui.error
            if (error != null && ui.groups.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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
                modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ui.groups.forEach { group ->
                    item(key = "hdr-${group.envId}") { GroupHeader(group) }

                    items(group.images, key = { it.id }) { image ->
                        ImageCard(
                            image = image,
                            busy = image.id in ui.busy,
                            onDelete = { viewModel.askDelete(group.envId, image) },
                        )
                    }

                    if (group.images.isEmpty()) {
                        item(key = "empty-${group.envId}") {
                            Text(
                                "Aucune image sur cet environnement.",
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
private fun GroupHeader(group: ImageGroup) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            group.envName.ifBlank { "Environnement ${group.envId}" },
            style = MaterialTheme.typography.titleSmall,
        )
        // Le chiffre qui motive le menage, annonce avant la liste.
        Text(
            if (group.unusedCount > 0) {
                "${group.unusedCount} inutilisée${if (group.unusedCount > 1) "s" else ""} · " +
                    "${formatSize(group.reclaimableBytes)} récupérables"
            } else {
                "Toutes les images sont utilisées."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImageCard(
    image: ImageView,
    busy: Boolean,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    image.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    UsageChip(inUse = image.inUse, dangling = image.dangling)
                    Text(
                        formatSize(image.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (image.tags.size > 1) {
                        Text(
                            "+${image.tags.size - 1} étiquette${if (image.tags.size > 2) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.size(4.dp))

            if (busy) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(onClick = onDelete, enabled = !image.inUse) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Supprimer ${image.displayName}",
                        tint = if (image.inUse) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
    }
}
