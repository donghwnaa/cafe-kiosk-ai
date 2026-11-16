package com.example.cafe_kiosk_ai

import com.example.cafe_kiosk_ai.llm.LlmUnavailableException
import com.example.cafe_kiosk_ai.llm.GeniePrompt
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Korean input with injected model output. These tests do not execute Genie. */
class GenieOrderSafetyTest {
    @Test fun koreanTextIsShownBeforeInferenceAndPreservedInTheUtf8Prompt() = runBlocking {
        val input = "  아이스 아메리카노 두 잔 주세요.  "
        var displayed: String? = null
        val conversation = ConversationManager(NluEngine { text, context ->
            assertEquals(input, displayed)
            val bytes = GeniePrompt("", "").format(text, context).encodeToByteArray()
            val utterance = bytes.decodeToString(throwOnInvalidSequence = true).substringAfterLast("\nUtterance: ")
            assertEquals(input, JsonParser.parseString(utterance).asString)
            NluResult(NluIntent.SHOW_CART)
        })
        conversation.startNewSession()
        conversation.handleUserInput(input) { displayed = conversation.messages.last().text }
        assertEquals(input, conversation.messages.first { it.fromUser }.text)
    }

    private class Session {
        private var nextResponse: (() -> String)? = null
        var calls = 0
            private set
        val conversation = ConversationManager(LlmNluEngine(LlmEngine { _, _ ->
            calls++
            checkNotNull(nextResponse).invoke()
        }, FunctionCallAdapter(NluJsonAdapter::decodeGenie))).also { it.startNewSession() }

        val cart get() = conversation.orders.cart
        val drafts get() = conversation.contextSnapshot()!!.drafts
        val pending get() = conversation.pending

        fun send(text: String, json: String): ConversationUpdate = send(text) { json }

        fun send(text: String, response: () -> String): ConversationUpdate {
            nextResponse = response
            return runBlocking { conversation.handleUserInput(text) }
        }

        fun confirm() = send("맞아요.", YES)
        fun orderAmericano() {
            send("아이스 아메리카노 두 잔 보통 크기로 주세요.", ICE_AMERICANO)
            confirm()
        }
    }

    @Test fun singleOrdersAskForSizeBeforeAddingToCart() {
        val s = Session()
        s.send("아이스 아메리카노 두 잔 주세요.", add("아메리카노", 2, "ICE"))
        assertEquals(PendingQuestion.SIZE, s.pending)
        assertTrue(s.cart.isEmpty())
        s.confirm()
        assertTrue(s.cart.isEmpty())
        s.send("보통 크기로 주세요.", REGULAR)
        s.confirm()
        assertEquals(listOf("아메리카노" to 2), s.cart.map { it.menu.name to it.quantity })
        assertEquals(Temperature.ICE, s.cart.single().temperature)

        s.send("따뜻한 카페라떼 한 잔 주세요.", add("카페라떼", 1, "HOT"))
        assertEquals(PendingQuestion.SIZE, s.pending)
        s.send("보통 크기로 주세요.", REGULAR)
        s.confirm()
        assertEquals(listOf(Temperature.ICE, Temperature.HOT), s.cart.map { it.temperature })
        assertEquals(10_000, s.conversation.orders.total)
    }

    @Test fun batchAndAliasesResolveMissingOptionsAndRepeatDoesNotDuplicateDrafts() {
        val batch = multi(add("아메리카노", 2, "ICE"), add("카페라떼", 1, "HOT"))
        val full = Session()
        repeat(2) { full.send("아이스 아메리카노 두 잔이랑 따뜻한 카페라떼 한 잔 주세요.", batch) }
        assertEquals(2, full.drafts.size)
        assertTrue(full.cart.isEmpty())
        repeat(2) { full.send("보통 크기로 주세요.", REGULAR) }
        full.confirm()
        full.confirm()
        assertEquals(listOf(2, 1), full.cart.map { it.quantity })

        val alias = Session()
        alias.send("아아 두 개랑 라떼 하나 주세요.", multi(add("아메리카노", 2, "ICE"), add("카페라떼", 1)))
        alias.send("보통 크기로 주세요.", REGULAR)
        assertEquals(PendingQuestion.TEMPERATURE, alias.pending)
        assertEquals("카페라떼", alias.conversation.draft?.menu?.name)
        alias.send("따뜻하게 주세요.", """{"function":"change_option","temperature":"HOT"}""")
        alias.send("보통 크기로 주세요.", REGULAR)
        alias.confirm()
        assertEquals(full.cart.map { it.copy(id = "") }, alias.cart.map { it.copy(id = "") })
    }

