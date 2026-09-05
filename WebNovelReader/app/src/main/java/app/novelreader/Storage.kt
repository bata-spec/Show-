package app.novelreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Storage(private val context: Context) {

    private val libraryFile = File(context.filesDir, "library.json")

    private fun novelDir(novelId: String): File =
        File(context.filesDir, "novels/$novelId").apply { mkdirs() }

    private fun episodesFile(novelId: String): File =
        File(novelDir(novelId), "episodes.json")

    private fun episodeTextFile(novelId: String, episodeId: String): File =
        File(novelDir(novelId), "$episodeId.txt")

    // ---------- 作品一覧 ----------

    fun loadLibrary(): List<Novel> {
        if (!libraryFile.exists()) return emptyList()
        val arr = JSONArray(libraryFile.readText())
        val list = mutableListOf<Novel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Novel(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    sourceUrl = o.getString("sourceUrl"),
                    site = Site.valueOf(o.optString("site", "UNKNOWN")),
                    addedAt = o.optLong("addedAt", System.currentTimeMillis())
                )
            )
        }
        return list.sortedByDescending { it.addedAt }
    }

    fun upsertNovel(novel: Novel) {
        val list = loadLibrary().filter { it.id != novel.id }.toMutableList()
        list.add(novel)
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(
                JSONObject().apply {
                    put("id", n.id)
                    put("title", n.title)
                    put("sourceUrl", n.sourceUrl)
                    put("site", n.site.name)
                    put("addedAt", n.addedAt)
                }
            )
        }
        libraryFile.writeText(arr.toString())
    }

    fun deleteNovel(novelId: String) {
        val list = loadLibrary().filter { it.id != novelId }
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(
                JSONObject().apply {
                    put("id", n.id)
                    put("title", n.title)
                    put("sourceUrl", n.sourceUrl)
                    put("site", n.site.name)
                    put("addedAt", n.addedAt)
                }
            )
        }
        libraryFile.writeText(arr.toString())
        novelDir(novelId).deleteRecursively()
    }

    // ---------- 話数リスト ----------

    fun loadEpisodes(novelId: String): List<Episode> {
        val f = episodesFile(novelId)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        val list = mutableListOf<Episode>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Episode(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    url = o.getString("url"),
                    order = o.getInt("order"),
                    downloaded = o.optBoolean("downloaded", false)
                )
            )
        }
        return list.sortedBy { it.order }
    }

    fun saveEpisodes(novelId: String, episodes: List<Episode>) {
        val arr = JSONArray()
        episodes.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("title", e.title)
                    put("url", e.url)
                    put("order", e.order)
                    put("downloaded", e.downloaded)
                }
            )
        }
        episodesFile(novelId).writeText(arr.toString())
    }

    fun appendEpisode(novelId: String, episode: Episode) {
        val list = loadEpisodes(novelId).toMutableList()
        list.removeAll { it.id == episode.id }
        list.add(episode)
        saveEpisodes(novelId, list)
    }

    // ---------- 本文 ----------

    fun saveEpisodeText(novelId: String, episodeId: String, text: String) {
        episodeTextFile(novelId, episodeId).writeText(text)
    }

    fun loadEpisodeText(novelId: String, episodeId: String): String {
        val f = episodeTextFile(novelId, episodeId)
        return if (f.exists()) f.readText() else "（本文が保存されていません）"
    }

    // ---------- しおり（続きから読む） ----------

    private fun lastReadFile(novelId: String): File = File(novelDir(novelId), "last_read.json")

    data class LastRead(val episodeId: String, val episodeTitle: String, val scrollY: Int, val updatedAt: Long)

    fun saveLastRead(novelId: String, episodeId: String, episodeTitle: String, scrollY: Int) {
        val o = JSONObject().apply {
            put("episodeId", episodeId)
            put("episodeTitle", episodeTitle)
            put("scrollY", scrollY)
            put("updatedAt", System.currentTimeMillis())
        }
        lastReadFile(novelId).writeText(o.toString())
    }

    fun loadLastRead(novelId: String): LastRead? {
        val f = lastReadFile(novelId)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            LastRead(
                episodeId = o.getString("episodeId"),
                episodeTitle = o.optString("episodeTitle", ""),
                scrollY = o.optInt("scrollY", 0),
                updatedAt = o.optLong("updatedAt", 0L)
            )
        } catch (e: Exception) {
            null
        }
    }
}
