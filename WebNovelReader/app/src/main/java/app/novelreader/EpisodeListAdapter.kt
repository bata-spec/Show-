package app.novelreader

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

sealed class EpisodeListItem {
    data class Header(val chapterName: String) : EpisodeListItem()
    data class EpisodeRow(val episode: Episode) : EpisodeListItem()
}

/**
 * 話一覧を「章見出し＋話」の形でグルーピング表示するためのアダプタ。
 * chapterNameが取得できていない話は見出し無しでそのまま並べる。
 */
class EpisodeListAdapter(private val context: Context) : BaseAdapter() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_EPISODE = 1
    }

    private var items: List<EpisodeListItem> = emptyList()

    fun submitEpisodes(episodes: List<Episode>) {
        val grouped = mutableListOf<EpisodeListItem>()
        var lastChapter: String? = null
        for (ep in episodes) {
            val chapter = ep.chapterName
            if (chapter != lastChapter) {
                if (chapter != null) grouped.add(EpisodeListItem.Header(chapter))
                lastChapter = chapter
            }
            grouped.add(EpisodeListItem.EpisodeRow(ep))
        }
        items = grouped
        notifyDataSetChanged()
    }

    fun episodeAt(position: Int): Episode? = (items.getOrNull(position) as? EpisodeListItem.EpisodeRow)?.episode

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): EpisodeListItem = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int =
        if (items[position] is EpisodeListItem.Header) TYPE_HEADER else TYPE_EPISODE

    override fun areAllItemsEnabled(): Boolean = false
    override fun isEnabled(position: Int): Boolean = items[position] is EpisodeListItem.EpisodeRow

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        return when (val item = items[position]) {
            is EpisodeListItem.Header -> {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_list_header, parent, false)
                view.findViewById<TextView>(R.id.textHeader).text = item.chapterName
                view
            }
            is EpisodeListItem.EpisodeRow -> {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_episode, parent, false)
                view.findViewById<TextView>(R.id.textEpisode).text = item.episode.title
                view
            }
        }
    }
}
