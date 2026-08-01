package com.clarifi.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.clarifi.core.model.Categories
import com.clarifi.core.model.TxnType
import com.clarifi.core.money.Currency
import com.clarifi.core.money.roundCurrency
import com.clarifi.data.db.Txn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** One movement read off a statement, before the user decides whether to keep it. */
data class StatementItem(
    val date: String,
    val description: String,
    val amount: Double,
    val type: TxnType,
    val category: String,
    val ivaRefund: Boolean = false,
    /** Already present in this account with the same date, type and amount. */
    val duplicate: Boolean = false,
    /** Ticked by default unless it looks like a duplicate. */
    val include: Boolean = !duplicate,
)

data class StatementResult(
    val items: List<StatementItem>,
    /** True when the PDF had more pages than could be sent. */
    val truncated: Boolean,
)

/** How far a scan has got, so the spinner can say something true. */
sealed interface StatementProgress {
    data class Page(val number: Int, val total: Int) : StatementProgress

    /** The provider asked us to slow down; [seconds] is how long it wants. */
    data class Waiting(val seconds: Int) : StatementProgress
}

/**
 * Bank statement PDF in, reviewable rows out.
 *
 * Same two paths as the desktop, reached differently. Where it reads the text with
 * pypdf and falls back to page images, this asks the platform for the page's text
 * layer (Android 15 and up) and renders the page with [PdfRenderer] when there is
 * none, which is what a scanned statement always is.
 *
 * Either way the pages go **one per request**. An image page costs several thousand
 * tokens and Groq's free tier allows 8000 a *minute*, so sending the whole statement
 * at once, the way the desktop does, came back as `HTTP 413: Request too large`
 * before the model had read a line. A page that still does not fit is re-rendered
 * smaller, and a provider that asks for a pause gets one.
 */
