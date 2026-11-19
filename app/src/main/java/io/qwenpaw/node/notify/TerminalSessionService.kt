package io.qwenpaw.node.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.qwenpaw.node.MainActivity
import io.qwenpaw.node.R

/**
 * 终端会话的前台服务。
 *
 * 只负责让进程在后台存活并常驻一条通知，不持有会话本身；会话仍归
 * TerminalViewModel 管理。任务从最近任务划掉时按用户约定结束全部会话。
 */
class TerminalSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_terminal_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.channel_terminal_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TerminalSessionNotifier.requestStopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        startForeground(NOTIFICATION_ID, buildNotification(count))
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        TerminalSessionNotifier.requestStopAll()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(count: Int) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(
            resources.getQuantityString(R.plurals.terminal_sessions_title, count, count),
        )
        .setContentText(getString(R.string.terminal_sessions_text))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentIntent(openIntent())
        .addAction(
            R.mipmap.ic_launcher,
            getString(R.string.terminal_stop_action),
            stopIntent(),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun openIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_TERMINAL)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, TerminalSessionService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            REQUEST_STOP,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val CHANNEL_ID = "qwenpaw_terminal_sessions"
        private const val NOTIFICATION_ID = 1003
        private const val REQUEST_OPEN = 21
        private const val REQUEST_STOP = 22
        private const val EXTRA_COUNT = "count"
        private const val ACTION_START = "io.qwenpaw.node.action.TERMINAL_SESSIONS"
        private const val ACTION_STOP = "io.qwenpaw.node.action.STOP_TERMINAL_SESSIONS"

        fun intent(context: Context, count: Int): Intent =
            Intent(context, TerminalSessionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_COUNT, count)
    }
}
