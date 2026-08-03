package com.glasspro.tracker

import com.glasspro.tracker.core.math.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {

    @Test
    fun `median of odd list`() {
        assertEquals(5.0, Stats.median(listOf(9.0, 5.0, 1.0))!!, 1e-9)
    }

    @Test
    fun `median of even list`() {
        assertEquals(3.5, Stats.median(listOf(1.0, 2.0, 5.0, 6.0))!!, 1e-9)
    }

    @Test
    fun `median of empty list is null`() {
        assertNull(Stats.median(emptyList()))
    }

    @Test
    fun `robust z-score flags outlier`() {
        val values = listOf(100.0, 101.0, 100.5, 99.8, 100.2, 100.9, 500.0)
        val z = Stats.robustZScore(500.0, values)
        assertTrue(z != null && z > 3.0)
        val zNormal = Stats.robustZScore(100.5, values)
        assertTrue(zNormal != null && zNormal < 1.0)
    }

    @Test
    fun `pearson correlation positive`() {
        val x = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val y = listOf(2.0, 4.0, 6.0, 8.0, 10.0)
        assertEquals(1.0, Stats.pearson(x, y)!!, 1e-9)
    }

    @Test
    fun `pearson correlation negative`() {
        val x = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val y = listOf(10.0, 8.0, 6.0, 4.0, 2.0)
        assertEquals(-1.0, Stats.pearson(x, y)!!, 1e-9)
    }

    @Test
    fun `clamp bounds values`() {
        assertEquals(100.0, Stats.clamp(250.0), 1e-9)
        assertEquals(-100.0, Stats.clamp(-250.0), 1e-9)
        assertEquals(42.0, Stats.clamp(42.0), 1e-9)
    }

    @Test
    fun `safeDouble rejects garbage`() {
        assertEquals(12.5, Stats.safeDouble("12.5")!!, 1e-9)
        assertEquals(12.5, Stats.safeDouble("1,2.5")!!, 1e-9)
        assertNull(Stats.safeDouble("abc"))
    }
}