class StatementScanner(
    private val context: Context,
    private val client: AiClient,
) {

    suspend fun scan(
        uri: Uri,
        apiKey: String,
        currency: Currency,
        existing: List<Txn>,
        onProgress: (StatementProgress) -> Unit = {},
    ): StatementResult = withContext(Dispatchers.IO) {
        val descriptor: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw AiException("That file could not be opened.", code = "bad_format")

        descriptor.use { fd ->
            val renderer = runCatching { PdfRenderer(fd) }.getOrElse {
                throw AiException("That file is not a readable PDF.", code = "bad_format")
            }
            renderer.use { pdf ->
                val pages = minOf(pdf.pageCount, MAX_PAGES)
                if (pages == 0) {
                    throw AiException("That PDF has no readable pages.", code = "bad_format")
                }

                val items = mutableListOf<StatementItem>()
                var unreadable = 0

                for (index in 0 until pages) {
                    onProgress(StatementProgress.Page(index + 1, pages))
                    val page = readPage(pdf, index, apiKey, currency, onProgress)
                    if (page == null) unreadable += 1 else items += page
                }

                // One page the model made nothing of is a cover sheet; every page is a
                // PDF it cannot read, and the user deserves to be told so.
                if (unreadable == pages) {
                    throw AiException("The AI could not read this statement. Try a clearer PDF.")
                }

                StatementResult(
                    items = flagDuplicates(consolidateIvaRefunds(items, currency), existing, currency),
                    truncated = pdf.pageCount > MAX_PAGES,
                )
            }
        }
    }

    /**
     * Reads one page, or returns null when the model answered with something that
     * held no transactions array.
     *
     * Two failures are worth another attempt rather than the whole scan: being told
     * to wait (the per-minute budget refills, so waiting genuinely fixes it) and
     * being told the request is too large (a smaller render genuinely fixes that).
     * Both are bounded, so a page can never loop.
     */
    private suspend fun readPage(
        pdf: PdfRenderer,
        index: Int,
        apiKey: String,
        currency: Currency,
        onProgress: (StatementProgress) -> Unit,
    ): List<StatementItem>? {
        var text = extractText(pdf, index)
        var longSide = RENDER_LONG_SIDE
        var image = if (text == null) render(pdf, index, longSide) else null
        var waits = 0

        while (true) {
            val raw = try {
                client.complete(
                    prompt = Prompts.statement(text),
                    apiKey = apiKey,
                    maxTokens = MAX_TOKENS_PER_PAGE,
                    timeoutSeconds = if (image == null) 60 else 120,
                    images = listOfNotNull(image),
                )
            } catch (e: AiException) {
                val wait = retrySeconds(e.message)
                if (wait != null && waits < MAX_WAITS) {
                    waits += 1
                    // Counted down a second at a time: a number that sits still for
                    // half a minute reads as a frozen screen, not as a wait.
                    for (remaining in wait downTo 1) {
                        onProgress(StatementProgress.Waiting(remaining))
                        delay(1000)
                    }
                    continue
                }
                if (image != null && isTooLarge(e.message) && longSide > REDUCED_LONG_SIDE) {
                    longSide = REDUCED_LONG_SIDE
                    image = render(pdf, index, longSide)
                    continue
                }
                throw explain(e)
            }

            val parsed = runCatching { extractTransactionsArray(raw) }.getOrNull()
            val items = parsed?.let { array ->
                (0 until array.length())
                    .mapNotNull { position -> array.optJSONObject(position)?.let { normalizeItem(it, currency) } }
            }

            // A text layer can be decorative, scrambled or drawn out of order, and
            // then the model reads a page of nothing. Looking at the page costs one
            // request; dropping its rows without a word costs the user their import.
            if (items.isNullOrEmpty() && text != null) {
                text = null
                image = render(pdf, index, longSide)
                continue
            }
            return items
        }
    }

    /**
     * The page's own text, when the PDF has one and the platform will hand it over.
     *
     * This is the desktop's pypdf path, and worth the version check for the same
     * reason it exists there: a statement page as text costs a few hundred tokens
     * where the same page as an image costs several thousand, which on a free AI
     * plan is the difference between reading the whole PDF straight through and
     * sitting out a rate limit between every page. Android only grew the API in 15,
     * and a scanned statement has no text at all, so both of those still go as
     * images.
     */
    private fun extractText(pdf: PdfRenderer, index: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        val text = runCatching {
            pdf.openPage(index).use { page ->
                page.textContents.joinToString("\n") { it.text }.trim()
            }
        }.getOrNull().orEmpty()
        // A page whose text layer is a header and a page number is not a statement
        // page: sending it as text would quietly drop every row on it.
        return text.takeIf { it.length >= MIN_TEXT_CHARS && it.count(Char::isDigit) >= MIN_TEXT_DIGITS }
    }

    /**
     * Renders one page to a JPEG.
     *
     * Rendered at a fixed long-side target rather than the page's native size: a
     * statement at 72dpi is unreadable to a vision model, and at 300dpi it is
     * needlessly large.
     */
    private fun render(pdf: PdfRenderer, index: Int, longSide: Int): VisionImage =
        pdf.openPage(index).use { page ->
            val scale = longSide.toFloat() / maxOf(page.width, page.height)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // PDF pages render with a transparent background; without this the text
            // comes out white-on-black once flattened to JPEG.
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            val bytes = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            }.toByteArray()
            bitmap.recycle()

            VisionImage("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }

    private companion object {
        const val MAX_PAGES = 8
        const val RENDER_LONG_SIDE = 1600
        /** Last resort for a page a plan's per-minute budget cannot hold at full size. */
        const val REDUCED_LONG_SIDE = 1100
        const val JPEG_QUALITY = 85
        /** Below these, a page's text layer is chrome rather than a table of movements. */
        const val MIN_TEXT_CHARS = 160
        const val MIN_TEXT_DIGITS = 12
        /** Per page, not per statement: enough for around fifty movements. */
        const val MAX_TOKENS_PER_PAGE = 2000
        const val MAX_WAITS = 2
        const val MAX_WAIT_SECONDS = 65

        /** `Please try again in 1m8.5s` → 69, or null when nothing says to wait. */
        fun retrySeconds(message: String?): Int? {
            val text = message.orEmpty()
            Regex("""try again in (?:(\d+)m)?([\d.]+)s""", RegexOption.IGNORE_CASE)
                .find(text)?.let { match ->
                    val minutes = match.groupValues[1].toIntOrNull() ?: 0
                    val seconds = match.groupValues[2].toDoubleOrNull() ?: 0.0
                    // A second of slack: waiting the exact figure lands on the boundary
                    // and comes back rate limited again.
                    val total = minutes * 60 + seconds.toInt() + 1
                    return total.coerceIn(1, MAX_WAIT_SECONDS)
                }
            // A limit hit with no advice attached still refills on the minute.
            return if (isRateLimited(text) && !isTooLarge(text)) 20 else null
        }

        fun isRateLimited(message: String?): Boolean =
            message.orEmpty().contains("rate limit", ignoreCase = true) ||
                message.orEmpty().contains("per minute", ignoreCase = true)

        fun isTooLarge(message: String?): Boolean =
            message.orEmpty().contains("too large", ignoreCase = true) ||
                message.orEmpty().contains("413", ignoreCase = true)

        /** The provider's wording names a token budget; this names what to do about it. */
        fun explain(e: AiException): AiException =
            if (isTooLarge(e.message) || isRateLimited(e.message)) {
                AiException(
                    "Your AI plan's per-minute token limit is too small for this statement. " +
                        "Try a shorter PDF, or a key on a higher tier.",
                    code = e.code,
                )
            } else {
                e
            }
    }
}

/**
 * Finds the transactions array, whether the model returned a bare array or
 * wrapped it in an object. Mirrors `_extract_json_array`.
 */
fun extractTransactionsArray(raw: String): JSONArray {
    val arrayStart = raw.indexOf('[')
    val objectStart = raw.indexOf('{')

    if (objectStart >= 0) {
        val objectEnd = raw.lastIndexOf('}')
        if (objectEnd > objectStart) {
            runCatching { JSONObject(raw.substring(objectStart, objectEnd + 1)) }.getOrNull()?.let { obj ->
                listOf("transactions", "items", "movements", "data").forEach { key ->
                    obj.optJSONArray(key)?.let { return it }
                }
            }
        }
    }

    if (arrayStart >= 0) {
        val arrayEnd = raw.lastIndexOf(']')
        if (arrayEnd > arrayStart) {
            runCatching { JSONArray(raw.substring(arrayStart, arrayEnd + 1)) }.getOrNull()?.let { return it }
        }
    }

    throw AiException("The AI could not read this statement. Try a clearer PDF.")
}

/** Mirrors `_normalize_statement_item`; returns null for rows that must be dropped. */
fun normalizeItem(raw: JSONObject, currency: Currency): StatementItem? {
    val amount = raw.opt("amount")?.let { value ->
        when (value) {
            is Number -> abs(value.toDouble())
            is String -> value.toDoubleOrNull()?.let { abs(it) }
            else -> null
        }
    }?.takeIf { it.isFinite() }?.let { roundCurrency(currency, it) } ?: return null
    if (amount <= 0) return null

    val date = raw.optString("date").trim()
    if (!date.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))) return null

    val type = if (raw.optString("type").trim().lowercase() == TxnType.FUND.wire) {
        TxnType.FUND
    } else {
        TxnType.EXPENSE
    }

    return StatementItem(
        date = date,
        description = raw.optString("description").trim().take(60),
        amount = amount,
        type = type,
        category = raw.optString("category").takeIf { it in Categories.ALL } ?: Categories.OTHERS,
        // Only a credit can be a tax refund, whatever the model claims.
        ivaRefund = raw.optBoolean("iva_refund", false) && type == TxnType.FUND,
    )
}

