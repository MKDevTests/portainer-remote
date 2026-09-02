package dev.mkdev.portainerremote.ui.stacks

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mkdev.portainerremote.core.WebUi
import dev.mkdev.portainerremote.domain.NetworkKind
import dev.mkdev.portainerremote.domain.PortBinding

/**
 * Au-dela, une carte n'affiche plus rien d'autre. Mesure : un conteneur de
 * l'instance sondee publie 24 ports, la moyenne est de 1.
 */
private const val COLLAPSED = 4

/**
 * Les ports publies d'un conteneur ou d'un stack, en pastilles compactes.
 *
 * Le port hote suffit a s'y rendre, c'est donc lui seul qui s'affiche. Une
 * pastille mene au service ; deux cas ne le peuvent pas et le disent au lieu
 * d'offrir un lien mort : l'UDP, qui ne porte pas de HTTP, et une liaison sur
 * la boucle locale, que l'hote joint et le telephone jamais.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PortRow(
    ports: List<PortBinding>,
    linkHost: String,
    modifier: Modifier = Modifier,
    network: NetworkKind = NetworkKind.NORMAL,
    sharesNetworkWith: String? = null,
    pinned: Set<Int> = emptySet(),
) {
    // Un port epingle s'affiche meme si l'API ne l'a jamais rapporte : couvrir
    // ce qu'elle ne dit pas est precisement la raison d'etre du reglage.
    val invented = pinned
        .filter { port -> ports.none { it.publicPort == port } }
        .sorted()
        .map { PortBinding(it, it, "tcp", bindIp = "") }

    val first = (invented + ports).filter { it.publicPort in pinned }
    val rest = ports.filterNot { it.publicPort in pinned }

    // Un mode reseau particulier explique une carte sans port : sans ce mot,
    // l'absence ressemble a un bug de l'application. Un port epingle rend la
    // mention inutile : la question qu'elle repondait est reglee.
    val note = when {
        first.isNotEmpty() || rest.isNotEmpty() -> null
        network == NetworkKind.HOST -> "réseau host · ports de la machine"
        network == NetworkKind.SHARED && sharesNetworkWith != null -> "réseau de $sharesNetworkWith"
        network == NetworkKind.SHARED -> "réseau partagé"
        else -> null
    }

    if (note != null) {
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(top = 6.dp),
        )
        return
    }

    if (first.isEmpty() && rest.isEmpty()) return

    var expanded by remember(ports, pinned) { mutableStateOf(false) }
    // Un port epingle n'est jamais replie : le cacher derriere « +8 » reviendrait
    // a annuler le choix que l'utilisateur vient de faire.
    val shown = if (expanded) first + rest else first + rest.take(COLLAPSED - first.size)
    val hidden = first.size + rest.size - shown.size

    FlowRow(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shown.forEach { port -> PortChip(port, linkHost, port.publicPort in pinned) }

        if (hidden > 0) {
            Chip(
                text = "+$hidden",
                highlighted = false,
                description = "Afficher les $hidden autres ports",
                onClick = { expanded = true },
            )
        }
    }
}

@Composable
private fun PortChip(port: PortBinding, linkHost: String, pinned: Boolean) {
    val context = LocalContext.current
    val url = if (port.linkable) WebUi.url(port.boundHost ?: linkHost, port.publicPort) else null

    val suffix = when {
        port.udp -> " udp"
        port.loopback -> " local"
        else -> ""
    }

    Chip(
        text = port.label + suffix,
        highlighted = url != null,
        pinned = pinned,
        description = when {
            url != null && pinned -> "Ouvrir $url, raccourci choisi"
            // « deduit » ne se voit pas a l'ecran : la pastille reste compacte,
            // mais l'information reste disponible pour qui la cherche.
            url != null && port.deduced -> "Ouvrir $url, port déduit de l'image"
            url != null -> "Ouvrir $url"
            port.udp -> "Port ${port.publicPort} en UDP, pas de page web"
            else -> "Port ${port.publicPort} lié à la machine seule, injoignable d'ici"
        },
        onClick = url?.let {
            {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(it))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "Aucun navigateur installé.", Toast.LENGTH_SHORT).show()
                }
            }
        },
    )
}

@Composable
private fun Chip(
    text: String,
    highlighted: Boolean,
    description: String,
    onClick: (() -> Unit)?,
    pinned: Boolean = false,
) {
    // Trois niveaux, et pas deux : le port choisi a la main doit se distinguer
    // des autres liens, sans quoi l'epingler ne changerait rien a l'oeil.
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = when {
            pinned -> MaterialTheme.colorScheme.primary
            highlighted -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = when {
            pinned -> MaterialTheme.colorScheme.onPrimary
            highlighted -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = description },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
