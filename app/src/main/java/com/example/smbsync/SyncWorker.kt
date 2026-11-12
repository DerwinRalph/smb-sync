package com.example.smbsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Live progress text per running pair, observed by the UI. */
object SyncStatus {
    val running = MutableStateFlow<Map<String, Progress>>(emptyMap())
    fun set(id: String, p: Progress) = running.update { it + (id to p) }
    fun clear(id: String) = running.update { it - id }
}

object Scheduler {
    private fun periodicName(id: String) = "periodic-$id"
    private fun manualName(id: String) = "manual-$id"

    private fun timedTag(id: String) = "timed-$id"

    private fun constraints(pair: SyncPair) = Constraints.Builder()
        .setRequiredNetworkType(if (pair.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(pair.chargingOnly)
        .build()

    /** (Re)registers or removes the background schedule for a pair. */
    fun apply(ctx: Context, pair: SyncPair) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelAllWorkByTag(timedTag(pair.id))
        when (pair.schedType) {
            SchedType.MANUAL -> wm.cancelUniqueWork(periodicName(pair.id))
            SchedType.INTERVAL -> {
                val req = PeriodicWorkRequestBuilder<SyncWorker>(maxOf(15, pair.intervalMin), TimeUnit.MINUTES)
                    .setConstraints(constraints(pair))
                    .setInputData(workDataOf("pairId" to pair.id))
                    .build()
                wm.enqueueUniquePeriodicWork(periodicName(pair.id), ExistingPeriodicWorkPolicy.UPDATE, req)
            }
            SchedType.DAILY -> {
                wm.cancelUniqueWork(periodicName(pair.id))
                scheduleNextTimed(ctx, pair)
            }
        }
    }

    /**
     * Queues one run for the pair's next set time. Each timed run queues the following one when it
     * finishes; the job name includes the target time so a run never replaces (and cancels) itself.
     */
    fun scheduleNextTimed(ctx: Context, pair: SyncPair) {
        val now = System.currentTimeMillis()
        val next = nextDailyTime(now + 60_000, pair.dailyAt, pair.days) ?: return
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(next - now, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(pair))
            .addTag(timedTag(pair.id))
            .setInputData(workDataOf("pairId" to pair.id, "timed" to true))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("timed-${pair.id}-${next / 60_000}", ExistingWorkPolicy.KEEP, req)
    }

    fun remove(ctx: Context, id: String) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(periodicName(id))
        wm.cancelUniqueWork(manualName(id))
        wm.cancelAllWorkByTag(timedTag(id))
    }

