package dev.mkdev.portainerremote.ui.stacks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mkdev.portainerremote.data.store.CustomLabel
import dev.mkdev.portainerremote.data.store.LabelKind
import dev.mkdev.portainerremote.data.store.portPinKey
import dev.mkdev.portainerremote.domain.ContainerView
import dev.mkdev.portainerremote.domain.EnvGroup
import dev.mkdev.portainerremote.domain.RunState
import dev.mkdev.portainerremote.domain.StackAction
import dev.mkdev.portainerremote.domain.StackFilter
import dev.mkdev.portainerremote.domain.StackOrigin
import dev.mkdev.portainerremote.domain.StackSort
import dev.mkdev.portainerremote.domain.StackView
import dev.mkdev.portainerremote.ui.components.OriginChip
import dev.mkdev.portainerremote.ui.components.StateChip

/**
 * Seuil tablette. En dessous, une seule colonne : deux cartes de stack cote a
 * cote sur un telephone laisseraient le nom tronque des le premier mot.
 */
private val TwoColumnBreakpoint = 700.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StacksScreen(
    viewModel: StacksViewModel,
    onBack: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenLogs: (envId: Int, containerId: String, name: String) -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var searchOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    var pinTarget by remember { mutableStateOf<PinTarget?>(null) }
    var labelTarget by remember { mutableStateOf<LabelTarget?>(null) }

    val serverId = ui.server?.id.orEmpty()
    // Le reglage est garde sous le nom du conteneur, pas sous son identifiant :
    // un redeploiement change l'identifiant et garde le nom.
    val pinnedOf: (Int, String) -> Set<Int> = { envId, name ->
        ui.pinnedPorts[portPinKey(serverId, envId, name)]?.let { setOf(it) }.orEmpty()
    }
    val isFavorite: (Int, String) -> Boolean = { envId, name ->
        portPinKey(serverId, envId, name) in ui.favoriteContainers
    }
    val labelOf: (LabelKind, Int, String) -> CustomLabel? = { kind, envId, name ->
        ui.labelOf(kind, envId, name)
    }

    labelTarget?.let { target ->
        LabelDialog(
            officialName = target.name,
            kindLabel = if (target.kind == LabelKind.STACK) "Stack" else "Conteneur",
            current = ui.labelOf(target.kind, target.envId, target.name),
            onDismiss = { labelTarget = null },
            onSave = { label ->
                viewModel.setLabel(target.kind, target.envId, target.name, label)
                labelTarget = null
            },
        )
    }

    pinTarget?.let { target ->
        PortPinDialog(
            containerName = target.container.name,
            detected = target.container.ports,
            current = ui.pinnedPorts[portPinKey(serverId, target.envId, target.container.name)],
            onDismiss = { pinTarget = null },
            onSave = { port ->
                viewModel.setPinnedPort(target.envId, target.container.name, port)
                pinTarget = null
            },
        )
    }

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
                    IconButton(
                        onClick = {
                            searchOpen = !searchOpen
                            // Fermer la recherche doit aussi la vider : sinon on
                            // repart d'une liste filtree sans indice visible.
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
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
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

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= TwoColumnBreakpoint
                val columns = if (wide) 2 else 1
                // Une colonne unique reste bornee pour rester lisible ; deux
                // colonnes ont besoin du double, sinon les cartes se resserrent.
                val contentWidth = if (wide) 1100.dp else 720.dp

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = contentWidth)
                        // Sans cet alignement, la liste bornee reste collee au
                        // bord gauche sur tablette au lieu d'occuper le centre.
                        .align(Alignment.TopCenter),
                ) {
                    TabRow(selectedTabIndex = ui.tab.ordinal) {
                        StacksTab.entries.forEach { entry ->
                            Tab(
                                selected = ui.tab == entry,
                                onClick = { viewModel.setTab(entry) },
                                text = {
                                    val count = when (entry) {
                                        StacksTab.STACKS ->
                                            ui.visibleGroups.sumOf { it.stacks.size }
                                        StacksTab.CONTAINERS -> ui.visibleContainers.size
                                        StacksTab.FAVORITES -> ui.visibleFavorites.size
                                    }
                                    Text("${entry.label} · $count")
                                },
                            )
                        }
                    }

                    if (searchOpen) {
                        OutlinedTextField(
                            value = ui.query,
                            onValueChange = viewModel::setQuery,
                            singleLine = true,
                            label = { Text("Nom d'un stack ou d'un conteneur") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (ui.query.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setQuery("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "Effacer")
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .focusRequester(searchFocus),
                        )
                        LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    }

                    FilterBar(
                        filter = ui.filter,
                        sort = ui.sort,
                        sortOpen = sortOpen,
                        onFilter = viewModel::setFilter,
                        onSortOpen = { sortOpen = it },
                        onSort = viewModel::setSort,
                    )

                    if (ui.filtering && ui.visibleCount == 0) {
                        Text(
                            "Aucun stack ne correspond.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                        return@Column
                    }

                    if (ui.tab == StacksTab.FAVORITES) {
                        FavoritesBody(
                            favorites = ui.visibleFavorites,
                            view = ui.favoritesView,
                            columns = columns,
                            busy = ui.busy,
                            pinnedOf = pinnedOf,
                            isFavorite = isFavorite,
                            labelOf = labelOf,
                            onRename = { envId, name ->
                                labelTarget = LabelTarget(LabelKind.CONTAINER, envId, name)
                            },
                            onView = viewModel::setFavoritesView,
                            onRemove = viewModel::removeFavoriteContainer,
                            onPin = { envId, container -> pinTarget = PinTarget(envId, container) },
                            onToggleFavorite = { envId, name ->
                                viewModel.toggleFavoriteContainer(envId, name)
                            },
                            onAction = { entry, action ->
                                viewModel.actOnContainer(entry.stack, entry.container, action)
                            },
                            onOpenLogs = { entry ->
                                onOpenLogs(entry.envId, entry.container.id, entry.container.name)
                            },
                        )
                        return@Column
                    }

                    if (ui.tab == StacksTab.CONTAINERS) {
                        ContainersGrid(
                            entries = ui.visibleContainers,
                            columns = columns,
                            busy = ui.busy,
                            pinnedOf = pinnedOf,
                            isFavorite = isFavorite,
                            labelOf = labelOf,
                            onRename = { envId, name ->
                                labelTarget = LabelTarget(LabelKind.CONTAINER, envId, name)
                            },
                            onToggleFavorite = { envId, name ->
                                viewModel.toggleFavoriteContainer(envId, name)
                            },
                            onPin = { entry ->
                                pinTarget = PinTarget(entry.envId, entry.container)
                            },
                            onAction = { entry, action ->
                                viewModel.actOnContainer(entry.stack, entry.container, action)
                            },
                            onOpenLogs = { entry ->
                                onOpenLogs(entry.envId, entry.container.id, entry.container.name)
                            },
                        )
                        return@Column
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ui.visibleGroups.forEach { group ->
                            // Un filtre actif qui vide un environnement ne doit
                            // pas laisser son en-tete seul en haut de la liste.
                            if (ui.filtering && group.stacks.isEmpty()) return@forEach

                            item(
                                key = "env-${group.envId}",
                                span = { GridItemSpan(maxLineSpan) },
                            ) { EnvHeader(group) }

                            if (!group.dockerCapable) {
                                item(
                                    key = "env-${group.envId}-unsupported",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    Text(
                                        "Environnement ${group.kindLabel} : cette application ne " +
                                            "pilote que les environnements Docker.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            items(group.stacks, key = { it.key }) { stack ->
                                StackCard(
                                    stack = stack,
                                    linkHost = group.linkHost,
                                    busy = ui.busy,
                                    pinnedOf = pinnedOf,
                                    isFavorite = isFavorite,
                                    label = labelOf(LabelKind.STACK, stack.envId, stack.name),
                                    labelOfContainer = { name ->
                                        labelOf(LabelKind.CONTAINER, stack.envId, name)
                                    },
                                    onRename = {
                                        labelTarget =
                                            LabelTarget(LabelKind.STACK, stack.envId, stack.name)
                                    },
                                    onRenameContainer = { container ->
                                        labelTarget = LabelTarget(
                                            LabelKind.CONTAINER,
                                            stack.envId,
                                            container.name,
                                        )
                                    },
                                    onToggleContainerFavorite = { container ->
                                        viewModel.toggleFavoriteContainer(stack.envId, container.name)
                                    },
                                    onPin = { container ->
                                        pinTarget = PinTarget(stack.envId, container)
                                    },
                                    favorite = stack.key in ui.favorites,
                                    expanded = expanded[stack.key] == true,
                                    onToggle = {
                                        expanded[stack.key] = expanded[stack.key] != true
                                    },
                                    onToggleFavorite = { viewModel.toggleFavorite(stack) },
                                    onAction = { action -> viewModel.act(stack, action) },
                                    onContainerAction = { container, action ->
                                        viewModel.actOnContainer(stack, container, action)
                                    },
                                    onOpenLogs = { container ->
                                        onOpenLogs(stack.envId, container.id, container.name)
                                    },
                                )
                            }

                            if (group.dockerCapable && group.stacks.isEmpty()) {
                                item(
                                    key = "env-${group.envId}-empty",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
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
    }
}

/**
 * Tous les conteneurs, sortis de leurs stacks.
 *
 * L'onglet stacks repond a « dans quel etat est ce service » ; celui-ci repond
 * a « ou est passe ce conteneur ». D'ou l'image et le stack d'origine affiches
 * sur chaque carte : sans eux, deux conteneurs nommes `web` sont identiques.
 */
@Composable
private fun ContainersGrid(
    entries: List<ContainerEntry>,
    columns: Int,
    busy: Set<String>,
    pinnedOf: (Int, String) -> Set<Int>,
    isFavorite: (Int, String) -> Boolean,
    labelOf: (LabelKind, Int, String) -> CustomLabel?,
    onAction: (ContainerEntry, StackAction) -> Unit,
    onOpenLogs: (ContainerEntry) -> Unit,
    onPin: (ContainerEntry) -> Unit,
    onToggleFavorite: (Int, String) -> Unit,
    onRename: (Int, String) -> Unit,
) {
    if (entries.isEmpty()) {
        Text(
            "Aucun conteneur.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = { it.container.id }) { entry ->
            ContainerCard(
                entry = entry,
                busy = entry.container.id in busy,
                pinned = pinnedOf(entry.envId, entry.container.name),
                favorite = isFavorite(entry.envId, entry.container.name),
                label = labelOf(LabelKind.CONTAINER, entry.envId, entry.container.name),
                stackLabel = labelOf(LabelKind.STACK, entry.envId, entry.stack.name),
                onAction = { action -> onAction(entry, action) },
                onOpenLogs = { onOpenLogs(entry) },
                onPin = { onPin(entry) },
                onToggleFavorite = { onToggleFavorite(entry.envId, entry.container.name) },
                onRename = { onRename(entry.envId, entry.container.name) },
            )
        }
    }
}

@Composable
internal fun ContainerCard(
    entry: ContainerEntry,
    busy: Boolean,
    pinned: Set<Int>,
    favorite: Boolean,
    label: CustomLabel?,
    stackLabel: CustomLabel?,
    onAction: (StackAction) -> Unit,
    onOpenLogs: () -> Unit,
    onPin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
) {
    val container = entry.container

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                TitleBlock(
                    official = container.name,
                    label = label,
                    modifier = Modifier.weight(1f),
                )
                StateChip(
                    if (container.running) RunState.RUNNING else RunState.STOPPED,
                    if (container.running) 1 else 0,
                    1,
                )
            }

            Text(
                container.image,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )

            Description(label)

            PortRow(
                ports = container.ports,
                linkHost = entry.linkHost,
                network = container.network,
                sharesNetworkWith = container.sharesNetworkWith,
                pinned = pinned,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        // Le stack d'origine porte lui aussi son nom choisi :
                        // afficher les deux noms differemment sur le meme ecran
                        // donnerait l'impression de deux stacks distincts.
                        stackLabel?.name?.takeIf { it.isNotBlank() } ?: entry.stack.name,
                        style = MaterialTheme.typography.labelMedium,
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

                IconButton(onClick = onOpenLogs) {
                    Icon(Icons.Default.Article, contentDescription = "Logs de ${container.name}")
                }

                ContainerMenu(container.name, favorite, onPin, onToggleFavorite, onRename)

                if (busy) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                } else if (container.running) {
                    IconButton(onClick = { onAction(StackAction.STOP) }) {
                        Icon(Icons.Default.Stop, contentDescription = "Arrêter ${container.name}")
                    }
                    IconButton(onClick = { onAction(StackAction.RESTART) }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Relancer ${container.name}",
                        )
                    }
                } else {
                    IconButton(onClick = { onAction(StackAction.START) }) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Démarrer ${container.name}",
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    filter: StackFilter,
    sort: StackSort,
    sortOpen: Boolean,
    onFilter: (StackFilter) -> Unit,
    onSortOpen: (Boolean) -> Unit,
    onSort: (StackSort) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Le defilement horizontal evite que le bouton de tri sorte de
            // l'ecran sur les telephones etroits.
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StackFilter.entries.forEach { entry ->
            FilterChip(
                selected = filter == entry,
                onClick = { onFilter(entry) },
                label = { Text(entry.label) },
            )
        }

        Box {
            FilterChip(
                selected = sort != StackSort.NAME_ASC,
                onClick = { onSortOpen(true) },
                label = { Text(sort.label) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            DropdownMenu(expanded = sortOpen, onDismissRequest = { onSortOpen(false) }) {
                StackSort.entries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(entry.label) },
                        onClick = {
                            onSortOpen(false)
                            onSort(entry)
                        },
                    )
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
    linkHost: String,
    busy: Set<String>,
    pinnedOf: (Int, String) -> Set<Int>,
    isFavorite: (Int, String) -> Boolean,
    label: CustomLabel?,
    labelOfContainer: (String) -> CustomLabel?,
    favorite: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAction: (StackAction) -> Unit,
    onContainerAction: (ContainerView, StackAction) -> Unit,
    onOpenLogs: (ContainerView) -> Unit,
    onPin: (ContainerView) -> Unit,
    onToggleContainerFavorite: (ContainerView) -> Unit,
    onRename: () -> Unit,
    onRenameContainer: (ContainerView) -> Unit,
) {
    val working = stack.key in busy
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                TitleBlock(
                    official = stack.name,
                    label = label,
                    modifier = Modifier.weight(1f),
                )
                StateChip(stack.runState, stack.runningCount, stack.containers.size)
                // L'étoile décide de ce que montre le widget : c'est le réglage
                // le plus important de l'app, il mérite d'être là où on regarde.
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (favorite) {
                            "Retirer ${stack.name} du widget"
                        } else {
                            "Épingler ${stack.name} au widget"
                        },
                        tint = if (favorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
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
                                text = { Text("Renommer…") },
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DriveFileRenameOutline,
                                        contentDescription = null,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Relancer") },
                                onClick = {
                                    menuOpen = false
                                    onAction(StackAction.RESTART)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Relancer avec images à jour") },
                                enabled = stack.managedId != null,
                                onClick = {
                                    menuOpen = false
                                    onAction(StackAction.REDEPLOY)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                },
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

            Description(label)

            // Tous les ports du stack, dedupliques : la question posee a ce
            // niveau est « par ou j'y accede », pas « quel conteneur les porte ».
            PortRow(
                ports = stack.ports,
                linkHost = linkHost,
                // Un port epingle sur un conteneur remonte au stack : c'est a ce
                // niveau qu'on cherche « par ou j'ouvre ce service ».
                pinned = stack.containers
                    .flatMap { pinnedOf(stack.envId, it.name) }
                    .toSet(),
            )

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
                        linkHost = linkHost,
                        busy = container.id in busy,
                        pinned = pinnedOf(stack.envId, container.name),
                        favorite = isFavorite(stack.envId, container.name),
                        label = labelOfContainer(container.name),
                        onAction = { action -> onContainerAction(container, action) },
                        onOpenLogs = { onOpenLogs(container) },
                        onPin = { onPin(container) },
                        onToggleFavorite = { onToggleContainerFavorite(container) },
                        onRename = { onRenameContainer(container) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContainerRow(
    container: ContainerView,
    linkHost: String,
    busy: Boolean,
    pinned: Set<Int>,
    favorite: Boolean,
    label: CustomLabel?,
    onAction: (StackAction) -> Unit,
    onOpenLogs: () -> Unit,
    onPin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label?.name?.takeIf { it.isNotBlank() } ?: container.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Le nom officiel se glisse dans la ligne d'etat plutot que sur
                // une ligne a lui : la rangee porte deja trois niveaux de texte.
                label?.name?.takeIf { it.isNotBlank() }
                    ?.let { "${container.name} · ${container.statusText.ifBlank { container.state }}" }
                    ?: container.statusText.ifBlank { container.state },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PortRow(
                ports = container.ports,
                linkHost = linkHost,
                network = container.network,
                sharesNetworkWith = container.sharesNetworkWith,
                pinned = pinned,
            )
        }

        IconButton(onClick = onOpenLogs) {
            Icon(Icons.Default.Article, contentDescription = "Logs de ${container.name}")
        }

        ContainerMenu(container.name, favorite, onPin, onToggleFavorite, onRename)

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

/**
 * Le seul reglage par conteneur, sorti dans un menu plutot que dans une
 * pression longue : un reglage qu'on ne trouve pas n'existe pas.
 */
@Composable
private fun ContainerMenu(
    containerName: String,
    favorite: Boolean,
    onPin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Réglages de $containerName")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Volontairement dans le menu et non sur une etoile : l'etoile des
            // stacks veut deja dire « epingler au widget », et deux etoiles pour
            // deux sens differents sur le meme ecran est un piege.
            DropdownMenuItem(
                text = { Text(if (favorite) "Retirer des favoris" else "Ajouter aux favoris") },
                onClick = {
                    open = false
                    onToggleFavorite()
                },
                leadingIcon = {
                    Icon(
                        if (favorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = null,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text("Renommer…") },
                onClick = {
                    open = false
                    onRename()
                },
                leadingIcon = {
                    Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null)
                },
            )
            DropdownMenuItem(
                text = { Text("Port du raccourci…") },
                onClick = {
                    open = false
                    onPin()
                },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
            )
        }
    }
}

/** Le conteneur dont on regle le port de raccourci. */
private data class PinTarget(val envId: Int, val container: ContainerView)

/** Le conteneur ou le stack dont on modifie le nom personnalise. */
private data class LabelTarget(val kind: LabelKind, val envId: Int, val name: String)

/**
 * Le nom affiche, et sous lui le nom officiel quand ils different.
 *
 * Le nom officiel n'est jamais cache : c'est celui qu'on tape dans un compose
 * et qu'on lit dans un log. Sans lui, un nom personnalise couperait la carte de
 * ce qu'elle designe.
 */
@Composable
internal fun TitleBlock(
    official: String,
    label: CustomLabel?,
    modifier: Modifier = Modifier,
) {
    val custom = label?.name?.takeIf { it.isNotBlank() }

    Column(modifier) {
        Text(
            custom ?: official,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (custom != null) {
            Text(
                official,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** La description personnalisee. Vue detaillee seulement : les tuiles n'en ont pas la place. */
@Composable
internal fun Description(label: CustomLabel?) {
    val text = label?.description?.takeIf { it.isNotBlank() } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
}
