package io.github.mayueanyou.lifetracker.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.mayueanyou.lifetracker.Diagnostics
import io.github.mayueanyou.lifetracker.backup.Backup
import io.github.mayueanyou.lifetracker.backup.BackupWorker
import io.github.mayueanyou.lifetracker.backup.Drive
import io.github.mayueanyou.lifetracker.backup.GoogleAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Who is signed in, what is under way ("Restoring 3 of 40"), and a count that moves on after each action. */
class Google(email: String?) {
    var email by mutableStateOf(email)
    var busy by mutableStateOf<String?>(null)
    var version by mutableIntStateOf(0)
    /** "signin", "backup", "restore" or "signout". */
    var run: (String) -> Unit = {}
}

/**
 * The Google account, for Settings and the setup guide alike: sign in, back
 * up, restore, sign out. Each needs a token, which may first need Google's
 * own screen (choose an account, allow).
 */
@Composable
fun rememberGoogle(): Google {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { Google(GoogleAccount.email(context)) }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    var pendingRetried by remember { mutableStateOf(false) }
    val toast = { text: String -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }

    // Set when Drive refused the token: it is dropped and the action tried again, once.
    var retryAction by remember { mutableStateOf<String?>(null) }

    suspend fun perform(action: String, token: String, retried: Boolean = false) {
        try {
            when (action) {
                "signin" -> {
                    state.busy = "Signing in…"
                    val who = withContext(Dispatchers.IO) { Drive(token).email() }
                    GoogleAccount.remember(context, who)
                    state.email = who
                    BackupWorker.schedule(context)
                    Diagnostics.note(context, "google sign-in: signed in")
                    // What Drive already holds comes back first -- your days, places and
                    // money choices -- and only then does this phone back up. Either
                    // failing leaves you signed in: the nightly run tries again.
                    state.busy = "Restoring your days…"
                    val restored = try {
                        withContext(Dispatchers.IO) { Backup.restore(context, token) { state.busy = it } }
                    } catch (e: Exception) {
                        if (e is Drive.HttpError && e.code == 401) throw e      // a refused token: tried again below
                        toast("Signed in, but restoring from Drive failed: ${e.message ?: e.javaClass.simpleName}")
                        return
                    }
                    state.busy = "Backing up…"
                    withContext(Dispatchers.IO) { Backup.run(context, token) { state.busy = it } }
                    toast(if (restored > 0) "Signed in. Your days are back from Google Drive." else "Signed in. Backing up each night.")
                }
                "backup" -> {
                    state.busy = "Backing up…"
                    val n = withContext(Dispatchers.IO) { Backup.run(context, token) { state.busy = it } }
                    toast(if (n == 0) "Already up to date." else "Backed up $n day${if (n == 1) "" else "s"}.")
                }
                "restore" -> {
                    state.busy = "Restoring…"
                    val n = withContext(Dispatchers.IO) { Backup.restore(context, token) { state.busy = it } }
                    toast(if (n == 0) "Nothing to restore — this phone already has it all." else "Restored $n record${if (n == 1) "" else "s"}.")
                }
                "signout" -> {
                    withContext(Dispatchers.IO) { Backup.signOut(context, token) }
                    BackupWorker.cancel(context)
                    state.email = null
                    toast("Signed out. Your files stay in your Drive.")
                }
            }
        } catch (e: Exception) {
            Diagnostics.note(context, "google $action: failed -- ${e.javaClass.simpleName}: ${e.message}")
            if (e is Drive.HttpError && e.code == 401 && !retried) {
                // The phone handed out a token Google no longer honours: drop it, ask again.
                withContext(Dispatchers.IO) { GoogleAccount.drop(context, token) }
                retryAction = action
            } else toast("Google Drive: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            state.busy = null
            state.version++
        }
    }

    val googleScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val action = pendingAction ?: return@rememberLauncherForActivityResult
        pendingAction = null
        // Google's screen reports its own failures in the result too, so read it either way:
        // "cancelled" only when there is truly nothing more to say.
        val read = runCatching { GoogleAccount.fromScreen(context, result.data) }
        val token = read.getOrNull()
        if (token == null) {
            state.busy = null
            // Backing out of Google's screen returns nothing at all.
            val why = if (result.data == null) null else read.exceptionOrNull()?.let { GoogleAccount.explain(it) }
            if (why != null) Diagnostics.note(context, "google sign-in ($action): result ${result.resultCode}, $why")
            if (action != "signout") toast(if (why == null || result.resultCode == Activity.RESULT_CANCELED && why.startsWith("16"))
                "Sign-in was cancelled." else "Google sign-in failed: $why")
            else scope.launch { withContext(Dispatchers.IO) { Backup.signOut(context, null) }; BackupWorker.cancel(context); state.email = null }
            return@rememberLauncherForActivityResult
        }
        scope.launch { perform(action, token, pendingRetried) }
    }

    fun withGoogle(action: String, retried: Boolean = false) {
        scope.launch {
            state.busy = "Connecting to Google…"
            val answer = try {
                withContext(Dispatchers.IO) { GoogleAccount.authorize(context) }
            } catch (e: Exception) {
                state.busy = null
                if (action == "signout") {
                    withContext(Dispatchers.IO) { Backup.signOut(context, null) }
                    BackupWorker.cancel(context); state.email = null
                } else {
                    Diagnostics.note(context, "google sign-in ($action): ${GoogleAccount.explain(e)}")
                    toast("Google sign-in failed: ${GoogleAccount.explain(e)}")
                }
                return@launch
            }
            when (answer) {
                is GoogleAccount.Answer.Token -> perform(action, answer.token, retried)
                is GoogleAccount.Answer.NeedsScreen -> {
                    pendingAction = action
                    pendingRetried = retried
                    googleScreen.launch(IntentSenderRequest.Builder(answer.intent.intentSender).build())
                }
            }
        }
    }

    state.run = { withGoogle(it) }
    retryAction?.let { action ->
        LaunchedEffect(action) { retryAction = null; withGoogle(action, retried = true) }
    }

    return state
}
