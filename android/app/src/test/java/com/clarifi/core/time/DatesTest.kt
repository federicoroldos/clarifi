package com.clarifi.core.time

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The due day is the one piece of date handling with a real edge: a payment set to
 * a day the month does not have used to go the whole month without becoming due,
 * so it was never applied and the user simply lost that instalment.
 */
class DatesTest {

    @Test
    fun `a day the month does not have falls on its last day`() {
        assertEquals(28, Dates.dueDayThisMonth(31, LocalDate.of(2026, 2, 10)))
        assertEquals(28, Dates.dueDayThisMonth(29, LocalDate.of(2026, 2, 10)))
        assertEquals(30, Dates.dueDayThisMonth(31, LocalDate.of(2026, 4, 10)))
        // 2028 is a leap year, so February really does have a 29th.
        assertEquals(29, Dates.dueDayThisMonth(31, LocalDate.of(2028, 2, 10)))
    }

    @Test
    fun `a day the month has is left alone`() {
        assertEquals(1, Dates.dueDayThisMonth(1, LocalDate.of(2026, 2, 10)))
        assertEquals(15, Dates.dueDayThisMonth(15, LocalDate.of(2026, 4, 10)))
        assertEquals(31, Dates.dueDayThisMonth(31, LocalDate.of(2026, 1, 10)))
    }

    @Test
    fun `ordinals read the way the desktop writes them`() {
        assertEquals("1st", Dates.ordinal(1))
        assertEquals("2nd", Dates.ordinal(2))
        assertEquals("3rd", Dates.ordinal(3))
        assertEquals("11th", Dates.ordinal(11))
        assertEquals("21st", Dates.ordinal(21))
        assertEquals("31st", Dates.ordinal(31))
    }
}
