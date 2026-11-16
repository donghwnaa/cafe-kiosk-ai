package com.example.cafe_kiosk_ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Streaming low-pass conversion from the capture rate to the model's 16 kHz. */
internal class PcmResampler(private val inputRate: Int) {
    private val outputRate = 16_000
    init {
        require(inputRate >= outputRate)
    }
    private val radius = 16
    private val history = FloatArray(radius * 2 + 1)
    private var inputIndex = -1L
    private var outputTime = 0L
    private val phaseStep = gcd(inputRate, outputRate)
    private val filters = if (inputRate == outputRate) emptyArray() else
        Array(outputRate / phaseStep) { phase ->
            val fraction = phase * phaseStep / outputRate.toDouble()
            val cutoff = 7_000.0 / inputRate
            val weights = DoubleArray(history.size) { tap ->
                val distance = tap - radius - fraction
                val sinc = if (distance == 0.0) 2 * cutoff else sin(2 * PI * cutoff * distance) / (PI * distance)
                sinc * (0.5 + 0.5 * cos(PI * distance / (radius + 1)))
            }
            val gain = weights.sum()
            FloatArray(weights.size) { (weights[it] / gain).toFloat() }
        }

    fun convert(pcm: ShortArray, count: Int, output: FloatArray): Int {
        if (inputRate == outputRate) {
            for (i in 0 until count) output[i] = pcm[i] / 32768f
            return count
        }
        var written = 0
        for (i in 0 until count) {
            inputIndex++
            history[(inputIndex % history.size).toInt()] = pcm[i] / 32768f
            val center = outputTime / outputRate
            if (center + radius != inputIndex) continue
            val weights = filters[(outputTime % outputRate).toInt() / phaseStep]
            var value = 0f
            for (tap in weights.indices) {
                val index = center + tap - radius
                if (index >= 0) value += history[(index % history.size).toInt()] * weights[tap]
            }
            output[written++] = value
            outputTime += inputRate
        }
        return written
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
