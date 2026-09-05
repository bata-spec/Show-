package app.novelreader

data class Novel(
    val id: String,       // 作品ごとのユニークID（サイト種別+作品ID）
    val title: String,
    val sourceUrl: String,
    val site: Site,
    val addedAt: Long
)

data class Episode(
    val id: String,        // エピソードごとのユニークID
    val title: String,
    val url: String,
    val order: Int,
    var downloaded: Boolean = false
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
