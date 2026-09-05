package app.novelreader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : Exception(message)

object NetworkClient {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

    /** ホストごとにCookieを保持する簡易CookieJar。サーバーが発行したセッションCookie等を自動的に引き継ぐ */
    private class SimpleCookieJar : CookieJar {
        private val store = HashMap<String, MutableList<Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { newCookie ->
                list.removeAll { it.name == newCookie.name }
                list.add(newCookie)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return store[url.host].orEmpty()
        }

        @Synchronized
        fun inject(host: String, rawCookieHeader: String) {
            val dummyUrl = "https://$host/".toHttpUrlOrNull() ?: return
            val cookies = rawCookieHeader.split(";").mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isEmpty()) return@mapNotNull null
                Cookie.Builder().domain(host).path("/").name(name).value(value).build()
            }
            saveFromResponse(dummyUrl, cookies)
        }
    }

    private val cookieJar = SimpleCookieJar()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    private var cookieStore: CookieStore? = null

    /** アプリ起動時に一度呼び出し、保存済みCookie（年齢確認突破分など）を読み込んでおく */
    fun init(context: Context) {
        val store = CookieStore(context)
        cookieStore = store
        store.loadAll().forEach { (host, cookie) -> cookieJar.inject(host, cookie) }
    }

    fun setCookie(host: String, cookie: String) {
        cookieJar.inject(host, cookie)
        cookieStore?.setCookie(host, cookie)
    }

    /**
     * リトライ・待機付きの取得。サイト側のレート制限に引っかかった場合、
     * 少し間隔を空けてから自動で再試行する。
     */
    suspend fun fetchHtml(url: String, referer: String? = null, maxRetries: Int = 2): String {
        var lastError: Exception? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                return fetchOnce(url, referer)
            } catch (e: Exception) {
                if (e is HttpException && e.code == 404) {
                    // 404（ページが存在しない＝欠番）はリトライしても結果が変わらないため、
                    // 待機せず即座にあきらめる。なろうの欠番スキップ判定がこれに依存しており、
                    // ここでリトライしていると欠番1件につき最大7.5秒×3回アクセス分無駄に待たされる。
                    throw e
                }
                lastError = e
                if (attempt < maxRetries) {
                    // 失敗のたびに待機時間を伸ばす（1回目失敗→2.5秒、2回目失敗→5秒 など）
                    delay(2500L * (attempt + 1))
                }
            }
        }
        throw lastError ?: Exception("不明なエラー: $url")
    }

    private suspend fun fetchOnce(url: String, referer: String?): String = withContext(Dispatchers.IO) {
        val host = try {
            java.net.URI(url).host ?: ""
        } catch (e: Exception) {
            ""
        }
        RateLimiter.waitForHost(host)

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "ja,en;q=0.8")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val code = response.code
                val hint = when (code) {
                    403, 429 -> "（アクセス制限の可能性があります。しばらく時間を置いてから再試行してください）"
                    else -> ""
                }
                throw HttpException(code, "HTTP $code: $url $hint")
            }
            response.body?.string() ?: throw Exception("空のレスポンス: $url")
        }
    }

    /**
     * 取得したHTMLが年齢確認ページっぽいかどうかの簡易判定。
     * workUrl/siteが分かる場合は、その作品自身のエピソードへのリンクが実在するかを
     * 先にチェックする。R18作品の本物のページにも「R18」「18歳以上」等の文言が
     * 含まれることがあり、キーワードの有無だけでは年齢確認ページと本物のページを
     * 区別できないため（本物のページなら必ずエピソードへのリンクがあるはず）。
     */
    fun looksLikeAgeGate(html: String, workUrl: String? = null, site: Site = Site.UNKNOWN): Boolean {
        val keywords = listOf("年齢確認", "18歳未満", "18歳以上", "age verification", "R18", "age-check")
        val hitCount = keywords.count { html.contains(it, ignoreCase = true) }
        if (hitCount == 0) return false

        if (workUrl != null && NovelScraper.pageHasWorkEpisodeLinks(html, workUrl, site)) {
            return false
        }

        return html.length < 20000
    }
}
