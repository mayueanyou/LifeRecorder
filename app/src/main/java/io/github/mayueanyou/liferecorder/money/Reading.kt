package io.github.mayueanyou.liferecorder.money

import java.util.Locale

/**
 * What a notification says about money.
 *
 * Two rules keep it honest. A number alone is never money: "62° outside" and
 * "3 new items" carry numbers; a purchase says "$12.34", "12,34 €" or
 * "Rs. 500". And a figure next to a currency is still not spending until the
 * words say so -- a bank also notifies about payments posted, balances,
 * refunds and codes, in much the same words. Words are read in the main
 * languages, amounts in every common way of writing them.
 */
object Reading {
    data class Amount(val value: Double, val currency: String?)

    enum class Kind { PURCHASE, MAYBE }

    /** A purchase; or MAYBE -- an amount, but nothing saying it was spent (kept for review); or null. */
    data class Found(val kind: Kind, val amount: Amount)

    // Word edges that hold in every alphabet and on every Java: Android has no
    // (?U) flag, and desktop Java's \b does not know "é" is a letter.
    private const val W = "(?<![\\p{L}\\p{N}_])"
    private const val WE = "(?![\\p{L}\\p{N}_])"

    private const val CODES = "USD|EUR|GBP|JPY|CNY|RMB|CAD|AUD|NZD|HKD|SGD|INR|BRL|MXN|CHF|SEK|NOK|DKK|PLN|CZK|HUF|" +
        "TRY|ZAR|KRW|TWD|THB|IDR|MYR|PHP|VND|ILS|AED|SAR|RUB|ARS|CLP|COP|PEN"
    // Longest first, so "R$" is not read as "$".
    private const val BEFORE = "R\\$|S\\$|A\\$|C\\$|HK\\$|NT\\$|US\\$|NZ\\$|MX\\$|RM|Rp|Rs\\.?|₹|\\$|£|€|¥|￥|₩|₽|₺|₱|₫|฿|₪|CHF|kr\\.?"
    private const val AFTER = "€|zł|kr|Kč|Ft|lei|₫|₽|円|元|₩|₺|руб\\.?"
    // "1,234.56", "1.234,56", "1 234,56" (non-breaking spaces), "45,90", "1,200", "12.5".
    private const val NUMBER = "\\d{1,3}(?:[.,\\u00a0\\u202f]\\d{3})+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?"
    private val MONEY = Regex(
        "(?:(?<code1>\\b(?:$CODES)\\b)\\s?|(?<sym1>$BEFORE)\\s?)(?<n1>$NUMBER)" +
            "|(?<n2>$NUMBER)\\s?(?:(?<code2>\\b(?:$CODES)\\b)|(?<sym2>$AFTER))",
    )

    private val SYMBOLS = mapOf(
        "R$" to "BRL", "S$" to "SGD", "A$" to "AUD", "C$" to "CAD", "HK$" to "HKD", "NT$" to "TWD",
        "US$" to "USD", "NZ$" to "NZD", "MX$" to "MXN", "RM" to "MYR", "Rp" to "IDR", "Rs" to "INR", "Rs." to "INR",
        "₹" to "INR", "£" to "GBP", "€" to "EUR", "₩" to "KRW", "₽" to "RUB", "₺" to "TRY", "₱" to "PHP",
        "₫" to "VND", "฿" to "THB", "₪" to "ILS", "CHF" to "CHF", "zł" to "PLN", "Kč" to "CZK", "Ft" to "HUF",
        "lei" to "RON", "円" to "JPY", "元" to "CNY", "руб" to "RUB", "руб." to "RUB",
    )

