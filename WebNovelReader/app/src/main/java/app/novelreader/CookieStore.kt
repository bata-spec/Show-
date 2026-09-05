package app.novelreader

import android.content.Context

/**
 * 年齢確認などを通過した後にサイトが発行するCookieを、ドメインごとに保存しておく。
 * 一度WebViewで年齢確認を突破すれば、以降はOkHttpでの高速なスクレイピング時に
 * このCookieを付与することで再確認を回避できる。
 */
class CookieStore(context: Context) {

    private val prefs = context.getSharedPreferences("cookie_store", Context.MODE_PRIVATE)

    fun getCookie(host: String): String? = prefs.getString(host, null)

    fun setCookie(host: String, cookie: String) {
        prefs.edit().putString(host, cookie).apply()
    }

    fun loadAll(): Map<String, String> {
        return prefs.all.mapNotNull { (k, v) ->
            if (v is String) k to v else null
        }.toMap()
    }
}
