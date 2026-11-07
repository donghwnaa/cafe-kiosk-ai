package com.example.cafe_kiosk_ai.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OrderRepositoryTest {
    private val menus = InMemoryMenuRepository()
    private val validator = CommandValidator(menus)

    @Test fun acceptsValidMockCommandAndUpdatesCartTotal() {
        val command = AiCommand(CommandType.ADD_ITEM, "M001", 2, mapOf("temperature" to "ICE", "size" to "R"))
        assertNull(validator.validate(command))
        val cart = CartStore()
        assertNull(cart.apply(command, menus))
        assertEquals(9000, cart.total())
        assertEquals(2, cart.lines().single().quantity)
    }

    @Test fun rejectsUnknownMenuAndUnsupportedOption() {
        assertNotNull(validator.validate(AiCommand(CommandType.ADD_ITEM, "UNKNOWN", 1)))
        assertNotNull(validator.validate(AiCommand(CommandType.ADD_ITEM, "M001", 1, mapOf("temperature" to "COLD"))))
    }

    @Test fun cancelClearsCart() {
        val cart = CartStore()
        cart.apply(AiCommand(CommandType.ADD_ITEM, "M001", 2), menus)
        cart.apply(AiCommand(CommandType.CANCEL_ORDER), menus)
        assertEquals(0, cart.total())
        assertEquals(emptyList<CartLine>(), cart.lines())
    }
}