    @Test fun quantityAndTemperatureEditsOnlyApplyAfterConfirmation() {
        val s = Session()
        s.orderAmericano()
        val original = s.cart.single()
        s.send("아메리카노 한 잔 더 추가해 줘.", """{"function":"change_quantity","menu":"아메리카노","delta":1}""")
        assertEquals(listOf(original), s.cart)
        s.confirm()
        assertEquals(3, s.cart.single().quantity)
        s.send("두 잔으로 바꿔 주세요.", """{"function":"change_quantity","menu":"아메리카노","quantity":2}""")
        s.confirm()
        s.send("아메리카노 두 잔 중 한 잔 빼줘.", """{"function":"remove_menu","menu":"아메리카노","quantity":1}""")
        assertEquals(2, s.cart.single().quantity)
        s.confirm()
        assertEquals(1, s.cart.single().quantity)
        s.send("아메리카노를 따뜻한 걸로 바꿔줘.", """{"function":"change_option","menu":"아메리카노","temperature":"HOT"}""")
        assertEquals(Temperature.ICE, s.cart.single().temperature)
        s.confirm()
        assertEquals(Temperature.HOT, s.cart.single().temperature)
        assertEquals(original.id, s.cart.single().id)
        val summary = s.send("장바구니 보여 주세요.", """{"function":"show_cart"}""")
        assertEquals(OrderStage.CART_REVIEW, summary.stage)
        val card = s.conversation.messages.last().card as ChatCard.CartSummary
        assertEquals(s.cart, card.items)
        assertEquals(3_000, card.total)
    }

    @Test fun invalidJsonAndUnresolvableOperationsLeaveCartAndDraftsIntact() {
        val s = Session()
        s.orderAmericano()
        val before = s.cart
        val invalid = listOf(
            "망고주스 주세요." to add("망고주스", 1),
            "따뜻한 레몬에이드 주세요." to add("레몬에이드", 1, "HOT"),
            "카페라떼 0잔 주세요." to add("카페라떼", 0),
            "카페라떼 마이너스 한 잔 주세요." to add("카페라떼", -1),
            "카페라떼 백 잔 주세요." to add("카페라떼", 100),
            "수량 요청" to """{"function":"add_menu","menu":"카페라떼","quantity":1.0}""",
            "수량 요청" to """{"function":"add_menu","menu":"카페라떼","quantity":"2"}""",
            "수량 요청" to """{"function":"add_menu","menu":"카페라떼","quantity":2,"quantity":3}""",
            "메뉴 주문" to """{"function":"add_menu","temperature":"COLD"}""",
            "메뉴 주문" to """{"function":"add_menu"}""",
            "메뉴 주문" to """{"function":"add_menu","menu":"카페라떼","delivery":"x"}""",
            "잘못된 JSON" to """{"function":""",
            "잘못된 함수" to """{"function":"reset_cart"}""",
            "카페라떼를 한 잔 빼줘." to """{"function":"remove_menu","menu":"카페라떼","quantity":1}""",
            "아메리카노를 빼줘." to """{"function":"remove_menu","menu":"아메리카노"}""",
            "아메리카노를 두 잔 줄여줘." to """{"function":"change_quantity","target_index":1,"delta":-2}""",
            "아메리카노를 98잔 늘려줘." to """{"function":"change_quantity","target_index":1,"delta":98}""",
            "두 번째 주문 변경" to """{"function":"change_quantity","target_index":2,"quantity":3}""",
            "두 메뉴 주문" to multi(add("카페라떼", 1), add("없는 메뉴", 1)),
            "같은 주문이 중복 출력됨" to multi(add("카페라떼", 1), add("카페라떼", 1)),
            "여러 메뉴 변경" to multi(add("카페라떼", 1), """{"function":"remove_menu","target_index":2,"quantity":1}"""),
            "저거 주세요." to """{"function":"request_clarification"}"""
        )
        invalid.forEach { (text, json) ->
            s.send(text, json)
            assertEquals(text, before, s.cart)
            assertTrue(text, s.drafts.isEmpty())
        }
        val calls = s.calls
        s.send("  ", "unused")
        assertEquals(calls, s.calls)
        assertEquals(before, s.cart)
    }

