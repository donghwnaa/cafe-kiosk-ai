package com.example.cafe_kiosk_ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmLevelsTest {
    @Test fun noFramesAndDigitalSilenceAreDistinct() {
        val levels = PcmLevels()
        assertNull(levels.rms)
        assertNull(levels.peak)
        levels.add(ShortArray(320), 320)
        assertEquals(320, levels.samples)
        assertEquals(0, levels.nonZero)
        assertEquals(320, levels.trailingZeros)
        assertEquals(0.0, levels.rms!!, 0.0)
        assertFalse(levels.varies)
    }

    @Test fun quietVariationIsPreservedAcrossReads() {
        val levels = PcmLevels()
        levels.add(shortArrayOf(1, 1, 0), 2)
        assertFalse(levels.varies)
        levels.add(shortArrayOf(0, -1), 2)
        assertEquals(4, levels.samples)
        assertEquals(3, levels.nonZero)
        assertEquals(1, levels.peak)
        assertTrue(levels.varies)
        assertTrue(levels.rms!! < 0.001)
        levels.add(ShortArray(320), 320)
        levels.add(ShortArray(320), 320)
        assertEquals(640, levels.trailingZeros)
        levels.add(shortArrayOf(0, 1, 0), 3)
        assertEquals(1, levels.trailingZeros)
    }
}
