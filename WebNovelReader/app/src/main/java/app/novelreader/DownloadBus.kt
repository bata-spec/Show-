package app.novelreader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ProgressEvent(val novelId: String, val progress: DownloadManager.Progress)

/** DownloadServiceで発生した進捗を、開いているActivityにも反映するための簡易バス */
object DownloadBus {
    private val _events = MutableStateFlow<ProgressEvent?>(null)
    val events: StateFlow<ProgressEvent?> = _events

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    fun emit(event: ProgressEvent) {
        _events.value = event
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }
}
