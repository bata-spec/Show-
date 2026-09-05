package app.novelreader

class DownloadManager(private val storage: Storage) {

    sealed class Progress {
        data class Started(val title: String, val total: Int?) : Progress()
        data class EpisodeSaved(val order: Int, val title: String, val total: Int?) : Progress()
        data class Finished(val totalEpisodes: Int) : Progress()
        data class Failed(val message: String) : Progress()
    }

    /**
     * URLから作品を登録し、全話を保存する。
     * なろうは作品ページの「全◯◯エピソード」表記から総数が分かるため、
     * 1話目〜最終話まで番号を直接指定して巡回する（404判定に頼らない）。
     * 総数が分からない場合・カクヨムの場合は、各話ページの「次のエピソード」リンクを
     * 辿る方式にフォールバックする。
     * 既に一部保存済みの作品を再度呼ぶと、未取得分だけ続きから取得する。
     */
    suspend fun downloadNovel(
        rawUrl: String,
        onProgress: suspend (Progress) -> Unit
    ) {
        val url = UrlNormalizer.normalize(rawUrl)
        val site = detectSite(url)
        if (site == Site.UNKNOWN) {
            onProgress(Progress.Failed("対応していないサイトです（カクヨム・なろうのみ対応）"))
            return
        }

        val novelId = "${site.name}_${NovelScraper.extractWorkId(url, site)}"

        val workHtml = try {
            NetworkClient.fetchHtml(url)
        } catch (e: Exception) {
            onProgress(Progress.Failed("作品ページの取得に失敗しました: ${e.message}"))
            return
        }

        if (NetworkClient.looksLikeAgeGate(workHtml)) {
            onProgress(
                Progress.Failed(
                    "年齢確認ページが表示されました。メイン画面の「年齢確認が必要なサイトを開く」から" +
                        "一度だけこのサイトを開いて確認を通過してから、もう一度お試しください。"
                )
            )
            return
        }

        val meta = NovelScraper.parseWorkMeta(workHtml, url, site)
        val totalEpisodes = NovelScraper.extractTotalEpisodes(workHtml, site)
        val chapterMap = NovelScraper.parseChapterMap(workHtml, url, site)
        val novel = Novel(
            id = novelId,
            title = meta.title,
            sourceUrl = url,
            site = site,
            addedAt = System.currentTimeMillis()
        )
        storage.upsertNovel(novel)
        onProgress(Progress.Started(meta.title, totalEpisodes))

        val existing = storage.loadEpisodes(novelId)
        val startOrder = (existing.filter { it.downloaded }.maxOfOrNull { it.order } ?: 0) + 1
        val savedCount = existing.count { it.downloaded }

        if (site == Site.NAROU && totalEpisodes != null) {
            downloadNarouByNumber(novelId, url, startOrder, totalEpisodes, savedCount, chapterMap, onProgress)
        } else {
            downloadByFollowingLinks(novelId, site, meta, existing, startOrder, totalEpisodes, savedCount, chapterMap, onProgress)
        }
    }

