package dev.mkdev.portainerremote.ui.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mkdev.portainerremote.data.net.ReleaseInfo
import dev.mkdev.portainerremote.domain.AuthMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    viewModel: ServersViewModel,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val servers by viewModel.servers.collectAsState()
    val update by viewModel.update.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Serveurs Portainer") },
                actions = {
                    IconButton(onClick = onOpenBackup) {
                        Icon(
                            Icons.Default.SettingsBackupRestore,
                            contentDescription = "Sauvegarde",
                        )
                    }
                    // Entree permanente : sans elle, on ne peut verifier une mise
                    // a jour que si l'application en a deja trouve une.
                    IconButton(onClick = onOpenUpdates) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = "Mises à jour")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Ajouter un serveur")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

        update?.let { release ->
            UpdateBanner(
                release = release,
                onOpen = onOpenUpdates,
                onDismiss = viewModel::dismissUpdate,
            )
        }

        if (servers.isEmpty()) {
            // Sur tablette, un texte laisse libre s'etale sur 1600 px et devient
            // illisible : on le centre et on borne sa largeur de ligne.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 420.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Aucun serveur configuré",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Ajoute l'adresse de ton Portainer et un jeton d'accès. " +
                            "L'application découvre seule les environnements et les stacks.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                // Une liste seulement bornee en largeur reste collee au bord
                // gauche sur tablette : c'est le parent qui doit la recentrer.
                contentAlignment = Alignment.TopCenter,
            ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    // Des cartes larges de 1600 px sur tablette obligent l'oeil a
                    // traverser l'ecran pour relier un nom a son bouton.
                    .widthIn(max = 720.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(servers, key = { it.id }) { server ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(server.id) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(server.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    server.baseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    when (server.authMode) {
                                        AuthMode.API_KEY -> "Jeton d'accès"
                                        AuthMode.PASSWORD -> "Mot de passe · ${server.username}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onEdit(server.id) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Modifier")
                            }
                        }
                    }
                }
            }
            }
        }
        }
    }
}

/**
 * Annonce une release GitHub plus recente que la version installee.
 *
 * La banniere ne fait qu'annoncer : le telechargement et l'installation vivent
 * dans l'ecran des mises a jour, ou l'utilisateur voit ce qu'il declenche.
 */
@Composable
private fun UpdateBanner(
    release: ReleaseInfo,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Mise à jour disponible : " + release.version,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        release.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Ignorer")
                }
            }

            if (release.notes.isNotBlank()) {
                Text(
                    release.notes,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End)) {
                Text("Voir la mise à jour")
            }
        }
    }
}
