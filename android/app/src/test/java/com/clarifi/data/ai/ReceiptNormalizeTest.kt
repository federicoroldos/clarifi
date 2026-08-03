package com.clarifi.data.ai

import com.clarifi.core.model.Categories
import com.clarifi.core.model.TxnType
import com.clarifi.core.time.Dates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A model can return anything: a category that does not exist, a negative total,
 * a date in the wrong format, a currency the app has never heard of. None of that
 * may reach the database, so `normalize` is the boundary - mirroring
 * `_normalize_fields` in app.py.
 */
class ReceiptNormalizeTest {

    @Test
    fun `reads a well-formed response`() {
        val fields = normalize(
            JSONObject(
                """
                {"amount": 1234.56, "date": "2026-03-04", "merchant": "Tienda Inglesa",
                 "category": "Supermarket", "currency": "uyu", "type": "expense"}
                """
            )
        )

        assertEquals(1234.56, fields.amount!!, 0.001)
        assertEquals("2026-03-04", fields.date)
        assertEquals("Tienda Inglesa", fields.merchant)
        assertEquals("Supermarket", fields.category)
        assertEquals("uyu", fields.currencyId)
        assertEquals(TxnType.EXPENSE, fields.type)
    }

    @Test
    fun `amounts are made positive`() {
        assertEquals(40.0, normalize(JSONObject("""{"amount": -40}""")).amount!!, 0.001)
    }

    @Test
    fun `an amount sent as a string is still read`() {
        assertEquals(40.5, normalize(JSONObject("""{"amount": "40.5"}""")).amount!!, 0.001)
    }

    @Test
    fun `an unusable amount becomes null rather than zero`() {
        // Zero would look like a real, free transaction; null keeps the field empty
        // so the user has to fill it in.
        assertNull(normalize(JSONObject("""{"amount": "abc"}""")).amount)
        assertNull(normalize(JSONObject("{}")).amount)
    }

    @Test
    fun `an invented category falls back to Others`() {
        assertEquals(Categories.OTHERS, normalize(JSONObject("""{"category": "Groceries"}""")).category)
        assertEquals(Categories.OTHERS, normalize(JSONObject("{}")).category)
    }

    @Test
    fun `an unsupported currency becomes null instead of being stored`() {
        assertNull(normalize(JSONObject("""{"currency": "xbt"}""")).currencyId)
        assertNull(normalize(JSONObject("""{"currency": ""}""")).currencyId)
    }

    @Test
    fun `a malformed date falls back to today`() {
        assertEquals(Dates.today(), normalize(JSONObject("""{"date": "04/03/2026"}""")).date)
        assertEquals(Dates.today(), normalize(JSONObject("""{"date": null}""")).date)
    }

    @Test
    fun `a refund comes back as income`() {
        assertEquals(TxnType.FUND, normalize(JSONObject("""{"type": "fund"}""")).type)
        assertEquals(TxnType.EXPENSE, normalize(JSONObject("""{"type": "whatever"}""")).type)
    }

    @Test
    fun `long merchant names are truncated to sixty characters`() {
        val long = "A".repeat(120)

        assertEquals(60, normalize(JSONObject("""{"merchant": "$long"}""")).merchant.length)
    }

    @Test
    fun `json wrapped in prose or a markdown fence is still extracted`() {
        val wrapped = """
            Here you go:
            ```json
            {"amount": 12.5, "category": "Food"}
            ```
        """.trimIndent()

        val fields = normalize(extractJsonObject(wrapped))

        assertEquals(12.5, fields.amount!!, 0.001)
        assertEquals("Food", fields.category)
    }

    @Test
    fun `a reply with no json is reported as unreadable`() {
        val error = runCatching { extractJsonObject("I cannot read this receipt.") }.exceptionOrNull()

        assertTrue(error is AiException)
    }

    @Test
    fun `providers are detected from the key prefix`() {
        assertEquals(AiProvider.GROQ, AiProvider.detect("gsk_abc123"))
        assertEquals(AiProvider.CLAUDE, AiProvider.detect("sk-ant-abc123"))
        assertEquals(AiProvider.GEMINI, AiProvider.detect("AIzaSyAbc123"))
        assertEquals(AiProvider.GEMINI, AiProvider.detect(null))
    }
}
