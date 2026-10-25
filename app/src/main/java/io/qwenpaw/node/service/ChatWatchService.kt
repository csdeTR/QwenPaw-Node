package io.qwenpaw.node.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.qwenpaw.node.MainActivity
import io.qwenpaw.node.R

/**
 * 前台服务占位。
 *
 * 设计说明：QwenPaw 的 run 在服务端执行，客户端断线不影响生成，
 * 因此这里**不需要**维持长连接，只负责在与服务器会话运行期间显示
 * 一条常驻通知并降低被系统回收的概率。真正的状态同步由界面层轮询
 * `chats/{id}/status` 与 `push-messages` 完成。
 */
class ChatWatchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "正在等待回复"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "服务端正在生成，可随时切回查看"
        createChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_watch_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_watch_desc)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "qwenpaw_chat_watch"
        private const val DONE_CHANNEL_ID = "qwenpaw_chat_done"
        private const val NOTIFICATION_ID = 1001
        private const val DONE_NOTIFICATION_ID = 1002
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        fun start(context: Context, title: String, text: String) {
            val intent = Intent(context, ChatWatchService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChatWatchService::class.java))
        }

        /**
         * 提示「回复已完成」。
         *
         * 只做本地提示，不做推送：QwenPaw 没有推送通道，App 已在前台时看界面即可，
         * 因此调用方需要自己判断前后台。点击通知回到列表页。
         */
        fun notifyDone(context: Context, title: String, text: String) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(DONE_CHANNEL_ID) == null
            ) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        DONE_CHANNEL_ID,
                        context.getString(R.string.channel_done_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.channel_done_desc)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, DONE_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            runCatching { manager.notify(DONE_NOTIFICATION_ID, notification) }
        }
    }
}
