package com.example.cafe_kiosk_ai

/** Tracks the one recognition turn allowed to update the screen or submit an order. */
internal class VoiceTurn {
    enum class Phase { PREPARING, LISTENING, PROCESSING }

    private var sequence = 0L
    var id: Long? = null
        private set
    var phase: Phase? = null
        private set

    fun start(): Long {
        check(id == null)
        phase = Phase.PREPARING
        return (++sequence).also { id = it }
    }

    fun ready(turnId: Long): Boolean {
        if (id != turnId || phase != Phase.PREPARING) return false
        phase = Phase.LISTENING
        return true
    }

    fun processing(turnId: Long): Boolean {
        if (id != turnId || phase != Phase.LISTENING) return false
        phase = Phase.PROCESSING
        return true
    }

    fun stop() {
        id = null
        phase = null
    }
}
