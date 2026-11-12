package com.example.cafe_kiosk_ai

import org.junit.Assert.assertEquals
import org.junit.Test

class TwoSetHangulTest {
    @Test fun composesAnOrderFromPhysicalKeys() {
        assertEquals("아아 두 잔 주세요", TwoSetHangul.compose("dkdk en wks wntpdy"))
    }

    @Test fun handlesShiftAndCompoundVowelsAndFinals() {
        assertEquals("까 과 값이 갑사", TwoSetHangul.compose("Rk rhk rkqtdl rkqtk"))
    }
}
