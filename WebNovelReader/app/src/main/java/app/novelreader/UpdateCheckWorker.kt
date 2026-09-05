package app.novelreader

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 登録済みの全作品について、新しい話が出ていないか定期的にチェックする。
 * なろうは「全◯◯エピソード」の数字を比較するだけで新着の有無が分かるので軽量。
 * それ以外（総話数不明な場合）は、最後に保存した話のページから「次のエピソード」
 * リンクがあるかどうかだけを確認する。
 * 新着があれば実際にダウンロードまで行い、通知で知らせる。
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        NetworkClient.init(context)
        NotificationHelper.ensureChannels(context)

        val storage = Storage(context)
        val downloadManager = DownloadManager(storage)
        val novels = storage.loadLibrary()

        for (novel in novels) {
            try {
                checkAndUpdateOne(context, storage, downloadManager, novel)
            } catch (e: Exception) {
                // 1作品の失敗で全体を止めない。次回の定期実行に任せる
                continue
            }
        }

        return Result.success()
    }

    private suspend fun checkAndUpdateOne(
        context: Context,
        storage: Storage,
        downloadManager: DownloadManager,
        novel: Novel
    ) {
        val workHtml = NetworkClient.fetchHtml(novel.sourceUrl)
        if (NetworkClient.looksLikeAgeGate(workHtml, novel.sourceUrl, novel.site)) return

        val existingEpisodes = storage.loadEpisodes(novel.id)
        val existingCount = existingEpisodes.count { it.downloaded }
        // 長編は目次が複数ページに分かれるため、1ページ目だけだと既読分より少ない総数に
        // 見えてしまい、新着があるのに検知できないことがある
        val tocHtmls = downloadManager.fetchAllTocPages(workHtml, novel.sourceUrl, novel.site)
        val totalNow = NovelScraper.extractTotalEpisodes(tocHtmls, novel.sourceUrl, novel.site)

        val hasNew = if (totalNow != null) {
            totalNow > existingCount
        } else {
            val last = existingEpisodes.filter { it.downloaded }.maxByOrNull { it.order }
            if (last != null) {
                val lastHtml = NetworkClient.fetchHtml(last.url)
                NovelScraper.findNextEpisodeUrl(lastHtml, last.url, novel.site) != null
            } else {
                false
            }
        }

        if (!hasNew) return

        var newCount = 0
        downloadManager.downloadNovel(novel.sourceUrl) { progress ->
            if (progress is DownloadManager.Progress.EpisodeSaved) {
                newCount++
            }
        }

        if (newCount > 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val notifId = 3_000_000 + novel.id.hashCode()
            nm.notify(
                notifId,
                NotificationHelper.newEpisodeBuilder(context, novel.title, "新着${newCount}話を自動保存しました").build()
            )
        }
    }
}
