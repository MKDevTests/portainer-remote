package dev.mkdev.portainerremote.ui.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mkdev.portainerremote.data.store.CustomLabel

/** Au-dela, la description ne tient plus sur les deux lignes que la carte lui accorde. */
private const val MAX_DESCRIPTION = 160
private const val MAX_NAME = 40

/**
 * Nom personnalise et description d'un stack ou d'un conteneur.
 *
 * Le nom officiel n'est jamais remplace : c'est lui qu'on tape dans un compose
 * et qu'on lit dans un log. Il reste affiche sous le nom choisi, et la
 * recherche continue de le trouver.
 */
@Composable
internal fun LabelDialog(
    officialName: String,
    kindLabel: String,
    current: CustomLabel?,
    onDismiss: () -> Unit,
    onSave: (CustomLabel) -> Unit,
) {
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var description by remember { mutableStateOf(current?.description.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renommer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "$kindLabel « $officialName ». Le nom officiel reste affiché " +
                        "sous le tien, et la recherche trouve les deux.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    label = { Text("Nom personnalisé") },
                    singleLine = true,
                    supportingText = { Text("Vide : garder le nom officiel.") },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(MAX_DESCRIPTION) },
                    label = { Text("Description") },
                    minLines = 2,
                    maxLines = 3,
                    supportingText = {
                        Text("${description.length} / $MAX_DESCRIPTION · vue détaillée seulement")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(CustomLabel(name.trim(), description.trim())) },
            ) {
                Text("Enregistrer")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
