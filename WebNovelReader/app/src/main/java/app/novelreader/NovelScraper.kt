package app.novelreader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * カクヨム・なろうのページ解析を担当する。
 *
 * 方針：
 *  - 作品ページから「最初のエピソードURL」を見つける
 *  - 各エピソードページを1話ずつ辿り、「次のエピソード」へのリンクを探して連鎖的に巡回する
 *    （目次が長い作品でもページネーションを気にせず全話たどれる）
 *  - 本文抽出はサイトのクラス名に依存しすぎないよう、
 *    「同じ親要素の下に<p>タグが最も多く連続している場所」をヒューリスティックに本文とみなす
 *    （サイト側のデザイン変更に対してある程度耐性を持たせるため）
 */
object NovelScraper {

    data class WorkMeta(val title: String, val firstEpisodeUrl: String?)

    fun extractWorkId(url: String, site: Site): String {
        return when (site) {
            Site.KAKUYOMU -> Regex("works/(\\d+)").find(url)?.groupValues?.get(1) ?: url.hashCode().toString()
            Site.NAROU -> Regex("syosetu\\.com/([a-zA-Z0-9]+)").find(url)?.groupValues?.get(1) ?: url.hashCode().toString()
            Site.UNKNOWN -> url.hashCode().toString()
        }
    }

    fun parseWorkMeta(html: String, workUrl: String, site: Site): WorkMeta {
        val doc = Jsoup.parse(html, workUrl)
        val title = extractTitleFromHead(doc)

        val firstEpisode: String? = when (site) {
            Site.KAKUYOMU -> {
                doc.select("a[href]").firstOrNull { a ->
                    Regex("/works/\\d+/episodes/\\d+/?$").containsMatchIn(a.attr("abs:href"))
                }?.attr("abs:href")
            }
            Site.NAROU -> {
                val ncode = extractWorkId(workUrl, site)
                "https://ncode.syosetu.com/$ncode/1/"
            }
            Site.UNKNOWN -> null
        }

        return WorkMeta(title, firstEpisode)
    }

    fun extractEpisodeTitle(html: String): String {
        val doc = Jsoup.parse(html)
        return extractTitleFromHead(doc)
    }