    // Words that make an amount money spent. Latin-script words are matched
    // whole; Chinese, Japanese and Korean have no spaces to bound a word by.
    private val SPEND = Regex(
        "(?iu)$W(paid|payment|purchased?|transaction|txn|charged?|spent|debit(ed)?|withdraw\\w*|sale|receipt|" +
            "order|checkout|bought|authori[sz]ed|card used|used at|upi|" +
            "compras?|pago|cargo|consumo|d[eé]bito|transacci[oó]n|pagamento|transa[cç][aã]o|" +
            "achat|paiement|pay[ée]|d[ée]pense|kauf|einkauf|zahlung|bezahlt|belastung|umsatz|kartenzahlung|" +
            "acquisto|addebito|spesa|betaling|aankoop|betaald|p[łl]atno[śs][ćc]|zakup|transakcja|harcama|[öo]deme|" +
            "pembayaran|transaksi|pembelian|покупка|оплата|списание|плат[её]ж)$WE",
    )
    private val SPEND_CJK = Regex("支付|消费|消費|付款|扣款|支出|刷卡|決済|支払|購入|ご利用|결제|승인|출금|사용")

    // An amount that is not money spent: refunds, money in, balances,
    // statements, rewards, failed payments, transfers, and one-time codes.
    private val NOT_SPEND = Regex(
        "(?iu)$W(refund\\w*|reembolso|devoluci[oó]n|estorno|remboursement|erstattung|gutschrift|rimborso|" +
            "terugbetaling|zwrot|iade|credited|abonad[oa]|creditad[oa]|cr[ée]dit[ée]e?|deposit\\w*|dep[oó]sito|" +
            "received|recibid[oa]|recebid[oa]|re[çc]u|erhalten|ricevut[oa]|ontvangen|" +
            "balance|saldo|solde|kontostand|statement|due|vencimiento|[ée]ch[ée]ance|f[äa]llig|minimum|" +
            "rewards?|cash ?back|redeem\\w*|declined|rechazad[oa]|recusad[oa]|refus[ée]e?|abgelehnt|rifiutat[oa]|" +
            "failed|fallid[oa]|[ée]chou\\w*|pending approval|" +
            "payment of .{0,40}?\\bposted|payment posted|payment scheduled|payment due|autopay|" +
            "transfer\\w*|transferencia|transfer[êe]ncia|virement|[üu]berweisung|bonifico|" +
            "otp|one[- ]time|verification|c[oó]digo|code de|best[äa]tigungscode|" +
            // A shop's own emails about an order already paid for: days later, so never a copy the bank's alert would match.
            "shipped|delivered|out for delivery|tracking|enviad[oa]|entregad[oa]|exp[ée]di[ée]e?|livr[ée]e?|versandt|zugestellt|spedit[oa])$WE",
    )
    private val NOT_SPEND_CJK = Regex("退款|退货|收款|到账|入账|余额|验证码|返金|入金|残高|認証|환불|입금|잔액|인증")

    // "$10.00 with <card>" -- a card-payment line, whatever app sends it.
    private val CARD_LINE = Regex("\\bwith\\b.{0,40}(card|credit|debit|visa|mastercard|•{2,}|\\*{2,}|\\d{4})",
        RegexOption.IGNORE_CASE)

    // BofA puts the merchant after the card: "Credit card charge $16.19 Credit card 1234 KFC G135340".
    private val AFTER_CARD = Regex("\\b(?:card|account)\\s*(?:ending(?:\\s+in)?\\s*)?[*•]*\\d{4}\\s*(?<merchant>\\S.*)$",
        RegexOption.IGNORE_CASE)

    // "... $16.19 at KFC", "... 12,50 € chez Monoprix" -- the merchant after the word for "at".
    private val AT_MERCHANT = Regex("(?iu)\\d\\s*(?:\\S{0,3}\\s+)?(?:at|to|en|em|chez|bei|presso|bij)\\s+(?<merchant>[\\p{L}\\d][^.,;\\n]{1,40})")

    // A trailing store number, bare or after a letter or #: "McDonalds 10238", "KFC G135340", "TOPS #362".
    private val STORE_NUMBER = Regex("[\\s#*]*\\b[A-Z]?\\d{3,}\\b\\s*$")

