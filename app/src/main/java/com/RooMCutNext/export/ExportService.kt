package com.RooMCutNext.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.RooMCutNext.MainActivity
import com.RooMCutNext.RooMCutNextApp
import com.RooMCutNext.R
import com.RooMCutNext.data.ProjectRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service owning one export at a time.
 * Progress is published on [ExportBus]; a notification mirrors it.
 */
class ExportService : Service() {

    companion object {
        const val ACTION_START = "com.RooMCutNext.export.START"
        const val ACTION_CANCEL = "com.RooMCutNext.export.CANCEL"
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context, projectId: String, height: Int? = null) {
            val intent = Intent(context, ExportService::class.java).apply {
                action = ACTION_START
                putExtra("projectId", projectId)
                putExtra("height", height ?: -1)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, ExportService::class.java).apply { action = ACTION_CANCEL }
            )
        }
    }

    private var engine: ExportEngine? = null
    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var exportStartMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                engine?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val projectId = intent?.getStringExtra("projectId")
                if (projectId == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (engine == null) {
                    startExport(projectId, intent.getIntExtra("height", -1).takeIf { it > 0 })
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startExport(projectId: String, height: Int?) {
        val repo = ProjectRepository(this)
        val project = repo.load(projectId)
        if (project == null) {
            ExportBus.update(
                ExportBus.ExportState(projectId, "", ExportBus.Phase.FAILED, -1f, error = "项目不存在 / project missing")
            )
            stopSelf()
            return
        }

        startAsForeground(project.name)

        val options = ExportOptions(height = height)
        exportStartMs = System.currentTimeMillis()
        ExportBus.update(
            ExportBus.ExportState(project.id, project.name, ExportBus.Phase.RUNNING, -1f)
        )

        engine = ExportEngine(this)
        engine?.start(
            project = project,
            mediaDir = repo.mediaDir(project.id),
            options = options,
            onProgress = { },
            onCompleted = { uri ->
                ExportBus.update(
                    ExportBus.ExportState(
                        project.id, project.name, ExportBus.Phase.COMPLETED, 1f,
                        outputUri = uri, outputPathHint = uri?.toString(),
                    )
                )
                notifyDone(project.name, uri)
                stopSelf()
            },
            onError = { msg ->
                ExportBus.update(
                    ExportBus.ExportState(project.id, project.name, ExportBus.Phase.FAILED, -1f, error = msg)
                )
                notifyFailed(project.name, msg)
                stopSelf()
            },
        )

        pollJob = scope.launch {
            while (true) {
                val p = engine?.currentProgress() ?: break
                val elapsed = (System.currentTimeMillis() - exportStartMs) / 1000
                val eta = if (p > 0.03f) (elapsed / p - elapsed).toLong() else null
                ExportBus.update(
                    ExportBus.ExportState(project.id, project.name, ExportBus.Phase.RUNNING, p, etaSeconds = eta)
                )
                updateProgressNotification(project.name, p, eta)
                delay(600)
            }
        }
    }

    // ---------------------------------------------------------------
    // Notification plumbing
    // ---------------------------------------------------------------

    private fun startAsForeground(name: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.export_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val n = buildProgressNotification(name, -1f, null)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildProgressNotification(name: String, progress: Float, etaSeconds: Long?): Notification {
        val cancelPi = PendingIntent.getService(
            this, 2,
            Intent(this, ExportService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.export_notif_title, name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.export_cancel), cancelPi)
        if (progress >= 0) {
            builder.setProgress(100, (progress * 100).toInt(), false)
            etaSeconds?.let {
                builder.setContentText(getString(R.string.export_eta, it))
            }
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun updateProgressNotification(name: String, progress: Float, etaSeconds: Long?) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildProgressNotification(name, progress, etaSeconds))
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 3,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notifyDone(name: String, uri: android.net.Uri?) {
        RooMCutNextApp.vibrate(this)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.export_done_title))
            .setContentText(getString(R.string.export_done_text, name))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
        uri?.let {
            val open = PendingIntent.getActivity(
                this, 4,
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(it, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, getString(R.string.export_open), open)
        }
        nm.notify(NOTIFICATION_ID + 1, builder.build())
    }

    private fun notifyFailed(name: String, msg: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.export_failed_title))
            .setContentText(msg)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID + 2, n)
    }

    override fun onDestroy() {
        pollJob?.cancel()
        engine?.cancel()
        engine = null
        super.onDestroy()
    }
}
