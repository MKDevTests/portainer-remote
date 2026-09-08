package dev.mkdev.portainerremote.ui.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mkdev.portainerremote.domain.HostApp
import dev.mkdev.portainerremote.domain.HostAppAction
import dev.mkdev.portainerremote.domain.HostPower
import dev.mkdev.portainerremote.domain.HostUsage
import dev.mkdev.portainerremote.domain.ScheduledOff

/**
 * L'ecran de l'hote : ce qui se trouve sous Portainer.
 *
 * Il ne double pas Portainer. Il n'expose que ce que Portainer ne peut pas
 * faire depuis l'interieur d'un conteneur : relancer Portainer lui-meme, voir
 * la charge de la machine, et l'eteindre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(viewModel: HostViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<HostPower?>(null) }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    confirm?.let { action ->
        PowerDialog(
            action = action,
            hostName = ui.server?.label.orEmpty(),
            onDismiss = { confirm = null },
            onConfirm = {
                viewModel.power(action)
                confirm = null
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hôte") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (ui.configured) {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rafraîchir")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Oublier cet hôte et son mot de passe") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.forget()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!ui.configured) {
                        item {
                            SetupCard(
                                suggestedUrl = ui.suggestedUrl,
                                testing = ui.testing,
                                onConnect = viewModel::connect,
                            )
                        }
                        return@LazyColumn
                    }

                    item { UsageCard(ui.usage) }

                    item {
                        PortainerCard(
                            app = ui.portainerApp,
                            chosen = ui.config.portainerAppId.isNotBlank(),
                            busy = ui.busyApp,
                            onAction = viewModel::appAction,
                        )
                    }

                    ui.scheduledOff?.let { item { ScheduleCard(it) } }

                    item {
                        SectionTitle(
                            "Applications de l'hôte",
                            "Celles que ZimaOS gère lui-même. Coche celle qui héberge Portainer.",
                        )
                    }

                    items(ui.apps, key = { it.id }) { app ->
                        AppRow(
                            app = app,
                            isPortainer = app.id == ui.config.portainerAppId,
                            busy = ui.busyApp == app.id,
                            onChoose = { viewModel.choosePortainerApp(app.id) },
                            onAction = { action -> viewModel.appAction(app, action) },
                        )
                    }

                    if (ui.apps.isEmpty() && !ui.loading) {
                        item {
                            Text(
                                "Aucune application déclarée à l'hôte.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item { PowerCard(onAction = { confirm = it }) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * La configuration initiale.
 *
 * L'adresse est pre-remplie a partir de celle de Portainer, parce que dans le
 * cas courant c'est la meme machine. Le mot de passe est saisi ici et scelle
 * dans le Keystore : il ne transite par aucun autre chemin.
 */
@Composable
private fun SetupCard(
    suggestedUrl: String,
    testing: Boolean,
    onConnect: (String, String, String) -> Unit,
) {
    var url by remember(suggestedUrl) { mutableStateOf(suggestedUrl) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // Le mot de passe d'un NAS vaut plus que le jeton d'un Portainer : il ouvre
    // la machine entiere. En http il part en clair dans le corps de la requete,
    // et l'utilisateur doit le savoir au moment ou il le tape, pas apres.
    val trimmed = url.trim()
    val cleartext = trimmed.startsWith("http://") &&
        !trimmed.startsWith("http://localhost") &&
        !trimmed.startsWith("http://127.0.0.1") &&
        !trimmed.startsWith("http://10.0.2.2")

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Connecter l'hôte", style = MaterialTheme.typography.titleMedium)
            Text(
                "Si ce Portainer tourne sur un NAS ZimaOS ou CasaOS, l'application peut " +
                    "aussi relancer Portainer lui-même et éteindre la machine. " +
                    "Sans cette étape, rien ne change.\n\n" +
                    "Le mot de passe est scellé par le Keystore Android, comme le jeton " +
                    "Portainer. Il n'est pas inclus dans les sauvegardes exportées.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Adresse de l'interface ZimaOS") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = cleartext,
                supportingText = if (!cleartext) null else {
                    {
                        Text(
                            "En http, ton mot de passe part en clair sur le réseau. " +
                                "Acceptable sur ton LAN ou via un VPN, à éviter ailleurs.",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Utilisateur") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mot de passe") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { onConnect(url, user, password) },
                enabled = !testing && url.isNotBlank() && user.isNotBlank() &&
                    password.isNotEmpty(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (testing) "Connexion…" else "Tester et enregistrer")
            }
        }
    }
}

