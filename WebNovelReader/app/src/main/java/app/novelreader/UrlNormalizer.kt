package app.novelreader

/**
 * 共有メニューなどから渡されるURLは、サイトによって表記ゆれ（http/https、
 * トラッキングパラメータ、モバイル版ドメインなど）があるため、
 * 処理を始める前に正規化しておく。
 */
object UrlNormalizer {

    fun normalize(rawUrl: String): String {
        var url = rawUrl.trim()

        if (url.startsWith("http://")) {
            url = "https://" + url.removePrefix("http://")
        }

        // よくあるモバイル版ドメインをPC版に統一
        url = url.replace("://m.syosetu.com", "://ncode.syosetu.com")
        url = url.replace("://sp.syosetu.com", "://ncode.syosetu.com")

        // トラッキング用クエリパラメータ（?utm_xxx, ?fbclid など）を除去
        val qIndex = url.indexOf('?')
        if (qIndex >= 0) {
            val base = url.substring(0, qIndex)
            val query = url.substring(qIndex + 1)
            val keptParams = query.split("&").filter { param ->
                val key = param.substringBefore("=")
                key.isNotBlank() && !key.startsWith("utm_") && key != "fbclid" && key != "ref"
            }
            url = if (keptParams.isEmpty()) base else "$base?${keptParams.joinToString("&")}"
        }

        // フラグメント（#以降）は本文取得には不要なので除去
        val hashIndex = url.indexOf('#')
        if (hashIndex >= 0) {
            url = url.substring(0, hashIndex)
        }

        // なろうは末尾のスラッシュが無いと表示が崩れることがあるため統一しておく
        if (url.contains("syosetu.com/") && !url.endsWith("/")) {
            url = "$url/"
        }

        return url
    }
}
