package app.novelreader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 通知チャンネルを目的別に分けておくことで、ユーザーが「進捗はミュートしたいけど
 * 新着だけは通知してほしい」といった細かい制御をOS標準の設定画面から行えるようにする。
 */
object NotificationHelper {

    const val CHANNEL_PROGRESS = "progress"   // ダウンロード中の進捗（低優先度・頻繁に更新）
    const val CHANNEL_RESULT = "result"       // ダウンロード完了・失敗の結果（1回だけ）
    const val CHANNEL_NEW_EPISODE = "new_episode" // バックグラウンド自動更新で見つかった新着

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "ダウンロード進捗", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "ダウンロード結果", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NEW_EPISODE, "新着エピソード通知", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun progressBuilder(context: Context, text: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("小説リーダー")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    fun resultBuilder(context: Context, title: String, text: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    fun newEpisodeBuilder(context: Context, title: String, text: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_NEW_EPISODE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }
}
