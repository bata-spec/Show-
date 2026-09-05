package app.novelreader

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.TextView

sealed class NovelListItem {
    data class Header(val label: String) : NovelListItem()
    data class NovelRow(val novel: Novel, val episodeCount: Int) : NovelListItem()
    /** キューに追加されたが、まだ順番が回ってきていないURL（作品情報はまだ取得できていない） */
    data class PendingRow(val url: String) : NovelListItem()
}

/** 保存済み作品一覧を、フォルダ見出し付きでグルーピング表示するためのアダプタ */
class NovelListAdapter(private val context: Context) : BaseAdapter() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_NOVEL = 1
    }

    private var items: List<NovelListItem> = emptyList()
    private var selectionMode = false
    private var selectedIds: Set<String> = emptySet()

    /** 一括操作用の選択モードの表示状態を更新する */
    fun setSelectionState(enabled: Boolean, selected: Set<String>) {
        selectionMode = enabled
        selectedIds = selected
        notifyDataSetChanged()
    }

    fun submit(
        novels: List<Novel>,
        folders: List<NovelFolder>,
        episodeCounts: Map<String, Int>,
        pendingUrls: Set<String> = emptySet()
    ) {
        val grouped = mutableListOf<NovelListItem>()

        if (pendingUrls.isNotEmpty()) {
            grouped.add(NovelListItem.Header("追加待ち（順番にダウンロードされます）"))
            pendingUrls.forEach { grouped.add(NovelListItem.PendingRow(it)) }
        }

        val validFolderIds = folders.map { it.id }.toSet()

        for (folder in folders) {
            val inFolder = novels.filter { it.folderId == folder.id }
            if (inFolder.isEmpty()) continue
            grouped.add(NovelListItem.Header(folder.name))
            inFolder.forEach { grouped.add(NovelListItem.NovelRow(it, episodeCounts[it.id] ?: 0)) }
        }

        val unassigned = novels.filter { it.folderId == null || it.folderId !in validFolderIds }
        if (unassigned.isNotEmpty()) {
            if (folders.isNotEmpty()) grouped.add(NovelListItem.Header("未分類"))
            unassigned.forEach { grouped.add(NovelListItem.NovelRow(it, episodeCounts[it.id] ?: 0)) }
        }

        items = grouped
        notifyDataSetChanged()
    }

    fun novelAt(position: Int): Novel? = (items.getOrNull(position) as? NovelListItem.NovelRow)?.novel

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): NovelListItem = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int =
        if (items[position] is NovelListItem.Header) TYPE_HEADER else TYPE_NOVEL

    override fun areAllItemsEnabled(): Boolean = false
    // 追加待ち（PendingRow）は作品IDがまだ無いため、タップやフォルダ移動の対象にはしない
    override fun isEnabled(position: Int): Boolean = items[position] is NovelListItem.NovelRow

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        return when (val item = items[position]) {
            is NovelListItem.Header -> {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_list_header, parent, false)
                view.findViewById<TextView>(R.id.textHeader).text = item.label
                view
            }
            is NovelListItem.NovelRow -> {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_novel, parent, false)
                view.findViewById<TextView>(R.id.textTitle).text = item.novel.title
                view.findViewById<TextView>(R.id.textSub).text =
                    "${item.novel.site.name} / ${item.episodeCount}話保存済み"
                val checkBox = view.findViewById<CheckBox>(R.id.checkSelect)
                checkBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
                checkBox.isChecked = item.novel.id in selectedIds
                view
            }
            is NovelListItem.PendingRow -> {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_novel, parent, false)
                view.findViewById<TextView>(R.id.textTitle).text = "確認中…（キュー待ち）"
                view.findViewById<TextView>(R.id.textSub).text = item.url
                view.findViewById<CheckBox>(R.id.checkSelect).visibility = View.GONE
                view
            }
        }
    }
}