/** La charge de la machine, telle que l'hote la mesure lui-meme. */
@Composable
private fun UsageCard(usage: HostUsage) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Machine", style = MaterialTheme.typography.titleMedium)
            if (!usage.known) {
                Text(
                    "Charge non communiquée par l'hôte.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Gauge("Processeur", usage.cpuPercent)
            Gauge("Mémoire", usage.memoryPercent)
            Gauge("Disque système", usage.diskPercent)
        }
    }
}

@Composable
private fun Gauge(label: String, percent: Int) {
    if (percent < 0) return
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("$percent %", style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

/**
 * Le cas qui justifie tout l'ecran : Portainer arrete.
 *
 * Quand il l'est, le reste de l'application ne repond plus - c'est donc le seul
 * endroit d'ou on peut le relancer.
 */
@Composable
private fun PortainerCard(
    app: HostApp?,
    chosen: Boolean,
    busy: String?,
    onAction: (HostApp, HostAppAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Portainer", style = MaterialTheme.typography.titleMedium)
            when {
                !chosen -> Text(
                    "Aucune application choisie. Coche ci-dessous celle qui héberge Portainer : " +
                        "elle pourra alors être relancée depuis ici, même quand Portainer ne " +
                        "répond plus.",
                    style = MaterialTheme.typography.bodySmall,
                )

                app == null -> Text(
                    "L'application choisie n'est plus déclarée à l'hôte.",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    Text(
                        if (app.running) "${app.name} · en marche" else "${app.name} · arrêté",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onAction(app, HostAppAction.START) },
                            enabled = busy != app.id,
                        ) { Text("Démarrer") }
                        OutlinedButton(
                            onClick = { onAction(app, HostAppAction.RESTART) },
                            enabled = busy != app.id,
                        ) { Text("Relancer") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: HostApp,
    isPortainer: Boolean,
    busy: Boolean,
    onChoose: () -> Unit,
    onAction: (HostAppAction) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isPortainer, onClick = onChoose)
            Column(Modifier.weight(1f)) {
                Text(
                    app.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (app.running) "en marche" else "arrêté",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (app.running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (app.running) {
                IconButton(onClick = { onAction(HostAppAction.STOP) }, enabled = !busy) {
                    Icon(Icons.Default.Stop, contentDescription = "Arrêter ${app.name}")
                }
            } else {
                IconButton(onClick = { onAction(HostAppAction.START) }, enabled = !busy) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Démarrer ${app.name}")
                }
            }
        }
    }
}

/**
 * L'extinction programmee est affichee, pas modifiee.
 *
 * Son ecriture demande un corps de requete dont la forme n'a pas ete verifiee :
 * envoyer une supposition a une route qui eteint une machine serait une mauvaise
 * facon de la decouvrir. Le reglage se fait donc dans ZimaOS, et se lit ici.
 */
@Composable
private fun ScheduleCard(schedule: ScheduledOff) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Extinction programmée", style = MaterialTheme.typography.titleMedium)
            Text(
                if (!schedule.active) {
                    "Désactivée sur l'hôte."
                } else {
                    "%02d:%02d · %s".format(
                        schedule.hour,
                        schedule.minute,
                        schedule.weekdays.joinToString(", "),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PowerCard(onAction: (HostPower) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Alimentation", style = MaterialTheme.typography.titleMedium)
            Text(
                "Une machine éteinte ne se rallume pas depuis cette application.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAction(HostPower.RESTART) }) { Text("Redémarrer") }
                OutlinedButton(onClick = { onAction(HostPower.OFF) }) { Text("Éteindre") }
            }
        }
    }
}

@Composable
private fun PowerDialog(
    action: HostPower,
    hostName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.label) },
        text = {
            Text(
                when (action) {
                    HostPower.OFF ->
                        "Éteindre $hostName ? Tous les conteneurs s'arrêtent, et " +
                            "l'application ne pourra pas le rallumer."

                    HostPower.RESTART ->
                        "Redémarrer $hostName ? Les conteneurs s'arrêtent puis " +
                            "repartent selon leur politique de redémarrage."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action.label) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
