package com.lumbangaol.idm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service latar depan yang menjaga proses tetap hidup selama ada unduhan berjalan
 * dan menampilkan notifikasi progres.
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL_PROGRESS = "downloads"
        private const val CHANNEL_DONE = "downloads_done"
        private const val NOTIF_ID = 1
        const val ACTION_PAUSE_ALL = "com.lumbangaol.idm.PAUSE_ALL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private val notifiedDone = HashSet<String>()
    private lateinit var manager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Unduhan berjalan", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Unduhan selesai", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as IdmApp).engine
        if (intent?.action == ACTION_PAUSE_ALL) engine.pauseAll()

        startForeground(
            NOTIF_ID,
            buildProgress(engine.items.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (collectJob == null) {
            engine.items.value
                .filter { it.status == Status.COMPLETED }
                .forEach { notifiedDone.add(it.id) }
            collectJob = scope.launch {
                engine.items.collect { render(it) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(list: List<DownloadItem>) {
        list.filter { it.status == Status.COMPLETED && notifiedDone.add(it.id) }
            .forEach { notifyDone(it) }

        val active = list.filter { it.status == Status.DOWNLOADING || it.status == Status.QUEUED }
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        manager.notify(NOTIF_ID, buildProgress(list))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildProgress(list: List<DownloadItem>): Notification {
        val active = list.filter { it.status == Status.DOWNLOADING || it.status == Status.QUEUED }
        val downloading = active.filter { it.status == Status.DOWNLOADING }
        val withSize = downloading.filter { it.totalBytes > 0 }
        val total = withSize.sumOf { it.totalBytes }
        val got = withSize.sumOf { it.downloadedBytes }
        val speed = downloading.sumOf { it.speedBps }
        val percent = if (total > 0) (got * 100 / total).toInt() else 0

        val title = when {
            active.size > 1 -> "Mengunduh ${active.size} file"
            active.size == 1 -> active[0].fileName
            else -> "Mengunduh"
        }
        val text = buildString {
            if (total > 0) append("$percent%")
            if (speed > 0) {
                if (isNotEmpty()) append(" · ")
                append("${formatBytes(speed)}/dtk")
            }
        }

        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Jeda semua", pauseIntent)
            .build()
    }

    private fun notifyDone(item: DownloadItem) {
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Unduhan selesai")
            .setContentText(item.fileName)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        manager.notify(item.id.hashCode(), notification)
    }
}
