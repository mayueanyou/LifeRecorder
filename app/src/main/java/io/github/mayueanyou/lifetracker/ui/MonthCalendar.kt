package io.github.mayueanyou.lifetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.mayueanyou.lifetracker.money.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A month at a glance, opened by tapping the day's name: pick any day instead
 * of stepping back one at a time, each with what it cost.
 */
@Composable
fun MonthCalendar(shown: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(shown)) }
    val spent by produceState<Money.Month?>(null, month) {
        value = withContext(Dispatchers.IO) { Money.month(context, month) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹", fontSize = 22.sp) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        spent?.total?.let {
                            Text(Money.format(it, spent?.currency), fontSize = 13.sp, color = MONEY_GREEN)
                        }
                    }
                    TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.from(today)) {
                        Text("›", fontSize = 22.sp)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                        Text(it, Modifier.weight(1f), fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(4.dp))
                val lead = month.atDay(1).dayOfWeek.value - 1
                val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (i in 0 until 7) {
                            val date = week.getOrNull(i)
                            Box(Modifier.weight(1f).aspectRatio(0.9f)) {
                                if (date != null) DayCell(date, today, shown, spent?.byDay?.get(date), spent?.currency) {
                                    onPick(date)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, today: LocalDate, shown: LocalDate, spent: Double?, currency: String?, onPick: () -> Unit) {
    val future = date > today
    val selected = date == shown
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth().aspectRatio(0.9f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.primary else colors.surfaceVariant.copy(alpha = 0.5f))
            .then(if (date == today && !selected) Modifier.border(1.dp, colors.primary, RoundedCornerShape(8.dp)) else Modifier)
            .alpha(if (future) 0.35f else 1f)
            .then(if (future) Modifier else Modifier.clickable(onClick = onPick)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("${date.dayOfMonth}", fontSize = 14.sp, lineHeight = 16.sp, color = if (selected) colors.onPrimary else colors.onSurface)
        if (spent != null) {
            // Whole units once it is in the hundreds: there is no room for cents.
            Text(if (spent >= 100) Money.format(spent, currency).substringBefore(".")
                 else Money.format(spent, currency),
                fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1,
                color = if (selected) colors.onPrimary else MONEY_GREEN)
        }
    }
}
