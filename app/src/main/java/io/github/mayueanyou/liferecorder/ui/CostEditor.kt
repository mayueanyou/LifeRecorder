package io.github.mayueanyou.liferecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.mayueanyou.liferecorder.data.CostItemRow
import io.github.mayueanyou.liferecorder.data.LifeDb
import io.github.mayueanyou.liferecorder.engine.Entry
import io.github.mayueanyou.liferecorder.money.EntryMoney
import io.github.mayueanyou.liferecorder.money.Money
import io.github.mayueanyou.liferecorder.money.MoneyApps
import io.github.mayueanyou.liferecorder.screentime.AppSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val MONEY_GREEN = Color(0xFF22C55E)

/**
 * What a stay or journey cost, opened by tapping it: the bank's charges (read
 * from its notifications, not editable), the items you typed, and a way to
 * add one or say it cost nothing. Every change is saved at once.
 */
@Composable
fun CostEditor(entry: Entry, money: EntryMoney?, screen: List<AppSession>, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { LifeDb.get(context).money() }
    val currency = remember { MoneyApps.homeCurrency() }
    var amount by remember { mutableStateOf("") }
    var what by remember { mutableStateOf("") }
    val cleared = money?.cleared == true
    val mid = (entry.start + entry.end) / 2

    fun change(block: suspend () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { block() }; onChanged() }
    }

    fun add() {
        val value = amount.replace(",", "").replace("$", "").trim().toDoubleOrNull()?.takeIf { it > 0 } ?: return
        val label = what.trim().ifEmpty { null }
        amount = ""; what = ""
        change { dao.insertItem(CostItemRow(at = mid, amount = Math.round(value * 100) / 100.0, currency = currency, what = label)) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(title(entry).removePrefix("▸ "), style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${timeOfDay(entry.start)} – ${timeOfDay(entry.end)}", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(money?.total?.let { Money.format(it, money.currency) } ?: "No cost",
                        fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                        color = if (money?.total != null) MONEY_GREEN else MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // What was on the phone's screen meanwhile: each app, its time, and when.
                val apps = byApp(screen)
                if (apps.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Label("ON YOUR PHONE · " + spellMinutes(screen.sumOf { it.millis } / 60_000).let { if (it == "0m") "under a minute" else it })
                    var showAll by remember { mutableStateOf(false) }
                    (if (showAll) apps else apps.take(6)).forEach { (label, millis) ->
                        val times = screen.filter { it.label == label }.filter { it.millis >= 60_000 }
                        Line(
                            left = label,
                            sub = times.take(3).joinToString(", ") { "${timeOfDay(it.start)}–${timeOfDay(it.end)}" } +
                                if (times.size > 3) " +${times.size - 3} more" else "",
                            right = if (millis < 60_000) "<1m" else spellMinutes(millis / 60_000),
                            struck = false,
                        )
                    }
                    if (apps.size > 6 && !showAll) TextButton(onClick = { showAll = true }) { Text("All ${apps.size} apps") }
                }

                val charges = money?.charges.orEmpty()
                if (charges.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Label(if (cleared) "FROM YOUR BANK · IGNORED HERE" else "FROM YOUR BANK")
                    charges.forEach { c ->
                        val p = c.payment
                        val note = when {
                            cleared -> "ignored"
                            c.sameAsItem -> "also typed — counted once"
                            c.outsideByMin > 0 -> "${c.outsideByMin} min outside the stay"
                            else -> null
                        }
                        Line(
                            left = (p.merchant ?: p.app) + " · " + timeOfDay(p.time),
                            sub = note,
                            right = Money.format(p.amount, p.currency),
                            struck = cleared || c.sameAsItem,
                        )
                    }
                }
                // Charges a "No cost" entry ignores are listed above; it still shows them.
                if (cleared && charges.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Marked as no cost.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                val items = money?.items.orEmpty()
                if (items.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Label("TYPED IN")
                    items.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.what ?: "Item", Modifier.weight(1f), fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Money.format(item.amount, item.currency), fontSize = 15.sp)
                            TextButton(onClick = { change { dao.deleteItem(item.id) } }) { Text("✕") }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Label("ADD A COST")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                        label = { Text("Amount") }, singleLine = true, modifier = Modifier.weight(0.9f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        value = what, onValueChange = { what = it }, label = { Text("What (optional)") },
                        singleLine = true, modifier = Modifier.weight(1.3f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { add() }, enabled = amount.replace(",", "").toDoubleOrNull()?.let { it > 0 } == true) {
                    Text("Add")
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        change {
                            if (cleared) dao.deleteCleared(entry.start, entry.end)
                            else dao.insertItem(CostItemRow(at = mid, amount = 0.0, currency = currency, what = null, cleared = true))
                        }
                    }) { Text(if (cleared) "Count the bank again" else "No cost") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 11.sp, letterSpacing = .5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun Line(left: String, sub: String?, right: String, struck: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(left, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(right, fontSize = 15.sp,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
            color = if (struck) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}
