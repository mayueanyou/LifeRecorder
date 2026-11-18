package io.github.mayueanyou.liferecorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.mayueanyou.liferecorder.engine.DayBuilder
import io.github.mayueanyou.liferecorder.engine.DayRecord
import io.github.mayueanyou.liferecorder.engine.Entry
import io.github.mayueanyou.liferecorder.engine.Mode
import io.github.mayueanyou.liferecorder.data.LifeDb
import io.github.mayueanyou.liferecorder.engine.Fix
import io.github.mayueanyou.liferecorder.location.Recording
import io.github.mayueanyou.liferecorder.places.Lookups
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HOUR: Dp = 96.dp          // the timeline's scale: an hour is this tall
private val MIN_BLOCK: Dp = 26.dp     // the least an entry is drawn at, however brief
private val AXIS: Dp = 52.dp
private val ORANGE = Color(0xFFF97316)

/**
 * The day, as the Record web app shows it: the map on top, and below it the
 * day as a calendar -- a 24-hour axis, each stay and journey placed at its
 * time and as tall as it lasted. Tapping either side selects the same entry
 * on the other.
 */
@Composable
fun RecordScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var day by remember { mutableStateOf(LocalDate.now()) }
    var record by remember { mutableStateOf<DayRecord?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val scope = rememberCoroutineScope()
    // The "where am I" button: a fresh position, centred on the map.
    var located by remember { mutableStateOf<Fix?>(null) }
    var locateRequest by remember { mutableIntStateOf(0) }
    // Somewhere to centre an empty day: the last place you were recorded at all.
    var lastEver by remember { mutableStateOf<Fix?>(null) }
    LaunchedEffect(refresh) {
        lastEver = withContext(Dispatchers.IO) {
            LifeDb.get(context).fixes().latest(1).firstOrNull()?.let { Fix(it.time, it.latitude, it.longitude, it.accuracy) }
        }
    }

    LaunchedEffect(day, refresh) {
        selected = null
        while (true) {
            val built = withContext(Dispatchers.IO) { DayBuilder.build(context, day) }
            record = built
            // Spots still without a name are looked up once, then the day redrawn.
            if (built.missingBuildings.isNotEmpty() || built.missingStreets.isNotEmpty()) {
                val answered = withContext(Dispatchers.IO) {
                    Lookups.fill(context, built.missingBuildings.take(10), built.missingStreets.take(10))
                }
                if (answered > 0) record = withContext(Dispatchers.IO) { DayBuilder.build(context, day) }
            }
            if (day != LocalDate.now()) break
            delay(60_000)      // today keeps moving
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        DayHeader(day, record, onDay = { day = it }, onLocate = {
            scope.launch {
                val fix = currentPosition(context) ?: lastEver
                if (fix == null) {
                    Toast.makeText(context, "No position yet — allow location in Settings", Toast.LENGTH_SHORT).show()
                } else {
                    located = fix
                    locateRequest++
                }
            }
        })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val mapHeight = maxHeight * 0.38f
            Column {
                // Clipped: the map view draws past its own edges otherwise,
                // over the date bar and the timeline.
                Box(Modifier.fillMaxWidth().height(mapHeight).clipToBounds()) {
                    DayMap(
                        entries = record?.entries.orEmpty(),
                        selected = selected,
                        here = if (day == LocalDate.now()) located ?: record?.lastFix ?: lastEver else null,
                        fallback = lastEver,
                        centreOn = located,
                        centreRequest = locateRequest,
                        onSelect = { selected = it },
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                    )
                    if (record != null && record!!.entries.isEmpty()) {
                        EmptyDay(day, Recording.wanted(context), onOpenSettings)
                    }
                }
                Timeline(record, day, selected) { i -> selected = if (selected == i) null else i }
            }
        }
    }
}

