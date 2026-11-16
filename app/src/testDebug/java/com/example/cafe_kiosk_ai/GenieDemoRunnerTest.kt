package com.example.cafe_kiosk_ai

import com.example.cafe_kiosk_ai.llm.GenieInference
import com.example.cafe_kiosk_ai.llm.LlmUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GenieDemoRunnerTest {
    @Test fun allScenariosUseTheProductionOrderFlowAndIsolatedCarts() = runBlocking {
        val responses = ArrayDeque(listOf(
            americano(1), yes, more("아메리카노"), yes, show, show,
            batch(americano(2), latte), yes, add("유자차", 1, "HOT"), yes, more("카페라떼"), yes, show,
            americano(2), yes, latte, yes, more("아메리카노"), yes, show,
            americano(2), yes, """{"function":"remove_menu","menu":"아메리카노","quantity":1}""", yes,
            """{"function":"change_option","menu":"아메리카노","temperature":"HOT"}""", yes, show,
            americano(1, "HOT"), """{"function":"cancel_order"}""", latte, yes, show,
            add("라면", 1, "HOT"), americano(0), americano(1000),
            """{"function":"add_menu","menu":"아메리카노"}""",
            """{"function":"change_option","temperature":"ICE"}""",
            """{"function":"change_option","size":"REGULAR"}""", yes, """{"function":"cancel_all"}""", yes, show
        ))
        var precedingSession: String? = null
        var freshCarts = 0
        val requests = mutableSetOf<String>()
        val turns = mutableListOf<DemoTurn>()
        val runner = GenieDemoRunner({ _, context, id ->
            assertTrue(requests.add(id))
            if (precedingSession != context.sessionId) {
                precedingSession = context.sessionId
                assertTrue(context.cart.isEmpty() && context.drafts.isEmpty())
                freshCarts++
            }
            GenieInference(responses.removeFirst(), 13)
        })

        assertTrue(runner.run(GenieDemoScenarios.all, {}, { _, _ -> }, { turns.add(it) }))
        assertEquals(42, turns.size)
        assertEquals(6, freshCarts)
        assertTrue(responses.isEmpty())
        assertTrue(turns.joinToString("\n") { "${it.text} ${it.failure}" }, turns.all { it.failure == null })
        assertEquals(3, turns.count { it.jsonValid == false })
        assertTrue(turns.any { it.outcome?.contains("총 2잔") == true })
        assertTrue(turns.all { it.inference?.inferenceMs == 13L })
    }

    @Test fun aDuplicateRunIsRejectedAndCancellationStopsBeforeTheNextTurn() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<GenieInference>()
        var calls = 0
        val runner = GenieDemoRunner({ _, _, _ ->
            calls++
            if (calls == 1) {
                entered.complete(Unit)
                blocked.await()
            } else GenieInference(show, 1)
        })
        val input = listOf(DemoScenario("테스트", List(2) { DemoStep("장바구니 보여주세요.", NluIntent.SHOW_CART) }))
        val first = async { runner.run(input, {}, { _, _ -> }, { error("Cancelled turn was published") }) }
        entered.await()
        assertFalse(runner.run(input, {}, { _, _ -> }, { error("Duplicate run was accepted") }))
        first.cancelAndJoin()
        assertEquals(1, calls)
        assertTrue(runner.run(input, {}, { _, _ -> }, { assertNull(it.failure) }))
        assertEquals(3, calls)
    }

    @Test fun initializationErrorStopsTheRunAndDoesNotReportJsonOrInference() = runBlocking {
        val turns = mutableListOf<DemoTurn>()
        val runner = GenieDemoRunner({ _, _, _ -> throw LlmUnavailableException("초기화 실패", "GENIE_INIT_FAILED") })
        runner.run(GenieDemoScenarios.all, {}, { _, _ -> }, { turns.add(it) })
        assertEquals(1, turns.size)
        assertTrue(turns.single().failure!!.contains("GENIE_INIT_FAILED"))
        assertNull(turns.single().jsonValid)
        assertNull(turns.single().inference)
    }

    @Test fun paymentIsBlockedEvenWithAnExplicitRequestAndCommittedItems() = runBlocking {
        val inputs = ArrayDeque(listOf(americano(1), yes, """{"function":"confirm_order"}"""))
        val manager = ConversationManager(
            LlmNluEngine(LlmEngine { _, _ -> inputs.removeFirst() }, FunctionCallAdapter(NluJsonAdapter::decodeGenie)),
            allowPayment = false
        )
        manager.startNewSession()
        manager.handleUserInput("아이스 아메리카노 한 잔 보통 크기로 주세요.")
        manager.handleUserInput("맞아요.")
        val before = manager.orders.cart
        assertEquals(1, before.size)
        val payment = manager.handleUserInput("결제해 주세요.")
        assertEquals(OrderStage.CART_REVIEW, payment.stage)
        assertTrue(payment.response.contains("결제를 진행할 수 없습니다"))
        assertFalse(manager.beginPaymentEvent())
        assertEquals(before, manager.orders.cart)
    }

    private companion object {
        const val yes = """{"function":"dialogue_response","response":"confirm"}"""
        const val show = """{"function":"show_cart"}"""
        val latte = add("카페라떼", 1, "HOT")
        fun add(name: String, count: Int, temp: String) =
            """{"function":"add_menu","menu":"$name","quantity":$count,"temperature":"$temp","size":"REGULAR"}"""
        fun americano(count: Int, temp: String = "ICE") = add("아메리카노", count, temp)
        fun more(name: String) = """{"function":"change_quantity","menu":"$name","delta":1}"""
        fun batch(vararg actions: String) = """{"function":"multi_action","actions":[${actions.joinToString(",")}]}"""
    }
}
