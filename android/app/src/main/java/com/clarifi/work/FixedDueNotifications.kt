package com.clarifi.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.clarifi.ClariFiApp
import com.clarifi.MainActivity
import com.clarifi.R
import com.clarifi.core.money.Money
import com.clarifi.data.repo.FixedPaymentView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reminders for fixed payments that have come due.
 *
 * Each notification carries an **Apply** action, so the common case - "yes, the
 * rent went out, record it" - takes one tap from the shade without opening the
 * app at all.
 */
object FixedDueNotifications {

    const val CHANNEL_ID = "fixed_due"
    private const val GROUP = "com.clarifi.FIXED_DUE"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fixed transactions due",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Reminds you when a recurring payment or income is due this month."
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun notify(context: Context, due: List<FixedPaymentView>) {
        if (due.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)

        due.forEach { payment ->
            manager.notify(payment.id, build(context, payment))
        }

        // Android groups notifications from one app behind a summary; without it the
        // shade shows them stacked but unlabelled.
        if (due.size > 1) {
            manager.notify(SUMMARY_ID, buildSummary(context, due.size))
        }
    }

    private fun build(context: Context, payment: FixedPaymentView): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            payment.id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val apply = PendingIntent.getBroadcast(
            context,
            payment.id,
            Intent(context, ApplyFixedReceiver::class.java).apply {
                action = ApplyFixedReceiver.ACTION_APPLY
                putExtra(ApplyFixedReceiver.EXTRA_ID, payment.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val amount = Money.format(payment.currency, payment.amount)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (payment.isIncome) "${payment.name} is due" else "${payment.name} is due"
            )
            .setContentText("$amount · ${payment.account.bank}")
            .setContentIntent(openApp)
            .addAction(
                0,
                if (payment.isIncome) "Mark received" else "Mark paid",
                apply,
            )
            .setGroup(GROUP)
            .setAutoCancel(true)
            .build()
    }

    private fun buildSummary(context: Context, count: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$count fixed transactions are due")
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

    private const val SUMMARY_ID = 999_000
}

/**
 * Applies a fixed payment straight from its notification.
 *
 * The work outlives this receiver's `onReceive`, so it runs on the application
 * scope with a `goAsync` slot held open until the write finishes.
 */
class ApplyFixedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_APPLY) return
        val paymentId = intent.getIntExtra(EXTRA_ID, -1)
        if (paymentId < 0) return

        val app = context.applicationContext as ClariFiApp
        val pending = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                app.container.fixed.apply(paymentId)
                NotificationManagerCompat.from(context).cancel(paymentId)
            } catch (e: Exception) {
                // Already applied, or the payment is gone: the reminder is stale either
                // way, so it goes rather than nagging again.
                NotificationManagerCompat.from(context).cancel(paymentId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_APPLY = "com.clarifi.action.APPLY_FIXED"
        const val EXTRA_ID = "payment_id"
    }
}
