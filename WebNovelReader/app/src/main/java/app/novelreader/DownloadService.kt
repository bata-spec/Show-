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
import java.util.EnumMap

/**
 * 通知バーに進捗を表示しながらダウンロードを行うフォアグラウンドサービス。
 * サイト（なろう／カクヨム）ごとに別々のキューを持ち、同じサイト内は1件ずつ順番に処理する
 * （そのサイトへの同時多重アクセスを避けるため）。一方、別サイト同士は互いのレート制限に
 * 影響しないため、並列にダウンロードして待ち時間を減らす。
 */
class DownloadService : Service() {

    companion object {
        private const val NOTIF_PROGRESS_ID_BASE = 1001
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

    private val lock = Any()
    private val queues = EnumMap<Site, ArrayDeque<Pair<String, String>>>(Site::class.java) // novelId to url
    private val activeSites = mutableSetOf<Site>()
    private var activeWorkerCount = 0

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
            if (activeWorkerCount == 0) stopSelf()
            return START_NOT_STICKY
        }

        val sitesToStart = mutableSetOf<Site>()
        synchronized(lock) {
            for (i in novelIds.indices) {
                val site = detectSite(urls[i])
                if (site == Site.UNKNOWN) continue
                queues.getOrPut(site) { ArrayDeque() }.addLast(novelIds[i] to urls[i])
                if (activeSites.add(site)) {
                    activeWorkerCount++
                    sitesToStart.add(site)
                }
            }
        }

        startForeground(NOTIF_PROGRESS_ID_BASE, NotificationHelper.progressBuilder(this, "ダウンロード準備中…").build())

        if (sitesToStart.isNotEmpty()) {
            DownloadBus.setRunning(true)
        }
        sitesToStart.forEach { site ->
            scope.launch { processSiteQueue(site, startId) }
        }

        return START_NOT_STICKY
    }

    /** 1つのサイトのキューを、そのサイト内では1件ずつ順番に処理し続けるワーカー */
    private suspend fun processSiteQueue(site: Site, startId: Int) {
        while (true) {
            // キューが空だった場合の「自分をactiveSitesから外す」までを同じロックの中で行うことで、
            // ちょうどそのタイミングで新しいURLが追加された場合でも取りこぼさないようにする
            // （空チェックと離脱がアトミックでないと、新規ワーカーが起動されないまま
            // 追加分だけキューに取り残されてしまうことがある）。
            val next = synchronized(lock) {
                val item = queues[site]?.removeFirstOrNull()
                if (item == null) {
                    activeSites.remove(site)
                    activeWorkerCount--
                }
                item
            } ?: break
            val (novelId, url) = next
            DownloadBus.removePending(url)
            var lastTitle = novelId

            try {
                downloadManager.downloadNovel(url) { progress ->
                    DownloadBus.emit(ProgressEvent(novelId, progress))
                    when (progress) {
                        is DownloadManager.Progress.Started -> {
                            lastTitle = progress.title
                            updateProgressNotification(site, progress)
                        }
                        is DownloadManager.Progress.EpisodeSaved -> {
                            lastTitle = progress.title
                            updateProgressNotification(site, progress)
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
        }

        val anyStillRunning = synchronized(lock) { activeWorkerCount > 0 }
        if (!anyStillRunning) {
            DownloadBus.setRunning(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun updateProgressNotification(site: Site, progress: DownloadManager.Progress) {
        val text = when (progress) {
            is DownloadManager.Progress.Started ->
                "[${site.name}] 「${progress.title}」を確認中…" + (progress.total?.let { "（全${it}話）" } ?: "")
            is DownloadManager.Progress.EpisodeSaved ->
                "[${site.name}] " + if (progress.total != null) {
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
        // サイトごとに別の通知にすることで、並列ダウンロード中でも進捗表示が互いを上書きしない
        notificationManager.notify(NOTIF_PROGRESS_ID_BASE + site.ordinal, builder.build())
    }

    private fun postResultNotification(novelId: String, title: String, text: String) {
        val notifId = NOTIF_RESULT_BASE + novelId.hashCode()
        notificationManager.notify(notifId, NotificationHelper.resultBuilder(this, title, text).build())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        synchronized(lock) {
            activeSites.clear()
            activeWorkerCount = 0
        }
        DownloadBus.setRunning(false)
    }
}
