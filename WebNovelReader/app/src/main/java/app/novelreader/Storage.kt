package app.novelreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Storage(private val context: Context) {

    private val libraryFile = File(context.filesDir, "library.json")
    private val foldersFile = File(context.filesDir, "folders.json")

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
                    addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                    folderId = o.optString("folderId", "").ifBlank { null }
                )
            )
        }
        return list.sortedByDescending { it.addedAt }
    }

    private fun writeLibrary(list: List<Novel>) {
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(
                JSONObject().apply {
                    put("id", n.id)
                    put("title", n.title)
                    put("sourceUrl", n.sourceUrl)
                    put("site", n.site.name)
                    put("addedAt", n.addedAt)
                    put("folderId", n.folderId ?: "")
                }
            )
        }
        libraryFile.writeText(arr.toString())
    }

    fun upsertNovel(novel: Novel) {
        val existing = loadLibrary().firstOrNull { it.id == novel.id }
        // フォルダ分けは巡回ダウンロード等の再登録で失われないよう既存の値を引き継ぐ
        val toSave = if (existing != null && novel.folderId == null) novel.copy(folderId = existing.folderId) else novel
        val list = loadLibrary().filter { it.id != novel.id }.toMutableList()
        list.add(toSave)
        writeLibrary(list)
    }

    fun deleteNovel(novelId: String) {
        writeLibrary(loadLibrary().filter { it.id != novelId })
        novelDir(novelId).deleteRecursively()
    }

    fun moveNovelToFolder(novelId: String, folderId: String?) {
        val list = loadLibrary().map { if (it.id == novelId) it.copy(folderId = folderId) else it }
        writeLibrary(list)
    }

    // ---------- フォルダ ----------

    fun loadFolders(): List<NovelFolder> {
        if (!foldersFile.exists()) return emptyList()
        val arr = JSONArray(foldersFile.readText())
        val list = mutableListOf<NovelFolder>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                NovelFolder(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }
        return list.sortedBy { it.createdAt }
    }

    private fun writeFolders(list: List<NovelFolder>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    put("createdAt", f.createdAt)
                }
            )
        }
        foldersFile.writeText(arr.toString())
    }

    fun createFolder(name: String): NovelFolder {
        val folder = NovelFolder(id = "folder_${System.currentTimeMillis()}", name = name, createdAt = System.currentTimeMillis())
        writeFolders(loadFolders() + folder)
        return folder
    }

    fun renameFolder(folderId: String, newName: String) {
        writeFolders(loadFolders().map { if (it.id == folderId) it.copy(name = newName) else it })
    }

    fun deleteFolder(folderId: String) {
        writeFolders(loadFolders().filter { it.id != folderId })
        // フォルダに属していた作品は未分類に戻す
        writeLibrary(loadLibrary().map { if (it.folderId == folderId) it.copy(folderId = null) else it })
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
                    downloaded = o.optBoolean("downloaded", false),
                    chapterName = o.optString("chapterName", "").ifBlank { null }
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
                    put("chapterName", e.chapterName ?: "")
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
