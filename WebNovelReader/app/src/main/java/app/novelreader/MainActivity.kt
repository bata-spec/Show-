package app.novelreader

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var storage: Storage
    private lateinit var editUrl: EditText
    private lateinit var statusText: TextView
    private lateinit var listNovels: ListView
    private lateinit var adapter: NovelListAdapter
    private lateinit var titleRow: android.view.View
    private lateinit var selectionRow: android.view.View
    private lateinit var textSelectionCount: TextView

    private var selectionMode = false
    private val selectedNovelIds = mutableSetOf<String>()

    private companion object {
        const val MENU_BATCH_ADD = 1
        const val MENU_SCREENSHOT = 2
        const val MENU_AGE_GATE = 3
        const val MENU_FOLDERS = 4
        const val MENU_TTS_TOGGLE = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        storage = Storage(applicationContext)
        NetworkClient.init(applicationContext)
        NotificationHelper.ensureChannels(applicationContext)
        UpdateScheduler.schedule(applicationContext)
        requestNotificationPermissionIfNeeded()

        editUrl = findViewById(R.id.editUrl)
        statusText = findViewById(R.id.statusText)
        listNovels = findViewById(R.id.listNovels)
        titleRow = findViewById(R.id.titleRow)
        selectionRow = findViewById(R.id.selectionRow)
        textSelectionCount = findViewById(R.id.textSelectionCount)
        adapter = NovelListAdapter(this)
        listNovels.adapter = adapter

        findViewById<android.widget.Button>(R.id.btnSelectionMove).setOnClickListener {
            showBulkMoveToFolderDialog()
        }
        findViewById<android.widget.Button>(R.id.btnSelectionDelete).setOnClickListener {
            showBulkDeleteDialog()
        }
        findViewById<android.widget.Button>(R.id.btnSelectionCancel).setOnClickListener {
            exitSelectionMode()
        }

        findViewById<android.widget.Button>(R.id.btnAdd).setOnClickListener {
            val url = editUrl.text.toString().trim()
            if (url.isNotBlank()) startDownload(url)
        }

        findViewById<android.widget.Button>(R.id.btnUpdateAll).setOnClickListener {
            updateAllNovels()
        }

        findViewById<android.widget.Button>(R.id.btnMenu).setOnClickListener { anchor ->
            showOverflowMenu(anchor)
        }

        listNovels.setOnItemClickListener { _, _, position, _ ->
            val novel = adapter.novelAt(position) ?: return@setOnItemClickListener
            if (selectionMode) {
                toggleSelection(novel.id)
            } else {
                val intent = Intent(this, EpisodeActivity::class.java)
                intent.putExtra("novelId", novel.id)
                startActivity(intent)
            }
        }

        listNovels.setOnItemLongClickListener { _, _, position, _ ->
            val novel = adapter.novelAt(position) ?: return@setOnItemLongClickListener true
            if (!selectionMode) enterSelectionMode()
            toggleSelection(novel.id)
            true
        }

        observeProgress()
        observePendingQueue()
        handleIncomingIntent(intent)
        refreshList()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val text: String? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!text.isNullOrBlank()) {
            val url = Regex("https?://\\S+").find(text)?.value ?: text.trim()
            editUrl.setText(url)
            startDownload(url)
        }
    }

    private fun startDownload(rawUrl: String) {
        val url = UrlNormalizer.normalize(rawUrl)
        val site = detectSite(url)
        if (site == Site.UNKNOWN) {
            Toast.makeText(this, "対応していないサイトです（カクヨム・なろう・ハーメルンのみ対応）", Toast.LENGTH_LONG).show()
            return
        }
        statusText.text = "取得中…（通知バーにも進捗が出ます）"
        DownloadService.enqueueMultiple(applicationContext, listOf(url))
        // 他の作品をダウンロード中だと、次にこの取得が始まるまでstatusTextがすぐ上書きされてしまい
        // 「反応が無い」ように見えるため、キューに積んだこと自体をToastでもはっきり伝える
        Toast.makeText(this, "キューに追加しました。順番にダウンロードされます", Toast.LENGTH_SHORT).show()
    }

    /** 上から1件ずつ、総話数確認→保存という流れを全作品分キューに積む（既存の直列キューに乗せるだけ） */
    private fun updateAllNovels() {
        val urls = storage.loadLibrary().sortedBy { it.addedAt }.map { it.sourceUrl }
        if (urls.isEmpty()) {
            Toast.makeText(this, "保存済みの作品がありません", Toast.LENGTH_SHORT).show()
            return
        }
        statusText.text = "全${urls.size}作品を上から順に確認・更新します…"
        DownloadService.enqueueMultiple(applicationContext, urls)
    }

    private fun showOverflowMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, MENU_BATCH_ADD, Menu.NONE, "複数URLをまとめて追加")
        popup.menu.add(Menu.NONE, MENU_SCREENSHOT, Menu.NONE, "URLをフルページスクショ保存")
        popup.menu.add(Menu.NONE, MENU_AGE_GATE, Menu.NONE, "年齢確認が必要なサイトを開く")
        popup.menu.add(Menu.NONE, MENU_FOLDERS, Menu.NONE, "フォルダ管理")
        val ttsLabel = if (Settings.isTtsEnabled(this)) "読み上げ機能: ON（タップでOFF）" else "読み上げ機能: OFF（タップでON）"
        popup.menu.add(Menu.NONE, MENU_TTS_TOGGLE, Menu.NONE, ttsLabel)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_BATCH_ADD -> startActivity(Intent(this, BatchAddActivity::class.java))
                MENU_SCREENSHOT -> {
                    val intent = Intent(this, ScreenshotActivity::class.java)
                    val current = editUrl.text.toString().trim()
                    if (current.isNotBlank()) intent.putExtra("prefillUrl", current)
                    startActivity(intent)
                }
                MENU_AGE_GATE -> {
                    val intent = Intent(this, AgeGateActivity::class.java)
                    val current = editUrl.text.toString().trim()
                    if (current.isNotBlank()) intent.putExtra("prefillUrl", current)
                    startActivity(intent)
                }
                MENU_FOLDERS -> showFolderManagementDialog()
                MENU_TTS_TOGGLE -> {
                    Settings.setTtsEnabled(this, !Settings.isTtsEnabled(this))
                }
            }
            true
        }
        popup.show()
    }

    // ---------- フォルダ管理 ----------

    private fun showFolderManagementDialog() {
        val folders = storage.loadFolders()
        val labels = (folders.map { it.name } + "＋ 新規フォルダを作成").toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("フォルダ管理")
            .setItems(labels) { _, index ->
                if (index == folders.size) {
                    promptCreateFolder(then = null)
                } else {
                    showFolderEditDialog(folders[index])
                }
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun showFolderEditDialog(folder: NovelFolder) {
        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setItems(arrayOf("名前を変更", "削除")) { _, which ->
                when (which) {
                    0 -> promptRenameFolder(folder)
                    1 -> {
                        AlertDialog.Builder(this)
                            .setTitle("「${folder.name}」を削除しますか？")
                            .setMessage("フォルダ内の作品は「未分類」に戻ります")
                            .setPositiveButton("削除") { _, _ ->
                                storage.deleteFolder(folder.id)
                                refreshList()
                            }
                            .setNegativeButton("キャンセル", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun promptRenameFolder(folder: NovelFolder) {
        val input = EditText(this)
        input.setText(folder.name)
        AlertDialog.Builder(this)
            .setTitle("フォルダ名を変更")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    storage.renameFolder(folder.id, name)
                    refreshList()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun promptCreateFolder(then: ((NovelFolder) -> Unit)?) {
        val input = EditText(this)
        input.hint = "フォルダ名"
        AlertDialog.Builder(this)
            .setTitle("新規フォルダを作成")
            .setView(input)
            .setPositiveButton("作成") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    val folder = storage.createFolder(name)
                    refreshList()
                    then?.invoke(folder)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---------- 複数選択モード（一括フォルダ移動・一括削除） ----------

    private fun enterSelectionMode() {
        selectionMode = true
        titleRow.visibility = android.view.View.GONE
        selectionRow.visibility = android.view.View.VISIBLE
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedNovelIds.clear()
        titleRow.visibility = android.view.View.VISIBLE
        selectionRow.visibility = android.view.View.GONE
        adapter.setSelectionState(false, emptySet())
    }

    private fun toggleSelection(novelId: String) {
        if (!selectedNovelIds.remove(novelId)) {
            selectedNovelIds.add(novelId)
        }
        if (selectedNovelIds.isEmpty()) {
            exitSelectionMode()
            return
        }
        textSelectionCount.text = "${selectedNovelIds.size}件選択中"
        adapter.setSelectionState(true, selectedNovelIds.toSet())
    }

    private fun showBulkDeleteDialog() {
        if (selectedNovelIds.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("削除しますか？")
            .setMessage("選択中の${selectedNovelIds.size}件を削除します")
            .setPositiveButton("削除") { _, _ ->
                selectedNovelIds.toList().forEach { storage.deleteNovel(it) }
                exitSelectionMode()
                refreshList()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showBulkMoveToFolderDialog() {
        if (selectedNovelIds.isEmpty()) return
        val folders = storage.loadFolders()
        val labels = (listOf("未分類") + folders.map { it.name } + "＋ 新規フォルダを作って移動").toTypedArray()
        val targetIds = selectedNovelIds.toList()

        AlertDialog.Builder(this)
            .setTitle("${targetIds.size}件をフォルダに移動")
            .setItems(labels) { _, index ->
                when {
                    index == 0 -> {
                        targetIds.forEach { storage.moveNovelToFolder(it, null) }
                        exitSelectionMode()
                        refreshList()
                    }
                    index == folders.size + 1 -> {
                        promptCreateFolder { folder ->
                            targetIds.forEach { storage.moveNovelToFolder(it, folder.id) }
                            exitSelectionMode()
                            refreshList()
                        }
                    }
                    else -> {
                        val folderId = folders[index - 1].id
                        targetIds.forEach { storage.moveNovelToFolder(it, folderId) }
                        exitSelectionMode()
                        refreshList()
                    }
                }
            }
            .show()
    }

    private fun observePendingQueue() {
        lifecycleScope.launch {
            DownloadBus.pendingUrls.collect { refreshList() }
        }
    }

    private fun observeProgress() {
        lifecycleScope.launch {
            DownloadBus.events.collect { event ->
                if (event == null) return@collect
                when (val p = event.progress) {
                    is DownloadManager.Progress.Started ->
                        statusText.text = "「${p.title}」を取得中…" + (p.total?.let { "（全${it}話）" } ?: "")
                    is DownloadManager.Progress.EpisodeSaved ->
                        statusText.text = if (p.total != null) {
                            "${p.order}/${p.total}話「${p.title}」を保存しました"
                        } else {
                            "第${p.order}話「${p.title}」を保存しました"
                        }
                    is DownloadManager.Progress.Finished -> {
                        statusText.text = "完了：${p.totalEpisodes}話を保存しました"
                        refreshList()
                    }
                    is DownloadManager.Progress.Failed -> {
                        statusText.text = "エラー: ${p.message}"
                        refreshList()
                    }
                }
            }
        }
    }

    private fun refreshList() {
        val novels = storage.loadLibrary()
        val folders = storage.loadFolders()
        val episodeCounts = novels.associate { it.id to storage.loadEpisodes(it.id).count { ep -> ep.downloaded } }
        adapter.submit(novels, folders, episodeCounts, DownloadBus.pendingUrls.value)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }
}
