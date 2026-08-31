package dev.mkdev.portainerremote.ui.images

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mkdev.portainerremote.core.ApiResult
import dev.mkdev.portainerremote.core.errorText
import dev.mkdev.portainerremote.data.PortainerRepository
import dev.mkdev.portainerremote.data.store.ServerStore
import dev.mkdev.portainerremote.domain.ImageFilter
import dev.mkdev.portainerremote.domain.ImageGroup
import dev.mkdev.portainerremote.domain.ImageView
import dev.mkdev.portainerremote.domain.Server
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImagesUi(
    val server: Server? = null,
    val groups: List<ImageGroup> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val busy: Set<String> = emptySet(),
    val message: String? = null,
    /** Image dont la suppression attend confirmation. */
    val pendingDelete: Pair<Int, ImageView>? = null,
    val filter: ImageFilter = ImageFilter.ALL,
) {
    /**
     * Le filtre ne s'applique qu'a la liste, jamais a l'en-tete : le volume
     * recuperable annonce reste celui de l'environnement entier, sinon filtrer
     * ferait fondre le chiffre qui motive le menage.
     */
    fun visibleImages(group: ImageGroup): List<ImageView> = when (filter) {
        ImageFilter.ALL -> group.images
        ImageFilter.UNUSED -> group.images.filterNot { it.inUse }
        ImageFilter.UNTAGGED -> group.images.filter { it.dangling }
    }

    val visibleCount: Int get() = groups.sumOf { visibleImages(it).size }
}

class ImagesViewModel(
    private val serverId: String,
    private val store: ServerStore,
    private val repository: PortainerRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ImagesUi())
    val ui: StateFlow<ImagesUi> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val server = store.get(serverId)
            if (server == null) {
                _ui.update { it.copy(loading = false, error = "Serveur introuvable.") }
                return@launch
            }
            when (val result = repository.loadImages(server)) {
                is ApiResult.Ok -> _ui.update {
                    it.copy(server = server, groups = result.value, loading = false, error = null)
                }
                else -> _ui.update {
                    it.copy(server = server, loading = false, error = result.errorText())
                }
            }
        }
    }

    fun askDelete(envId: Int, image: ImageView) =
        _ui.update { it.copy(pendingDelete = envId to image) }

    fun cancelDelete() = _ui.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val pending = _ui.value.pendingDelete ?: return
        val server = _ui.value.server ?: return
        val (envId, image) = pending
        _ui.update { it.copy(pendingDelete = null, busy = it.busy + image.id) }

        viewModelScope.launch {
            val result = repository.deleteImage(server, envId, image.id)
            val message = when (result) {
                is ApiResult.Ok -> "${image.displayName} supprimée."
                // Docker refuse tant qu'un conteneur, meme arrete, reference l'image.
                is ApiResult.HttpError -> if (result.code == 409) {
                    "Impossible : un conteneur utilise encore cette image."
                } else {
                    result.errorText()
                }
                else -> result.errorText()
            }
            _ui.update { it.copy(busy = it.busy - image.id, message = message) }
            refresh()
        }
    }

    fun setFilter(value: ImageFilter) = _ui.update { it.copy(filter = value) }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
}
