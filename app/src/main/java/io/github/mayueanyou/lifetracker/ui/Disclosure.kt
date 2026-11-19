package io.github.mayueanyou.lifetracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * What the app collects and why, shown in full before Android's own prompt
 * for each sensitive access -- what Google Play calls a prominent disclosure.
 * Nothing is asked of Android until "Continue" is tapped.
 */
enum class Disclosure(val title: String, val body: String, val keptHow: String) {
    LOCATION(
        "Your location",
        "Life Tracker collects location data to record the places you visit and the journeys between them, " +
            "even when the app is closed or not in use. That is how your day appears as a map and a timeline " +
            "without you opening the app.",
        "Your locations are kept on this phone, and in your own Google Drive only if you turn on backup. " +
            "To name the places you stay, the coordinates of a spot can be sent to OpenStreetMap services, " +
            "with no name or account attached — you can turn that off in Settings. Nothing is sent to the developer.",
    ),
    BACKGROUND_LOCATION(
        "Location all the time",
        "To record your day while Life Tracker is closed, Android needs \"Allow all the time\". Life Tracker " +
            "collects location data to record the places you visit and the journeys between them, even when " +
            "the app is closed or not in use. A \"Recording your day\" notification shows whenever it does, " +
            "and you can turn recording off at any time.",
        "Your locations are kept on this phone, and in your own Google Drive only if you turn on backup. " +
            "Nothing is sent to the developer.",
    ),
    NOTIFICATIONS(
        "Reading notifications",
        "Life Tracker reads the notifications your phone receives to find card purchases your bank tells you " +
            "about, so it can show where your money went without a bank login. Only a notification that reads " +
            "as money spent is kept — the app it came from, its words, the amount and the shop.",
        "Every other notification is ignored, and never stored or sent anywhere. Purchases are kept on this " +
            "phone, and in your own Google Drive only if you turn on backup. Nothing is sent to the developer.",
    ),
    USAGE(
        "Usage access",
        "Life Tracker reads which apps you use and when, to show your screen time per day, per app, and " +
            "during each place you stayed.",
        "This is kept on this phone, and in your own Google Drive only if you turn on backup. Nothing is sent " +
            "to the developer.",
    ),
}

@Composable
fun DisclosureDialog(disclosure: Disclosure, onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(disclosure.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(disclosure.body)
                Spacer(Modifier.height(10.dp))
                Text("Where it is kept", fontWeight = FontWeight.SemiBold)
                Text(disclosure.keptHow, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onContinue) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
