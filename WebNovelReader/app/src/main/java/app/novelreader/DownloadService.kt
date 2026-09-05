package app.novelreader

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知バーに進捗を表示しながらダウンロードを行うフォアグラウンドサービス。
 * 複数URLをまとめて渡すと、内部キューに積んで1件ずつ順番に処理する
 * （サイトへの同時多重アクセスを避けるため、並列ではなく直列で処理する）。
 */
class DownloadService : Service() {

    companion object {
        private const val NOTIF_PROGRESS_ID = 1001
        private const val NOTIF_RESULT_BASE = 2_000_000

        /** 1件だけダウンロード（既存の呼び出し元との互換用） */
        fun start(context: Context, novelId: String, url: String) {
            enqueueMultiple(context, listOf(url))
        }

        /** 複数URLをまとめてキューに積む */
        fun enqueueMultiple(context: Context, urls: List<String>) {
            val normalized = urls.map { UrlNormalizer.normalize(it) }.filter { it.isNotBlank() }
            val novelIds = ArrayList<String>()
            val urlList = ArrayList<String>()
            for (u in normalized) {
                val site = detectSite(u)
                if (site == Site.UNKNOWN) continue
                novelIds.add("${site.name}_${NovelScraper.extractWorkId(u, site)}")
                urlList.add(u)
            }
            if (urlList.isEmpty()) return

            urlList.forEach { DownloadBus.addPending(it) }

            val intent = Intent(context, DownloadService::class.java)
            intent.putStringArrayListExtra("novelIds", novelIds)
            intent.putStringArrayListExtra("urls", urlList)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var storage: Storage
    private lateinit var downloadManager: DownloadManager
    private lateinit var notificationManager: NotificationManager

    private val queue = ArrayDeque<Pair<String, String>>() // novelId to url
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        storage = Storage(applicationContext)
        downloadManager = DownloadManager(storage)
        NetworkClient.init(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationHelper.ensureChannels(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val novelIds = intent?.getStringArrayListExtra("novelIds")
        val urls = intent?.getStringArrayListExtra("urls")

        if (novelIds == null || urls == null || novelIds.size != urls.size || novelIds.isEmpty()) {
            if (!isProcessing) stopSelf()
            return START_NOT_STICKY
        }

        for (i in novelIds.indices) {
            queue.addLast(novelIds[i] to urls[i])
        }

        startForeground(NOTIF_PROGRESS_ID, NotificationHelper.progressBuilder(this, "ダウンロード準備中…").build())
        if (!isProcessing) {
            isProcessing = true
            DownloadBus.setRunning(true)
            scope.launch { processQueue(startId) }
        }

        return START_NOT_STICKY
    }

    private suspend fun processQueue(startId: Int) {
        while (queue.isNotEmpty()) {
            val (novelId, url) = queue.removeFirst()
            DownloadBus.removePending(url)
            var lastTitle = novelId

            try {
                downloadManager.downloadNovel(url) { progress ->
                    DownloadBus.emit(ProgressEvent(novelId, progress))
                    when (progress) {
                        is DownloadManager.Progress.Started -> {
                            lastTitle = progress.title
                            updateProgressNotification(progress)
                        }
                        is DownloadManager.Progress.EpisodeSaved -> {
                            lastTitle = progress.title
                            updateProgressNotification(progress)
                        }
                        is DownloadManager.Progress.Finished -> {
                            postResultNotification(novelId, lastTitle, "完了：${progress.totalEpisodes}話を保存しました")
                        }
                        is DownloadManager.Progress.Failed -> {
                            postResultNotification(novelId, lastTitle, "エラー: ${progress.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                postResultNotification(novelId, lastTitle, "予期しないエラー: ${e.message}")
            }

            if (queue.isNotEmpty()) {
                notificationManager.notify(
                    NOTIF_PROGRESS_ID,
                    NotificationHelper.progressBuilder(this, "次の作品を準備中…（残り${queue.size}件）").build()
                )
            }
        }

        isProcessing = false
        DownloadBus.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun updateProgressNotification(progress: DownloadManager.Progress) {
        val text = when (progress) {
            is DownloadManager.Progress.Started ->
                "「${progress.title}」を確認中…" + (progress.total?.let { "（全${it}話）" } ?: "")
            is DownloadManager.Progress.EpisodeSaved ->
                if (progress.total != null) {
                    "${progress.order}/${progress.total}話「${progress.title}」"
                } else {
                    "第${progress.order}話「${progress.title}」"
                }
            else -> return
        }
        val builder = NotificationHelper.progressBuilder(this, text)
        if (progress is DownloadManager.Progress.EpisodeSaved && progress.total != null) {
            builder.setProgress(progress.total, progress.order, false)
        }
        notificationManager.notify(NOTIF_PROGRESS_ID, builder.build())
    }

    private fun postResultNotification(novelId: String, title: String, text: String) {
        val notifId = NOTIF_RESULT_BASE + novelId.hashCode()
        notificationManager.notify(notifId, NotificationHelper.resultBuilder(this, title, text).build())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isProcessing = false
        DownloadBus.setRunning(false)
    }
}
