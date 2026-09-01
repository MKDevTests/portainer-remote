package dev.mkdev.portainerremote.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    viewModel: LogsViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var searchOpen by remember { mutableStateOf(false) }

    LaunchedEffect(ui.error) {
        ui.error?.let { snackbar.showSnackbar(it) }
    }

    // Les logs se lisent par la fin. Le defilement automatique ne s'impose que
    // pendant le suivi : hors suivi, il arracherait la vue a qui remonte le fil.
    LaunchedEffect(ui.visibleLines.size, ui.following) {
        if (ui.following && ui.visibleLines.isNotEmpty()) {
            listState.animateScrollToItem(ui.visibleLines.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.containerName.ifBlank { "Logs" }) },
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
                    IconButton(onClick = { viewModel.toggleFollow() }) {
                        Icon(
                            if (ui.following) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (ui.following) {
                                "Arrêter le suivi"
                            } else {
                                "Suivre en direct"
                            },
                        )
                    }
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rafraîchir")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (searchOpen) {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = viewModel::setQuery,
                    singleLine = true,
                    label = { Text("Filtrer les lignes") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogTail.entries.forEach { entry ->
                    FilterChip(
                        selected = ui.tail == entry,
                        onClick = { viewModel.setTail(entry) },
                        label = { Text(entry.label) },
                    )
                }
                FilterChip(
                    selected = ui.timestamps,
                    onClick = { viewModel.toggleTimestamps() },
                    label = { Text("Horodatage") },
                )
                FilterChip(
                    selected = false,
                    onClick = {
                        clipboard.setText(AnnotatedString(viewModel.copyText()))
                    },
                    label = { Text("Copier") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }

            val lines = ui.visibleLines

            if (!ui.loading && lines.isEmpty()) {
                Text(
                    when {
                        ui.filtering -> "Aucune ligne ne correspond."
                        ui.empty -> "Ce conteneur n'a rien écrit."
                        else -> "Aucune ligne à afficher."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                return@Column
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    Box(
                        // Une ligne de log ne se replie pas : elle defile.
                        // Retour a la ligne force, on ne saurait plus ou commence
                        // l'entree suivante.
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            softWrap = false,
                            color = if (index % 2 == 0) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
