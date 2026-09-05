package app.novelreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebViewでページを開き、ページ全体（縦に長い分も含めて）をBitmapとして撮って
 * PNGファイルに保存する。Node.js/Puppeteerのfull-page screenshotに相当する処理を
 * Android上でネイティブに行う。
 */
object PageScreenshot {

    /**
     * @param webView 呼び出し側で生成し、画面に表示してもしなくても良いWebView
     * @param url 撮影したいページのURL
     * @param outputFile 保存先PNGファイル
     */
    suspend fun captureFullPage(
        context: Context,
        webView: WebView,
        url: String,
        outputFile: File
    ): File = suspendCancellableCoroutine { cont ->

        // Bitmapへの描画を確実にするためソフトウェアレイヤーにする
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        webView.settings.javaScriptEnabled = true
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                // 遅延読み込み(Lazy Load)対策：一度最下部までスクロールしてから
                // 少し待ち、画像などを読み込ませてからキャプチャする
                view.evaluateJavascript(
                    "window.scrollTo(0, document.body.scrollHeight);"
                ) {
                    view.postDelayed({
                        view.evaluateJavascript("window.scrollTo(0, 0);") {
                            view.postDelayed({
                                try {
                                    val file = drawToFile(view, outputFile)
                                    if (cont.isActive) cont.resume(file)
                                } catch (e: Exception) {
                                    if (cont.isActive) cont.resumeWithException(e)
                                }
                            }, 500)
                        }
                    }, 1200)
                }
            }

            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (cont.isActive) cont.resumeWithException(Exception("読み込み失敗: $description"))
            }
        }

        // 画面の外に置く場合でも、幅・高さを明示的に測定/レイアウトしないと
        // 正しく描画されないため、まずは一定幅でロードする
        val displayWidth = context.resources.displayMetrics.widthPixels
        webView.layout(0, 0, displayWidth, 1)
        webView.loadUrl(url)
    }

    private fun drawToFile(webView: WebView, outputFile: File): File {
        val width = webView.width.takeIf { it > 0 } ?: webView.resources.displayMetrics.widthPixels
        // contentHeightはCSSピクセル単位なのでdensityを掛けて実ピクセルに変換
        val contentHeightPx = (webView.contentHeight * webView.resources.displayMetrics.density).toInt()
        val height = if (contentHeightPx > 0) contentHeightPx else webView.height.coerceAtLeast(1)

        webView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val finalHeight = webView.measuredHeight.takeIf { it > 0 } ?: height
        webView.layout(0, 0, width, finalHeight)

        val bitmap = Bitmap.createBitmap(width, finalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        webView.draw(canvas)

        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return outputFile
    }
}
