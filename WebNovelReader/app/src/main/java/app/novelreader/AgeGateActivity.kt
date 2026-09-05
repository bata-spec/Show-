package app.novelreader

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URI

/**
 * 年齢確認（アダルト作品などのゲート）があるサイトを、実際に操作可能なWebViewで開く画面。
 * ユーザーが手動で「はい」等をタップして通過した後、そのドメインのCookieを保存し、
 * 以降はNetworkClient（OkHttp）経由のスクレイピングでそのCookieを使い回す。
 */
class AgeGateActivity : AppCompatActivity() {

    private var currentUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_age_gate)

        val editUrl = findViewById<EditText>(R.id.editGateUrl)
        val statusText = findViewById<TextView>(R.id.statusGate)
        val webView = findViewById<WebView>(R.id.gateWebView)

        intent.getStringExtra("prefillUrl")?.let { editUrl.setText(it) }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                currentUrl = url
                statusText.text = "表示中: $url"
            }
        }

        findViewById<Button>(R.id.btnOpenGate).setOnClickListener {
            val url = editUrl.text.toString().trim()
            if (url.isBlank()) return@setOnClickListener
            val target = if (url.startsWith("http")) url else "https://$url"
            currentUrl = target
            webView.loadUrl(target)
        }

        findViewById<Button>(R.id.btnConfirmDone).setOnClickListener {
            if (currentUrl.isBlank()) {
                Toast.makeText(this, "先にサイトを開いてください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cookie = CookieManager.getInstance().getCookie(currentUrl)
            if (cookie.isNullOrBlank()) {
                statusText.text = "Cookieが取得できませんでした。ページ内で確認操作をしてから再度お試しください。"
                return@setOnClickListener
            }
            val host = try {
                URI(currentUrl).host ?: ""
            } catch (e: Exception) {
                ""
            }
            if (host.isBlank()) {
                statusText.text = "URLの解析に失敗しました"
                return@setOnClickListener
            }
            NetworkClient.setCookie(host, cookie)
            statusText.text = "保存しました（$host）。この画面を閉じて、もう一度作品を追加してください。"
            Toast.makeText(this, "年齢確認情報を保存しました", Toast.LENGTH_SHORT).show()
        }
    }
}
