package dev.mkdev.portainerremote.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mkdev.portainerremote.data.WidgetSync
import dev.mkdev.portainerremote.data.backup.BackupCrypto
import dev.mkdev.portainerremote.data.backup.BackupManager
import dev.mkdev.portainerremote.data.backup.UnknownFormatException
import dev.mkdev.portainerremote.data.backup.WrongPassphraseException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupUi(
    val passphrase: String = "",
    val confirmation: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** 0 tant qu'aucune sauvegarde n'a ete exportee depuis cette installation. */
    val lastExportAt: Long = 0L,
) {
    val longEnough: Boolean get() = passphrase.length >= BackupCrypto.MIN_PASSPHRASE

    /** L'export scelle : il exige la confirmation. L'import ne fait que lire. */
    val canExport: Boolean get() = !busy && longEnough && passphrase == confirmation

    val canImport: Boolean get() = !busy && passphrase.isNotEmpty()
}

class BackupViewModel(
    private val manager: BackupManager,
    private val widgetSync: WidgetSync,
) : ViewModel() {

    private val _ui = MutableStateFlow(BackupUi())
    val ui: StateFlow<BackupUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            _ui.update { it.copy(lastExportAt = manager.lastExportAt()) }
        }
    }

    fun suggestedFileName(): String = manager.suggestedFileName()

    fun setPassphrase(value: String) = _ui.update { it.copy(passphrase = value) }

    fun setConfirmation(value: String) = _ui.update { it.copy(confirmation = value) }

    fun export(target: Uri) {
        val passphrase = _ui.value.passphrase
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, message = null) }
            try {
                val count = manager.export(target, passphrase)
                _ui.update {
                    it.copy(
                        busy = false,
                        lastExportAt = manager.lastExportAt(),
                        message = "$count serveur${if (count > 1) "s" else ""} sauvegardé" +
                            "${if (count > 1) "s" else ""}.",
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message ?: "Échec de la sauvegarde.") }
            }
        }
    }

    fun import(source: Uri) {
        val passphrase = _ui.value.passphrase
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, message = null) }
            try {
                val result = manager.import(source, passphrase)
                // Les favoris restaures ne veulent rien dire pour le widget tant
                // qu'il n'a pas relu leur etat.
                widgetSync.refresh()
                _ui.update {
                    it.copy(
                        busy = false,
                        message = "${result.servers} serveur(s) et ${result.favorites} " +
                            "favori(s) restaurés.",
                    )
                }
            } catch (e: WrongPassphraseException) {
                _ui.update { it.copy(busy = false, error = e.message) }
            } catch (e: UnknownFormatException) {
                _ui.update { it.copy(busy = false, error = e.message) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message ?: "Échec de la restauration.") }
            }
        }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null, error = null) }
}