    /**
     * The first amount with a currency. [home] settles symbols several
     * currencies share: "$" is the phone's own dollar where it uses one,
     * "¥" yen in Japan, "kr" the local crown.
     */
    fun amountIn(text: String, home: String = "USD"): Amount? {
        for (m in MONEY.findAll(text)) {
            val number = m.groups["n1"]?.value ?: m.groups["n2"]?.value ?: continue
            val value = parseNumber(number) ?: continue
            val code = (m.groups["code1"] ?: m.groups["code2"])?.value?.let { if (it == "RMB") "CNY" else it }
            val symbol = (m.groups["sym1"] ?: m.groups["sym2"])?.value
            val currency = code ?: when (symbol) {
                null -> continue
                "$" -> if (home in DOLLARS) home else "USD"
                "¥", "￥" -> if (home == "JPY") "JPY" else "CNY"
                "kr", "kr." -> if (home in CROWNS) home else "SEK"
                else -> SYMBOLS[symbol]
            }
            return Amount(value, currency)
        }
        return null
    }

    /** "1,234.56", "1.234,56" and "45,90" alike: a separator followed by one or two digits at the end is the decimal point. */
    fun parseNumber(raw: String): Double? {
        val s = raw.trim()
        val last = maxOf(s.lastIndexOf('.'), s.lastIndexOf(','))
        val tail = if (last >= 0) s.length - last - 1 else 0
        return if (last >= 0 && tail in 1..2) {
            (s.substring(0, last).filter { it.isDigit() } + "." + s.substring(last + 1)).toDoubleOrNull()
        } else {
            s.filter { it.isDigit() }.toDoubleOrNull()
        }
    }

    fun read(title: String, text: String, home: String = "USD"): Found? {
        val body = "$title $text"
        val amount = amountIn(body, home) ?: return null
        if (amount.value <= 0.0) return null
        if (NOT_SPEND.containsMatchIn(body) || NOT_SPEND_CJK.containsMatchIn(body)) return null
        // "$25.00 at Starbucks" says it plainly enough, with no word for "purchase".
        val spent = SPEND.containsMatchIn(body) || SPEND_CJK.containsMatchIn(body) || CARD_LINE.containsMatchIn(body) ||
            AT_MERCHANT.containsMatchIn(body)
        return Found(if (spent) Kind.PURCHASE else Kind.MAYBE, amount)
    }

    /** A purchase, or null: an amount with a currency, in words that mean it was spent. */
    fun purchase(title: String, text: String, home: String = "USD"): Amount? =
        read(title, text, home)?.takeIf { it.kind == Kind.PURCHASE }?.amount

    fun looksLikeSpending(title: String, text: String): Boolean =
        read(title, text)?.kind == Kind.PURCHASE

    /** Who was paid, if the notification says. */
    fun merchant(app: String, title: String, text: String): String? {
        AFTER_CARD.find(text)?.let { return tidy(it.groups["merchant"]!!.value) }
        AT_MERCHANT.find(text)?.let {
            return tidy(it.groups["merchant"]!!.value.split(Regex("(?i)\\s+(?:on|for|with|el|le|am|il|op|via)\\s+"))[0])
        }
        val t = title.trim()
        // Otherwise the title, which is where wallets put it -- unless it is only the app's name.
        if (t.isNotEmpty() && !t.equals(app, ignoreCase = true) && amountIn(t) == null &&
            !SPEND.containsMatchIn(t) && !SPEND_CJK.containsMatchIn(t)) {
            return tidy(t)
        }
        return null
    }

    /**
     * The shape of a notification with its numbers taken out -- "payment of $#
     * posted on #" -- so a rule made from one ("never count messages like
     * this") recognises the next.
     */
    fun pattern(title: String, text: String): String =
        "$title $text".lowercase(Locale.ROOT).replace(Regex("\\d[\\d.,:/\\-\\u00a0]*"), "#")
            .split(Regex("\\s+")).filter { it.isNotBlank() }.take(6).joinToString(" ")

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

    private val DOLLARS = setOf("USD", "CAD", "AUD", "NZD", "SGD", "HKD", "TWD", "MXN", "ARS", "CLP", "COP")
    private val CROWNS = setOf("SEK", "NOK", "DKK", "ISK")
}
