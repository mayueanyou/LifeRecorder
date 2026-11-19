package io.github.mayueanyou.lifetracker.money

/**
 * What an app is to money. A bank sees every card payment however it was
 * made, so its purchases count without asking. Every other kind is asked
 * about once: a wallet, an email app showing the bank's alert, or the SMS app
 * showing the bank's text can each be the only source for someone -- and for
 * someone else, a second copy of what the bank already said.
 */
enum class Role(val label: String, val hint: String) {
    BANK("Bank", "Your bank sees every card purchase."),
    WALLET("Wallet", "Usually your bank reports these too — copies are counted once."),
    EMAIL("Email", "Counts your bank's purchase alert emails as they arrive."),
    MESSAGES("Text messages", "Counts your bank's purchase texts — for banks that alert by SMS."),
    OTHER("Other app", "Not known as a money app."),
}

object KnownApps {
    data class Known(val name: String, val role: Role)

    private val apps: Map<String, Known> = listOf(
        // United States
        "com.infonow.bofa" to "Bank of America", "com.chase.sig.android" to "Chase",
        "com.wf.wellsfargomobile" to "Wells Fargo", "com.konylabs.capitalone" to "Capital One",
        "com.citi.citimobile" to "Citi", "com.americanexpress.android.acctsvcs.us" to "American Express",
        "com.discoverfinancial.mobile" to "Discover", "com.usaa.mobile.android.usaa" to "USAA",
        "com.usbank.mobilebanking" to "U.S. Bank", "com.pnc.ecommerce.mobile" to "PNC",
        "com.navyfederal.android" to "Navy Federal", "com.onedebit.chime" to "Chime",
        "com.sofi.mobile" to "SoFi", "com.ally.MobileBanking" to "Ally",
        // United Kingdom and Europe
        "co.uk.getmondo" to "Monzo", "com.revolut.revolut" to "Revolut", "com.transferwise.android" to "Wise",
        "de.number26.android" to "N26", "com.starlingbank.android" to "Starling",
        "com.barclays.android.barclaysmobilebanking" to "Barclays", "uk.co.hsbc.hsbcukmobilebanking" to "HSBC UK",
        "com.grppl.android.shell.CMBlloydsTSB73" to "Lloyds", "com.rbs.mobile.android.natwest" to "NatWest",
        // Canada
        "com.rbc.mobile.android" to "RBC", "com.td" to "TD", "com.scotiabank.banking" to "Scotiabank",
        "com.bmo.mobile" to "BMO", "com.cibc.android.mobi" to "CIBC",
        // Australia
        "com.commbank.netbank" to "CommBank", "com.anz.android.gomoney" to "ANZ",
        "org.westpac.bank" to "Westpac", "au.com.nab.mobile" to "NAB",
        // India
        "com.snapwork.hdfc" to "HDFC Bank", "com.csam.icici.bank.imobile" to "ICICI iMobile",
        "com.sbi.lotusintouch" to "SBI YONO", "com.axis.mobile" to "Axis Bank", "com.msf.kbank.mobile" to "Kotak",
        // Brazil
        "com.nu.production" to "Nubank", "com.itau" to "Itaú",
    ).associate { (pkg, name) -> pkg to Known(name, Role.BANK) } + listOf(
        "com.google.android.apps.walletnfcrel" to "Google Wallet", "com.samsung.android.spay" to "Samsung Wallet",
        "com.paypal.android.p2pmobile" to "PayPal", "com.venmo" to "Venmo", "com.squareup.cash" to "Cash App",
        "com.google.android.apps.nbu.paisa.user" to "Google Pay", "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm", "com.mercadopago.wallet" to "Mercado Pago",
        "com.eg.android.AlipayGphone" to "Alipay", "com.tencent.mm" to "WeChat",
    ).associate { (pkg, name) -> pkg to Known(name, Role.WALLET) } + listOf(
        "com.google.android.gm" to "Gmail", "com.microsoft.office.outlook" to "Outlook",
        "com.yahoo.mobile.client.android.mail" to "Yahoo Mail",
    ).associate { (pkg, name) -> pkg to Known(name, Role.EMAIL) } + listOf(
        "com.google.android.apps.messaging" to "Messages", "com.samsung.android.messaging" to "Samsung Messages",
    ).associate { (pkg, name) -> pkg to Known(name, Role.MESSAGES) }

    fun of(pkg: String): Known? = apps[pkg]
    fun role(pkg: String): Role = apps[pkg]?.role ?: Role.OTHER

    /** Which copy of one purchase to keep when several apps report it: the bank's first. */
    fun rank(pkg: String): Int = when (role(pkg)) {
        Role.BANK -> 0
        Role.WALLET -> 1
        Role.EMAIL -> 2
        Role.MESSAGES -> 3
        Role.OTHER -> 4
    }
}
