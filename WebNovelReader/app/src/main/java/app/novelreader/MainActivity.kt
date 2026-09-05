package app.novelreader

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
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

        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            val url = editUrl.text.toString().trim()
            if (url.isNotBlank()) startDownload(url)
        }

        findViewById<Button>(R.id.btnScreenshot).setOnClickListener {
            val intent = Intent(this, ScreenshotActivity::class.java)
            val current = editUrl.text.toString().trim()
            if (current.isNotBlank()) intent.putExtra("prefillUrl", current)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnAgeGate).setOnClickListener {
            val intent = Intent(this, AgeGateActivity::class.java)
            val current = editUrl.text.toString().trim()
            if (current.isNotBlank()) intent.putExtra("prefillUrl", current)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnBatchAdd).setOnClickListener {
            startActivity(Intent(this, BatchAddActivity::class.java))
        }

        listNovels.setOnItemClickListener { _, _, position, _ ->
            val novel = storage.loadLibrary()[position]
            val intent = Intent(this, EpisodeActivity::class.java)
            intent.putExtra("novelId", novel.id)
            startActivity(intent)
        }

        listNovels.setOnItemLongClickListener { _, _, position, _ ->
            val novel = storage.loadLibrary()[position]
            AlertDialog.Builder(this)
                .setTitle("削除しますか？")
                .setMessage(novel.title)
                .setPositiveButton("削除") { _, _ ->
                    storage.deleteNovel(novel.id)
                    refreshList()
                }
                .setNegativeButton("キャンセル", null)
                .show()
            true
        }

        observeProgress()
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
            Toast.makeText(this, "対応していないサイトです（カクヨム・なろうのみ対応）", Toast.LENGTH_LONG).show()
            return
        }
        val novelId = "${site.name}_${NovelScraper.extractWorkId(url, site)}"
        statusText.text = "取得中…（通知バーにも進捗が出ます）"
        DownloadService.start(applicationContext, novelId, url)
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
        val items = novels.map { n ->
            val epCount = storage.loadEpisodes(n.id).count { it.downloaded }
            "${n.title}\n（${n.site.name} / ${epCount}話保存済み）"
        }
        listNovels.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }
}
