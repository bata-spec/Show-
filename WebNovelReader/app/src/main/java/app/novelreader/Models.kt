package app.novelreader

data class Novel(
    val id: String,       // 作品ごとのユニークID（サイト種別+作品ID）
    val title: String,
    val sourceUrl: String,
    val site: Site,
    val addedAt: Long,
    val folderId: String? = null
)

data class Episode(
    val id: String,        // エピソードごとのユニークID
    val title: String,
    val url: String,
    val order: Int,
    var downloaded: Boolean = false,
    val chapterName: String? = null // 章立てグルーピング用（不明な場合はnull）
)

data class NovelFolder(
    val id: String,
    val name: String,
    val createdAt: Long
)

enum class Site {
    KAKUYOMU,
    NAROU,
    UNKNOWN
}

fun detectSite(url: String): Site {
    return when {
        url.contains("kakuyomu.jp") -> Site.KAKUYOMU
        url.contains("syosetu.com") -> Site.NAROU
        else -> Site.UNKNOWN
    }
}
