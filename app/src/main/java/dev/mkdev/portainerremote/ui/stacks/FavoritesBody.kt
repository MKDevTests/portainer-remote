package dev.mkdev.portainerremote.ui.stacks

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mkdev.portainerremote.core.WebUi
import dev.mkdev.portainerremote.data.store.CustomLabel
import dev.mkdev.portainerremote.data.store.LabelKind
import dev.mkdev.portainerremote.domain.ContainerView
import dev.mkdev.portainerremote.domain.FavoritesView
import dev.mkdev.portainerremote.domain.StackAction

/**
 * L'onglet des conteneurs favoris.
 *
 * Deux lectures du meme jeu de donnees. « Raccourcis » repond a « ou est-ce que
 * je clique » : une tuile, un nom, un port, et l'appui ouvre le service.
 * « Detaille » reprend la carte complete de l'onglet Conteneurs, avec etat,
 * image et actions.
 *
 * Un favori dont le conteneur n'est plus dans la liste reste affiche, grise.
 * Le masquer le rendrait impossible a retirer, ce qui est exactement le moment
 * ou l'on veut pouvoir le faire.
 */
@Composable
internal fun FavoritesBody(
    favorites: List<FavoriteEntry>,
    view: FavoritesView,
    columns: Int,
    busy: Set<String>,
    pinnedOf: (Int, String) -> Set<Int>,
    isFavorite: (Int, String) -> Boolean,
    labelOf: (LabelKind, Int, String) -> CustomLabel?,
    onView: (FavoritesView) -> Unit,
    onRename: (Int, String) -> Unit,
    onRemove: (String) -> Unit,
    onPin: (Int, ContainerView) -> Unit,
    onToggleFavorite: (Int, String) -> Unit,
    onAction: (ContainerEntry, StackAction) -> Unit,
    onOpenLogs: (ContainerEntry) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FavoritesView.entries.forEach { entry ->
                FilterChip(
                    selected = view == entry,
                    onClick = { onView(entry) },
                    label = { Text(entry.label) },
                )
            }
        }

        if (favorites.isEmpty()) {
            Text(
                "Aucun favori. Le menu d'un conteneur permet de l'ajouter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return
        }

        // En mode raccourci les tuiles sont courtes : on en met deux fois plus
        // par ligne, sinon la moitie de l'ecran reste vide.
        val gridColumns = if (view == FavoritesView.SHORTCUTS) columns * 2 else columns

        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(favorites, key = { it.key }) { favorite ->
                val entry = favorite.entry
                when {
                    entry == null -> GhostTile(
                        name = labelOf(LabelKind.CONTAINER, favorite.envId, favorite.name)?.name
                            ?.takeIf { it.isNotBlank() } ?: favorite.name,
                        onRemove = { onRemove(favorite.key) },
                    )

                    view == FavoritesView.SHORTCUTS -> ShortcutTile(
                        entry = entry,
                        pinned = pinnedOf(entry.envId, entry.container.name),
                        label = labelOf(LabelKind.CONTAINER, entry.envId, entry.container.name),
                        onRemove = { onRemove(favorite.key) },
                        onPin = { onPin(entry.envId, entry.container) },
                    )

                    else -> ContainerCard(
                        entry = entry,
                        busy = entry.container.id in busy,
                        pinned = pinnedOf(entry.envId, entry.container.name),
                        favorite = isFavorite(entry.envId, entry.container.name),
                        label = labelOf(LabelKind.CONTAINER, entry.envId, entry.container.name),
                        stackLabel = labelOf(LabelKind.STACK, entry.envId, entry.stack.name),
                        onAction = { action -> onAction(entry, action) },
                        onOpenLogs = { onOpenLogs(entry) },
                        onPin = { onPin(entry.envId, entry.container) },
                        onToggleFavorite = {
                            onToggleFavorite(entry.envId, entry.container.name)
                        },
                        onRename = { onRename(entry.envId, entry.container.name) },
                    )
                }
            }

            // Une ligne vide en fin de grille, pour que la derniere tuile ne
            // colle pas au bord bas sur les ecrans sans barre de navigation.
            item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.size(8.dp)) }
        }
    }
}

/**
 * Une tuile de lancement : nom, port, et c'est tout.
 *
 * Sans port joignable elle n'est pas morte pour autant — l'appui ouvre le choix
 * du port, qui est precisement ce qui lui manque.
 */
@Composable
private fun ShortcutTile(
    entry: ContainerEntry,
    pinned: Set<Int>,
    label: CustomLabel?,
    onRemove: () -> Unit,
    onPin: () -> Unit,
) {
    val context = LocalContext.current
    val container = entry.container
    val binding = shortcutBinding(container, pinned)
    val url = binding?.let { WebUi.url(it.boundHost ?: entry.linkHost, it.publicPort) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (url == null) {
                    onPin()
                } else {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "Aucun navigateur installé.", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label?.name?.takeIf { it.isNotBlank() } ?: container.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        url == null -> "définir un port"
                        !container.running -> "arrêté · ${binding.publicPort}"
                        else -> "port ${binding.publicPort}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (url == null || !container.running) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                Icons.Default.Link,
                contentDescription = null,
                tint = if (url == null) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(18.dp),
            )

            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Bookmark,
                    contentDescription = "Retirer ${container.name} des favoris",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Un favori dont le conteneur a disparu : renomme, supprime, ou hors ligne. */
@Composable
private fun GhostTile(name: String, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "introuvable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Bookmark,
                    contentDescription = "Retirer $name des favoris",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Le port qui ouvre le service : celui choisi a la main s'il existe, sinon le
 * premier port joignable. Un port epingle absent de la liste reste valable —
 * c'est le cas d'un service en reseau host que l'API ne decrit pas.
 */
internal fun shortcutBinding(
    container: ContainerView,
    pinned: Set<Int>,
): dev.mkdev.portainerremote.domain.PortBinding? {
    val chosen = pinned.firstOrNull()
    if (chosen != null) {
        return container.ports.firstOrNull { it.publicPort == chosen }
            ?: dev.mkdev.portainerremote.domain.PortBinding(chosen, chosen, "tcp", bindIp = "")
    }
    return container.ports.firstOrNull { it.linkable }
}
