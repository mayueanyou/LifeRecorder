package io.github.mayueanyou.lifetracker.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.mayueanyou.lifetracker.location.Recording
import io.github.mayueanyou.lifetracker.money.MoneyApps
import io.github.mayueanyou.lifetracker.money.NotificationReader
import io.github.mayueanyou.lifetracker.screentime.UsageReader

/**
 * Where Android's switches live. Usage access and notification access can't
 * be asked for in a pop-up, so each opens straight at Life Tracker's own
 * switch -- or, on a phone that can't, at the list of apps.
 */
object Access {
    fun openUsage(context: Context) = open(context,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    fun openNotificationReading(context: Context) = open(context,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, NotificationReader::class.java).flattenToString())
        else null,
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    fun openAppInfo(context: Context) = open(context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")), null)

    /** Where "Unrestricted" lives on this phone, and the taps from there -- each maker names it its own way. */
    class BatteryWay(val opens: Intent, val steps: List<String>)

    fun batteryWay(context: Context): BatteryWay {
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        // Stock Android's own battery page for one app, as on Pixel phones: one tap closer.
        val page = Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", Uri.parse("package:${context.packageName}"))
        val back = "Come back to Life Tracker: press **Back** until you see it, or open it from your recent apps."
        return when (Build.MANUFACTURER.lowercase()) {
            "samsung" -> BatteryWay(appInfo, listOf("Tap **Battery**.", "Choose **Unrestricted**.", back))
            "xiaomi", "redmi", "poco" -> BatteryWay(appInfo,
                listOf("Tap **Battery saver** (on some phones: **Battery**).", "Choose **No restrictions**.", back))
            "oneplus", "oppo", "realme" -> BatteryWay(appInfo,
                listOf("Tap **Battery usage**.", "Turn on **Allow background activity**.",
                    "If there is **Optimize battery use**, turn it off.", back))
            else -> when {
                Build.VERSION.SDK_INT >= 34 && resolves(context, page) -> BatteryWay(page,
                    listOf("Tap **Allow background usage**.", "Choose **Unrestricted**.", back))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && resolves(context, page) -> BatteryWay(page,
                    listOf("Choose **Unrestricted**.", back))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> BatteryWay(appInfo,
                    listOf("Tap **App battery usage** (on some phones: **Battery**).",
                        "If you see **Allow background usage**, tap it.", "Choose **Unrestricted**.", back))
                else -> BatteryWay(appInfo,
                    listOf("Tap **Battery** (it may be under **Advanced**).", "Tap **Battery optimization**.",
                        "At the top, switch **Not optimized** to **All apps**.",
                        "Tap **Life Tracker**, choose **Don't optimize**, then **Done**.", back))
            }
        }
    }

    fun openBattery(context: Context) {
        val way = batteryWay(context)
        if (runCatching { context.startActivity(way.opens) }.isFailure) openAppInfo(context)
    }

    /** Android's list of every app's battery setting: the way that works when the menus are named differently. */
    fun openBatteryList(context: Context) = open(context,
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    private fun resolves(context: Context, intent: Intent) =
        context.packageManager.resolveActivity(intent, 0) != null

    /**
     * Installed from Google Play. Android holds back notification access
     * ("restricted setting") only from apps installed some other way.
     */
    fun fromPlay(context: Context): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
        installer == "com.android.vending"
    }.getOrDefault(false)

    private fun open(context: Context, first: Intent?, fallback: Intent?) {
        for (intent in listOfNotNull(first, fallback)) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }
}

/** "Explain first, then ask": a [DisclosureDialog] shown on demand, with what to do after "Continue". */
@Composable
fun rememberDisclosure(): (Disclosure, () -> Unit) -> Unit {
    var showing by remember { mutableStateOf<Pair<Disclosure, () -> Unit>?>(null) }
    showing?.let { (what, then) ->
        DisclosureDialog(what, onContinue = { showing = null; then() }, onDismiss = { showing = null })
    }
    return remember { { what, then -> showing = what to then } }
}

/** The way back from "Don't allow" twice, after which Android shows no pop-up at all: App info. */
private fun refusedForGood(context: Context, permission: String) =
    (context as? Activity)?.shouldShowRequestPermissionRationale(permission) == false

object Setup {
    /** Everything the app needs, in the order it is asked for. Any of it missing brings the guide back. */
    fun needed(context: Context) =
        !Recording.hasForeground(context) || !Recording.hasBackground(context) || !Recording.hasNotifications(context) ||
            !Recording.batteryUnrestricted(context) || !UsageReader(context).hasAccess() || !MoneyApps.hasAccess(context)
}

/**
 * The first open: one card at a time for everything the app needs -- location,
 * "Allow all the time", the notice, battery, screen time and notifications.
 * Each is explained, then Android asks or opens its page at Life Tracker's
 * switch; coming back moves on to the next by itself. The app opens only
 * once all are done.
 */
@Composable
fun SetupGuide(onDone: () -> Unit) {
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    val disclose = rememberDisclosure()
    // Refused twice: Android no longer shows its pop-up, so the button opens App info instead.
    var blocked by remember { mutableStateOf(setOf<String>()) }
    // Back from the battery page and still not Unrestricted: say so, and offer the other way.
    var triedBattery by remember { mutableStateOf(false) }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        checks++
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true &&
            refusedForGood(context, Manifest.permission.ACCESS_FINE_LOCATION)) blocked += "location"
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        checks++
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            refusedForGood(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) blocked += "background"
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        checks++
        // A recorder started before the notice was allowed shows it only once started again.
        if (granted && Recording.wanted(context)) Recording.start(context)
        if (!granted && refusedForGood(context, Manifest.permission.POST_NOTIFICATIONS)) blocked += "notifications"
    }

    // Recording itself starts as soon as location is allowed (MainActivity.onResume).
    val steps = remember(checks) {
        buildList {
            add(SetupStep("location", "Your location",
                "So your day can be drawn as a map: where you stayed and how you got there.",
                Recording.hasForeground(context)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(SetupStep("background", "Allow all the time",
                "So it keeps recording when the app is closed. Android shows its own page for this — choose \"Allow all the time\", then come back.",
                Recording.hasBackground(context)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(SetupStep("notifications", "The recording notice",
                "A small \"Recording your day\" notification, there whenever your location is recorded.",
                Recording.hasNotifications(context)))
            add(SetupStep("battery", "Battery: Unrestricted",
                "So Android doesn't stop recording to save battery. Tap the button below, then:",
                Recording.batteryUnrestricted(context)))
            add(SetupStep("usage", "Screen time",
                "So each day shows how long you used each app, and what was on screen during each place you stayed. Android opens its Usage access page — turn on Life Tracker, then come back.",
                UsageReader(context).hasAccess()))
            add(SetupStep("reading", "Money from your bank's notifications",
                "So card purchases appear by themselves, at the place you paid. Android opens its Notification access page — turn on Life Tracker, then come back.",
                MoneyApps.hasAccess(context)))
        }
    }
    val current = steps.indexOfFirst { !it.done }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Life Tracker", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(if (current < 0) "All set." else "Your day, recorded by itself. ${steps.size} quick steps to start — each explained first.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))

        steps.forEachIndexed { i, step ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (step.done) "✓" else "${i + 1}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            color = if (step.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp))
                        Text(step.title, style = MaterialTheme.typography.titleMedium,
                            color = if (step.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    if (i == current) {
                        Spacer(Modifier.height(6.dp))
                        Text(step.why, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (step.key == "battery") BatterySteps()
                        Spacer(Modifier.height(10.dp))
                        val stuck = step.key in blocked
                        if (stuck) Text("Android won't ask again here. In App info, open Permissions and allow it.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                        Button(onClick = {
                            when {
                                stuck -> Access.openAppInfo(context)
                                step.key == "location" -> disclose(Disclosure.LOCATION) {
                                    askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }
                                step.key == "background" -> disclose(Disclosure.BACKGROUND_LOCATION) {
                                    askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                }
                                step.key == "notifications" -> askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                                step.key == "battery" -> { triedBattery = true; Access.openBattery(context) }
                                step.key == "usage" -> disclose(Disclosure.USAGE) { Access.openUsage(context) }
                                else -> disclose(Disclosure.NOTIFICATIONS) { Access.openNotificationReading(context) }
                            }
                        }) { Text(when { stuck -> "Open App info"; step.key == "battery" -> "Open battery settings"; else -> "Allow" }) }
                        if (step.key == "battery") BatteryHelp(triedBattery)
                        if (step.key == "reading") RestrictedHint()
                    }
                }
            }
        }

        if (current < 0) {
            Spacer(Modifier.height(4.dp))
            Text("Recording has started.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onDone, Modifier.fillMaxWidth()) { Text("Show my day") }
        }
    }
}

/** The taps from where the button lands to "Unrestricted", numbered, in the words this phone uses. */
@Composable
fun BatterySteps() {
    val context = LocalContext.current
    val way = remember { Access.batteryWay(context) }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        way.steps.forEachIndexed { i, step ->
            Row {
                Text("${i + 1}.", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(24.dp))
                Text(bold(step))
            }
        }
    }
}

/** Not done after a try: the menus may be named differently here, so the list every phone has. */
@Composable
fun BatteryHelp(tried: Boolean) {
    val context = LocalContext.current
    if (tried) Text("Not yet — Android still has Life Tracker on Optimized. Try the steps again, or:",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    TextButton(onClick = { Access.openBatteryList(context) }, contentPadding = PaddingValues(0.dp)) {
        Text("Can't find it? Use Android's list of all apps")
    }
    if (tried) Text("In that list, choose **All apps** at the top if needed, tap **Life Tracker**, and pick **Unrestricted** or **Don't optimize**."
        .let { bold(it) }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** **Words like this** in bold: the names Android shows, so they stand out on screen. */
private fun bold(text: String) = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
    }
}

private class SetupStep(val key: String, val title: String, val why: String, val done: Boolean)