    /**
     * 作品ページから総話数を読み取る（分かる場合のみ）。
     * なろうは「全600エピソード」のような表記があるため高精度に取れる。
     * カクヨムは表記が無い場合も多いのでベストエフォート。
     */
    fun extractTotalEpisodes(html: String, site: Site): Int? {
        val text = Jsoup.parse(html).text()
        val patterns = when (site) {
            Site.NAROU -> listOf(Regex("全\\s*(\\d+)\\s*エピソード"), Regex("全\\s*(\\d+)\\s*話"))
            Site.KAKUYOMU -> listOf(Regex("全\\s*(\\d+)\\s*話"), Regex("全\\s*(\\d+)\\s*エピソード"))
            Site.UNKNOWN -> emptyList()
        }
        for (p in patterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1].toIntOrNull()
        }
        return null
    }

    /**
     * 作品ページ（TOC）から章立て情報を読み取る。
     * 「序章」「地位向上編」等の見出し要素と、そのすぐ後に続くエピソードへのリンクを
     * ドキュメント順に走査して対応付ける。見出しのクラス名は"chapter"を含むもの
     * （なろう旧デザインのchapter_title、新デザインのp-eplist__chapter-title、
     * カクヨムのwidget-toc-chapter-title等）をヒューリスティックに拾う方式のため、
     * サイト側のデザイン変更にもある程度耐性がある。
     * 戻り値のキーは、なろうは話数（"1","2",…）、カクヨムはエピソードURL（正規化済み）。
     * 章が判定できないエピソードはマップに含まれない（呼び出し側でnull扱いにする）。
     */
    fun parseChapterMap(html: String, workUrl: String, site: Site): Map<String, String> {
        if (site == Site.UNKNOWN) return emptyMap()
        val doc = Jsoup.parse(html, workUrl)
        val episodeUrlPattern = when (site) {
            Site.NAROU -> Regex("syosetu\\.com/[a-zA-Z0-9]+/(\\d+)/?(?:[?#].*)?$")
            Site.KAKUYOMU -> Regex("/works/\\d+/episodes/\\d+/?$")
            Site.UNKNOWN -> return emptyMap()
        }

        val result = LinkedHashMap<String, String>()
        var currentChapter: String? = null

        for (el in doc.select("*")) {
            val isHeading = el.className().contains("chapter", ignoreCase = true) &&
                el.select("a[href]").isEmpty()
            if (isHeading) {
                val text = el.text().trim()
                if (text.isNotEmpty() && text.length <= 60) currentChapter = text
                continue
            }
            if (el.tagName() == "a" && el.hasAttr("href")) {
                val chapter = currentChapter ?: continue
                val href = el.attr("abs:href")
                if (!episodeUrlPattern.containsMatchIn(href)) continue
                val key = when (site) {
                    Site.NAROU -> episodeUrlPattern.find(href)!!.groupValues[1]
                    else -> normalizeEpisodeKey(href)
                }
                result.putIfAbsent(key, chapter)
            }
        }
        return result
    }

    /** チャプターマップのキーとURLの表記ゆれ（末尾スラッシュ・クエリ）を揃えるための正規化 */
    fun normalizeEpisodeKey(url: String): String = url.substringBefore("?").trimEnd('/')

    /** <title>タグから " - サイト名" 等を取り除いてざっくり見出しだけにする */
    private fun extractTitleFromHead(doc: Document): String {
        val raw = doc.title().ifBlank {
            doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "無題"
        }
        // "エピソードタイトル - 作品タイトル（作者） - カクヨム" のような形式の先頭部分だけ使う
        val firstPart = raw.split(" - ").firstOrNull()?.trim() ?: raw
        // 末尾の「（作者名）」を取り除く（作品タイトル取得時用）
        return firstPart.replace(Regex("[（(][^（）()]{1,40}[）)]$"), "").trim()
    }

    fun findNextEpisodeUrl(html: String, currentUrl: String, site: Site): String? {
        val doc = Jsoup.parse(html, currentUrl)
        return when (site) {
            Site.KAKUYOMU -> {
                // <link rel="next"> があれば最優先
                doc.selectFirst("link[rel=next]")?.attr("abs:href")?.takeIf { it.isNotBlank() }
                    ?: doc.select("a[href]").firstOrNull { a ->
                        a.text().contains("次のエピソード") &&
                            Regex("/episodes/\\d+/?$").containsMatchIn(a.attr("abs:href"))
                    }?.attr("abs:href")
            }
            Site.NAROU -> {
                // なろうはURLが /ncode/番号/ という連番構成なので次の番号を機械的に組み立てる
                val m = Regex("syosetu\\.com/([a-zA-Z0-9]+)/(\\d+)/?").find(currentUrl)
                if (m != null) {
                    val ncode = m.groupValues[1]
                    val num = m.groupValues[2].toInt()
                    "https://ncode.syosetu.com/$ncode/${num + 1}/"
                } else null
            }
            Site.UNKNOWN -> null
        }
    }

    /**
     * 本文抽出：<p>タグが最も密集している親要素を本文コンテナとみなして中身を取り出す。
     * サイトのCSSクラス名が変わっても壊れにくいようにするための汎用ロジック。
     */
    fun extractEpisodeBody(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script,style,noscript").remove()

        val allParagraphs = doc.select("p")
        if (allParagraphs.isEmpty()) return doc.body()?.text().orEmpty()

        val byParent = HashMap<Element, MutableList<Element>>()
        for (p in allParagraphs) {
            val parent = p.parent() ?: continue
            byParent.getOrPut(parent) { mutableListOf() }.add(p)
        }

        // 「子要素の数が多い」かつ「合計文字数が多い」親を本文コンテナとみなす
        val bestEntry = byParent.entries.maxByOrNull { (_, children) ->
            val totalLen = children.sumOf { it.text().length }
            children.size * 10 + totalLen
        } ?: return doc.body()?.text().orEmpty()

        val sb = StringBuilder()
        for (p in bestEntry.value) {
            val text = p.text()
            sb.append(text)
            sb.append("\n")
            // 空段落（全角スペースのみ等）は行間として1行だけ残す
        }
        return sb.toString().trim()
    }
}
