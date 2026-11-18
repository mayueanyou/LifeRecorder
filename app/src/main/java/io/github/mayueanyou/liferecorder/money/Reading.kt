package io.github.mayueanyou.liferecorder.money

import java.util.Locale

/**
 * What a notification says about money, as Portal reads it.
 *
 * Two rules keep it honest. A number alone is never money: "62° outside" and
 * "3 new items" carry numbers; a purchase says "$12.34" or "USD 12.34". And a
 * figure next to a currency is still not spending until the words say so --
 * a bank also notifies about payments posted, balances and rewards, in much
 * the same words, and one of those once counted as a purchase.
 */
object Reading {
    data class Amount(val value: Double, val currency: String?)

    // Real currency codes only: any three capitals once read "PIZZA HUT 039665"
    // as 39,665 of a currency called HUT.
    private const val CODES = "USD|CNY|RMB|EUR|GBP|JPY|CAD|HKD|INR|AUD"
    private val MONEY = Regex(
        "(?:(?<before>\\b(?:$CODES)\\b)\\s*)?" +
            "(?<symbol>[$£€¥₹])?\\s?" +
            "(?<amount>\\d[\\d,]*\\.\\d{2}|\\d[\\d,]*)" +
            "(?:\\s*(?<after>\\b(?:$CODES)\\b))?",
    )
    private val SYMBOLS = mapOf("$" to "USD", "£" to "GBP", "€" to "EUR", "¥" to "CNY", "₹" to "INR")

    private val SPEND = Regex(
        "\\b(paid|payment|purchase|purchased|transaction|charged?|spent|debit|" +
            "withdraw\\w*|sale|receipt|order|checkout|bought)\\b",
        RegexOption.IGNORE_CASE,
    )

    // Things that carry an amount but are not money you spent.
    private val NOT_SPEND = Regex(
        "\\b(refund\\w*|credited|deposit\\w*|received|balance|statement|due|minimum|" +
            "rewards?|redeem\\w*|cash ?back|declined|failed|pending approval|" +
            "payment of .{0,40}?\\bposted|payment posted|payment scheduled|autopay|" +
            "transfer\\w*|zelle\\w* (?:sent|request))\\b",
        RegexOption.IGNORE_CASE,
    )

    // "$10.00 with <card>" -- a card-payment line, whatever app sends it.
    private val CARD_LINE = Regex("\\bwith\\b.{0,40}(card|credit|debit|visa|mastercard|•{2,}|\\*{2,}|\\d{4})",
        RegexOption.IGNORE_CASE)

    // BofA puts the merchant after the card: "Credit card charge $16.19 Credit card 1234 KFC G135340".
    private val AFTER_CARD = Regex("\\b(?:card|account)\\s*(?:ending(?:\\s+in)?\\s*)?[*•]*\\d{4}\\s*(?<merchant>\\S.*)$",
        RegexOption.IGNORE_CASE)

    // "... $16.19 at KFC" -- the merchant after "at", up to the end of the clause.
    private val AT_MERCHANT = Regex("\\d\\s+(?:at|to)\\s+(?<merchant>[\\p{L}\\d][^.,;\\n]{1,40})")

    // A trailing store number, bare or after a letter or #: "McDonalds 10238", "KFC G135340", "TOPS #362".
    private val STORE_NUMBER = Regex("[\\s#*]*\\b[A-Z]?\\d{3,}\\b\\s*$")

    fun amountIn(text: String): Amount? {
        for (m in MONEY.findAll(text)) {
            val code = m.groups["before"]?.value ?: m.groups["after"]?.value
            val symbol = m.groups["symbol"]?.value
            if (code == null && symbol == null) continue
            val value = m.groups["amount"]!!.value.replace(",", "").toDoubleOrNull() ?: continue
            return Amount(value, code?.let { if (it == "RMB") "CNY" else it } ?: SYMBOLS[symbol])
        }
        return null
    }

    fun looksLikeSpending(title: String, text: String): Boolean {
        val body = "$title $text"
        if (NOT_SPEND.containsMatchIn(body)) return false
        return SPEND.containsMatchIn(body) || CARD_LINE.containsMatchIn(body)
    }

    /** A purchase, or null: an amount with a currency, in words that mean it was spent. */
    fun purchase(title: String, text: String): Amount? {
        val amount = amountIn("$title $text") ?: return null
        if (amount.value <= 0.0 || !looksLikeSpending(title, text)) return null
        return amount
    }

    /** Who was paid, if the notification says. */
    fun merchant(app: String, title: String, text: String): String? {
        AFTER_CARD.find(text)?.let { return tidy(it.groups["merchant"]!!.value) }
        AT_MERCHANT.find(text)?.let { return tidy(it.groups["merchant"]!!.value.substringBefore(" on ").substringBefore(" with ")) }
        val t = title.trim()
        // Otherwise the title, which is where wallets put it -- unless it is only the app's name.
        if (t.isNotEmpty() && !t.equals(app, ignoreCase = true) && amountIn(t) == null && !SPEND.containsMatchIn(t)) {
            return tidy(t)
        }
        return null
    }

    /** "COSTCO WHSE #1176" -> "Costco Whse": the store number means nothing to anyone but the bank. */
    fun tidy(raw: String): String? {
        val cleaned = STORE_NUMBER.replace(raw.split(Regex("\\s+")).joinToString(" "), "").trim(' ', '-', '–', '—', '·', '*')
        if (cleaned.isEmpty()) return null
        // A short one-word name in capitals is an acronym -- KFC, CVS -- and stays one.
        val shouting = cleaned.any { it.isLetter() } && cleaned.none { it.isLowerCase() } &&
            !(cleaned.length <= 4 && ' ' !in cleaned)
        return if (!shouting) cleaned else cleaned.lowercase(Locale.ROOT).split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }
}
