package app.novelreader

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class ScreenshotActivity : AppCompatActivity() {

    private var lastSavedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screenshot)

        val editUrl = findViewById<EditText>(R.id.editShotUrl)
        val statusText = findViewById<TextView>(R.id.statusTextShot)
        val imagePreview = findViewById<ImageView>(R.id.imagePreview)
        val webView = findViewById<WebView>(R.id.hiddenWebView)
        val btnShare = findViewById<Button>(R.id.btnShare)

        // 共有から呼ばれた場合はURLを引き継ぐ
        intent.getStringExtra("prefillUrl")?.let { editUrl.setText(it) }

        findViewById<Button>(R.id.btnCapture).setOnClickListener {
            val url = editUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "URLを入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val target = if (url.startsWith("http")) url else "https://$url"

            statusText.text = "読み込み中…（ページの長さによっては数秒〜数十秒かかります）"
            btnShare.visibility = android.view.View.GONE

            // スクショは小説の保存データとは別フォルダに保存する
            val dir = File(getExternalFilesDir(null), "screenshots")
            dir.mkdirs()
            val fileName = "shot_${System.currentTimeMillis()}.png"
            val outFile = File(dir, fileName)

            lifecycleScope.launch {
                try {
                    val saved = PageScreenshot.captureFullPage(this@ScreenshotActivity, webView, target, outFile)
                    lastSavedFile = saved
                    statusText.text = "保存しました: ${saved.absolutePath}"
                    val bmp = BitmapFactory.decodeFile(saved.absolutePath)
                    imagePreview.setImageBitmap(bmp)
                    btnShare.visibility = android.view.View.VISIBLE
                } catch (e: Exception) {
                    statusText.text = "失敗しました: ${e.message}"
                }
            }
        }

        btnShare.setOnClickListener {
            val file = lastSavedFile ?: return@setOnClickListener
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "画像を共有／保存"))
        }
    }
}
