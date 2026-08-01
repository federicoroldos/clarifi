package com.clarifi.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.clarifi.core.model.Categories
import com.clarifi.core.model.TxnType
import com.clarifi.core.money.Currency
import com.clarifi.core.money.roundCurrency
import com.clarifi.data.db.Txn
import kotlinx.coroutines.Dispatchers
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

/**
 * Bank statement PDF in, reviewable rows out.
 *
 * **Difference from the desktop, on purpose:** the desktop first tries to extract
 * the PDF's text with pypdf and only falls back to page images. Android has no
 * text-extraction library worth bundling, so this always renders the pages with
 * the platform's own [PdfRenderer] and sends the images to the vision model -
 * which is also the desktop's path for scanned statements, and the more reliable
 * one for the bank layouts that matter.
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
    ): StatementResult {
        val (images, truncated) = renderPages(uri)
        if (images.isEmpty()) {
            throw AiException("That PDF has no readable pages.", code = "bad_format")
        }

        val raw = client.complete(
            prompt = Prompts.statement(),
            apiKey = apiKey,
            maxTokens = MAX_TOKENS,
            timeoutSeconds = 120,
            images = images,
        )

        val parsed = extractTransactionsArray(raw)
        val items = (0 until parsed.length())
            .mapNotNull { index -> parsed.optJSONObject(index)?.let { normalizeItem(it, currency) } }

        return StatementResult(
            items = flagDuplicates(consolidateIvaRefunds(items, currency), existing, currency),
            truncated = truncated,
        )
    }

    /**
     * Renders up to [MAX_PAGES] pages to JPEGs.
     *
     * Rendered at a fixed long-side target rather than the page's native size: a
     * statement at 72dpi is unreadable to a vision model, and at 300dpi it is
     * needlessly large.
     */
    private suspend fun renderPages(uri: Uri): Pair<List<VisionImage>, Boolean> =
        withContext(Dispatchers.IO) {
            val descriptor: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw AiException("That file could not be opened.", code = "bad_format")

            descriptor.use { fd ->
                val renderer = runCatching { PdfRenderer(fd) }.getOrElse {
                    throw AiException("That file is not a readable PDF.", code = "bad_format")
                }
                renderer.use { pdf ->
                    val pageCount = pdf.pageCount
                    val images = mutableListOf<VisionImage>()

                    for (index in 0 until minOf(pageCount, MAX_PAGES)) {
                        pdf.openPage(index).use { page ->
                            val scale = RENDER_LONG_SIDE.toFloat() / maxOf(page.width, page.height)
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)

                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            // PDF pages render with a transparent background; without this
                            // the text comes out white-on-black once flattened to JPEG.
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            val bytes = ByteArrayOutputStream().also {
                                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                            }.toByteArray()
                            bitmap.recycle()

                            images += VisionImage("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        }
                    }

                    images to (pageCount > MAX_PAGES)
                }
            }
        }

    private companion object {
        const val MAX_PAGES = 8
        const val RENDER_LONG_SIDE = 1600
        const val JPEG_QUALITY = 85
        const val MAX_TOKENS = 4000
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
