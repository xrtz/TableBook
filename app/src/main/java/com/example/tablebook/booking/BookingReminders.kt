package com.example.tablebook.booking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.tablebook.MainActivity
import com.example.tablebook.R
import com.example.tablebook.TableBookApplication
import com.example.tablebook.domain.booking.BookingError
import com.example.tablebook.domain.booking.BookingException
import com.example.tablebook.domain.booking.OwnedReservation
import com.example.tablebook.domain.booking.ReservationStatus
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class BookingReminderScheduler(private val context: Context) {
    val syncLock = Mutex()
    private val work get() = WorkManager.getInstance(context)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val delivered get() = context.getSharedPreferences("booking_reminders", Context.MODE_PRIVATE)

    fun start(scope: String) {
        val request = PeriodicWorkRequestBuilder<BookingSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(online).setInputData(workDataOf(scopeKey to scope)).addTag(scopeTag(scope)).build()
        work.enqueueUniquePeriodicWork("periodic-$scope", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun sync(scope: String) {
        val request = OneTimeWorkRequestBuilder<BookingSyncWorker>()
            .setConstraints(online).setInputData(workDataOf(scopeKey to scope)).addTag(scopeTag(scope)).build()
        work.enqueueUniqueWork("sync-$scope", ExistingWorkPolicy.REPLACE, request)
    }

    fun stop(scope: String) {
        work.cancelAllWorkByTag(scopeTag(scope))
        val editor = delivered.edit()
        for (key in delivered.all.keys.filter { it.startsWith("$scope:") }) {
            notifications.cancel(key, notificationId)
            editor.remove(key)
        }
        editor.apply()
    }

    fun reconcile(scope: String, snapshot: ReservationSnapshot, now: Instant) {
        val upcoming = snapshot.reservations.filter { it.upcoming(now) }
            .associateBy { deliveryKey(scope, it) }
        val pending = work.getWorkInfosByTag(reminderTag(scope)).get()
            .filterNot { it.state.isFinished }
        for (item in pending) {
            if (item.tags.none { it in upcoming }) work.cancelWorkById(item.id)
        }
        val editor = delivered.edit()
        for (key in delivered.all.keys.filter { it.startsWith("$scope:") && it !in upcoming }) {
            notifications.cancel(key, notificationId)
            editor.remove(key)
        }
        editor.apply()
        for ((key, item) in upcoming) {
            if (delivered.contains(key) || pending.any { key in it.tags }) continue
            val delayMillis = reminderDelayMillis(item, now) ?: continue
            val request = OneTimeWorkRequestBuilder<BookingReminderWorker>()
                .setConstraints(online).setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(scopeKey to scope, reservationIdKey to item.reservation.id,
                    startKey to item.reservation.startsAt.toString()))
                .addTag(scopeTag(scope)).addTag(reminderTag(scope)).addTag(key).build()
            work.enqueueUniqueWork(key, ExistingWorkPolicy.KEEP, request)
        }
    }

    fun notify(scope: String, item: OwnedReservation) {
        val key = deliveryKey(scope, item)
        if (delivered.contains(key) || !notifications.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        notifications.createNotificationChannel(NotificationChannel(
            channelId, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT,
        ))
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(item.reservation.startsAt.atZone(item.zone))
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(context.getString(R.string.reminder_details, item.hallName, item.tableLabel, time))
            .setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true).build()
        notifications.notify(key, notificationId, notification)
        delivered.edit().putBoolean(key, true).apply()
    }

    private fun scopeTag(scope: String) = "booking-session-$scope"
    private fun reminderTag(scope: String) = "booking-reminders-$scope"
    private fun deliveryKey(scope: String, item: OwnedReservation) =
        "$scope:${item.reservation.id}:${item.reservation.startsAt}"
}

class BookingSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val app = applicationContext as TableBookApplication
        app.reminders.syncLock.withLock {
            runScoped { current, scope, snapshot, now ->
                current.sessions.inScope(scope) { current.reminders.reconcile(scope, snapshot, now) }
                Result.success()
            }
        }
    }
}

class BookingReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        runScoped { app, scope, snapshot, now ->
            val id = inputData.getLong(reservationIdKey, 0)
            val start = inputData.getString(startKey)
            val item = snapshot.reservations.firstOrNull {
                it.reservation.id == id && it.reservation.startsAt.toString() == start && it.upcoming(now)
            } ?: return@runScoped Result.success()
            if (now.isBefore(reminderTime(item))) return@runScoped Result.retry()
            app.sessions.inScope(scope) { app.reminders.notify(scope, item) }
            Result.success()
        }
    }
}

private suspend fun CoroutineWorker.runScoped(
    action: suspend (TableBookApplication, String, ReservationSnapshot, Instant) -> androidx.work.ListenableWorker.Result,
): androidx.work.ListenableWorker.Result {
    val app = applicationContext as TableBookApplication
    val scope = inputData.getString(scopeKey) ?: return androidx.work.ListenableWorker.Result.failure()
    return try {
        app.sessions.restore()
        if (app.sessions.access()?.scope != scope) return androidx.work.ListenableWorker.Result.success()
        val snapshot = app.booking.readReservations(expectedScope = scope)
        val now = snapshot.serverTime ?: return androidx.work.ListenableWorker.Result.retry()
        action(app, scope, snapshot, now)
    } catch (error: BookingException) {
        when (error.error) {
            BookingError.SESSION_EXPIRED, BookingError.FORBIDDEN -> androidx.work.ListenableWorker.Result.success()
            else -> androidx.work.ListenableWorker.Result.retry()
        }
    } catch (_: java.io.IOException) {
        androidx.work.ListenableWorker.Result.retry()
    } catch (_: java.security.GeneralSecurityException) {
        androidx.work.ListenableWorker.Result.retry()
    } catch (_: ExecutionException) {
        androidx.work.ListenableWorker.Result.retry()
    }
}

private fun OwnedReservation.upcoming(now: Instant) =
    status == ReservationStatus.CONFIRMED && reservation.startsAt.isAfter(now)

private fun reminderTime(item: OwnedReservation) = item.reservation.startsAt.minus(Duration.ofHours(2))

internal fun reminderDelayMillis(item: OwnedReservation, now: Instant): Long? {
    if (!item.upcoming(now)) return null
    return Duration.between(now, reminderTime(item)).toMillis().coerceAtLeast(0)
}

private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
private const val scopeKey = "scope"
private const val reservationIdKey = "reservationId"
private const val startKey = "startsAt"
private const val channelId = "booking_reminders"
private const val notificationId = 1
