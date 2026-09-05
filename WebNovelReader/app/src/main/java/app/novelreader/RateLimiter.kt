package app.novelreader

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * ドメインごとに最後にアクセスした時刻を記録し、サイトごとに設定した最短間隔が
 * 空くまで自動的に待つ。サイトによって適切な間隔が違う（重いサイトほど間隔を空けたい）ため、
 * 一律の待機時間よりも狙い撃ちでアクセス制限を回避しやすくする狙い。
 */
object RateLimiter {

    private val intervalMsByHost = mapOf(
        "kakuyomu.jp" to 2200L,
        "ncode.syosetu.com" to 1300L,
        "novel18.syosetu.com" to 1300L
    )
    private const val DEFAULT_INTERVAL_MS = 2000L

    private val lastAccessAt = HashMap<String, Long>()

    @Synchronized
    private fun intervalFor(host: String): Long {
        val base = intervalMsByHost.entries.firstOrNull { host.endsWith(it.key) }?.value ?: DEFAULT_INTERVAL_MS
        // 規則的すぎるアクセスに見えないよう、多少のランダムな揺らぎを足す
        return base + Random.nextLong(0, 400)
    }

    suspend fun waitForHost(host: String) {
        val waitMs = synchronized(this) {
            val now = System.currentTimeMillis()
            val next = (lastAccessAt[host] ?: 0L)
            val interval = intervalFor(host)
            val wait = (next + interval) - now
            lastAccessAt[host] = now + maxOf(wait, 0L)
            wait
        }
        if (waitMs > 0) delay(waitMs)
    }
}
