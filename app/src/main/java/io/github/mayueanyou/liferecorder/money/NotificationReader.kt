package io.github.mayueanyou.liferecorder.money

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.mayueanyou.liferecorder.Diagnostics
import io.github.mayueanyou.liferecorder.data.LifeDb
import io.github.mayueanyou.liferecorder.data.PaymentRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads purchases out of notifications as they arrive. Only a notification
 * that reads as money spent is kept -- its app, words and amount -- and
 * nothing else any notification says is stored or leaves the phone.
 */
class NotificationReader : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        // Whatever is still in the shade was posted while we were not listening.
        val showing = runCatching { activeNotifications }.getOrNull().orEmpty()
        scope.launch { showing.forEach { read(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch { runCatching { read(sbn) }.onFailure { Diagnostics.note(this@NotificationReader, "money: could not read -- ${Diagnostics.trace(it)}") } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun read(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ")
        val text = (big ?: lines ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()).orEmpty()
            .split(Regex("\\s+")).joinToString(" ").trim()
        // Android 15+ hides what it takes for a one-time code from apps that
        // read notifications -- a card's last four digits can look like one.
        // Nothing can be read then; saying so is the most that can be done.
        if (text == HIDDEN && MoneyApps.counts(this, sbn.packageName)) {
            Diagnostics.note(this, "money: Android hid a notification from ${sbn.packageName} as sensitive")
            return
        }
        val amount = Reading.purchase(title, text) ?: return

        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrElse { if (it is PackageManager.NameNotFoundException) sbn.packageName else sbn.packageName }
            .split(Regex("\\s+")).joinToString(" ")   // labels carry non-breaking spaces surprisingly often
        // The time it says it happened, unless that is nonsense; otherwise when it was posted.
        val time = n.`when`.takeIf { kotlin.math.abs(it - sbn.postTime) < 24 * 3600_000L } ?: sbn.postTime
        val dao = LifeDb.get(this).money()
        if (dao.countSame(sbn.packageName, text, time, SAME_WITHIN_MS) > 0) return
        dao.insertPayment(
            PaymentRow(
                time = time, pkg = sbn.packageName, app = app, title = title, text = text,
                amount = amount.value, currency = amount.currency, merchant = Reading.merchant(app, title, text),
            ),
        )
        Diagnostics.note(this, "money: read a purchase from $app" + if (MoneyApps.counts(this, sbn.packageName)) "" else " (not counted -- turn it on in Settings)")
    }

    companion object {
        private const val SAME_WITHIN_MS = 30 * 60 * 1000L
        private const val HIDDEN = "Sensitive notification content hidden"
    }
}
