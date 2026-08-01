package com.clarifi.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.clarifi.core.model.Categories
import com.clarifi.core.model.TxnType
import com.clarifi.core.money.Currencies
import com.clarifi.core.time.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** What the model read off a receipt, after normalisation. Nothing is saved until confirmed. */
data class ReceiptFields(
    val amount: Double?,
    val date: String,
    val merchant: String,
    val category: String,
    val currencyId: String?,
    val type: TxnType,
)

/**
 * Photo in, structured fields out.
 *
 * The image goes straight to a vision model - no on-device OCR - which is what
 * makes faded, creased and angled receipts work, and matches the desktop exactly.
 */
class ReceiptScanner(
    private val context: Context,
    private val client: AiClient,
) {

    suspend fun scan(uri: Uri, apiKey: String): ReceiptFields {
        val image = prepare(uri)
        val raw = client.complete(
            prompt = Prompts.receipt(),
            apiKey = apiKey,
            maxTokens = 500,
            images = listOf(image),
        )
        return normalize(extractJsonObject(raw))
    }

    /**
     * Decodes, downscales and re-encodes to JPEG, matching
     * `_prepare_image_for_vision`: longest side capped at 1600px, quality 85.
     *
     * Sampling happens while decoding rather than after, so a 50-megapixel phone
     * photo never has to fit in memory at full size.
     */
    private suspend fun prepare(uri: Uri): VisionImage = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw AiException(
                "This image could not be read. Try a JPG or PNG photo.",
                code = "bad_format",
            )
        }

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_DIMENSION) {
            sampleSize *= 2
        }

        val decoded = context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: throw AiException(
            "This image could not be read. Try a JPG or PNG photo.",
            code = "bad_format",
        )

        val scaled = scaleToLimit(decoded)
        val bytes = ByteArrayOutputStream().also { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }.toByteArray()
        if (scaled != decoded) scaled.recycle()
        decoded.recycle()

        VisionImage(
            mimeType = "image/jpeg",
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    private fun scaleToLimit(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_DIMENSION) return source
        val ratio = MAX_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        const val MAX_DIMENSION = 1600
        const val JPEG_QUALITY = 85
    }
}

/**
 * Pulls the JSON object out of a model reply, which may be wrapped in prose or a
 * markdown fence. Same approach as `_extract_json`.
 */
fun extractJsonObject(raw: String): JSONObject {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) {
        throw AiException("The AI could not read this receipt. Try a clearer, straight-on photo.")
    }
    return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrElse {
        throw AiException("The AI could not read this receipt. Try a clearer, straight-on photo.")
    }
}

/**
 * Coerces whatever the model returned into values the app can trust - mirrors
 * `_normalize_fields`. A model is free to invent a category or a currency; the
 * app is not free to store one.
 */
fun normalize(raw: JSONObject): ReceiptFields {
    val amount = raw.opt("amount")?.let { value ->
        when (value) {
            is Number -> abs(value.toDouble())
            is String -> value.toDoubleOrNull()?.let { parsed -> abs(parsed) }
            else -> null
        }
    }?.takeIf { it.isFinite() }

    val category = raw.optString("category").takeIf { it in Categories.ALL } ?: Categories.OTHERS
    val currency = raw.optString("currency").trim().lowercase().takeIf { Currencies.find(it) != null }
    val type = if (raw.optString("type").trim().lowercase() == TxnType.FUND.wire) {
        TxnType.FUND
    } else {
        TxnType.EXPENSE
    }
    val date = raw.optString("date").trim()
        .takeIf { it.matches(Regex("""^\d{4}-\d{2}-\d{2}$""")) }
        ?: Dates.today()

    return ReceiptFields(
        amount = amount,
        date = date,
        merchant = raw.optString("merchant").trim().take(60),
        category = category,
        currencyId = currency,
        type = type,
    )
}
