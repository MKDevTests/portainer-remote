package dev.mkdev.portainerremote.ui.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mkdev.portainerremote.BuildConfig
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.core.errorText
import dev.mkdev.portainerremote.data.InstallReceiver
import dev.mkdev.portainerremote.data.UpdateManager
import dev.mkdev.portainerremote.data.net.ReleaseInfo
import dev.mkdev.portainerremote.data.net.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Etapes du parcours de mise a jour, dans l'ordre ou elles se presentent. */
enum class UpdateStage {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY,

    /** Remise au systeme faite : c'est Android qui parle desormais. */
    INSTALLING,
}

data class UpdatesUi(
    val installedVersion: String = BuildConfig.VERSION_NAME,
    val repo: String = BuildConfig.UPDATE_REPO,
    val stage: UpdateStage = UpdateStage.IDLE,
    val release: ReleaseInfo? = null,
    /** Null tant que la taille annoncee est inconnue : la barre reste indeterminee. */
    val progress: Float? = null,
    val apk: File? = null,
    val error: String? = null,
    /** Reglage systeme, revocable : il est relu a chaque affichage de l'ecran. */
    val canInstall: Boolean = false,
)

class UpdatesViewModel(
    private val checker: UpdateChecker,
    private val manager: UpdateManager,
) : ViewModel() {

    private val _ui = MutableStateFlow(UpdatesUi())
    val ui: StateFlow<UpdatesUi> = _ui.asStateFlow()

    init {
        check()
        // Ce que le systeme repond a une installation arrive ici, meme si
        // l'utilisateur a quitte l'ecran entre-temps.
        viewModelScope.launch {
            InstallReceiver.messages.collect { message ->
                _ui.update {
                    it.copy(
                        error = message,
                        stage = if (it.stage == UpdateStage.INSTALLING) {
                            UpdateStage.READY
                        } else {
                            it.stage
                        },
                    )
                }
            }
        }
    }

    /** A rappeler au retour du reglage systeme : l'autorisation a pu changer. */
    fun refreshInstallPermission() {
        _ui.update { it.copy(canInstall = manager.canInstall()) }
    }

    fun check() {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    stage = UpdateStage.CHECKING,
                    error = null,
                    canInstall = manager.canInstall(),
                )
            }

            when (val result = checker.check()) {
                is ApiResult.Ok -> {
                    val release = result.value
                    _ui.update {
                        it.copy(
                            stage = if (release == null) {
                                UpdateStage.UP_TO_DATE
                            } else {
                                UpdateStage.AVAILABLE
                            },
                            release = release,
                        )
                    }
                }
                else -> _ui.update {
                    it.copy(stage = UpdateStage.IDLE, error = result.errorText())
                }
            }
        }
    }

    fun download() {
        val release = _ui.value.release ?: return
        viewModelScope.launch {
            _ui.update { it.copy(stage = UpdateStage.DOWNLOADING, progress = null, error = null) }

            val result = manager.download(release) { fraction ->
                _ui.update { it.copy(progress = fraction) }
            }

            when (result) {
                is ApiResult.Ok -> _ui.update {
                    it.copy(stage = UpdateStage.READY, apk = result.value, progress = 1f)
                }
                else -> _ui.update {
                    it.copy(
                        stage = UpdateStage.AVAILABLE,
                        progress = null,
                        error = result.errorText(),
                    )
                }
            }
        }
    }

    fun install() {
        val apk = _ui.value.apk ?: return
        if (!manager.canInstall()) {
            manager.openInstallPermissionSettings()
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(stage = UpdateStage.INSTALLING, error = null) }
            val result = manager.install(apk)
            if (result !is ApiResult.Ok) {
                _ui.update {
                    it.copy(
                        stage = UpdateStage.READY,
                        error = "Le système a refusé la remise du fichier : " +
                            result.errorText(),
                    )
                }
            }
        }
    }

    fun openInstallPermissionSettings() = manager.openInstallPermissionSettings()

    fun dismissError() = _ui.update { it.copy(error = null) }
}
