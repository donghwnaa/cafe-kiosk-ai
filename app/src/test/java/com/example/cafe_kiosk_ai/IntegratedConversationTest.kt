package com.example.cafe_kiosk_ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IntegratedConversationTest {
    private fun ConversationManager.send(text: String) = runBlocking { handleUserInput(text) }
    private fun ConversationManager.latestCard() = messages.last().card

    @Test fun affirmativeItemConfirmationSkipsInference() = runBlocking {
        val c = ConversationManager()
        c.startNewSession()
        c.send("아아 두 잔 보통으로 주세요")
        val context = requireNotNull(c.contextSnapshot())
        val question = requireNotNull(context.question)
        var calls = 0
        val engine = LlmNluEngine(LlmEngine { _, _ ->
            calls++
            """{"function":"show_cart","parameters":{}}"""
        })
        assertEquals(NluIntent.CONFIRM, engine.analyze("네, 맞아요.", context).intent)
        assertEquals(0, calls)
        engine.analyze("네, 그런데 두 잔으로 바꿔 주세요", context)
        engine.analyze("네", context.copy(question = question.copy(paused = true)))
        engine.analyze("네", context.copy(question = null))
        assertEquals(3, calls)
    }
    @Test fun additionalOrdersRetainCartAndAppendSnapshotsToOneHistory() {
        val c = ConversationManager()
        c.startNewSession()
        c.send("아아 두 잔 주세요")
        val questionHistory = c.messages
        assertEquals(OrderStage.ITEM_CONFIRM, c.send("큰 걸로 주세요").stage)
        val confirmation = c.latestCard() as ChatCard.ItemConfirmation
        assertEquals(questionHistory, c.messages.take(questionHistory.size))
        assertEquals(R.drawable.americano, confirmation.items.single().menu.image)
        assertEquals(2, confirmation.items.single().quantity)
        assertEquals(OrderStage.CART_REVIEW, c.send("맞아요").stage)
        val firstCart = c.latestCard() as ChatCard.CartSummary
        assertEquals(7000, firstCart.total)
        val history = c.messages

        c.send("다른 메뉴 추가해줘")
        c.send("카푸치노 차가운 걸로 하나 더 주세요")
        assertEquals(PendingQuestion.SIZE, c.pending)
        assertEquals(firstCart.items, c.orders.cart)
        assertEquals(history, c.messages.take(history.size))
        c.send("보통으로")
        c.send("맞아요")
        assertEquals(listOf("아메리카노", "카푸치노"), c.orders.cart.map { it.menu.name })
        assertEquals(Temperature.ICE, c.orders.cart.last().temperature)
        assertEquals(11000, (c.latestCard() as ChatCard.CartSummary).total)
        assertEquals(7000, firstCart.total)
        assertEquals(1, firstCart.items.size)
    }

    @Test fun browsingMenuCartAndPriceKeepsTheExactDraftQuestion() {
        val c = ConversationManager()
        c.startNewSession()
        c.send("아메리카노 두 잔 주세요")
        assertEquals(PendingQuestion.TEMPERATURE, c.pending)
        c.send("차갑게요")
        val draft = c.draft
        val question = c.question
        for (text in listOf("메뉴 보여줘", "메뉴 다시 보여줘", "음료 뭐 있어?", "전체 메뉴 보여줘", "아까 메뉴 다시 보여줘")) {
            c.send(text)
            assertSame(ChatCard.Menu, c.latestCard())
            assertEquals(question?.copy(paused = true), c.question)
            assertEquals(draft, c.draft)
        }
        c.send("주문한 거 보여줘")
        assertTrue(c.latestCard() is ChatCard.CartSummary)
        assertTrue(c.send("카페라떼 얼마야").response.contains("4000원"))
        c.send("도움말")
        assertTrue(c.orders.cart.isEmpty())
        assertEquals(PendingQuestion.SIZE, c.pending)
        assertEquals(OrderStage.ITEM_CONFIRM, c.send("큰 걸로요").stage)
        assertEquals(DrinkSize.TALL, c.draft?.size)
    }

    @Test fun keepsUnconfirmedItemAndAsksOptionsForEachItemInABatch() {
        val c = ConversationManager()
        c.startNewSession()
        c.send("아아 두 잔 보통으로 주세요")
        val original = c.draft
        c.send("아니요")
        c.send("수정할게요")
        c.send("다른 것도 추가할게")
        c.send("레몬에이드 한 잔 큰 걸로")
        var items = (c.latestCard() as ChatCard.ItemConfirmation).items
        assertEquals(original?.id, items.first().id)
        assertEquals(listOf("아메리카노", "레몬에이드"), items.map { it.menu.name })
        c.send("첫 번째 음료 두 잔 말고 한 잔")
        items = (c.latestCard() as ChatCard.ItemConfirmation).items
        assertEquals(1, items.first().quantity)
        c.send("맞아요")
        c.send("카푸치노 한 잔 그리고 유자차 두 잔")
        c.send("따뜻하게")
        c.send("보통 크기로")
        assertEquals(PendingQuestion.TEMPERATURE, c.pending)
        c.send("차갑게")
        c.send("큰 걸로")
        items = (c.latestCard() as ChatCard.ItemConfirmation).items
        assertEquals(listOf(Temperature.HOT, Temperature.ICE), items.map { it.temperature })
        assertEquals(2, c.orders.cart.size)
        c.send("맞아요")
        assertEquals(4, c.orders.cart.size)
        c.send("맞아요")
        assertEquals(4, c.orders.cart.size)
    }

    @Test fun validatedCartEditsResolveTargetsAndNeverApplyPartOfABatch() {
        val c = ConversationManager()
        c.startNewSession()
        c.send("아아 두 잔 보통으로")
        c.send("맞아요")
        c.send("카푸치노 따뜻하게 한 잔 큰 걸로")
        c.send("맞아요")
        val baseline = c.orders.cart
        assertTrue(c.send("아까 그거 하나 빼줘").response.contains("어떤"))
        assertEquals(PendingQuestion.CART_TARGET, c.pending)
        assertEquals(baseline, c.orders.cart)
        c.send("첫 번째 음료")
        assertEquals(1, c.orders.cart.first().quantity)
        c.send("카푸치노 HOT → ICE 변경")
        assertEquals(Temperature.HOT, c.orders.cart.last().temperature)
        c.send("맞아요")
        assertEquals(Temperature.ICE, c.orders.cart.last().temperature)
        val changed = c.orders.cart
        c.send("아메리카노 하나 빼고 레몬에이드 따뜻하게 주세요")
        assertEquals(changed, c.orders.cart)
        c.send("아메리카노 하나 빼고 레몬에이드 큰 걸로 주세요")
        assertEquals(changed, c.orders.cart)
        assertTrue((c.latestCard() as ChatCard.ItemConfirmation).removals.isNotEmpty())
        c.send("이건 취소할게")
        assertEquals(changed, c.orders.cart)
        c.send("아메리카노 9잔 빼줘")
        assertEquals(changed, c.orders.cart)
        c.send("아메리카노 -1잔으로 바꿔줘")
        assertEquals(changed, c.orders.cart)
        assertNull(c.draft)
        c.send("전부 취소할게요")
        c.send("아니요")
        assertEquals(changed, c.orders.cart)
        c.send("모르는 메뉴 주문")
        assertEquals(changed, c.orders.cart)
    }

    @Test fun sameVariantsMergeAndPaymentLocksTheCompletedOrderUntilNewSession() {
        val c = ConversationManager()
        c.startNewSession()
        val sessionId = c.contextSnapshot()!!.sessionId
        repeat(2) {
            c.send("아아 두 잔 보통으로")
            c.send("맞아요")
        }
        c.send("뜨아 한 잔 보통으로")
        c.send("맞아요")
        assertEquals(listOf(4, 1), c.orders.cart.map { it.quantity })
        assertEquals(15000, c.orders.total)
        repeat(3) { c.send("주문한 거 보여줘") }
        assertEquals(sessionId, c.contextSnapshot()?.sessionId)
        assertEquals(OrderStage.PAYMENT, c.send("계산할게요").stage)
        assertTrue(c.beginPaymentEvent())
        assertFalse(c.beginPaymentEvent())
        c.paymentEvent(true)
        val paidCart = c.orders.cart
        val paidHistory = c.messages
        c.send("아메리카노 삭제")
        c.send("계산할게요")
        c.returnFromPayment()
        assertEquals(paidCart, c.orders.cart)
        assertEquals(paidHistory, c.messages)
        c.startNewSession()
        assertNotEquals(sessionId, c.contextSnapshot()?.sessionId)
        assertEquals(1, c.messages.size)
        assertTrue(c.orders.cart.isEmpty())
    }

    @Test fun llmAdapterUsesContextAndStableIdsAndRejectsInvalidWireData() {
        val fake = FakeNluEngine()
        var contextSeen: NluContext? = null
        val c = ConversationManager(LlmNluEngine(LlmEngine { text, context ->
            contextSeen = context
            when (text) {
                "모델 수정" -> """{"function":"change_quantity","target_id":"${context.cart.first().id}","quantity":2}"""
                "모델 오류" -> """{"function":"remove_menu","quantity":"1"}"""
                "없는 대상" -> """{"function":"remove_menu","target_id":"missing","quantity":1}"""
                else -> NluJsonAdapter.toDebugJson(fake.analyze(text))
            }
        }))
        c.startNewSession()
        c.send("아아 한 잔 보통으로")
        c.send("맞아요")
        val before = c.orders.cart
        c.send("모델 오류")
        c.send("없는 대상")
        assertEquals(before, c.orders.cart)
        c.send("모델 수정")
        assertEquals(before.single().id, contextSeen?.cart?.single()?.id)
        assertEquals(before, c.orders.cart)
        c.send("맞아요")
        assertEquals(2, c.orders.cart.single().quantity)
        assertEquals(before.single().id, c.orders.cart.single().id)
    }

    @Test fun deployedMalloItemsReachTheCartOnlyAfterConfirmation() {
        val c = ConversationManager(LlmNluEngine(LlmEngine { text, _ ->
            when (text) {
                "네" -> """{"function":"dialogue_response","parameters":{"value":"YES"}}"""
                "오류" -> """{"function":"add_menu","parameters":{"items":[{"quantity":2}]}}"""
                else -> """{"function":"add_menu","parameters":{"items":[{"menu_name":"아메리카노","quantity":1,"temperature":"ICE","size":"REGULAR"},{"menu_name":"카페라떼","quantity":2,"temperature":"HOT","size":"TALL"}]}}"""
            }
        }))
        c.startNewSession()
        c.send("오류")
        assertTrue(c.orders.cart.isEmpty())
        c.send("두 메뉴 주문")
        assertTrue(c.orders.cart.isEmpty())
        c.send("네")
        assertEquals(listOf("아메리카노", "카페라떼"), c.orders.cart.map { it.menu.name })
        assertEquals(listOf(1, 2), c.orders.cart.map { it.quantity })
        assertEquals(listOf(Temperature.ICE, Temperature.HOT), c.orders.cart.map { it.temperature })
        assertEquals(listOf(DrinkSize.REGULAR, DrinkSize.TALL), c.orders.cart.map { it.size })
    }
}
