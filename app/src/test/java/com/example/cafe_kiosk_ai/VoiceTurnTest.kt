package com.example.cafe_kiosk_ai

import org.junit.Assert.*
import org.junit.Test

class VoiceTurnTest {
    @Test fun preparationTransitionsThroughReadyAndProcessingOnlyOnce() {
        val turn = VoiceTurn()
        val id = turn.start()
        assertEquals(VoiceTurn.Phase.PREPARING, turn.phase)
        assertFalse(turn.processing(id))
        assertTrue(turn.ready(id))
        assertEquals(VoiceTurn.Phase.LISTENING, turn.phase)
        assertFalse(turn.ready(id))
        assertTrue(turn.processing(id))
        assertEquals(VoiceTurn.Phase.PROCESSING, turn.phase)
        assertFalse(turn.ready(id))
    }

    @Test fun stoppedAndSupersededCallbacksCannotAdvanceANewTurn() {
        val turn = VoiceTurn()
        val old = turn.start()
        turn.stop()
        assertNull(turn.id)
        assertFalse(turn.ready(old))
        val current = turn.start()
        assertNotEquals(old, current)
        assertFalse(turn.ready(old))
        assertTrue(turn.ready(current))
        assertFalse(turn.processing(old))
    }
}
