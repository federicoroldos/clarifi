package com.clarifi.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.clarifi.ClariFiApp
import com.clarifi.MainActivity
import com.clarifi.core.money.Money
import com.clarifi.data.db.Account
import androidx.glance.action.actionStartActivity

/**
 * Home-screen widget: the balances, and a way straight into the app.
 *
 * Deliberately read-only. Logging an expense needs an amount, an account and a
 * category - a widget cannot ask for those without becoming a worse version of
 * the app - so the button opens the app with the add sheet instead of pretending
 * to be a form.
 */
class BalanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as ClariFiApp).container
        val accounts = container.accounts.active()

        provideContent {
            GlanceTheme {
                WidgetBody(accounts)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun WidgetBody(accounts: List<Account>) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ColorProvider(BACKGROUND))
                .cornerRadius(24.dp)
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "ClariFi",
                    style = TextStyle(
                        color = ColorProvider(ACCENT),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    text = "+",
                    style = TextStyle(
                        color = ColorProvider(ACCENT),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = GlanceModifier
                        .clickable(actionStartActivity<MainActivity>())
                        .padding(horizontal = 8.dp),
                )
            }

            Spacer(GlanceModifier.height(10.dp))

            if (accounts.isEmpty()) {
                Text(
                    text = "No accounts yet",
                    style = TextStyle(color = ColorProvider(MUTED), fontSize = 13.sp),
                )
            } else {
                // A widget has very little room; more than four rows just clips.
                accounts.take(4).forEach { account ->
                    Row(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = account.bank,
                            style = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp),
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        Text(
                            text = Money.format(account.currencyMeta, account.balance),
                            style = TextStyle(
                                color = ColorProvider(if (account.balance < 0) NEGATIVE else TEXT),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                        )
                    }
                }
                if (accounts.size > 4) {
                    Text(
                        text = "+${accounts.size - 4} more",
                        style = TextStyle(color = ColorProvider(MUTED), fontSize = 11.sp),
                    )
                }
            }
        }
    }

    private companion object {
        // The widget sits on the user's wallpaper, not inside the app, so it always
        // uses the dark surface rather than following the in-app theme.
        val BACKGROUND = Color(0xFF1C1C20)
        val ACCENT = Color(0xFF10B981)
        val TEXT = Color(0xFFECECEF)
        val MUTED = Color(0xFF8A8A92)
        val NEGATIVE = Color(0xFFEF4444)
    }
}

class BalanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BalanceWidget()
}
