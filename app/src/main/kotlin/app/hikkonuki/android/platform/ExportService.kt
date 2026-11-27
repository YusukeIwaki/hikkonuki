package app.hikkonuki.android.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.hikkonuki.android.MainActivity
import app.hikkonuki.android.R
import app.hikkonuki.android.core.ExportEngine
import app.hikkonuki.android.core.ExportJob
import app.hikkonuki.android.core.ExportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the engine works, and mirrors its state into one
 * notification. All export logic is in [app.hikkonuki.android.core.ExportEngine]; this
 * class only hosts it, so closing the app or turning the screen off does not stop exports.
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var draining: Job? = null
    private val engine by lazy { AppGraph.get(this).engine }
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hikkonuki:export").apply { setReferenceCounted(false) }
    }

    private val usbDetached = UsbDetachReceiver { engine.sourceDisconnected() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        usbDetached.register(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "エクスポート", NotificationManager.IMPORTANCE_LOW).apply { description = "動画・写真のエクスポート進捗" },
        )
        scope.launch {
            // Android drops notification updates that come faster than a few per second,
            // and a dropped update can be the last one. Progress is therefore throttled;
            // a change of file always gets through.
            var shownId: Long? = null
            var shownAt = 0L
            engine.queue.jobs.map(::summarize).distinctUntilChanged().collect { summary ->
                val now = SystemClock.elapsedRealtime()
                val due = summary?.id != shownId || now - shownAt >= PROGRESS_INTERVAL_MILLIS
                if (draining?.isActive == true && due) {
                    shownId = summary?.id
                    shownAt = now
                    manager.notify(NOTIFICATION_ID, progressNotification(summary))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).cancel(RESULT_NOTIFICATION_ID)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, progressNotification(summarize(engine.queue.jobs.value)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (draining?.isActive != true) {
            draining = scope.launch {
                wakeLock.acquire(6 * 60 * 60 * 1000L)
                try {
                    // Work can be added while the last job is finishing: look again before stopping.
                    do engine.drain() while (engine.queue.hasQueued())
                } finally {
                    wakeLock.release()
                }
                finish()
            }
        }
        return START_STICKY
    }

    private fun finish() {
        // The result is posted as a new notification rather than as an update of the
        // progress one: updates can be dropped by rate limiting, which would leave an
        // undismissable "exporting" notification behind after the service is gone.
        stopForeground(STOP_FOREGROUND_REMOVE)
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
        resultNotification()?.let { manager.notify(RESULT_NOTIFICATION_ID, it) }
        stopSelf()
    }

    override fun onDestroy() {
        usbDetached.unregister(this)
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    /** The part of the running job the notification shows; changes at most once per percent. */
    private data class Summary(val id: Long, val displayName: String, val progressPercent: Int)

    private fun summarize(jobs: List<ExportJob>): Summary? =
        jobs.firstOrNull { it.status == ExportStatus.RUNNING }?.let { Summary(it.id, it.displayName, it.progressPercent) }

    /** Tapping the notification lands on the exports screen, not the app top. */
    private fun openExports(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_EXPORTS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(job: Summary?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (job != null) "${job.displayName} をエクスポート中 ${job.progressPercent}%" else "エクスポート待機中")
            .setContentIntent(openExports())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (job != null) {
            builder.setProgress(100, job.progressPercent, job.progressPercent <= 0)
            val cancel = PendingIntent.getBroadcast(
                this, job.id.toInt(),
                Intent(this, ExportCancelReceiver::class.java).putExtra(ExportCancelReceiver.EXTRA_JOB_ID, job.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "キャンセル", cancel)
        }
        return builder.build()
    }

    /** One shared notification for progress and results: the shade shows the latest state only. */
    private fun resultNotification(): Notification? {
        val (job, ending) = engine.lastEnded ?: return null
        val success = ending == ExportEngine.Ending.COMPLETED
        val text = when (ending) {
            ExportEngine.Ending.COMPLETED -> "${job.displayName} のエクスポートが完了しました"
            ExportEngine.Ending.FAILED -> "${job.displayName} のエクスポートに失敗しました"
            ExportEngine.Ending.CANCELLED -> "${job.displayName} のエクスポートをキャンセルしました"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openExports())
            .setAutoCancel(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 42
        private const val RESULT_NOTIFICATION_ID = 43
        private const val PROGRESS_INTERVAL_MILLIS = 500L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
        }
    }
}

class ExportCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getLongExtra(EXTRA_JOB_ID, -1L)
        if (jobId > 0) AppGraph.get(context).engine.cancel(jobId)
    }

    companion object {
        const val EXTRA_JOB_ID = "job_id"
    }
}
