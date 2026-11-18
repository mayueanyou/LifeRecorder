package io.github.mayueanyou.liferecorder.money

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import java.util.Currency
import java.util.Locale

/**
 * Which apps' notifications count as money. Banks by default: the bank sees
 * every card payment however it was made, while a wallet sees only its own --
 * counting both doubled the day. Any other app that has sent something that
 * reads as a purchase is listed in Settings, off until you turn it on.
 */
object MoneyApps {
    private const val PREFS = "money"
    private const val KEY_ON = "apps_on"
    private const val KEY_OFF = "apps_off"

    val BANKS = setOf(
        "com.infonow.bofa",                       // Bank of America
        "com.chase.sig.android",                  // Chase
        "com.wf.wellsfargomobile",                // Wells Fargo
        "com.konylabs.capitalone",                // Capital One
        "com.citi.citimobile",                    // Citi
        "com.americanexpress.android.acctsvcs.us",// Amex
        "com.discoverfinancial.mobile",           // Discover
        "com.usaa.mobile.android.usaa",           // USAA
        "com.usbank.mobilebanking",               // U.S. Bank
        "com.pnc.ecommerce.mobile",               // PNC
    )

    fun counts(context: Context, pkg: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (pkg in prefs.getStringSet(KEY_OFF, emptySet())!!) return false
        return pkg in BANKS || pkg in prefs.getStringSet(KEY_ON, emptySet())!!
    }

    fun set(context: Context, pkg: String, on: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val onSet = prefs.getStringSet(KEY_ON, emptySet())!!.toMutableSet()
        val offSet = prefs.getStringSet(KEY_OFF, emptySet())!!.toMutableSet()
        if (on) { onSet += pkg; offSet -= pkg } else { onSet -= pkg; offSet += pkg }
        prefs.edit().putStringSet(KEY_ON, onSet).putStringSet(KEY_OFF, offSet).apply()
    }

    /** Whether Android lets the app read notifications at all. */
    fun hasAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, NotificationReader::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** The currency typed costs are in: the phone's own. */
    fun homeCurrency(): String =
        runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull() ?: "USD"
}
