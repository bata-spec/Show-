package app.novelreader

import android.os.Bundle
import android.speech.tts.TextToSpeech
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

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        storage = Storage(applicationContext)
        novelId = intent.getStringExtra("novelId") ?: return
        episodeId = intent.getStringExtra("episodeId") ?: return
        val title = intent.getStringExtra("episodeTitle") ?: ""
        val resumeScroll = intent.getBooleanExtra("resumeScroll", false)

        scrollView = findViewById(R.id.readerScroll)
        val bodyView = findViewById<TextView>(R.id.textBody)

        findViewById<TextView>(R.id.textEpisodeTitle).text = title
        val bodyText = storage.loadEpisodeText(novelId, episodeId)
        bodyView.text = bodyText

        // このエピソードを開いたことを「最後に読んだ話」として記録しておく
        storage.saveLastRead(novelId, episodeId, title, 0)

        if (resumeScroll) {
            val lastRead = storage.loadLastRead(novelId)
            if (lastRead != null && lastRead.episodeId == episodeId) {
                scrollView.post { scrollView.scrollTo(0, lastRead.scrollY) }
            }
        }

        setupTts(bodyText)
    }

    private fun setupTts(bodyText: String) {
        val btnStart = findViewById<Button>(R.id.btnTts)
        val btnStop = findViewById<Button>(R.id.btnTtsStop)

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.JAPANESE
            }
        }

        btnStart.setOnClickListener {
            if (!ttsReady) {
                Toast.makeText(this, "読み上げエンジンの準備中です", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            speak(bodyText)
        }

        btnStop.setOnClickListener {
            tts?.stop()
        }
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

    override fun onStop() {
        super.onStop()
        if (!::novelId.isInitialized || !::episodeId.isInitialized || !::scrollView.isInitialized) return
        // 画面を離れるタイミングのスクロール位置を保存しておく（続きから読む用）
        val title = intent.getStringExtra("episodeTitle") ?: ""
        storage.saveLastRead(novelId, episodeId, title, scrollView.scrollY)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
