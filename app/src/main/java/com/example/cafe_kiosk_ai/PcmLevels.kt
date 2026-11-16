package com.example.cafe_kiosk_ai

import kotlin.math.abs
import kotlin.math.sqrt

internal class PcmLevels {
    var samples = 0
        private set
    var nonZero = 0
        private set
    var trailingZeros = 0
        private set
    private var sumSquares = 0L
    private var minimum = Int.MAX_VALUE
    private var maximum = Int.MIN_VALUE
    val varies get() = minimum < maximum
    val rms get() = if (samples == 0) null else sqrt(sumSquares.toDouble() / samples) / 32768
    val peak get() = if (samples == 0) null else maxOf(abs(minimum), abs(maximum))

    fun add(pcm: ShortArray, count: Int) {
        for (i in 0 until count) {
            val sample = pcm[i].toInt()
            if (sample == 0) {
                trailingZeros++
            } else {
                nonZero++
                trailingZeros = 0
            }
            minimum = minOf(minimum, sample)
            maximum = maxOf(maximum, sample)
            sumSquares += sample.toLong() * sample
        }
        samples += count
    }

    override fun toString() = "samples=$samples rms=${rms ?: "n/a"} peak=${peak ?: "n/a"} " +
        "nonZero=$nonZero trailingZeros=$trailingZeros varies=$varies"
}
