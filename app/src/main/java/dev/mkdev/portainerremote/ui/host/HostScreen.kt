package dev.mkdev.portainerremote.ui.host

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
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
import dev.mkdev.portainerremote.domain.DiskRole
import dev.mkdev.portainerremote.domain.DiskSleep
import dev.mkdev.portainerremote.domain.Host
import dev.mkdev.portainerremote.domain.HostApp
import dev.mkdev.portainerremote.domain.HostAppAction
import dev.mkdev.portainerremote.domain.HostDisk
import dev.mkdev.portainerremote.domain.HostKind
import dev.mkdev.portainerremote.domain.HostMachine
import dev.mkdev.portainerremote.domain.HostPower
import dev.mkdev.portainerremote.domain.HostUpdate
import dev.mkdev.portainerremote.domain.HostUsage
import dev.mkdev.portainerremote.domain.NetRate
import dev.mkdev.portainerremote.domain.ScheduledOff
import dev.mkdev.portainerremote.domain.Server

/**
 * L'ecran des NAS : ce qui se trouve sous Portainer.
 *
 * Il ne double pas Portainer. Il n'expose que ce que Portainer ne peut pas faire
 * depuis l'interieur d'un conteneur : relancer Portainer lui-meme, voir la
 * charge de la machine, et l'eteindre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    viewModel: HostViewModel,
    onBack: () -> Unit,
    onOpenJournal: (hostId: String, title: String) -> Unit,
) {
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
            hostName = ui.selected?.title.orEmpty(),
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
                title = { Text("NAS") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    // Le journal n'existe que la ou l'hote en publie un : chez
                    // Synology oui, chez ZimaOS non. Une icone qui ouvrirait un
                    // ecran vide serait une promesse non tenue.
                    ui.selected?.takeIf { !ui.setup && it.kind.hasJournal }?.let { host ->
                        IconButton(onClick = { onOpenJournal(host.id, host.title) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ListAlt,
                                contentDescription = "Journal de l'hôte",
                            )
                        }
                    }
                    if (!ui.setup) {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rafraîchir")
                        }
                    }
                    if (ui.hosts.isNotEmpty()) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Ajouter un NAS") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.startAdding()
                                },
                            )
                            if (ui.selected != null) {
                                // Une adresse change - un bail DHCP, une IP
                                // Tailscale. La modifier ne doit pas couter la
                                // machine : oublier puis re-ajouter perdrait le
                                // jeton d'appareil, donc un code a ressaisir.
                                DropdownMenuItem(
                                    text = { Text("Modifier ce NAS") },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.startEditing()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Oublier ce NAS et son mot de passe") },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.forget()
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            // Le selecteur n'apparait qu'a partir de deux machines : une seule
            // n'a pas besoin qu'on demande laquelle.
            if (ui.hosts.size > 1 && !ui.adding) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ui.hosts.forEach { host ->
                        FilterChip(
                            selected = host.id == ui.selectedId,
                            onClick = { viewModel.select(host.id) },
                            label = { Text(host.title) },
                        )
                    }
                }
            }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val host = ui.selected
                    if (ui.setup || host == null) {
                        item {
                            SetupCard(
                                servers = ui.servers,
                                testing = ui.testing,
                                canCancel = ui.hosts.isNotEmpty(),
                                otpNeeded = ui.otpNeeded,
                                existing = ui.edited,
                                suggestUrl = viewModel::suggestedUrl,
                                onCancel = viewModel::cancelAdding,
                                onConnect = viewModel::connect,
                            )
                        }
                        return@LazyColumn
                    }

                    if (ui.otpAsked) {
                        item {
                            OtpCard(
                                sending = ui.sendingOtp,
                                onSubmit = viewModel::submitOtp,
                            )
                        }
                    }

                    item { IdentityCard(host, ui.servers) }

                    if (ui.systemUpdate.available) {
                        item { SystemUpdateCard(ui.systemUpdate) }
                    }

                    item { UsageCard(ui.usage) }

                    if (ui.disks.isNotEmpty()) {
                        item { DisksCard(ui.disks) }
                    }

                    item {
                        HealthCard(
                            usage = ui.usage,
                            machine = ui.machine,
                            diskSleep = ui.diskSleep,
                            rates = ui.rates,
                            update = ui.systemUpdate,
                        )
                    }

                    if (host.kind.canApps) {
                        item {
                            PortainerCard(
                                app = ui.portainerApp,
                                chosen = host.portainerAppId.isNotBlank(),
                                busy = ui.busyApp,
                                onAction = viewModel::appAction,
                            )
                        }
                    }

                    ui.scheduledOff?.let { schedule ->
                        item {
                            ScheduleCard(
                                schedule = schedule,
                                saving = ui.savingSchedule,
                                editable = host.kind.canWriteSchedule,
                                onSave = viewModel::saveSchedule,
                            )
                        }
                    }

                    if (host.kind.canSeeApps) {
                        item {
                            if (host.kind.canApps) {
                                SectionTitle(
                                    "Applications de l'hôte",
                                    "Celles que le NAS gère lui-même. " +
                                        "Coche celle qui héberge Portainer.",
                                )
                            } else {
                                SectionTitle(
                                    "Conteneurs de l'hôte",
                                    "Ce que le NAS déclare. Lecture seule : " +
                                        "ses commandes n'ont pas été mesurées.",
                                )
                            }
                        }

                        items(ui.apps, key = { it.id }) { app ->
                            AppRow(
                                app = app,
                                manageable = host.kind.canApps,
                                isPortainer = host.isPortainerApp(app.id),
                                busy = ui.busyApp == app.id,
                                upgradable = app.id in ui.upgradable,
                                onChoose = { viewModel.choosePortainerApp(app.id) },
                                onAction = { action -> viewModel.appAction(app, action) },
                                onUpgrade = { viewModel.upgrade(app) },
                            )
                        }

                        if (ui.apps.isEmpty() && !ui.loading) {
                            item {
                                Text(
                                    if (host.kind.canApps) {
                                        "Aucune application déclarée à l'hôte."
                                    } else {
                                        "Aucun conteneur déclaré à l'hôte."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (host.kind.canPower) {
                        item { PowerCard(onAction = { confirm = it }) }
                    }
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

/** Qui est cette machine, et a quel Portainer elle est rattachee. */
@Composable
private fun IdentityCard(host: Host, servers: List<Server>) {
    val linked = servers.firstOrNull { it.id == host.serverId }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(host.title, style = MaterialTheme.typography.titleMedium)
            Text(
                host.kind.label + " · " + host.username,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                linked?.let { "Portainer associé : " + it.label } ?: "Aucun Portainer associé",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * La configuration d'un NAS.
 *
 * Le systeme est declare, pas devine. L'adresse est pre-remplie a partir de
 * celle du Portainer choisi, parce que dans le cas courant c'est la meme
 * machine. Le mot de passe est saisi ici et scelle dans le Keystore : il ne
 * transite par aucun autre chemin.
 */
@Composable
private fun SetupCard(
    servers: List<Server>,
    testing: Boolean,
    canCancel: Boolean,
    otpNeeded: Boolean,
    /**
     * Le NAS qu'on modifie, ou null pour en connecter un nouveau.
     *
     * Changer une adresse ne devrait pas obliger a oublier la machine : on y
     * perdrait le jeton d'appareil, donc un code de verification a ressaisir,
     * et le Portainer associe.
     */
    existing: Host?,
    suggestUrl: (String) -> String,
    onCancel: () -> Unit,
    onConnect: (HostKind, String, String, String, String, String, String) -> Unit,
) {
    var kind by remember(existing) { mutableStateOf(existing?.kind ?: HostKind.ZIMA) }
    var serverId by remember(existing) {
        mutableStateOf(existing?.serverId ?: servers.firstOrNull()?.id.orEmpty())
    }
    var label by remember(existing) { mutableStateOf(existing?.label.orEmpty()) }
    var url by remember(existing) { mutableStateOf(existing?.baseUrl ?: suggestUrl(serverId)) }
    var user by remember(existing) { mutableStateOf(existing?.username.orEmpty()) }
    var otp by remember(existing) { mutableStateOf("") }
    var password by remember(existing) { mutableStateOf("") }
    var urlTouched by remember(existing) { mutableStateOf(existing != null) }

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
            Text(
                if (existing != null) "Modifier ce NAS" else "Connecter un NAS",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Portainer ne peut pas se relancer lui-même : il tourne dans un conteneur. " +
                    "Si ton NAS tourne sous un système reconnu, l'application peut le relancer " +
                    "depuis dessous, et éteindre la machine. Sans cette étape, rien ne change." +
                    "\n\nLe mot de passe est scellé par le Keystore Android, comme le jeton " +
                    "Portainer. Il n'est pas inclus dans les sauvegardes exportées.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Système du NAS", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HostKind.entries.forEach { candidate ->
                    FilterChip(
                        selected = kind == candidate,
                        onClick = { kind = candidate },
                        label = { Text(candidate.label) },
                    )
                }
            }
            // Ce que chaque systeme attend, dit avant la saisie : un port par
            // defaut faux fait echouer une connexion qui aurait marche.
            if (kind.supported) {
                Text(
                    when (kind) {
                        HostKind.ZIMA ->
                            "Adresse de l'interface ZimaOS, port 80 ou 85 selon l'installation."

                        HostKind.SYNOLOGY ->
                            "Adresse de DSM, port 5000 en clair ou 5001 en TLS. " +
                                "Charge, disques, conteneurs, journal, plus l'extinction et " +
                                "le redémarrage. Les conteneurs sont en lecture seule, et " +
                                "aucune extinction ne se programme depuis ici."

                        HostKind.QNAP -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!kind.supported) {
                // Refuser en silence laisse chercher l'erreur ailleurs : on dit
                // ce qui manque, et on empeche la tentative plutot que de la
                // laisser echouer sans raison visible.
                Text(
                    "${kind.label} n'est pas encore géré. L'entrée existe pour que " +
                        "l'absence se voie, pas pour faire attendre : rien ne sera " +
                        "envoyé à cette adresse.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (servers.isNotEmpty()) {
                Text("Portainer associé (facultatif)", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = serverId.isBlank(),
                        onClick = { serverId = "" },
                        label = { Text("Aucun") },
                    )
                    servers.forEach { server ->
                        FilterChip(
                            selected = serverId == server.id,
                            onClick = {
                                serverId = server.id
                                // Tant que l'adresse n'a pas ete touchee, elle
                                // suit le serveur choisi : c'est la proposition
                                // qui s'ajuste, jamais la saisie qu'on ecrase.
                                if (!urlTouched) url = suggestUrl(server.id)
                            },
                            label = { Text(server.label) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Nom (facultatif)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    urlTouched = true
                },
                label = { Text("Adresse de l'interface du NAS") },
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
                label = {
                    Text(
                        if (existing != null) {
                            "Mot de passe (vide = inchangé)"
                        } else {
                            "Mot de passe"
                        },
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = if (existing == null) null else {
                    {
                        Text(
                            "Laisse ce champ vide pour changer seulement l'adresse : " +
                                "le mot de passe scellé et le jeton d'appareil sont conservés.",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (otpNeeded) {
                OutlinedTextField(
                    value = otp,
                    onValueChange = { otp = it.filter(Char::isDigit).take(8) },
                    label = { Text("Code de vérification") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Ce NAS utilise la double authentification. Le code n'est demandé " +
                        "qu'une fois : il sert à obtenir un jeton d'appareil, scellé par le " +
                        "Keystore comme le mot de passe, qui le remplacera ensuite.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (canCancel) {
                    OutlinedButton(onClick = onCancel, enabled = !testing) { Text("Annuler") }
                }
                Button(
                    onClick = { onConnect(kind, label, url, user, password, serverId, otp) },
                    enabled = !testing && kind.supported && url.isNotBlank() &&
                        user.isNotBlank() &&
                        // Sur une modification, un mot de passe vide veut dire
                        // « garde celui que tu as » : l'exiger reviendrait a le
                        // faire ressaisir pour changer une adresse.
                        (password.isNotEmpty() || existing != null) &&
                        (!otpNeeded || otp.length >= 6),
                ) {
                    Text(if (testing) "Connexion…" else "Tester et enregistrer")
                }
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
            // Ce chiffre est la somme des disques de donnees, pas le disque du
            // systeme : le detail est juste en dessous, disque par disque.
            Gauge("Stockage", usage.diskPercent)
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
 * Le code de verification, demande la ou l'on est.
 *
 * Un NAS peut reclamer un code alors qu'il est deja enregistre : son jeton
 * d'appareil a ete revoque, ou il n'en delivre pas. Faire oublier le NAS pour
 * repondre reviendrait a ressaisir une adresse et un mot de passe qui n'ont
 * jamais change.
 */
@Composable
private fun OtpCard(sending: Boolean, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }

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
            Text("Code de vérification", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ce NAS demande un code pour ouvrir la session. Rien d'autre n'est à " +
                    "ressaisir : ni l'adresse, ni le mot de passe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(8) },
                    label = { Text("Code à 6 chiffres") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onSubmit(code) },
                    enabled = !sending && code.length >= 6,
                ) {
                    Text(if (sending) "Envoi…" else "Valider")
                }
            }
        }
    }
}

/**
 * Une version du systeme est disponible.
 *
 * La carte n'apparait que dans ce cas : quand tout est a jour, la ligne
 * « Systeme » de la carte Sante le dit deja, et une carte de plus ne
 * dirait rien.
 *
 * Aucun bouton n'installe. Une mise a jour de systeme redemarre le NAS et
 * coupe tous les conteneurs : cela se decide devant sa propre interface, pas
 * au bout d'un doigt sur un telephone qui ne verra pas si ca se passe mal.
 */
@Composable
private fun SystemUpdateCard(update: HostUpdate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (update.important) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (update.important) {
                    "Mise à jour de sécurité disponible"
                } else {
                    "Mise à jour du système disponible"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                buildString {
                    if (update.currentVersion.isNotBlank()) {
                        append(update.currentVersion)
                        append(" → ")
                    }
                    append(update.latestVersion.ifBlank { "version inconnue" })
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Elle s'installe depuis l'interface du NAS : elle le redémarre et " +
                    "arrête tous les conteneurs.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Les disques, un par un.
 *
 * Un total ne dit pas lequel se remplit : c'est pourtant la seule question qui
 * se pose devant un NAS. Chaque disque porte donc sa propre jauge, et ce qui
 * manque - une temperature, une sante - ne laisse pas de trou.
 */
@Composable
private fun DisksCard(disks: List<HostDisk>) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Disques", style = MaterialTheme.typography.titleMedium)

            // Quand l'hote publie des volumes, ce sont eux qui portent la place
            // occupee : les disques dessous n'en ont pas a eux seuls, et le
            // signaler ligne par ligne ferait croire a une panne.
            val volumes = disks.count { it.role == DiskRole.VOLUME }
            var role: DiskRole? = null

            disks.forEach { disk ->
                if (volumes > 0 && disk.role != role) {
                    role = disk.role
                    Text(
                        if (disk.role == DiskRole.VOLUME) "Volumes" else "Disques physiques",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            disk.title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (disk.percent >= 0) {
                            Text(
                                "${disk.percent} %",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }

                    if (disk.percent >= 0) {
                        LinearProgressIndicator(
                            progress = { disk.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    val details = buildList {
                        if (disk.sizeBytes > 0) {
                            add(
                                if (disk.usedBytes >= 0) {
                                    "${humanBytes(disk.usedBytes)} / ${humanBytes(disk.sizeBytes)}"
                                } else {
                                    humanBytes(disk.sizeBytes)
                                },
                            )
                        }
                        if (disk.kind.isNotBlank()) add(disk.kind)
                        if (disk.temperature >= 0) add("${disk.temperature} °C")
                        disk.healthy?.let { add(if (it) "santé bonne" else "santé dégradée") }
                    }

                    if (details.isNotEmpty()) {
                        Text(
                            details.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (disk.healthy == false) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }

                    // Une partition non montee ne se compte pas : le dire evite
                    // de faire passer un disque plein pour un disque vide. Mais
                    // la ou des volumes existent, la place est comptee ailleurs.
                    if (disk.usedBytes < 0 && disk.sizeBytes > 0 && volumes == 0) {
                        Text(
                            "Aucune partition montée : l'occupation reste inconnue.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * L'etat de sante de la machine.
 *
 * Elle ne montre que ce que l'hote a effectivement publie : chaque ligne
 * disparait quand son champ manque, plutot que d'afficher un tiret qui laisse
 * croire a une panne. Une carte vide n'apparait pas du tout.
 */
@Composable
private fun HealthCard(
    usage: HostUsage,
    machine: HostMachine,
    diskSleep: DiskSleep?,
    rates: List<NetRate>,
    update: HostUpdate,
) {
    val lines = buildList {
        if (machine.model.isNotBlank()) add("Modèle" to machine.model)
        if (machine.osVersion.isNotBlank()) {
            // « a jour » n'est ecrit que si l'hote s'est prononce. Sans reponse
            // de sa part, la ligne reste la version seule : affirmer qu'une
            // machine est a jour sans le savoir serait pire que se taire.
            add(
                "Système" to if (update.known && !update.available) {
                    "${machine.osVersion} · à jour"
                } else {
                    machine.osVersion
                },
            )
        }
        if (machine.cpuModel.isNotBlank()) {
            add(
                "Processeur" to if (machine.cpuCores > 0) {
                    "${machine.cpuModel} · ${machine.cpuCores} cœurs"
                } else {
                    machine.cpuModel
                },
            )
        }
        if (usage.cpuTemperature >= 0) add("Température" to "${usage.cpuTemperature} °C")
        if (usage.memoryTotalBytes > 0) {
            add(
                "Mémoire" to buildString {
                    append(humanBytes(usage.memoryUsedBytes))
                    append(" / ")
                    append(humanBytes(usage.memoryTotalBytes))
                    if (machine.memoryType.isNotBlank()) append(" · ${machine.memoryType}")
                },
            )
        }
        if (usage.diskTotalBytes > 0) {
            add(
                "Stockage total" to
                    "${humanBytes(usage.diskUsedBytes)} / ${humanBytes(usage.diskTotalBytes)}",
            )
        }
        diskSleep?.let { add("Veille des disques" to it.label) }
    }

    if (lines.isEmpty() && rates.isEmpty()) return

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Santé", style = MaterialTheme.typography.titleMedium)

            lines.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (rates.isNotEmpty()) {
                Text(
                    "Réseau",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                rates.forEach { rate ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            rate.name,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "↑ ${humanBytes(rate.sentPerSecond)}/s · " +
                                "↓ ${humanBytes(rate.receivedPerSecond)}/s",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            } else if (usage.network.isNotEmpty()) {
                Text(
                    "Débit réseau au prochain rafraîchissement : il se déduit de deux mesures.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Des octets lisibles. Base 1000, comme les fabricants et comme l'hote. */
private fun humanBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    val units = listOf("o", "ko", "Mo", "Go", "To")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return if (unit == 0 || value >= 100) {
        "%.0f %s".format(value, units[unit])
    } else {
        "%.1f %s".format(value, units[unit])
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
    manageable: Boolean,
    isPortainer: Boolean,
    busy: Boolean,
    upgradable: Boolean,
    onChoose: () -> Unit,
    onAction: (HostAppAction) -> Unit,
    onUpgrade: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.padding(
                    start = if (manageable) 4.dp else 16.dp,
                    end = 4.dp,
                    top = if (manageable) 4.dp else 10.dp,
                    bottom = if (manageable) 4.dp else 10.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Le bouton de choix designe l'application qui heberge
                // Portainer : il ne veut rien dire la ou rien ne se pilote.
                if (manageable) {
                    RadioButton(selected = isPortainer, onClick = onChoose)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        app.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // La phrase de l'hote quand il en donne une : elle
                        // porte la duree et la sante, que « en marche » perd.
                        app.detail.ifBlank { if (app.running) "en marche" else "arrêté" },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (app.running) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (manageable) {
                    // « Relancer » n'a de sens que sur une application en
                    // marche : sur une application arretee, c'est « Demarrer »
                    // qu'on veut.
                    if (app.running) {
                        IconButton(onClick = { onAction(HostAppAction.RESTART) }, enabled = !busy) {
                            Icon(
                                Icons.Default.RestartAlt,
                                contentDescription = "Relancer ${app.name}",
                            )
                        }
                        IconButton(onClick = { onAction(HostAppAction.STOP) }, enabled = !busy) {
                            Icon(Icons.Default.Stop, contentDescription = "Arrêter ${app.name}")
                        }
                    } else {
                        IconButton(onClick = { onAction(HostAppAction.START) }, enabled = !busy) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Démarrer ${app.name}",
                            )
                        }
                    }
                }
            }

            if (manageable && upgradable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Mise à jour disponible",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = onUpgrade, enabled = !busy) {
                        Text("Mettre à jour")
                    }
                }
            }
        }
    }
}

/**
 * L'extinction programmee, en lecture et en ecriture.
 *
 * Ce qui s'affiche est toujours ce que l'hote annonce, jamais ce qu'on lui a
 * demande : apres enregistrement, le reglage est relu. Le corps de la requete
 * est deduit de la forme de la lecture et non d'un contrat publie, donc la
 * machine reste seule juge de ce qu'elle a compris.
 */
@Composable
private fun ScheduleCard(
    schedule: ScheduledOff,
    saving: Boolean,
    editable: Boolean,
    onSave: (ScheduledOff) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    // La saisie repart de l'etat annonce a chaque fois qu'on ouvre l'edition,
    // et a chaque fois que l'hote annonce autre chose.
    var hour by remember(schedule, editing) { mutableStateOf(schedule.hour.toString()) }
    var minute by remember(schedule, editing) { mutableStateOf("%02d".format(schedule.minute)) }
    var days by remember(schedule, editing) { mutableStateOf(schedule.weekdays.toSet()) }

    val hourValue = hour.toIntOrNull()
    val minuteValue = minute.toIntOrNull()
    val valid = hourValue in 0..23 && minuteValue in 0..59

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Extinction programmée",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (editable) {
                    TextButton(onClick = { editing = !editing }) {
                        Text(if (editing) "Fermer" else "Modifier")
                    }
                }
            }

            Text(
                if (!schedule.active) {
                    "Désactivée sur l'hôte."
                } else {
                    "%02d:%02d · %s".format(
                        schedule.hour,
                        schedule.minute,
                        schedule.weekdays.joinToString(", ") { ScheduledOff.shortLabel(it) },
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!editing || !editable) return@Column

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = hour,
                    onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                    label = { Text("Heure") },
                    singleLine = true,
                    isError = hourValue !in 0..23,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = minute,
                    onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                    label = { Text("Minute") },
                    singleLine = true,
                    isError = minuteValue !in 0..59,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ScheduledOff.WEEK.forEach { code ->
                    FilterChip(
                        selected = code in days,
                        onClick = {
                            days = if (code in days) days - code else days + code
                        },
                        label = { Text(ScheduledOff.shortLabel(code)) },
                    )
                }
            }

            // Ce que la machine fera, en toutes lettres, avant d'appuyer.
            Text(
                if (days.isEmpty()) {
                    "Aucun jour choisi : enregistrer désactivera l'extinction programmée."
                } else {
                    "Le NAS s'éteindra à %s:%s, %s.".format(
                        hour.padStart(2, '0'),
                        minute.padStart(2, '0'),
                        ScheduledOff.WEEK.filter { it in days }
                            .joinToString(", ") { ScheduledOff.shortLabel(it) },
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    onSave(
                        ScheduledOff(
                            hour = hourValue ?: 0,
                            minute = minuteValue ?: 0,
                            weekdays = ScheduledOff.WEEK.filter { it in days },
                        ),
                    )
                    editing = false
                },
                enabled = valid && !saving,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (saving) "Enregistrement…" else "Enregistrer")
            }
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
