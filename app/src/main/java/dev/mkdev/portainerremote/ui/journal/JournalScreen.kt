package dev.mkdev.portainerremote.ui.journal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mkdev.portainerremote.domain.LogEntry
import dev.mkdev.portainerremote.domain.LogLevel
import dev.mkdev.portainerremote.domain.LogSession

/**
 * Le journal de l'hote.
 *
 * Deux choses s'y lisent : qui est connecte en ce moment, et ce que la machine
 * a consigne. Rien n'y est reformule - l'horodatage, la categorie et le texte
 * sont ceux de l'hote, parce qu'un journal reecrit ne prouve plus rien.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(viewModel: JournalViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var searchOpen by remember { mutableStateOf(false) }

    LaunchedEffect(ui.error) {
        ui.error?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Journal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) viewModel.setQuery("")
                        },
                    ) {
                        Icon(
                            if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchOpen) {
                                "Fermer la recherche"
                            } else {
                                "Rechercher"
                            },
                        )
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

            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (ui.unsupported) {
                Text(
                    "Cet hôte ne publie pas de journal système.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }

            if (searchOpen) {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = viewModel::setQuery,
                    singleLine = true,
                    label = { Text("Chercher dans le texte, l'utilisateur, la catégorie") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // Le niveau part vers l'hote, la recherche reste ici : demander les
            // erreurs a la machine, c'est en avoir mille ; les filtrer apres
            // coup, c'est n'avoir que celles des cent dernieres lignes.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = ui.level == null,
                    onClick = { viewModel.setLevel(null) },
                    label = { Text("Tout") },
                )
                LogLevel.entries.forEach { niveau ->
                    FilterChip(
                        selected = ui.level == niveau,
                        onClick = { viewModel.setLevel(niveau) },
                        label = { Text(niveau.label) },
                    )
                }
                JournalDepth.entries.forEach { profondeur ->
                    FilterChip(
                        selected = ui.depth == profondeur,
                        onClick = { viewModel.setDepth(profondeur) },
                        label = { Text(profondeur.label) },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
            ) {
                if (ui.journal.sessions.isNotEmpty()) {
                    item {
                        SessionsCard(
                            sessions = ui.journal.sessions,
                            open = ui.sessionsOpen,
                            onToggle = viewModel::toggleSessions,
                        )
                    }
                }

                if (ui.visible.isEmpty() && !ui.loading) {
                    item {
                        Text(
                            if (ui.filtering) {
                                "Aucune entrée ne contient « ${ui.query} »."
                            } else {
                                "L'hôte n'a rien consigné pour ce niveau."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                items(ui.visible) { entry -> EntryRow(entry) }

                // Trois nombres qui ne disent pas la meme chose : ce que l'hote
                // possede, ce qu'on a charge, ce que la recherche laisse voir.
                // Ne montrer que le premier laisserait croire que le journal
                // s'arrete la ; ne montrer que le dernier ferait passer un
                // filtre pour un journal vide.
                if (ui.journal.entries.isNotEmpty()) {
                    item {
                        val chargees = ui.journal.entries.size
                        val mot = if (chargees > 1) "chargées" else "chargée"
                        Text(
                            buildString {
                                if (ui.filtering) {
                                    append("${ui.visible.size} sur $chargees $mot ")
                                    append("correspondent. ")
                                } else {
                                    append("$chargees $mot. ")
                                }
                                append("L'hôte en annonce ${ui.journal.total}.")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Qui est connecte maintenant. Un journal regarde le passe ; ceci regarde l'instant. */
@Composable
private fun SessionsCard(
    sessions: List<LogSession>,
    open: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Connexions en cours · ${sessions.size}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggle) { Text(if (open) "Replier" else "Voir") }
            }

            if (!open) return@Column

            sessions.forEach { session ->
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            session.who.ifBlank { "inconnu" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            listOfNotNull(
                                session.protocol.takeIf { it.isNotBlank() },
                                session.from.takeIf { it.isNotBlank() },
                                session.since.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (session.current) {
                        Text(
                            "cet appareil",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** Une entree : l'heure, la gravite, l'auteur, et le texte de l'hote. */
@Composable
private fun EntryRow(entry: LogEntry) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.category.isNotBlank()) {
                Text(
                    " · ${entry.category}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (entry.who.isNotBlank()) {
                Text(
                    " · ${entry.who}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = when (entry.level) {
                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
            },
        )

        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}
