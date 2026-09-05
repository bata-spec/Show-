package app.novelreader

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class BatchAddActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_add)

        val editUrls = findViewById<EditText>(R.id.editBatchUrls)

        findViewById<Button>(R.id.btnBatchStart).setOnClickListener {
            val urls = editUrls.text.toString()
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            if (urls.isEmpty()) {
                Toast.makeText(this, "URLを1つ以上入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            DownloadService.enqueueMultiple(applicationContext, urls)
            Toast.makeText(this, "${urls.size}件をキューに追加しました。通知バーで進捗を確認できます", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
