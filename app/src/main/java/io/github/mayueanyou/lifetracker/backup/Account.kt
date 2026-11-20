package io.github.mayueanyou.lifetracker.backup

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Your Google account, for the backup only. The one thing asked of Google
 * is drive.file: the files this app creates in your Drive, and nothing else
 * there. The account is remembered on the phone; no password ever passes
 * through the app -- Google's own sign-in screen handles that.
 */
object GoogleAccount {
    private const val PREFS = "backup"
    private const val KEY_EMAIL = "email"
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    fun email(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EMAIL, null)

    fun signedIn(context: Context) = email(context) != null

    fun remember(context: Context, email: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_EMAIL, email).apply()
    }

    private fun request(context: Context): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE)))
        email(context)?.let { b.setAccount(Account(it, "com.google")) }
        return b.build()
    }

    /** Either a token now, or the screen Google needs to show first (choosing an account, allowing access). */
    sealed interface Answer {
        data class Token(val token: String) : Answer
        data class NeedsScreen(val intent: PendingIntent) : Answer
    }

    suspend fun authorize(context: Context): Answer {
        val result = suspendCancellableCoroutine<AuthorizationResult> { cont ->
            Identity.getAuthorizationClient(context).authorize(request(context))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val pending = result.pendingIntent
        return if (result.hasResolution() && pending != null) Answer.NeedsScreen(pending)
        else Answer.Token(result.accessToken ?: error("Google gave no access token"))
    }

    /** The token after Google's screen came back. */
    fun fromScreen(context: Context, data: Intent?): String =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
            ?: error("Google gave no access token")

    /**
     * What went wrong, in words someone can act on. Code 10 (developer error)
     * is almost always this install's signing key missing from the app's
     * Google Cloud OAuth clients -- e.g. a Play install, signed by Google's key.
     */
    fun explain(e: Throwable): String = when (e) {
        is ApiException -> when (e.statusCode) {
            CommonStatusCodes.DEVELOPER_ERROR -> "10 — this copy of the app isn't registered with Google yet (signing key). Please report it."
            CommonStatusCodes.NETWORK_ERROR -> "7 — no connection to Google. Try again online."
            CommonStatusCodes.CANCELED -> "16 — cancelled"
            else -> "${e.statusCode} — ${CommonStatusCodes.getStatusCodeString(e.statusCode)}" +
                (e.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        }
        else -> e.message ?: e.javaClass.simpleName
    }

    /** A token without showing anything -- for the nightly backup. Null when Google needs you first. */
    suspend fun silentToken(context: Context): String? =
        if (!signedIn(context)) null else (authorize(context) as? Answer.Token)?.token
}
