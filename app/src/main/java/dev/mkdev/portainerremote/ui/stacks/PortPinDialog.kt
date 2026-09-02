package dev.mkdev.portainerremote.ui.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.mkdev.portainerremote.domain.PortBinding

/**
 * Choix du port de raccourci d'un conteneur.
 *
 * Aucune regle ne peut deviner lequel des ports publies porte l'interface web :
 * un client BitTorrent en publie un pour ses pairs, un pour son interface, et
 * rien dans l'API ne les distingue. La saisie libre est la pour le cas ou le
 * bon port n'apparait meme pas dans la liste — reseau host sans EXPOSE, service
 * derriere un proxy, port ouvert hors de Docker.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PortPinDialog(
    containerName: String,
    detected: List<PortBinding>,
    current: Int?,
    onDismiss: () -> Unit,
    onSave: (Int?) -> Unit,
) {
    var text by remember { mutableStateOf(current?.toString().orEmpty()) }
    val port = text.trim().toIntOrNull()
    val valid = text.isBlank() || (port != null && port in 1..65535)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Port du raccourci") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Le port qui ouvre l'interface web de $containerName.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                // Un meme port en TCP et en UDP donne deux entrees pour un seul
                // numero : proposer « 6881 » deux fois n'aiderait personne.
                val choices = detected.distinctBy { it.publicPort }

                if (choices.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        choices.forEach { binding ->
                            FilterChip(
                                selected = port == binding.publicPort,
                                onClick = { text = binding.publicPort.toString() },
                                label = { Text(binding.label) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    isError = !valid,
                    supportingText = {
                        Text(
                            if (!valid) {
                                "Entre 1 et 65535."
                            } else {
                                "Vide : revenir au comportement automatique."
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(port.takeIf { text.isNotBlank() }) },
            ) {
                Text("Enregistrer")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
