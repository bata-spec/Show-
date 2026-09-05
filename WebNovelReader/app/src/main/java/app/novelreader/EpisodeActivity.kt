package app.novelreader

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class EpisodeActivity : AppCompatActivity() {

    private lateinit var storage: Storage
    private lateinit var novelId: String
    private lateinit var listEpisodes: ListView
    private lateinit var statusText: TextView
    private lateinit var adapter: EpisodeListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_episode)

        storage = Storage(applicationContext)
        NetworkClient.init(applicationContext)
        novelId = intent.getStringExtra("novelId") ?: run { finish(); return }

        val novel = storage.loadLibrary().firstOrNull { it.id == novelId }
        findViewById<TextView>(R.id.textNovelTitle).text = novel?.title ?: novelId

        listEpisodes = findViewById(R.id.listEpisodes)
        statusText = findViewById(R.id.statusText)
        adapter = EpisodeListAdapter(this)
        listEpisodes.adapter = adapter

        listEpisodes.setOnItemClickListener { _, _, position, _ ->
            val ep = adapter.episodeAt(position) ?: return@setOnItemClickListener
            val intent = Intent(this, ReaderActivity::class.java)
            intent.putExtra("novelId", novelId)
            intent.putExtra("episodeId", ep.id)
            intent.putExtra("episodeTitle", ep.title)
            startActivity(intent)
        }

        setupResumeButton()

        findViewById<Button>(R.id.btnDownloadAll).setOnClickListener {
            val url = novel?.sourceUrl ?: return@setOnClickListener
            statusText.text = "取得中…（通知バーにも進捗が出ます）"
            DownloadService.start(applicationContext, novelId, url)
        }

        observeProgress()
        refreshList()
    }

    private fun setupResumeButton() {
        val btnResume = findViewById<Button>(R.id.btnResume)
        val lastRead = storage.loadLastRead(novelId)
        if (lastRead != null) {
            btnResume.visibility = android.view.View.VISIBLE
            btnResume.text = "続きから読む（${lastRead.episodeTitle}）"
            btnResume.setOnClickListener {
                val intent = Intent(this, ReaderActivity::class.java)
                intent.putExtra("novelId", novelId)
                intent.putExtra("episodeId", lastRead.episodeId)
                intent.putExtra("episodeTitle", lastRead.episodeTitle)
                intent.putExtra("resumeScroll", true)
                startActivity(intent)
            }
        } else {
            btnResume.visibility = android.view.View.GONE
        }
    }

    private fun observeProgress() {
        lifecycleScope.launch {
            DownloadBus.events.collect { event ->
                if (event == null || event.novelId != novelId) return@collect
                when (val p = event.progress) {
                    is DownloadManager.Progress.Started ->
                        statusText.text = "取得中…" + (p.total?.let { "（全${it}話）" } ?: "")
                    is DownloadManager.Progress.EpisodeSaved -> {
                        statusText.text = if (p.total != null) {
                            "${p.order}/${p.total}話「${p.title}」を保存しました"
                        } else {
                            "第${p.order}話「${p.title}」を保存しました"
                        }
                        refreshList()
                    }
                    is DownloadManager.Progress.Finished -> {
                        statusText.text = "完了：合計${p.totalEpisodes}話"
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
        val episodes = storage.loadEpisodes(novelId).filter { it.downloaded }.sortedBy { it.order }
        // サイト側のタイトルに既に話数（「01話」等）が含まれていることが多いため、
        // タイトルをそのまま表示する（「第1話」等をこちらで重ねて付けない）。
        // 章立てが分かる場合はEpisodeListAdapter側で見出しを差し込んで表示する。
        adapter.submitEpisodes(episodes)
    }

    override fun onResume() {
        super.onResume()
        setupResumeButton()
    }
}
