package com.clarifi.data.ai

import com.clarifi.core.model.Categories
import com.clarifi.core.money.Currencies

/**
 * The prompts, carried over word for word from `_structure_prompt` in app.py.
 *
 * They are shared verbatim on purpose: the same receipt photographed once should
 * be categorised the same way whether it was scanned on the desktop or the phone.
 * Any wording change here has to be made in both places.
 */
object Prompts {

    fun receipt(): String = buildString {
        append("You read the attached photo of a store receipt and extract structured data. ")
        append("Respond with ONLY a JSON object (no markdown, no prose) with these keys:\n")
        append("  amount   - number, the grand total actually paid (not subtotal)\n")
        append("  date     - 'YYYY-MM-DD' or null if not found\n")
        append("  merchant - the store/vendor name, or '' if unknown\n")
        append("  category - exactly one of ${pythonList(Categories.ALL)}. Choose by the kind of vendor:\n")
        append(
            "             - Supermarket: grocery/supermarket/convenience stores selling " +
                "packaged goods, where the receipt lists many individual products " +
                "(e.g. Walmart, Carrefour, Costco, Lidl, corner shop).\n"
        )
        append(
            "             - Food: places that serve prepared meals/drinks - restaurants, " +
                "cafes, bars, fast food, bakeries, delivery. Tip/cover/table lines are strong hints.\n"
        )
        append(
            "             - Transport: fuel/gas stations, ride-hailing, taxis, parking, " +
                "tolls, public transit, flights.\n"
        )
        append("             - Health: pharmacies, clinics, hospitals, dental, optical.\n")
        append(
            "             - Services: subscriptions, utilities, phone/internet, insurance, " +
                "rent, repairs, gym, salons - anything billed as a service rather than goods.\n"
        )
        append("             - Games: video games, consoles, in-game purchases, gaming subscriptions.\n")
        append("             - Others: only when none clearly fit.\n")
        append(
            "             Receipts are often from Uruguay - use local knowledge of merchants, e.g. " +
                "Tienda Inglesa / Devoto / Disco / Ta-Ta / Multiahorro (Supermarket); La Pasiva / " +
                "Bonjour / PedidosYa (Food); ANCAP / DUCSA / CUTCSA / STM (Transport); Farmashop / " +
                "San Roque / CASMU (Health); Antel / UTE / OSE / Abitab / Redpagos (Services).\n"
        )
        append("  currency - one of ${pythonList(Currencies.ALL.map { it.id })} (lowercase) or null if unknown\n")
        append("  type     - 'expense' for a normal purchase, 'fund' for a refund/return/credit")
    }

    /**
     * Ported from `_statement_prompt`, in both its forms: [text] is the page's own
     * text when the PDF carries one, and null when the page has to be sent as an
     * image. The desktop swaps the same sentence and appends the same block.
     */
    fun statement(text: String? = null): String = buildString {
        append("You extract every transaction from ")
        append(
            if (text == null) {
                "the attached page images of a bank or credit-card statement"
            } else {
                "the raw text of a bank or credit-card statement"
            }
        )
        append(". ")
        append("Respond with ONLY a JSON object (no markdown, no prose) of the ")
        append("form {\"transactions\": [ ... ]}, where each array item has these keys:\n")
        append(
            "  date        - 'YYYY-MM-DD'. Infer the year from the statement period when " +
                "a row only shows day/month.\n"
        )
        append("  description - the merchant or description of the movement, trimmed.\n")
        append("  amount      - a positive number (the movement amount, never negative).\n")
        append(
            "  type        - 'expense' for money leaving the account (debits, charges, " +
                "purchases, withdrawals), 'fund' for money coming in (credits, deposits, " +
                "refunds, incoming transfers). IMPORTANT: when the statement has a running " +
                "balance/saldo column, use it as the source of truth. Read the rows in date " +
                "order and compare each row's balance to the previous row's: if the balance " +
                "went UP the movement is a 'fund' (credit), if it went DOWN it is an " +
                "'expense' (debit). Trust the balance over the wording: merchant names like " +
                "supermarkets or restaurants can appear on credit lines too (for example tax " +
                "refunds), so a row mentioning a store is not automatically an expense.\n"
        )
        append(
            "  iva_refund  - true ONLY for Uruguayan IVA-refund credits: small 'fund' " +
                "lines such as 'REDIVA', 'Reintegro de IVA' or 'Devolucion de IVA' that the " +
                "bank gives back for paying by card. false for every other movement.\n"
        )
        append("  category    - exactly one of ${pythonList(Categories.ALL)}, chosen by the kind of vendor:\n")
        append(
            "                Supermarket (grocery/convenience), Food (restaurants, cafes, " +
                "bars, fast food, delivery), Transport (fuel, ride-hailing, taxis, parking, " +
                "tolls, transit, flights), Health (pharmacies, clinics, dental, optical), " +
                "Services (subscriptions, utilities, phone/internet, insurance, rent, repairs, " +
                "gym, bank fees), Games (video games, consoles, gaming subscriptions), " +
                "'Hanging out' (leisure, entertainment, shopping for fun), Others (only when " +
                "none clearly fit).\n"
        )
        append(
            "                Statements are often from Uruguay - use local knowledge: " +
                "Tienda Inglesa / Devoto / Disco / Ta-Ta / Multiahorro (Supermarket); La Pasiva " +
                "/ Bonjour / PedidosYa (Food); ANCAP / DUCSA / CUTCSA / STM (Transport); " +
                "Farmashop / San Roque / CASMU (Health); Antel / UTE / OSE / Abitab / Redpagos " +
                "(Services).\n"
        )
        append(
            "Skip every non-transaction line: opening/closing balances, totals, subtotals, " +
                "interest summaries, and any row without a real movement amount. Keep the " +
                "order they appear. If there are no transactions, return " +
                "{\"transactions\": []}."
        )
        if (text != null) append("\n\nRaw statement text:\n\"\"\"\n$text\n\"\"\"")
    }

    /** The desktop interpolates Python lists straight into the prompt; this reproduces that. */
    private fun pythonList(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { "'$it'" }
}
