package io.qwenpaw.node.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.qwenpaw.node.MainActivity
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.i18n.Texts

/**
 * 本地通知。
 *
 * QwenPaw 没有推送通道，也没有常驻长连接的保活机制，所以这里只做
 * 「本进程已经拿到结果」的提醒：调用方需要自己判断 App 是否在前台，
 * 前台时不打扰。注意 App 进程被系统杀掉后不会再有提醒。
 */
object ChatNotifier {

    private const val DONE_CHANNEL_ID = "qwenpaw_chat_done"
    private const val DONE_NOTIFICATION_ID = 1002

    /** 提示「回复已完成」。点击通知回到对话列表。 */
    fun notifyDone(context: Context, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 已存在的渠道也会更新名称与描述：切换应用语言后渠道名要跟着变，
            // 只在 getNotificationChannel == null 时创建会留下旧语言的名称。
            manager.createNotificationChannel(
                NotificationChannel(
                    DONE_CHANNEL_ID,
                    Texts.get(R.string.channel_done_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = Texts.get(R.string.channel_done_desc)
                    // 锁屏上不展示助手回复正文，只留通用说明。
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                },
            )
        }
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = NotificationCompat.Builder(context, DONE_CHANNEL_ID)
            .setContentTitle(Texts.get(R.string.channel_done_name))
            .setContentText(Texts.get(R.string.notification_reply_done_public))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion)
            .build()
        runCatching { manager.notify(DONE_NOTIFICATION_ID, notification) }
    }
}