    @Test fun refusalFalseConfirmationAndPaymentCannotCommitPendingChanges() {
        val s = Session()
        s.orderAmericano()
        val original = s.cart
        s.send("두 잔 더 주세요.", """{"function":"change_quantity","menu":"아메리카노","delta":2}""")
        s.send("계산해 주세요.", PAY)
        assertEquals(OrderStage.ITEM_CONFIRM, s.conversation.stage)
        assertFalse(s.conversation.beginPaymentEvent())
        s.send("한 잔인가요?", YES)
        assertEquals(original, s.cart)
        assertTrue(s.conversation.messages.last().card is ChatCard.ItemConfirmation)
        s.send("아니요.", YES) // The model misclassified the refusal.
        assertEquals(PendingQuestion.ITEM_CORRECTION, s.pending)
        s.confirm()
        assertEquals(original, s.cart)
        s.send("세 잔으로 바꿔줘.", """{"function":"change_quantity","quantity":3}""")
        s.confirm()
        assertEquals(3, s.cart.single().quantity)
        s.send("결제하지 마세요.", PAY)
        assertFalse(s.conversation.beginPaymentEvent())
        assertEquals(OrderStage.CART_REVIEW, s.conversation.stage)
        s.send("계산해 주세요.", PAY)
        assertEquals(OrderStage.PAYMENT, s.conversation.stage)
        assertNull(s.conversation.orderNumber)
    }

    @Test fun cancellationAndRemovalRespectConfirmationAndAllowRecoveryAfterErrors() {
        val s = Session()
        s.orderAmericano()
        val original = s.cart
        s.send("아메리카노 한 잔 빼줘.", """{"function":"remove_menu","target_index":1,"quantity":1}""")
        s.send("존재하지 않는 두 번째 항목 취소", """{"function":"cancel_order","target_index":2}""")
        assertEquals(PendingQuestion.ITEM_CONFIRMATION, s.pending)
        s.send("취소할게요.", """{"function":"cancel_order"}""")
        s.confirm()
        assertEquals(original, s.cart)
        s.send("전체 취소해 주세요.", """{"function":"cancel_all"}""")
        s.send("아니요.", YES)
        assertEquals(original, s.cart)
        s.send("전체 취소해 주세요.", """{"function":"cancel_all"}""")
        s.send("뭐라고요?", YES)
        assertEquals(original, s.cart)
        s.confirm()
        assertTrue(s.cart.isEmpty())
        val failure = s.send("주문할게요.") { throw LlmUnavailableException("모델 초기화 실패") }
        assertEquals("모델 초기화 실패", failure.response)
        assertTrue(s.cart.isEmpty())
        s.orderAmericano()
        assertEquals(2, s.cart.single().quantity)
    }

    @Test fun mergedQuantityOverflowRejectsTheWholeTransaction() {
        val s = Session()
        s.send("아이스 아메리카노 아흔아홉 잔 주세요.", ICE_AMERICANO.replace(":2,", ":99,"))
        s.confirm()
        val before = s.cart
        s.send("카페라떼랑 아메리카노 추가해 주세요.", multi(
            """{"function":"add_menu","menu":"카페라떼","quantity":1,"temperature":"HOT","size":"REGULAR"}""", ICE_AMERICANO))
        assertTrue(s.confirm().response.contains("99잔"))
        assertEquals(before, s.cart)
        assertEquals(99, s.cart.single().quantity)
    }

    @Test fun lateInferenceFromAbandonedSessionDoesNotChangeAReplacementSession() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<String>()
        val c = ConversationManager(LlmNluEngine(LlmEngine { _, _ ->
            entered.complete(Unit)
            result.await()
        }, FunctionCallAdapter(NluJsonAdapter::decodeGenie)))
        c.startNewSession()
        val old = async { c.handleUserInput("아이스 아메리카노 두 잔 주세요.") }
        entered.await()
        c.startNewSession()
        result.complete(ICE_AMERICANO)
        assertTrue(old.await().response.contains("세션"))
        assertTrue(c.orders.cart.isEmpty())
        assertTrue(c.contextSnapshot()!!.drafts.isEmpty())
    }

    private companion object {
        const val YES = """{"function":"dialogue_response","response":"confirm"}"""
        const val PAY = """{"function":"confirm_order"}"""
        const val REGULAR = """{"function":"change_option","size":"REGULAR"}"""
        const val ICE_AMERICANO = """{"function":"add_menu","menu":"아메리카노","quantity":2,"temperature":"ICE","size":"REGULAR"}"""
        fun add(menu: String, quantity: Int, temperature: String? = null): String {
            val option = temperature?.let { ""","temperature":"$it"""" }.orEmpty()
            return """{"function":"add_menu","menu":"$menu","quantity":$quantity$option}"""
        }
        fun multi(vararg actions: String) =
            """{"function":"multi_action","actions":[${actions.joinToString(",")}]}"""
    }
}