    fun syncNow(ctx: Context, id: String) {
        SyncStatus.set(id, Progress("Starting…"))
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf("pairId" to id, "manual" to true))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(manualName(id), ExistingWorkPolicy.REPLACE, req)
    }

    fun stop(ctx: Context, id: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(manualName(id))
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        private val active = Collections.synchronizedSet(HashSet<String>())
        private const val CH_PROGRESS = "progress"
        private const val CH_RESULT = "result"
    }

    private val nm get() = applicationContext.getSystemService(NotificationManager::class.java)
    private val pairId get() = inputData.getString("pairId") ?: ""
    private val notifId get() = pairId.hashCode()

    private fun channels() {
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Sync in progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Sync problems", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp() = PendingIntent.getActivity(
        applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun progressNotification(title: String, text: String, percent: Int = -1) =
        NotificationCompat.Builder(applicationContext, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, maxOf(percent, 0), percent < 0)
            .setContentIntent(openApp())
            .build()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        channels()
        return ForegroundInfo(
            notifId, progressNotification("Syncing", "Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        PairStore.init(ctx)
        val id = pairId
        val pair = PairStore.get(id) ?: return Result.success()
        val manual = inputData.getBoolean("manual", false)
        if (!active.add(id)) return Result.success() // this pair is already syncing
        try {
            channels()
            var foreground = false
            try {
                setForeground(
                    ForegroundInfo(
                        notifId, progressNotification("Syncing ${pair.name}", "Starting…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                )
                foreground = true
            } catch (_: Exception) {
                // Not allowed from the background on newer Android; the sync still runs.
            }
            withContext(Dispatchers.IO) { runSync(pair, manual, foreground) }
        } finally {
            active.remove(id)
            SyncStatus.clear(id)
            if (inputData.getBoolean("timed", false)) {
                PairStore.get(id)?.takeIf { it.schedType == SchedType.DAILY }?.let { Scheduler.scheduleNextTimed(ctx, it) }
            }
        }
        return Result.success()
    }

    // ---- Gallery / media index: Android only learns about files written by this app if told.
    private val pendingScan = ArrayList<String>()

    private fun scan(path: String) {
        pendingScan += path
        if (pendingScan.size >= 50) flushScan()
    }

    private fun flushScan() {
        if (pendingScan.isEmpty()) return
        try {
            android.media.MediaScannerConnection.scanFile(applicationContext, pendingScan.toTypedArray(), null, null)
        } catch (_: Exception) {
        }
        pendingScan.clear()
    }

    /** One-time pass per pair so files synced by earlier versions of the app show up in Gallery. */
    private fun rescanOnce(pair: SyncPair) {
        val prefs = applicationContext.getSharedPreferences("smbsync", Context.MODE_PRIVATE)
        val key = "rescanned_${pair.id}"
        if (prefs.getBoolean(key, false)) return
        try {
            val media = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "dng", "bmp", "mp4", "mov", "mkv", "webm", "3gp", "avi", "mp3", "m4a", "wav", "flac", "ogg")
            java.io.File(pair.localPath).walkTopDown()
                .onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in media }
                .forEach { scan(it.path) }
            flushScan()
            prefs.edit().putBoolean(key, true).apply()
        } catch (_: Exception) {
        }
    }

    private fun runSync(pair: SyncPair, manual: Boolean, foreground: Boolean) {
        val logger = SyncLog(PairStore.logFile(pair.id))
        logger.add(LogType.START, "${pair.mode.label} · ${if (manual) "manual" else "scheduled"}")
        var lastNotify = 0L
        val engine = SyncEngine(pair, Smb(pair), PairStore.stateFile(pair.id), logger::add, { p ->
            SyncStatus.set(pair.id, p)
            val now = System.currentTimeMillis()
            if (foreground && now - lastNotify > 1000) {
                lastNotify = now
                try {
                    val pct = if (p.count > 0) (p.fraction * 100).toInt() else -1
                    val text = if (p.count > 0)
                        "$pct% · ${p.index}/${p.count}" + (if (p.speed > 0) " · ${fmtBytes(p.speed)}/s" else "") + " · ${p.phase.substringAfterLast('/')}"
                    else p.phase
                    nm.notify(notifId, progressNotification("Syncing ${pair.name}", text, pct))
                } catch (_: Exception) {
                }
            }
        }, { isStopped }, { f -> scan(f.path) })

        var ok: Boolean
        var msg: String
        try {
            if (pair.host.isBlank() || pair.share.isBlank()) throw SyncAbort("Pair is not fully set up")
            if (!android.os.Environment.isExternalStorageManager())
                throw SyncAbort("\"All files access\" permission is not granted")
            val r = engine.run()
            ok = r.errors.isEmpty()
            msg = r.summary()
        } catch (e: SyncAbort) {
            ok = false
            msg = e.message ?: "Stopped"
        } catch (e: Exception) {
            ok = false
            msg = friendlyError(e)
        }
        if (isStopped && !msg.startsWith("Stopped")) msg = "Stopped"
        flushScan()
        rescanOnce(pair)
        logger.add(if (ok) LogType.END else LogType.FAILED, msg)
        logger.trim()
        PairStore.update(pair.id) { it.copy(lastSync = System.currentTimeMillis(), lastResult = msg, lastOk = ok) }

        // Being away from home is normal for scheduled runs; don't nag about it.
        if (!ok && !isStopped && (manual || !isUnreachable(msg))) {
            try {
                nm.notify(
                    notifId + 1,
                    NotificationCompat.Builder(applicationContext, CH_RESULT)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("Sync problem: ${pair.name}")
                        .setContentText(msg)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                        .setAutoCancel(true)
                        .setContentIntent(openApp())
                        .build()
                )
            } catch (_: Exception) {
            }
        }
    }
}
