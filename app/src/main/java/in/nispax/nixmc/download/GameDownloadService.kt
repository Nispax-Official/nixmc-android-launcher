package `in`.nispax.nixmc.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.nispax.nixmc.MainActivity
import `in`.nispax.nixmc.install.AndroidJavaRuntimeInstaller
import `in`.nispax.nixmc.install.DownloadCancellation
import `in`.nispax.nixmc.install.MinecraftClientInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * User-initiated, visible download worker. Android keeps this foreground service alive
 * while NixMC is backgrounded, and the notification describes current work.
 */
class GameDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeAction: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedAction = intent?.action
        if (requestedAction == DownloadActions.CANCEL) {
            DownloadCancellation.request()
            activeJob?.cancel()
            return START_REDELIVER_INTENT
        }

        val action = requestedAction
            ?: DownloadStateStore.read(this).action.takeIf {
                it == DownloadActions.GAME || it == DownloadActions.JAVA
            }
            ?: return START_NOT_STICKY

        if (action != DownloadActions.GAME && action != DownloadActions.JAVA) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (activeJob?.isActive == true) {
            publishStatus(activeAction ?: action, "A download is already running. Its status is shown here.")
            return START_REDELIVER_INTENT
        }

        DownloadCancellation.reset()
        activeAction = action
        val label = labelFor(action)
        DownloadStateStore.begin(this, action, label)
        startForeground(NOTIFICATION_ID, buildNotification(action, "Preparing $label…", 0, running = true))

        val task = serviceScope.launch(start = CoroutineStart.LAZY) {
            acquireWakeLock()
            try {
                when (action) {
                    DownloadActions.GAME -> {
                        MinecraftClientInstaller.downloadClient(applicationContext) { status ->
                            publishStatus(action, status)
                        }
                        DownloadStateStore.finish(
                            this@GameDownloadService, action, "COMPLETE",
                            "Minecraft 1.21.11 files downloaded and verified."
                        )
                    }
                    DownloadActions.JAVA -> {
                        val runtime = AndroidJavaRuntimeInstaller.install(filesDir) { status ->
                            publishStatus(action, status)
                        }
                        DownloadStateStore.finish(
                            this@GameDownloadService, action, "COMPLETE",
                            "Java ${runtime.majorVersion} runtime installed and passed its startup check."
                        )
                    }
                }
                val finalState = DownloadStateStore.read(this@GameDownloadService)
                showNotification(buildNotification(action, finalState.status, 100, running = false))
            } catch (_: CancellationException) {
                DownloadStateStore.finish(
                    this@GameDownloadService, action, "CANCELED",
                    "Download canceled. Verified files were kept; incomplete temporary files will not be used."
                )
                showNotification(buildNotification(action, DownloadStateStore.read(this@GameDownloadService).status, 0, running = false))
            } catch (error: Exception) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                DownloadStateStore.finish(
                    this@GameDownloadService, action, "FAILED",
                    "Download failed: $detail. Verified files were kept; tap retry when the connection is available."
                )
                showNotification(buildNotification(action, DownloadStateStore.read(this@GameDownloadService).status, 0, running = false))
            } finally {
                releaseWakeLock()
                activeJob = null
                activeAction = null
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
        activeJob = task
        task.start()
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (activeJob?.isActive == true) {
            DownloadCancellation.request()
            activeJob?.cancel()
        }
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun publishStatus(action: String, status: String) {
        DownloadStateStore.update(this, action, status)
        val current = DownloadStateStore.read(this)
        showNotification(buildNotification(action, status, current.progress, running = true))
    }

    private fun buildNotification(action: String, status: String, progress: Int, running: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPendingIntent = PendingIntent.getActivity(
            this, REQUEST_OPEN_APP, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (running) "NixMC · ${labelFor(action)}" else "NixMC · Download status")
            .setContentText(status.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(running)
            .setAutoCancel(!running)

        if (running) {
            builder.setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            val cancelIntent = Intent(this, GameDownloadService::class.java).setAction(DownloadActions.CANCEL)
            val cancelPendingIntent = PendingIntent.getService(
                this, REQUEST_CANCEL, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
        } else {
            builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    private fun showNotification(notification: Notification) {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID, "NixMC downloads", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible progress and status for Minecraft files and Java runtime downloads."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NixMC:Download")
            .apply {
                setReferenceCounted(false)
                acquire(6L * 60L * 60L * 1000L)
            }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock?.isHeld == true) runCatching { lock.release() }
    }

    private fun labelFor(action: String): String =
        if (action == DownloadActions.JAVA) "Java 21 runtime" else "Minecraft game files"

    companion object {
        const val CHANNEL_ID = "nixmc_download_progress"
        const val NOTIFICATION_ID = 42011
        private const val REQUEST_OPEN_APP = 42012
        private const val REQUEST_CANCEL = 42013
    }
}
