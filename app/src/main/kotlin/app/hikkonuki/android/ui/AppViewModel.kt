package app.hikkonuki.android.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.hikkonuki.android.core.ExportBatch
import app.hikkonuki.android.core.ExportJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BrowserLayout { GRID, LIST }

data class BrowserUiState(
    val usbConnected: Boolean = false,
    val loading: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val layout: BrowserLayout = BrowserLayout.GRID,
    val selectedIds: Set<String> = emptySet(),
    /** Granted camera folders; watched for the camera coming back. */
    val watchedFolders: List<String> = emptyList(),
    val folderPermissionError: Boolean = false,
) {
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val selectedItems: List<MediaItem> get() = items.filter { it.id in selectedIds }
    val canPickFolder: Boolean get() = usbConnected && !loading && items.isEmpty()
    val hasSavedFolder: Boolean get() = watchedFolders.isNotEmpty()
}

/**
 * Screen state for browsing and selecting. It talks to two interfaces only, so every
 * behaviour here is covered by plain unit tests with fakes: no USB, no codecs.
 */
class AppViewModel(private val library: MediaLibrary, private val exports: ExportController) : ViewModel() {
    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()
    val exportJobs: StateFlow<List<ExportJob>> get() = exports.jobs
    val exportBatches: StateFlow<List<ExportBatch>> get() = exports.batches

    private var refreshJob: Job? = null
    private var refreshPending = false
    private var initialLoadDone = false
    private var mountRetries = 0
    private val details = HashMap<String, MediaDetails>()
    private var detailsJob: Job? = null

    init {
        exports.resume()
        refresh()
    }

    fun refresh() {
        // Scans are serialized, but a trigger that arrives mid-scan (a mount event, a new
        // folder grant) must not be lost, or the stale result of the old scan would stick.
        refreshPending = true
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            do {
                refreshPending = false
                // A rescan never blanks the screen: whatever is showing stays until the new
                // result replaces it. The spinner belongs to the very first load, and to the
                // wait for a known camera's storage (below), where it stays on steadily
                // instead of blinking with every retry.
                val snapshot = library.load()
                val items = snapshot.items.map(::withDetails)
                val ids = items.mapTo(HashSet()) { it.id }
                // Android announces the USB device a few seconds before its storage is readable.
                val waitingForStorage = snapshot.connected && items.isEmpty() &&
                    snapshot.watchedFolders.isNotEmpty() && mountRetries < MAX_MOUNT_RETRIES
                _state.update {
                    it.copy(
                        usbConnected = snapshot.connected, loading = waitingForStorage, items = items,
                        // Selections for media that vanished (cable pulled) must not linger.
                        selectedIds = it.selectedIds intersect ids,
                        watchedFolders = snapshot.watchedFolders,
                    )
                }
                initialLoadDone = true
                if (items.isNotEmpty()) {
                    mountRetries = 0
                    loadVideoDetails()
                }
                if (!refreshPending && waitingForStorage) {
                    mountRetries++
                    delay(MOUNT_RETRY_MILLIS)
                    refreshPending = true
                }
            } while (refreshPending)
        }
    }

    /** The camera was plugged in or removed. */
    fun onUsbChanged() {
        mountRetries = 0
        refresh()
    }

    /**
     * The ViewModel survives rotation with its list, so a plain resume must not rescan.
     * Rescan only when there is nothing to show or the connection changed meanwhile.
     */
    fun refreshOnResume() {
        if (!initialLoadDone) return
        if (_state.value.items.isEmpty()) {
            onUsbChanged()
            return
        }
        viewModelScope.launch {
            if (library.isConnected() != _state.value.usbConnected) onUsbChanged()
        }
    }

    private fun withDetails(item: MediaItem): MediaItem {
        val known = details[item.id] ?: return item
        return item.copy(
            width = known.width.takeIf { it > 0 } ?: item.width,
            height = known.height.takeIf { it > 0 } ?: item.height,
            durationMillis = known.durationMillis ?: item.durationMillis,
            dateTakenMillis = known.dateTakenMillis ?: item.dateTakenMillis,
        )
    }

    /** Durations for the grid, filled in behind the already visible list. */
    private fun loadVideoDetails() {
        if (detailsJob?.isActive == true) return
        detailsJob = viewModelScope.launch {
            while (true) {
                val next = _state.value.items.firstOrNull { it.isVideo && it.id !in details } ?: break
                loadDetails(next)
            }
        }
    }

    /** Resolution and exact date for the item on screen in the detail view. */
    fun ensureDetails(item: MediaItem) {
        if (item.id in details) return
        viewModelScope.launch { loadDetails(item) }
    }

    private suspend fun loadDetails(item: MediaItem) {
        details[item.id] = library.details(item) ?: MediaDetails(0, 0, null, null)
        _state.update { state -> state.copy(items = state.items.map { if (it.id == item.id) withDetails(it) else it }) }
    }

    fun toggleLayout() = _state.update {
        it.copy(layout = if (it.layout == BrowserLayout.GRID) BrowserLayout.LIST else BrowserLayout.GRID)
    }

    fun startSelection(item: MediaItem) = _state.update { it.copy(selectedIds = setOf(item.id)) }

    // An empty selection is what ends selection mode; there is no separate flag to get out of sync.
    fun toggleSelection(item: MediaItem) = _state.update {
        it.copy(selectedIds = if (item.id in it.selectedIds) it.selectedIds - item.id else it.selectedIds + item.id)
    }

    fun clearSelection() = _state.update { it.copy(selectedIds = emptySet()) }

    fun selectAll() = _state.update { state -> state.copy(selectedIds = state.items.mapTo(HashSet()) { it.id }) }

    fun exportSelected() {
        val items = _state.value.selectedItems
        if (items.isEmpty()) return
        exports.export(items.map(MediaItem::toRequest))
        clearSelection()
    }

    fun exportItem(item: MediaItem) = exports.export(listOf(item.toRequest()))

    fun cancelExport(jobId: Long) = exports.cancel(jobId)
    fun clearExportHistory() = exports.clearHistory()

    fun folderPickerIntent(): Intent = library.folderPickerIntent()

    fun onFolderPicked(uri: Uri) {
        val saved = library.rememberFolder(uri)
        _state.update { it.copy(folderPermissionError = !saved) }
        if (saved) onUsbChanged()
    }

    suspend fun exists(item: MediaItem): Boolean = library.exists(item)

    companion object {
        const val MAX_MOUNT_RETRIES = 10
        const val MOUNT_RETRY_MILLIS = 1500L

        fun factory(library: MediaLibrary, exports: ExportController): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(library, exports) }
        }
    }
}