/**
 * Collapses Uruguayan IVA refunds into one row, keeping the position of the first
 * and the date of the last. Mirrors `_consolidate_iva_refunds`: a statement can
 * carry dozens of tiny tax credits, and importing them one by one buries the
 * movements that matter.
 */
fun consolidateIvaRefunds(items: List<StatementItem>, currency: Currency): List<StatementItem> {
    val refunds = items.filter { it.ivaRefund }
    if (refunds.size < 2) return items

    val merged = StatementItem(
        date = refunds.maxOf { it.date },
        description = "Reintegro de IVA",
        // Summed in code so the total is exact rather than whatever the model added up to.
        amount = roundCurrency(currency, refunds.sumOf { it.amount }),
        type = TxnType.FUND,
        category = Categories.OTHERS,
        ivaRefund = true,
    )

    val result = mutableListOf<StatementItem>()
    var inserted = false
    items.forEach { item ->
        if (item.ivaRefund) {
            if (!inserted) {
                result += merged
                inserted = true
            }
        } else {
            result += item
        }
    }
    return result
}

/**
 * Marks rows that already exist in the account, matching on date, type and amount.
 *
 * Description is deliberately ignored: the bank's wording for the same purchase
 * rarely matches what was typed or scanned by hand. Mirrors `_flag_duplicates`.
 */
fun flagDuplicates(
    items: List<StatementItem>,
    existing: List<Txn>,
    currency: Currency,
): List<StatementItem> {
    val known = existing
        .map { Triple(it.date, it.type, roundCurrency(currency, abs(it.amount))) }
        .toSet()

    return items.map { item ->
        val isDuplicate = Triple(item.date, item.type.wire, item.amount) in known
        item.copy(duplicate = isDuplicate, include = !isDuplicate)
    }
}
