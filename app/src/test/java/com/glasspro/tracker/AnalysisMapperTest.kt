package com.glasspro.tracker

import com.glasspro.tracker.core.model.ComponentScore
import com.glasspro.tracker.core.model.Direction
import com.glasspro.tracker.core.model.ResolvedPrediction
import com.glasspro.tracker.data.repository.AnalysisMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisMapperTest {

    @Test
    fun `component round trip`() {
        val components = listOf(
            ComponentScore("Order Book", 42.0, 0.16, 6.72, true),
            ComponentScore("Options", 0.0, 0.0, 0.0, false)
        )
        val json = AnalysisMapper.serializeComponents(components)
        val parsed = AnalysisMapper.parseComponents(json)
        assertEquals(2, parsed.size)
        assertEquals("Order Book", parsed[0].key)
        assertEquals(42.0, parsed[0].score, 1e-9)
        assertEquals(false, parsed[1].available)
    }

    @Test
    fun `resolved prediction round trip`() {
        val predictions = listOf(
            ResolvedPrediction(
                priceAtPrediction = 100.0,
                priceAfter = 102.5,
                direction = Direction.LONG,
                atrPct = 1.5,
                components = mapOf("Order Book" to 30.0, "Funding" to -10.0)
            )
        )
        val json = AnalysisMapper.serializeResolved(predictions)
        val parsed = AnalysisMapper.parseResolved(json)
        assertEquals(1, parsed.size)
        assertEquals(Direction.LONG, parsed[0].direction)
        assertEquals(30.0, parsed[0].components["Order Book"]!!, 1e-9)
    }

    @Test
    fun `double map round trip`() {
        val map = mapOf("Order Book" to 0.42, "Whale Netflow" to -0.15)
        val json = AnalysisMapper.serializeDoubleMap(map)
        val parsed = AnalysisMapper.parseDoubleMap(json)
        assertEquals(2, parsed.size)
        assertEquals(0.42, parsed["Order Book"]!!, 1e-9)
    }
}
