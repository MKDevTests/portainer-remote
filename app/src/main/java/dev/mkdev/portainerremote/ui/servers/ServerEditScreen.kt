package dev.mkdev.portainerremote.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.mkdev.portainerremote.core.ServerUrl
import dev.mkdev.portainerremote.domain.AuthMode
import dev.mkdev.portainerremote.domain.Server

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    viewModel: ServersViewModel,
    serverId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf(AuthMode.API_KEY) }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(serverId == null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(serverId) {
        if (serverId != null) {
            viewModel.load(serverId) { existing ->
                if (existing != null) {
                    label = existing.label
                    baseUrl = existing.baseUrl
                    authMode = existing.authMode
                    username = existing.username
                }
                loaded = true
            }
        }
    }

    fun current() = Server(
        id = serverId.orEmpty(),
        label = label.trim(),
        baseUrl = ServerUrl.normalize(baseUrl),
        authMode = authMode,
        username = username.trim(),
    )

    val trimmedUrl = baseUrl.trim()
    val urlLooksValid = trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")

    // Le clair est autorise par la configuration reseau, mais il ne doit jamais
    // passer inapercu : hors boucle locale, le jeton circule en clair sur le reseau.
    val cleartextWarning = trimmedUrl.startsWith("http://") &&
        !trimmedUrl.startsWith("http://localhost") &&
        !trimmedUrl.startsWith("http://127.0.0.1") &&
        !trimmedUrl.startsWith("http://10.0.2.2")
    val canSave = loaded && urlLooksValid &&
        (serverId != null || secret.isNotBlank() || authMode == AuthMode.PASSWORD && secret.isNotBlank())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (serverId == null) "Nouveau serveur" else "Modifier le serveur") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (serverId != null) {
                        IconButton(onClick = { viewModel.delete(serverId); onDone() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Nom") },
                placeholder = { Text("NAS maison") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Adresse") },
                placeholder = { Text("https://nas.local:9443") },
                supportingText = {
                    when {
                        baseUrl.isNotBlank() && !urlLooksValid ->
                            Text("L'adresse doit commencer par http:// ou https://")

                        cleartextWarning -> Text(
                            "En http, ton jeton circule en clair sur le réseau. " +
                                "Acceptable sur un LAN ou dans un VPN, à éviter ailleurs.",
                            color = MaterialTheme.colorScheme.error,
                        )

                        ServerUrl.willChange(baseUrl) ->
                            Text("Sera complété en ${ServerUrl.normalize(baseUrl)}")

                        else -> Text("Sans /api à la fin. Le port est ajouté si tu l'omets.")
                    }
                },
                isError = baseUrl.isNotBlank() && !urlLooksValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .fillMaxWidth()
                    // On complete en quittant le champ, pas a chaque frappe :
                    // sinon le port apparaitrait au milieu du nom d'hote en cours
                    // de saisie.
                    .onFocusChanged { state ->
                        if (!state.isFocused && baseUrl.isNotBlank()) {
                            baseUrl = ServerUrl.normalize(baseUrl)
                        }
                    },
            )

            Text("Authentification", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = authMode == AuthMode.API_KEY,
                    onClick = { authMode = AuthMode.API_KEY },
                    label = { Text("Jeton d'accès") },
                )
                FilterChip(
                    selected = authMode == AuthMode.PASSWORD,
                    onClick = { authMode = AuthMode.PASSWORD },
                    label = { Text("Mot de passe") },
                )
            }

            if (authMode == AuthMode.PASSWORD) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Identifiant") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = {
                    Text(if (authMode == AuthMode.API_KEY) "Jeton d'accès" else "Mot de passe")
                },
                placeholder = { Text(if (serverId == null) "" else "Inchangé si laissé vide") },
                supportingText = {
                    Text("Chiffré par le Keystore Android. Ne quitte jamais l'appareil.")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            feedback?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    enabled = urlLooksValid && secret.isNotBlank() && !testing,
                    onClick = {
                        testing = true
                        feedback = "Test en cours…"
                        viewModel.test(current(), secret) { _, message ->
                            feedback = message
                            testing = false
                        }
                    },
                ) { Text("Tester") }

                Button(
                    enabled = canSave,
                    onClick = {
                        viewModel.save(current(), secret.ifBlank { null }) { onDone() }
                    },
                ) { Text("Enregistrer") }
            }
        }
    }
}
