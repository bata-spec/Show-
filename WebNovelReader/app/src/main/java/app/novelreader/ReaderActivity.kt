package app.novelreader

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class ReaderActivity : AppCompatActivity() {

    private lateinit var storage: Storage
    private lateinit var novelId: String
    private lateinit var episodeId: String
    private lateinit var scrollView: ScrollView
    private lateinit var bodyView: TextView
    private lateinit var titleView: TextView
    private lateinit var ttsRow: View
    private lateinit var navBar: View
    private lateinit var btnTts: Button
    private lateinit var btnTtsStop: Button
    private lateinit var btnPrevEpisode: Button
    private lateinit var btnNextEpisode: Button

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var currentTitle: String = ""
    private var currentBodyText: String = ""
    private var downloadedEpisodes: List<Episode> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        storage = Storage(applicationContext)
        novelId = intent.getStringExtra("novelId") ?: return
        episodeId = intent.getStringExtra("episodeId") ?: return
        val title = intent.getStringExtra("episodeTitle") ?: ""
        val resumeScroll = intent.getBooleanExtra("resumeScroll", false)

        scrollView = findViewById(R.id.readerScroll)
        bodyView = findViewById(R.id.textBody)
        titleView = findViewById(R.id.textEpisodeTitle)
        ttsRow = findViewById(R.id.ttsRow)
        navBar = findViewById(R.id.navBar)
        btnTts = findViewById(R.id.btnTts)
        btnTtsStop = findViewById(R.id.btnTtsStop)
        btnPrevEpisode = findViewById(R.id.btnPrevEpisode)
        btnNextEpisode = findViewById(R.id.btnNextEpisode)

        downloadedEpisodes = storage.loadEpisodes(novelId).filter { it.downloaded }.sortedBy { it.order }

        setupTts()
        setupNavBar()

        loadEpisode(episodeId, title, resumeScroll)
    }

    private fun loadEpisode(episodeId: String, title: String, resumeScroll: Boolean) {
        this.episodeId = episodeId
        currentTitle = title
        currentBodyText = storage.loadEpisodeText(novelId, episodeId)

        titleView.text = currentTitle
        bodyView.text = currentBodyText
        scrollView.scrollTo(0, 0)

        // このエピソードを開いたことを「最後に読んだ話」として記録しておく
        storage.saveLastRead(novelId, episodeId, currentTitle, 0)

        if (resumeScroll) {
            val lastRead = storage.loadLastRead(novelId)
            if (lastRead != null && lastRead.episodeId == episodeId) {
                scrollView.post { scrollView.scrollTo(0, lastRead.scrollY) }
            }
        }

        updateNavButtonVisibility()
    }

    /** 端末に保存された読み上げON/OFF設定に合わせてTTSボタンの表示・非表示を切り替える */
    private fun applyTtsVisibility() {
        val enabled = Settings.isTtsEnabled(this)
        ttsRow.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) tts?.stop()
    }

    private fun setupTts() {
        applyTtsVisibility()

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.JAPANESE
            }
        }

        btnTts.setOnClickListener {
            if (!ttsReady) {
                Toast.makeText(this, "読み上げエンジンの準備中です", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            speak(currentBodyText)
        }

        btnTtsStop.setOnClickListener {
            tts?.stop()
        }
    }

    /** 画面をタップすると前へ／次へボタンのバーを表示・非表示するトグル */
    private fun setupNavBar() {
        val toggle = View.OnClickListener {
            navBar.visibility = if (navBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        scrollView.setOnClickListener(toggle)
        bodyView.setOnClickListener(toggle)

        btnPrevEpisode.setOnClickListener {
            currentEpisodeIndex().takeIf { it > 0 }?.let { index ->
                val prev = downloadedEpisodes[index - 1]
                loadEpisode(prev.id, prev.title, resumeScroll = false)
            }
        }
        btnNextEpisode.setOnClickListener {
            val index = currentEpisodeIndex()
            if (index in 0 until downloadedEpisodes.lastIndex) {
                val next = downloadedEpisodes[index + 1]
                loadEpisode(next.id, next.title, resumeScroll = false)
            }
        }
    }

    private fun currentEpisodeIndex(): Int = downloadedEpisodes.indexOfFirst { it.id == episodeId }

    /** 先に話が無い方向のボタンはそもそも表示しない */
    private fun updateNavButtonVisibility() {
        val index = currentEpisodeIndex()
        btnPrevEpisode.visibility = if (index > 0) View.VISIBLE else View.GONE
        btnNextEpisode.visibility = if (index in 0 until downloadedEpisodes.lastIndex) View.VISIBLE else View.GONE
    }

    /** TTSには1回の発話に文字数制限があるため、段落単位に分割してキューに積む */
    private fun speak(text: String) {
        val engine = tts ?: return
        engine.stop()
        val chunks = text.split("\n").filter { it.isNotBlank() }
        if (chunks.isEmpty()) return

        chunks.forEachIndexed { index, chunk ->
            val id = "chunk_$index"
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(chunk, mode, null, id)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::ttsRow.isInitialized) applyTtsVisibility()
    }

    override fun onStop() {
        super.onStop()
        if (!::novelId.isInitialized || !::episodeId.isInitialized || !::scrollView.isInitialized) return
        // 画面を離れるタイミングのスクロール位置を保存しておく（続きから読む用）
        storage.saveLastRead(novelId, episodeId, currentTitle, scrollView.scrollY)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
