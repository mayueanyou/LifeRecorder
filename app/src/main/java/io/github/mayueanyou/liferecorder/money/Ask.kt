package io.github.mayueanyou.liferecorder.money

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.mayueanyou.liferecorder.R
import io.github.mayueanyou.liferecorder.location.Recording
import io.github.mayueanyou.liferecorder.ui.MainActivity

/**
 * The once-per-app question: "Found $12.40 at Starbucks from Chase -- count
 * purchases from Chase?" Answered from the notification itself, or later in
 * Settings, where an unanswered app waits at the top of Money.
 */
object Ask {
    private const val CHANNEL = "money"
    const val ACTION = "io.github.mayueanyou.liferecorder.MONEY_ANSWER"
    const val EXTRA_PKG = "pkg"
    const val EXTRA_COUNT = "count"

    fun about(context: Context, pkg: String, app: String, found: String) {
        if (!Recording.hasNotifications(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Money", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Asks once whether purchases from a new app should count."
            })
        }
        val name = MoneyApps.name(pkg, app)
        val role = KnownApps.role(pkg)
        val text = "Found $found from $name. Count purchases from $name?" +
            if (role != Role.OTHER) " ${role.hint}" else ""
        fun answer(count: Boolean, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, AnswerReceiver::class.java).setAction(ACTION)
                .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_COUNT, count),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val id = id(pkg)
        manager.notify(id, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle("A purchase from $name")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "Count", answer(true, id))
            .addAction(0, "Don't count", answer(false, id + 1))
            .setAutoCancel(true)
            .build())
    }

    fun id(pkg: String) = 1000 + (pkg.hashCode() and 0x3fffffff) % 100_000 * 2
}

class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Ask.ACTION) return
        val pkg = intent.getStringExtra(Ask.EXTRA_PKG) ?: return
        MoneyApps.set(context, pkg, intent.getBooleanExtra(Ask.EXTRA_COUNT, false))
        context.getSystemService(NotificationManager::class.java).cancel(Ask.id(pkg))
    }
}
