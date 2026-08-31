package dev.mkdev.portainerremote.ui.updates

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    viewModel: UpdatesViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // L'autorisation d'installer se donne dans les reglages Android, hors de
    // l'application : au retour, l'ecran doit refleter le nouveau reglage.
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshInstallPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mises à jour") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InstalledCard(version = ui.installedVersion, repo = ui.repo)

                when (ui.stage) {
                    UpdateStage.CHECKING -> StatusRow("Vérification…", busy = true)

                    UpdateStage.UP_TO_DATE -> Text(
                        "Aucune mise à jour : cette version est la plus récente publiée.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    UpdateStage.IDLE -> Text(
                        "Vérification impossible pour l'instant.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    UpdateStage.AVAILABLE, UpdateStage.DOWNLOADING, UpdateStage.READY -> Unit
                }

                val release = ui.release
                if (release != null && ui.stage != UpdateStage.UP_TO_DATE) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(release.version, style = MaterialTheme.typography.titleLarge)
                            Text(release.title, style = MaterialTheme.typography.titleSmall)

                            if (release.notes.isNotBlank()) {
                                HorizontalDivider()
                                Text(release.notes, style = MaterialTheme.typography.bodySmall)
                            }

                            HorizontalDivider()

                            when (ui.stage) {
                                UpdateStage.DOWNLOADING -> {
                                    val fraction = ui.progress
                                    if (fraction == null) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                        Text(
                                            "Téléchargement…",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            progress = { fraction },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            "Téléchargement… ${(fraction * 100).toInt()} %",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }

                                UpdateStage.READY -> {
                                    if (!ui.canInstall) {
                                        Text(
                                            "Android demande d'abord l'autorisation d'installer " +
                                                "des applications depuis cette source.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Button(
                                            onClick = { viewModel.openInstallPermissionSettings() },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("Ouvrir le réglage Android") }
                                    } else {
                                        Text(
                                            "Téléchargé. Android affichera son propre écran de " +
                                                "confirmation.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Button(
                                            onClick = { viewModel.install() },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("Installer ${release.version}") }
                                    }
                                }

                                else -> Button(
                                    onClick = { viewModel.download() },
                                    enabled = release.hasApk,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                    Text(
                                        if (release.hasApk) {
                                            "  Télécharger et installer"
                                        } else {
                                            "  Aucun APK dans cette release"
                                        },
                                    )
                                }
                            }

                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, release.downloadUrl.toUri()),
                                    )
                                },
                            ) {
                                Icon(
                                    Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text("  Ouvrir dans le navigateur")
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.check() },
                    enabled = ui.stage != UpdateStage.CHECKING &&
                        ui.stage != UpdateStage.DOWNLOADING,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Vérifier maintenant") }

                Text(
                    "L'application ne s'installe pas toute seule : elle télécharge l'APK et " +
                        "ouvre l'installateur d'Android, qui refuse tout fichier signé par une " +
                        "autre clé que celle de la version déjà installée.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InstalledCard(version: String, repo: String) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Version installée", style = MaterialTheme.typography.labelMedium)
            Text(version, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Dépôt suivi : $repo",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = if (busy) 10.dp else 0.dp),
        )
    }
}