@Composable
private fun EmptyDay(day: LocalDate, recording: Boolean, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Card {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    !recording -> {
                        Text("Recording is off", style = MaterialTheme.typography.titleMedium)
                        Text("Turn on \"Record my location\" in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onOpenSettings) { Text("Open Settings") }
                    }
                    day == LocalDate.now() -> {
                        Text("Nothing recorded yet today", style = MaterialTheme.typography.titleMedium)
                        Text("Stays appear after 8 minutes in one place; journeys as you travel.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    else -> Text("Nothing was recorded on this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, record: DayRecord?, onDay: (LocalDate) -> Unit, onLocate: () -> Unit) {
    val today = LocalDate.now()
    val name = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
    val places = record?.entries?.count { it is Entry.StayEntry } ?: 0
    var picking by remember { mutableStateOf(false) }
    if (picking) MonthCalendar(day, onPick = { picking = false; onDay(it) }, onDismiss = { picking = false })
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onDay(day.minusDays(1)) }) { Text("‹", fontSize = 22.sp) }
        // Tap the name for the month, to jump to any day.
        TextButton(onClick = { picking = true }) {
            Text("$name ▾", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        TextButton(onClick = { onDay(day.plusDays(1)) }, enabled = day < today) { Text("›", fontSize = 22.sp) }
        TextButton(onClick = onLocate) { Text("◎", fontSize = 22.sp) }
        Spacer(Modifier.weight(1f))
        Text(if (record == null) "" else "$places place${if (places == 1) "" else "s"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
    }
}

private data class Block(val index: Int, val top: Dp, val height: Dp)

@Composable
private fun Timeline(record: DayRecord?, day: LocalDate, selected: Int?, onTap: (Int) -> Unit) {
    val zone = ZoneId.systemDefault()
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val entries = record?.entries.orEmpty()
    val minuteOf = { t: Long -> ((t - dayStart) / 60_000.0).coerceIn(0.0, 1440.0) }
    val perMinute = HOUR / 60

    // Placed by the clock. A brief entry gets a little room, and one that
    // would then overlap it is nudged down just enough -- but its end stays
    // at its true time, so the drift never accumulates.
    val blocks = remember(entries) {
        var bottom = 0.dp
        entries.mapIndexed { i, e ->
            val top = maxOf(perMinute * minuteOf(e.start).toFloat(), bottom)
            val height = maxOf(MIN_BLOCK, perMinute * minuteOf(e.end).toFloat() - top)
            bottom = top + height
            Block(i, top, height)
        }
    }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Only once the day shown is the day asked for -- just after switching
    // day, the previous day's entries are still on screen, and scrolling to
    // them put a new day at the wrong hour.
    LaunchedEffect(record?.day, entries.isNotEmpty()) {
        if (record?.day != day) return@LaunchedEffect
        // Today opens around now; another day at its first entry after the night.
        val now = System.currentTimeMillis()
        val minute = if (day == LocalDate.now()) minuteOf(now) - 180
        else entries.firstOrNull { minuteOf(it.start) >= 300 }?.let { minuteOf(it.start) - 30 } ?: 0.0
        scroll.scrollTo(with(density) { (perMinute * maxOf(0.0, minute).toFloat()).roundToPx() })
    }
    LaunchedEffect(selected) {
        val b = blocks.firstOrNull { it.index == selected } ?: return@LaunchedEffect
        scroll.animateScrollTo(with(density) { (b.top - 80.dp).coerceAtLeast(0.dp).roundToPx() })
    }

    Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 8.dp, bottom = 24.dp)) {
        val total = maxOf(HOUR * 24, blocks.lastOrNull()?.let { it.top + it.height } ?: 0.dp)
        Box(Modifier.fillMaxWidth().height(total)) {
            for (h in 0..24) {
                Box(Modifier.offset(y = HOUR * h).padding(start = AXIS).fillMaxWidth().height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)))
                Text(hourName(h), Modifier.offset(y = HOUR * h - 7.dp).width(AXIS - 8.dp),
                    textAlign = TextAlign.End, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (day == LocalDate.now()) {
                Box(Modifier.offset(x = AXIS - 6.dp, y = perMinute * minuteOf(System.currentTimeMillis()).toFloat() - 4.dp)
                    .size(8.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                Box(Modifier.offset(y = perMinute * minuteOf(System.currentTimeMillis()).toFloat() - 1.dp)
                    .padding(start = AXIS - 2.dp).fillMaxWidth().height(2.dp).background(Color(0xFFEF4444)))
            }
            var stayNo = 0
            blocks.forEach { b ->
                val e = entries[b.index]
                val number = if (e is Entry.StayEntry) (++stayNo).toString() else null
                EventBlock(e, number, b, b.index == selected) { onTap(b.index) }
            }
        }
    }
}

@Composable
private fun EventBlock(entry: Entry, number: String?, block: Block, on: Boolean, onTap: () -> Unit) {
    val tiny = block.height < 40.dp
    val short = block.height < 72.dp
    val (edge, fill) = when (entry) {
        is Entry.StayEntry -> Color(0xFF6366F1) to Color(0xFF6366F1).copy(alpha = .16f)
        is Entry.JourneyEntry -> Color(modeColour(entry.journey.mode)) to Color(0xFF0EA5E9).copy(alpha = .10f)
    }
    Box(
        // Inset rather than shifted: shifting kept the full width and pushed
        // the right edge -- and the duration -- off the screen.
        Modifier.offset(y = block.top)
            .padding(start = AXIS + 4.dp, end = 12.dp)
            .fillMaxWidth()
            .height(block.height - 1.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (on) ORANGE.copy(alpha = .22f) else fill)
            .border(if (on) 1.dp else 0.dp, if (on) ORANGE.copy(alpha = .55f) else Color.Transparent, RoundedCornerShape(7.dp))
            .clickable(onClick = onTap),
    ) {
        Box(Modifier.width(3.dp).fillMaxSize().background(if (on) ORANGE else edge))
        Row(
            Modifier.padding(start = 11.dp, end = 8.dp, top = if (tiny) 0.dp else 5.dp).fillMaxWidth()
                .let { if (tiny) it.fillMaxSize() else it },
            verticalAlignment = if (tiny) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val badge = number ?: (entry as Entry.JourneyEntry).journey.mode.icon
            Box(
                Modifier.size(if (tiny) 18.dp else 24.dp).clip(CircleShape)
                    .background(if (number == null) Color.Transparent else if (on) ORANGE else Color(0xFF6366F1).copy(alpha = .25f)),
                contentAlignment = Alignment.Center,
            ) { Text(badge, fontSize = if (number == null) 14.sp else 11.sp, fontWeight = FontWeight.SemiBold) }
            Column(Modifier.weight(1f)) {
                Text(title(entry), maxLines = if (short) 1 else 2, overflow = TextOverflow.Ellipsis,
                    fontSize = if (tiny) 13.sp else if (entry is Entry.JourneyEntry) 14.sp else 16.sp,
                    color = if (entry is Entry.JourneyEntry) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (!tiny) {
                    if (!short) Text(kind(entry), fontSize = 11.sp, letterSpacing = .5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(details(entry), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(spellMinutes((entry.end - entry.start) / 60_000), fontSize = if (tiny) 12.sp else 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun title(e: Entry) = when (e) {
    is Entry.StayEntry -> (if (e.stay.live) "▸ " else "") + (e.stay.place ?: "Unknown place")
    is Entry.JourneyEntry -> (if (e.journey.live) "▸ " else "") + "${e.journey.fromPlace} → ${e.journey.toPlace}"
}

private fun kind(e: Entry) = when (e) {
    is Entry.StayEntry -> "STAY"
    is Entry.JourneyEntry -> "JOURNEY · " + e.journey.mode.name.lowercase()
}

private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault())
private fun clock(t: Long) = CLOCK.format(Instant.ofEpochMilli(t))

private fun details(e: Entry): String {
    val span = "${clock(e.start)} – ${clock(e.end)}"
    return when (e) {
        is Entry.StayEntry -> span + if (!e.stay.exact && e.stay.placeSource == "osm") " · approximate" else ""
        is Entry.JourneyEntry -> {
            val j = e.journey
            if (j.mode == Mode.MOVING) span else "$span · ${miles(j.distanceM)} · avg ${mph(j.avgKmh)}"
        }
    }
}

private fun miles(m: Double): String {
    val mi = m / 1609.344
    return if (mi < 0.1) "${(m * 3.28084).toInt()} ft" else String.format(Locale.US, "%.1f mi", mi)
}

private fun mph(kmh: Double) = String.format(Locale.US, "%.1f mph", kmh / 1.609344)

private fun hourName(h: Int) = "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12 || h == 24) "AM" else "PM"}"

fun spellMinutes(minutes: Long): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0L -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

/**
 * Where the phone is right now: a fresh reading if one comes within a few
 * seconds, otherwise the last one Android has. Null without permission.
 */
@android.annotation.SuppressLint("MissingPermission")
private suspend fun currentPosition(context: android.content.Context): Fix? {
    if (!Recording.hasForeground(context)) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val fresh = withTimeoutOrNull(6_000) {
        suspendCancellableCoroutine<android.location.Location?> { cont ->
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
    }
    val location = fresh ?: suspendCancellableCoroutine<android.location.Location?> { cont ->
        client.lastLocation.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
    }
    return location?.let { Fix(it.time, it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null) }
}
