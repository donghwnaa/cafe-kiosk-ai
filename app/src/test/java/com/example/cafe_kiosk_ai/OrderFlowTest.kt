package com.example.cafe_kiosk_ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OrderFlowTest {
    private fun ConversationManager.send(text: String) = runBlocking { handleUserInput(text) }

    @Test fun t01To03_iceAmericanoQuestionAndConfirmation() {
        val c = ConversationManager()
        c.startNewSession()
        val first = c.send("아아 두 잔 주세요")
        assertEquals(1, first.screen)
        assertEquals(DialogueState.WAITING_FOR_SIZE, c.state)
        assertEquals(Temperature.ICE, c.draft?.temperature)
        assertEquals(2, c.draft?.quantity)
        assertEquals(2, c.send("큰 걸로 주세요").screen)
        assertTrue(c.orders.cart.isEmpty())
        assertEquals(3, c.send("맞아요").screen)
        assertEquals(2, c.orders.cart.single().quantity)
        assertEquals(DrinkSize.TALL, c.orders.cart.single().size)
    }

    @Test fun t04To06_temperatureAndCorrection() {
        val c = ConversationManager()
        c.startNewSession()
        assertEquals(DialogueState.WAITING_FOR_TEMPERATURE, c.run { send("아메리카노 한 잔 주세요"); state })
        assertEquals(DialogueState.WAITING_FOR_SIZE, c.run { send("따뜻한 걸로 주세요"); state })
        assertEquals(2, c.send("큰 걸로 주세요").screen)
        assertEquals(2, c.send("아니요, 보통으로 바꿔줘").screen)
        assertEquals(DrinkSize.REGULAR, c.draft?.size)
        assertTrue(c.orders.cart.isEmpty())
    }

    @Test fun t07To10_addRemoveAndCancelConfirmation() {
        val c = ConversationManager()
        c.startNewSession()
        c.send("아아 두 잔 보통으로 주세요")
        c.send("맞아요")
        assertEquals(1, c.send("아아 하나 더 추가해줘").screen)
        c.send("보통으로")
        c.send("맞아요")
        assertEquals(3, c.orders.cart.single().quantity)
        c.send("유자차 따뜻하게 하나 보통으로 주세요")
        c.send("맞아요")
        c.send("유자차 하나 빼줘")
        assertEquals(1, c.orders.cart.size)
        c.send("전부 취소할게요")
        assertEquals(DialogueState.WAITING_FOR_CANCEL_CONFIRMATION, c.state)
        c.send("아니요")
        assertEquals(3, c.orders.cart.single().quantity)
    }

    @Test fun t11To14_cartAndMockPayment() {
        val c = ConversationManager()
        c.startNewSession()
        assertEquals(3, c.send("주문한 거 보여줘").screen)
        assertEquals(3, c.send("계산할게요").screen)
        c.send("아아 한 잔 보통으로 주세요")
        c.send("맞아요")
        assertEquals(4, c.send("계산할게요").screen)
        c.beginPaymentEvent()
        assertEquals(PaymentState.PROCESSING_PAYMENT, c.paymentState)
        c.paymentEvent(false)
        assertEquals(PaymentState.PAYMENT_FAILED, c.paymentState)
        assertNull(c.orderNumber)
        assertTrue(c.beginPaymentEvent())
        c.paymentEvent(true)
        assertEquals(PaymentState.PAYMENT_SUCCESS, c.paymentState)
        assertNotNull(c.orderNumber)
    }

    @Test fun t15To20_ambiguityMultiActionUnknownAndReset() {
        val c = ConversationManager()
        c.startNewSession()
        assertTrue(c.send("아아 두 잔 아니 한 잔").response.contains("수량"))
        assertNull(c.draft)
        assertEquals(2, c.send("카페라떼 따뜻하게 큰 걸로 하나").screen)
        c.send("맞아요")
        c.send("아아 두 잔 보통으로 주세요")
        c.send("맞아요")
        assertEquals(1, c.send("아메리카노 하나 빼고 레몬에이드 하나 추가해줘").screen)
        assertEquals(2, c.orders.cart.first { it.menu.name == "아메리카노" }.quantity)
        c.send("보통으로")
        c.send("맞아요")
        assertEquals(1, c.orders.cart.first { it.menu.name == "아메리카노" }.quantity)
        assertEquals(1, c.orders.cart.first { it.menu.name == "레몬에이드" }.quantity)
        val count = c.orders.cart.size
        c.send("모르는 메뉴 주문")
        assertEquals(count, c.orders.cart.size)
        assertTrue(c.messages.any { it.fromUser })
        c.resetSession()
        assertTrue(c.messages.isEmpty())
        c.startNewSession()
        assertEquals(listOf(ChatMessage(false, "무엇을 주문하시겠어요?", ChatCard.Menu)), c.messages)
        assertTrue(c.orders.cart.isEmpty())
        assertNull(c.draft)
        assertEquals(PendingQuestion.NONE, c.pending)
        c.send("맞아요")
        assertTrue(c.orders.cart.isEmpty())
    }

    @Test fun cartChangesAreValidatedBeforeCommitAndVariantsStaySeparate() {
        val c = ConversationManager()
        c.startNewSession()
        c.send("아아 한 잔 보통으로")
        c.send("맞아요")
        c.send("뜨아 한 잔 보통으로")
        c.send("맞아요")
        assertEquals(2, c.orders.cart.size)
        assertEquals(PendingQuestion.CART_TARGET, c.run { send("아메리카노 하나 빼줘"); pending })
        c.send("차갑게")
        assertEquals(1, c.orders.cart.size)
        c.send("아메리카노 두 잔으로 바꿔줘")
        assertEquals(1, c.orders.cart.single().quantity)
        c.send("맞아요")
        assertEquals(2, c.orders.cart.single().quantity)
    }

    @Test fun unsupportedOptionsAndMultipleMenusNeverCommitSilently() {
        val c = ConversationManager()
        c.startNewSession()
        assertTrue(c.send("아메리카노와 레몬에이드 뜨겁게 주세요").response.contains("옵션이 없습니다"))
        assertNull(c.draft)
        assertTrue(c.send("레몬에이드 따뜻하게 한 잔").response.contains("옵션이 없습니다"))
        assertTrue(c.orders.cart.isEmpty())
        val result = FakeNluEngine().analyze("아메리카노 하나 빼고 레몬에이드 두 잔 추가해줘")
        assertEquals(NluIntent.MULTI_ACTION, result.intent)
        assertEquals(2, result.actions.size)
        assertTrue(NluJsonAdapter.toDebugJson(result).contains("\"multi_action\""))
    }

    @Test fun deletingAnItemDiffersFromRemovingOneCup() {
        val c = ConversationManager()
        c.startNewSession()
        c.send("유자차 따뜻하게 두 잔 보통으로")
        c.send("맞아요")
        c.send("유자차 한 잔 빼줘")
        assertEquals(1, c.orders.cart.single().quantity)
        c.send("유자차 삭제")
        assertTrue(c.orders.cart.isEmpty())
    }
}
