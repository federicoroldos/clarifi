package com.clarifi.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The y-axis has to land on the same round numbers the desktop picks, or the two
 * versions of the same chart would disagree about their own scale.
 */
class ScaleTest {

    /** Every expectation below was produced by running the desktop's own `niceScale`. */
    @Test
    fun `snaps the step up to 1, 2, 5 or 10 times a power of ten`() {
        // 870 / 4 = 217.5 → normalised 2.175 → the 5 bucket → step 500.
        assertEquals(1000.0, niceScale(870.0, 4).max, 0.0)
        assertEquals(500.0, niceScale(870.0, 4).step, 0.0)
        assertEquals(2, niceScale(870.0, 4).ticks)

        assertEquals(100.0, niceScale(100.0, 4).max, 0.0)
        assertEquals(50.0, niceScale(100.0, 4).step, 0.0)

        assertEquals(60.0, niceScale(55.0, 4).max, 0.0)
        assertEquals(20.0, niceScale(55.0, 4).step, 0.0)

        assertEquals(1_500_000.0, niceScale(1_234_567.0, 4).max, 0.0)
        assertEquals(500_000.0, niceScale(1_234_567.0, 4).step, 0.0)
    }

    @Test
    fun `the max always covers the data`() {
        listOf(1.0, 7.0, 43.0, 99.0, 1234.0, 987654.0).forEach { value ->
            val scale = niceScale(value, 4)
            assertTrue("$value should fit under ${scale.max}", scale.max >= value)
        }
    }

    @Test
    fun `ticks multiply back up to the max`() {
        listOf(3.0, 55.0, 812.0, 1_234_567.0).forEach { value ->
            val scale = niceScale(value, 4)
            assertEquals(scale.max, scale.step * scale.ticks, scale.max * 1e-9)
        }
    }

    @Test
    fun `an empty chart still gets a usable axis`() {
        val scale = niceScale(0.0, 4)

        assertEquals(4.0, scale.max, 0.0)
        assertEquals(1.0, scale.step, 0.0)
        assertEquals(4, scale.ticks)
    }

    @Test
    fun `there are eight category colours, matching the desktop`() {
        assertEquals(8, CategoryChartColors.size)
        assertEquals(0xFF3B82F6, CategoryChartColors.first())
    }
}
