package com.example.cafe_kiosk_ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class PcmResamplerTest {
    @Test fun sixteenKilohertzPreservesEverySample() {
        val pcm = shortArrayOf(Short.MIN_VALUE, 0, 16384, Short.MAX_VALUE)
        val output = FloatArray(pcm.size)
        val count = PcmResampler(16_000).convert(pcm, pcm.size, output)
        assertTrue(count == pcm.size)
        assertArrayEquals(pcm.map { it / 32768f }.toFloatArray(), output, 0f)
    }

    @Test fun usbRatesKeepPhaseAcrossReadsAndSuppressAliasing() {
        for (rate in listOf(44_100, 48_000)) {
            val pcm = tone(rate, 1_000)
            val single = convert(rate, pcm, pcm.size)
            val fragmented = convert(rate, pcm, 173)
            assertArrayEquals(single, fragmented, 0f)
            assertTrue(single.size in 15_990..16_000)
            assertTrue(rms(single) in 0.4..0.5)
            assertTrue(rms(convert(rate, tone(rate, 12_000), 173)) < 0.02)
        }
    }

    private fun tone(rate: Int, hz: Int) =
        ShortArray(rate) { (sin(2 * PI * hz * it / rate) * 20_000).toInt().toShort() }

    private fun convert(rate: Int, pcm: ShortArray, blockSize: Int): FloatArray {
        val resampler = PcmResampler(rate)
        val output = FloatArray(blockSize)
        val result = mutableListOf<Float>()
        for (offset in pcm.indices step blockSize) {
            val block = pcm.copyOfRange(offset, minOf(offset + blockSize, pcm.size))
            val count = resampler.convert(block, block.size, output)
            for (i in 0 until count) result.add(output[i])
        }
        return result.toFloatArray()
    }

    private fun rms(pcm: FloatArray): Double =
        sqrt(pcm.drop(64).map { it.toDouble() * it }.average())
}
