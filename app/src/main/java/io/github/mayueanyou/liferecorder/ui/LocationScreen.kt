package io.github.mayueanyou.liferecorder.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.mayueanyou.liferecorder.data.FixRow
import io.github.mayueanyou.liferecorder.data.LifeDb
import io.github.mayueanyou.liferecorder.location.Recording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class LocationState(val today: Int, val recent: List<FixRow>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { LifeDb.get(context).fixes() }
    var checks by remember { mutableIntStateOf(0) }       // bumped to re-read permissions
    var recording by remember { mutableStateOf(Recording.wanted(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    val askForeground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checks++ }
    val askOne = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }

    val foreground = remember(checks) { Recording.hasForeground(context) }
    val background = remember(checks) { Recording.hasBackground(context) }
    val notifications = remember(checks) { Recording.hasNotifications(context) }
    val unrestricted = remember(checks) { batteryUnrestricted(context) }

    // A fresh look every few seconds while the screen is open.
    val state by produceState(LocationState(0, emptyList()), checks) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                LocationState(dao.countBetween(start, start + DAY_MILLIS), dao.latest(30))
            }
            delay(5_000)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Location") }) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Before recording", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Your location is kept on this phone only. Android asks in steps:",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Step("1. Location while using the app", foreground) {
                            askForeground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                        Step("2. \"Allow all the time\" — keeps recording with the app closed", background, enabled = foreground) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) askOne.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                        Step("3. Notifications — for the small \"recording\" notice", notifications) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askOne.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        Step("4. Battery: Unrestricted — so Android never pauses it", unrestricted) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Record my location", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (recording) "On — a position every 10 m you move" else "Off",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = recording,
                            enabled = foreground,
                            onCheckedChange = { on ->
                                if (on) Recording.start(context) else Recording.stop(context)
                                recording = on
                            },
                        )
                    }
                }
            }
            item {
                Column {
                    Text("Today: ${state.today} positions", style = MaterialTheme.typography.titleMedium)
                    val last = state.recent.firstOrNull()
                    Text(
                        if (last == null) "No position recorded yet."
                        else "Last: ${clock(last.time)} · ±${last.accuracy?.toInt() ?: "?"} m",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { scope.launch { exportToday(context) } }) {
                        Text("Export today")
                    }
                }
            }
            items(state.recent, key = { it.id }) { fix ->
                Column {
                    Text(
                        "${clock(fix.time)}   %.5f, %.5f".format(fix.latitude, fix.longitude),
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    )
                    Text(
                        listOfNotNull(
                            fix.accuracy?.let { "±${it.toInt()} m" },
                            fix.speed?.let { "%.0f km/h".format(it * 3.6f) },
                            fix.provider,
                        ).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    )
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun Step(label: String, done: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text((if (done) "✓ " else "") + label, Modifier.weight(1f),
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (!done) Button(onClick = onClick, enabled = enabled) { Text("Allow") }
    }
}

private fun batteryUnrestricted(context: Context): Boolean {
    val power = context.getSystemService(android.os.PowerManager::class.java)
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private fun clock(time: Long) = CLOCK.format(Instant.ofEpochMilli(time))
private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

/** Today's positions as a JSON file, handed to whichever app the user picks. */
private suspend fun exportToday(context: Context) {
    val day = LocalDate.now()
    val file = withContext(Dispatchers.IO) {
        val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val fixes = LifeDb.get(context).fixes().between(start, start + DAY_MILLIS)
        val list = JSONArray()
        fixes.forEach { f ->
            list.put(JSONObject().apply {
                put("time", Instant.ofEpochMilli(f.time).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString())
                put("latitude", f.latitude); put("longitude", f.longitude)
                f.accuracy?.let { put("accuracy", it.toDouble()) }
                f.speed?.let { put("speed", it.toDouble()) }
                f.bearing?.let { put("bearing", it.toDouble()) }
                f.altitude?.let { put("altitude", it) }
                f.provider?.let { put("provider", it) }
            })
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        File(dir, "life-recorder-$day.json").apply {
            writeText(JSONObject().put("date", day.toString()).put("fixes", list).toString(1))
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Export today's positions"))
}
