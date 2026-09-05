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

    // ダウンロードキューに積まれたが、まだ処理が始まっていないURL。
    // 順番待ちの間は作品一覧にまだ出てこないため、「追加はしたが反応が無い」と
    // 誤解されないよう、待機中であることを画面に表示するために使う。
    private val _pendingUrls = MutableStateFlow<Set<String>>(emptySet())
    val pendingUrls: StateFlow<Set<String>> = _pendingUrls

    fun emit(event: ProgressEvent) {
        _events.value = event
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun addPending(url: String) {
        _pendingUrls.value = _pendingUrls.value + url
    }

    fun removePending(url: String) {
        _pendingUrls.value = _pendingUrls.value - url
    }
}
