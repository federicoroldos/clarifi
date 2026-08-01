package com.clarifi.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.clarifi.ClariFiApp
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Checks once a day whether any fixed transaction has come due and, if so, posts
 * the reminders.
 *
 * A periodic worker rather than an exact alarm: the reminder is useful within the
 * day, not to the minute, and WorkManager already handles doze, reboots and the
 * battery cost that an exact alarm would impose.
 */
class FixedDueWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ClariFiApp).container
        val due = container.fixed.payments.first().filter { it.dueThisMonth }

        FixedDueNotifications.notify(applicationContext, due)
        return Result.success()
    }

    companion object {
        private const val NAME = "clarifi-fixed-due"

        /**
         * Schedules the daily check, first firing at the next 9am.
         *
         * `KEEP` so relaunching the app does not restart the window and quietly
         * skip a day.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FixedDueWorker>(Duration.ofDays(1))
                .setInitialDelay(delayUntilNextMorning())
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun delayUntilNextMorning(): Duration {
            val now = LocalDateTime.now()
            val todayAtNine = now.toLocalDate().atTime(LocalTime.of(9, 0))
            val target = if (now.isBefore(todayAtNine)) todayAtNine else todayAtNine.plusDays(1)
            return Duration.between(now, target)
        }
    }
}