    /** なろう専用：総話数が分かっている場合、番号を直接指定して1話ずつ取得する */
    private suspend fun downloadNarouByNumber(
        novelId: String,
        workUrl: String,
        startOrder: Int,
        totalEpisodes: Int,
        savedCountStart: Int,
        chapterMap: Map<String, String>,
        onProgress: suspend (Progress) -> Unit
    ) {
        val ncode = NovelScraper.extractWorkId(workUrl, Site.NAROU)
        var savedCount = savedCountStart
        var refererUrl = workUrl

        for (order in startOrder..totalEpisodes) {
            val episodeUrl = "https://ncode.syosetu.com/$ncode/$order/"

            val html = try {
                NetworkClient.fetchHtml(episodeUrl, referer = refererUrl)
            } catch (e: Exception) {
                if (e is HttpException && e.code == 404) {
                    // その話数だけ欠番（削除済み等）の可能性があるのでスキップして続行
                    refererUrl = episodeUrl
                    continue
                }
                onProgress(
                    Progress.Failed(
                        "第${order}話の取得に失敗しました: ${e.message}\n" +
                            "（${savedCount}/${totalEpisodes}話まで保存済みです。しばらく待ってからもう一度「更新」を押してください）"
                    )
                )
                return
            }

            val epTitle = NovelScraper.extractEpisodeTitle(html)
            val body = NovelScraper.extractEpisodeBody(html)
            val epId = "ep_${order}_${episodeUrl.hashCode()}"

            storage.saveEpisodeText(novelId, epId, body)
            storage.appendEpisode(
                novelId,
                Episode(
                    id = epId,
                    title = epTitle,
                    url = episodeUrl,
                    order = order,
                    downloaded = true,
                    chapterName = chapterMap[order.toString()]
                )
            )
            savedCount++
            onProgress(Progress.EpisodeSaved(order, epTitle, totalEpisodes))

            refererUrl = episodeUrl
        }

        onProgress(Progress.Finished(savedCount))
    }

    /** カクヨム、または総話数が不明な場合：各話ページの「次のエピソード」リンクを辿る */
    private suspend fun downloadByFollowingLinks(
        novelId: String,
        site: Site,
        meta: NovelScraper.WorkMeta,
        existing: List<Episode>,
        startOrder: Int,
        totalEpisodes: Int?,
        savedCountStart: Int,
        chapterMap: Map<String, String>,
        onProgress: suspend (Progress) -> Unit
    ) {
        var order = startOrder
        var startUrl = meta.firstEpisodeUrl
        var refererUrl = meta.firstEpisodeUrl ?: ""

        val lastDownloaded = existing.filter { it.downloaded }.maxByOrNull { it.order }
        if (lastDownloaded != null) {
            startUrl = lastDownloaded.url // 次のエピソードリンクはこのページから再取得する
            refererUrl = lastDownloaded.url
        }

        if (startUrl == null) {
            onProgress(Progress.Failed("エピソードが見つかりませんでした"))
            return
        }

        var currentUrl: String? = startUrl
        var isFirstFetchOfResume = lastDownloaded != null
        var savedCount = savedCountStart

        while (currentUrl != null && order < 20000) {
            val html = try {
                NetworkClient.fetchHtml(currentUrl, referer = refererUrl)
            } catch (e: Exception) {
                if (site == Site.NAROU && e is HttpException && e.code == 404) {
                    // 総話数不明のなろう作品での旧フォールバック：404＝最終話到達とみなす
                    onProgress(Progress.Finished(savedCount))
                    return
                }
                onProgress(
                    Progress.Failed(
                        "第${order}話の取得に失敗しました: ${e.message}\n" +
                            "（${savedCount}話まで保存済みです。しばらく待ってからもう一度「更新」を押してください）"
                    )
                )
                return
            }

            if (!isFirstFetchOfResume) {
                val epTitle = NovelScraper.extractEpisodeTitle(html)
                val body = NovelScraper.extractEpisodeBody(html)
                val epId = "ep_${order}_${currentUrl.hashCode()}"

                storage.saveEpisodeText(novelId, epId, body)
                storage.appendEpisode(
                    novelId,
                    Episode(
                        id = epId,
                        title = epTitle,
                        url = currentUrl,
                        order = order,
                        downloaded = true,
                        chapterName = chapterMap[NovelScraper.normalizeEpisodeKey(currentUrl)]
                    )
                )
                savedCount++
                onProgress(Progress.EpisodeSaved(order, epTitle, totalEpisodes))
                order++
            } else {
                isFirstFetchOfResume = false
            }

            refererUrl = currentUrl
            currentUrl = NovelScraper.findNextEpisodeUrl(html, currentUrl, site)
        }

        onProgress(Progress.Finished(savedCount))
    }
}
