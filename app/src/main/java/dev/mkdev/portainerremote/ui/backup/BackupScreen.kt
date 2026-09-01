package dev.mkdev.portainerremote.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.mkdev.portainerremote.data.backup.BackupCrypto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    viewModel: BackupViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // Le Storage Access Framework : l'utilisateur choisit l'emplacement, et
    // l'application n'a besoin d'aucune permission de stockage.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::export) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::import) }

    LaunchedEffect(ui.message, ui.error) {
        (ui.error ?: ui.message)?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sauvegarde") },
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
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Le fichier contient tes jetons",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Il est chiffré en AES-256-GCM par une clé dérivée de cette phrase " +
                                "de passe. Sans elle, le fichier est inutilisable — y compris " +
                                "par toi. Rien ne permet de la retrouver.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (ui.lastExportAt == 0L) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (ui.lastExportAt == 0L) {
                                "Aucune sauvegarde depuis cette installation"
                            } else {
                                "Dernière sauvegarde : " + SimpleDateFormat(
                                    "d MMMM yyyy 'à' HH:mm",
                                    Locale.FRANCE,
                                ).format(Date(ui.lastExportAt))
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (ui.lastExportAt == 0L) {
                            Text(
                                "Une mise à jour ne perd rien. Seule une désinstallation efface " +
                                    "la configuration, et c'est là que ce fichier sert.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = ui.passphrase,
                    onValueChange = viewModel::setPassphrase,
                    label = { Text("Phrase de passe") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        Text("${BackupCrypto.MIN_PASSPHRASE} caractères au minimum.")
                    },
                    isError = ui.passphrase.isNotEmpty() && !ui.longEnough,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = ui.confirmation,
                    onValueChange = viewModel::setConfirmation,
                    label = { Text("Confirmation (export seulement)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = ui.confirmation.isNotEmpty() && ui.confirmation != ui.passphrase,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (ui.busy) {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }

                Button(
                    onClick = { exportLauncher.launch(viewModel.suggestedFileName()) },
                    enabled = ui.canExport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Text("  Exporter la configuration")
                }

                OutlinedButton(
                    onClick = {
                        // Certains gestionnaires de fichiers etiquettent un .json
                        // en octet-stream : filtrer strictement le rendrait
                        // invisible dans le selecteur.
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    enabled = ui.canImport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null)
                    Text("  Importer une sauvegarde")
                }

                Text(
                    "L'import restaure par-dessus la configuration en place. Les identifiants " +
                        "sont conservés : réimporter deux fois la même sauvegarde ne crée pas " +
                        "de doublons.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    "Une désinstallation efface la clé du Keystore qui scelle les jetons sur " +
                        "l'appareil. C'est pour cela que la sauvegarde a sa propre phrase de " +
                        "passe : chiffrée par le Keystore, elle serait illisible après " +
                        "réinstallation.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
