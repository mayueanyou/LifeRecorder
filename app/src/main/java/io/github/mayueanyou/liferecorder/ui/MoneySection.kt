package io.github.mayueanyou.liferecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mayueanyou.liferecorder.data.LifeDb
import io.github.mayueanyou.liferecorder.data.MoneyRuleRow
import io.github.mayueanyou.liferecorder.data.PaymentRow
import io.github.mayueanyou.liferecorder.money.KnownApps
import io.github.mayueanyou.liferecorder.money.Money
import io.github.mayueanyou.liferecorder.money.MoneyApps
import io.github.mayueanyou.liferecorder.money.Reading
import io.github.mayueanyou.liferecorder.money.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class AppInfo(val pkg: String, val name: String, val role: Role, val count: Int, val counts: Boolean,
                      val decided: Boolean, val last: PaymentRow?)

private class MoneyState(val apps: List<AppInfo>, val rules: List<MoneyRuleRow>, val review: List<PaymentRow>,
                         val names: Map<String, String>)

/**
 * Settings › Money: apps waiting for an answer, every app that has sent
 * money with what it last said, the rules you made, and a week of amounts
 * that were not counted -- each one a tap from counting.
 */
@Composable
fun MoneySection(reading: Boolean, checks: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val state by produceState<MoneyState?>(null, checks, version) {
        value = withContext(Dispatchers.IO) {
            val dao = LifeDb.get(context).money()
            val apps = dao.apps().map { a ->
                AppInfo(a.pkg, MoneyApps.name(a.pkg, a.app), KnownApps.role(a.pkg), a.count,
                    MoneyApps.counts(context, a.pkg), MoneyApps.decided(context, a.pkg), dao.lastFrom(a.pkg))
            }
            val review = dao.review(System.currentTimeMillis() - 7 * 24 * 3600_000L)
            val names = (apps.map { it.pkg to it.name } + review.map { it.pkg to MoneyApps.name(it.pkg, it.app) }).toMap()
            MoneyState(apps, dao.rules(), review, names)
        }
    }
    fun change(block: suspend () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { block() }
            MoneyApps.changed(context)
            version++
        }
    }
    val dao = remember { LifeDb.get(context).money() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val s = state ?: return

    if (s.apps.isEmpty()) {
        Text(if (reading) "No purchases read yet — they appear here after your next card payment."
            else "Allow notification access above to start.", color = muted)
    }

    // Waiting for an answer: counted only after a yes.
    s.apps.filter { !it.decided }.forEach { app ->
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Count purchases from ${app.name}?", style = MaterialTheme.typography.titleSmall)
                Text(app.role.label + " · " + app.role.hint, fontSize = 13.sp, color = muted)
                app.last?.let { Quote(it) }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { change { MoneyApps.set(context, app.pkg, true) } }) { Text("Count") }
                    OutlinedButton(onClick = { change { MoneyApps.set(context, app.pkg, false) } }) { Text("Don't count") }
                }
            }
        }
    }

    s.apps.filter { it.decided }.forEach { app ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleSmall)
                Text("${app.role.label} · ${app.count} purchase${if (app.count == 1) "" else "s"} read", fontSize = 13.sp, color = muted)
                app.last?.let { Quote(it) }
            }
            Switch(checked = app.counts, onCheckedChange = { on -> change { MoneyApps.set(context, app.pkg, on) } })
        }
    }

    if (s.rules.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Text("YOUR RULES", fontSize = 11.sp, letterSpacing = .5.sp, color = muted, modifier = Modifier.padding(top = 8.dp))
        s.rules.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((s.names[r.pkg] ?: r.pkg) + ": " + (if (r.count) "always count" else "never count") + " “${r.pattern}…”",
                    Modifier.weight(1f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { change { dao.deleteRule(r.pkg, r.pattern) } }) { Text("✕") }
            }
        }
    }

    if (s.review.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Text("NOT COUNTED · LAST 7 DAYS", fontSize = 11.sp, letterSpacing = .5.sp, color = muted, modifier = Modifier.padding(top = 8.dp))
        Text("An amount from your money apps without words saying it was spent. Count any that were purchases.",
            fontSize = 13.sp, color = muted)
        s.review.take(20).forEach { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("${s.names[p.pkg] ?: p.app} · ${Money.format(p.amount, p.currency)} · ${timeOfDay(p.time)}",
                    style = MaterialTheme.typography.titleSmall)
                Quote(p)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { change { dao.setVerdict(p.id, 1) } }) { Text("Count") }
                    TextButton(onClick = {
                        change { dao.upsertRule(MoneyRuleRow(p.pkg, Reading.pattern(p.title, p.text), true)) }
                    }) { Text("Always count these") }
                    TextButton(onClick = { change { dao.setVerdict(p.id, -1) } }) { Text("Not money") }
                }
            }
        }
    }
}

@Composable
private fun Quote(p: PaymentRow) {
    val words = listOf(p.title, p.text).filter { it.isNotBlank() }.joinToString(" — ")
    Text("“$words”", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
        overflow = TextOverflow.Ellipsis)
}
