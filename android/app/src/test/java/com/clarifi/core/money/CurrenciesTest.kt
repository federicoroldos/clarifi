package com.clarifi.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CurrenciesTest {

    /**
     * Every expected value here was produced by the desktop's rule
     * (`round(float(val), decimals)` in Python) and pasted in verbatim.
     *
     * Python rounds half to *even* on the exact binary value of the double, so a
     * naive HALF_UP implementation disagrees on the first, fifth and seventh
     * cases - and the two apps share one database, where a one-cent drift is a
     * real, visible bug.
     */
    @Test
    fun `rounding matches the desktop exactly`() {
        assertEquals(0.12, roundCurrency(Currencies.USD, 0.125), 0.0)
        assertEquals(0.14, roundCurrency(Currencies.USD, 0.135), 0.0)
        assertEquals(2.67, roundCurrency(Currencies.USD, 2.675), 0.0)
        assertEquals(1.0, roundCurrency(Currencies.USD, 1.005), 0.0)
        assertEquals(10.01, roundCurrency(Currencies.USD, 10.005), 0.0)
        assertEquals(0.14, roundCurrency(Currencies.USD, 0.145), 0.0)
        assertEquals(-0.12, roundCurrency(Currencies.UYU, -0.125), 0.0)
    }

    @Test
    fun `won has no decimals`() {
        assertEquals(2.0, roundCurrency(Currencies.KRW, 2.5), 0.0)
        assertEquals(4.0, roundCurrency(Currencies.KRW, 3.5), 0.0)
        assertEquals(1235.0, roundCurrency(Currencies.KRW, 1234.567), 0.0)
    }

    @Test
    fun `currency ids are always lowercase`() {
        assertEquals(Currencies.USD, Currencies.require("usd"))
        assertEquals(Currencies.USD, Currencies.require("USD"))
        assertEquals(Currencies.USD, Currencies.require(" Usd "))
    }

    @Test
    fun `unknown currencies are rejected, not defaulted`() {
        assertThrows(IllegalArgumentException::class.java) { Currencies.require("gbp") }
        assertThrows(IllegalArgumentException::class.java) { Currencies.require(null) }
        assertNull(Currencies.find("gbp"))
    }

    @Test
    fun `amounts format like the web app`() {
        assertEquals("US\$1,240.50", Money.format(Currencies.USD, 1240.5))
        assertEquals("₩120,000", Money.format(Currencies.KRW, 120000.0))
        assertEquals("€0.00", Money.format(Currencies.EUR, 0.0))
        assertEquals("\$U1,000.00", Money.format(Currencies.UYU, 1000.0))
    }

    @Test
    fun `compact and axis formats match fmtShort and fmtChartY`() {
        assertEquals("1.2M", Money.formatShort(1_234_567.0))
        assertEquals("4.5k", Money.formatShort(4_500.0))
        assertEquals("230", Money.formatShort(230.4))

        assertEquals("US\$1.2M", Money.formatAxis(Currencies.USD, 1_234_567.0))
        assertEquals("US\$12k", Money.formatAxis(Currencies.USD, 12_000.0))
        // Below 10k the axis keeps the full number with thousands separators.
        assertEquals("US\$4,500", Money.formatAxis(Currencies.USD, 4_500.0))
    }
}
