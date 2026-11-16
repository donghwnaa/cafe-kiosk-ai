package com.example.cafe_kiosk_ai

import com.example.cafe_kiosk_ai.llm.GenieInference
import com.example.cafe_kiosk_ai.llm.LlmUnavailableException
import com.google.gson.JsonParser
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

internal data class DemoItem(val menu: String, val quantity: Int?, val temperature: Temperature?, val size: DrinkSize?)
internal data class DemoStep(
    val text: String,
    val intent: NluIntent,
    val pending: PendingQuestion = PendingQuestion.NONE,
    val cart: List<DemoItem> = emptyList(),
    val drafts: List<DemoItem> = emptyList(),
    val expectRejection: Boolean = false
)
internal data class DemoScenario(val label: String, val steps: List<DemoStep>)
internal data class DemoTurn(
    val requestId: String,
    val text: String,
    val messages: List<ChatMessage>,
    val inference: GenieInference?,
    val function: String?,
    val jsonValid: Boolean?,
    val outcome: String?,
    val failure: String?
)

internal class GenieDemoRunner(
    private val infer: suspend (String, NluContext, String) -> GenieInference,
    private val pauseMs: Long = 0
) {
    private val running = Mutex()

    suspend fun run(
        scenarios: List<DemoScenario>,
        onScenario: (DemoScenario) -> Unit,
        onStart: (requestId: String, text: String) -> Unit,
        onTurn: (DemoTurn) -> Unit
    ): Boolean {
        if (!running.tryLock()) return false
        try {
            val runId = UUID.randomUUID().toString().take(8)
            var stepNumber = 0
            for (scenario in scenarios) {
                currentCoroutineContext().ensureActive()
                onScenario(scenario)
                var inference: GenieInference? = null
                var modelError: String? = null
                var jsonValid: Boolean? = null
                var decodedIntent: NluIntent? = null
                var requestId = ""
                val nlu = LlmNluEngine(LlmEngine { text, context ->
                    try {
                        infer(text, context, requestId).also { inference = it }.json
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        val code = (error as? LlmUnavailableException)?.code ?: "GENIE_ERROR"
                        modelError = "${code}: ${error.cause?.message ?: error.message.orEmpty()}"
                        throw error
                    }
                }, FunctionCallAdapter { raw ->
                    val decoded = runCatching { NluJsonAdapter.decodeGenieOrThrow(raw) }
                    jsonValid = decoded.isSuccess
                    decoded.getOrNull()?.also { decodedIntent = it.intent }
                        ?: NluResult(NluIntent.UNKNOWN, ambiguous = "주문 내용을 확인하지 못했습니다. 다시 말씀해 주세요.")
                })
                val conversation = ConversationManager(nlu, allowPayment = false)
                conversation.startNewSession()
                for (step in scenario.steps) {
                    currentCoroutineContext().ensureActive()
                    requestId = "$runId-${++stepNumber}"
                    inference = null
                    modelError = null
                    jsonValid = null
                    decodedIntent = null
                    onStart(requestId, step.text)
                    val before = conversation.messages.size
                    val update = conversation.handleUserInput(step.text)
                    val next = checkNotNull(conversation.contextSnapshot())
                    val cart = next.cart.map { DemoItem(it.menu.name, it.quantity, it.temperature, it.size) }
                    val drafts = next.drafts.map { DemoItem(it.menu.name, it.quantity, it.temperature, it.size) }
                    val failure = modelError ?: when {
                        inference == null -> "Genie 응답을 받지 못했습니다."
                        jsonValid != true && !step.expectRejection -> "Function Call JSON 검증 실패"
                        jsonValid == true && decodedIntent != step.intent -> "예상 기능 ${step.intent}, 실제 기능 $decodedIntent"
                        next.question?.kind.orNone() != step.pending -> "예상 확인 단계 ${step.pending}, 실제 단계 ${next.question?.kind.orNone()}"
                        cart.toSet() != step.cart.toSet() || cart.size != step.cart.size -> "테스트 장바구니가 예상과 다릅니다."
                        drafts.toSet() != step.drafts.toSet() || drafts.size != step.drafts.size -> "확인 전 주문이 예상과 다릅니다."
                        else -> null
                    }
                    val function = inference?.let { result ->
                        runCatching { JsonParser.parseString(result.json).asJsonObject.get("function").asString }.getOrNull()
                    }
                    onTurn(DemoTurn(requestId, step.text, conversation.messages.drop(before).filterNot { it.fromUser },
                        inference, function, jsonValid, update.response, failure))
                    if (modelError != null) return true
                    if (pauseMs > 0 && stepNumber < scenarios.sumOf { it.steps.size }) delay(pauseMs)
                }
            }
            return true
        } finally {
            running.unlock()
        }
    }

    private fun PendingQuestion?.orNone(): PendingQuestion = this ?: PendingQuestion.NONE
}

internal object GenieDemoScenarios {
    private val regular = DrinkSize.REGULAR
    private val ice = Temperature.ICE
    private val hot = Temperature.HOT
    private val confirm = PendingQuestion.ITEM_CONFIRMATION
    private fun americano(count: Int, temperature: Temperature? = ice, size: DrinkSize? = regular) =
        DemoItem("아메리카노", count, temperature, size)
    private fun latte(count: Int) = DemoItem("카페라떼", count, hot, regular)
    private val tea = DemoItem("유자차", 1, hot, regular)
    private fun add(text: String, vararg items: DemoItem, cart: List<DemoItem> = emptyList()) =
        DemoStep(text, if (items.size > 1) NluIntent.MULTI_ACTION else NluIntent.ADD, confirm, cart, items.toList())
    private fun yes(vararg items: DemoItem) = DemoStep("맞아요.", NluIntent.CONFIRM, cart = items.toList())
    private fun show(text: String, vararg items: DemoItem) = DemoStep(text, NluIntent.SHOW_CART, cart = items.toList())
    private fun quantity(text: String, cart: List<DemoItem>, vararg drafts: DemoItem) =
        DemoStep(text, NluIntent.CHANGE, confirm, cart, drafts.toList())
    private fun reject(text: String) = DemoStep(text, NluIntent.UNKNOWN, expectRejection = true)

    val all = listOf(
        DemoScenario("A · 기본 주문", listOf(
            add("아이스 아메리카노 한 잔 보통 크기로 주세요.", americano(1)), yes(americano(1)),
            quantity("아메리카노 한 잔 더 추가해 주세요.", listOf(americano(1)), americano(2)), yes(americano(2)),
            show("지금 주문한 거 보여주세요.", americano(2)), show("총 몇 잔인가요?", americano(2))
        )),
        DemoScenario("B · 다중 주문", listOf(
            add("아이스 아메리카노 두 잔이랑 따뜻한 카페라떼 한 잔 모두 보통 크기로 주세요.", americano(2), latte(1)),
            yes(americano(2), latte(1)),
            add("따뜻한 유자차도 한 잔 보통 크기로 주세요.", tea, cart = listOf(americano(2), latte(1))),
            yes(americano(2), latte(1), tea),
            quantity("카페라떼 한 잔 더 주세요.", listOf(americano(2), latte(1), tea), latte(2)),
            yes(americano(2), latte(2), tea),
            show("지금까지 주문한 메뉴 모두 보여주세요.", americano(2), latte(2), tea)
        )),
        DemoScenario("C · 자연스러운 주문", listOf(
            add("아아 두 개만 보통 크기로 줘요.", americano(2)), yes(americano(2)),
            add("뜨거운 라떼도 보통으로 하나 주시고요.", latte(1), cart = listOf(americano(2))),
            yes(americano(2), latte(1)),
            quantity("그 아메리카노 하나만 더 주세요.", listOf(americano(2), latte(1)), americano(3)),
            yes(americano(3), latte(1)), show("내가 지금 뭐 주문했죠?", americano(3), latte(1))
        )),
        DemoScenario("D · 주문 수정", listOf(
            add("아이스 아메리카노 두 잔 보통 크기로 주세요.", americano(2)), yes(americano(2)),
            DemoStep("아메리카노 한 잔 빼 주세요.", NluIntent.REMOVE, confirm, listOf(americano(2))),
            yes(americano(1)),
            quantity("남은 아메리카노는 따뜻한 걸로 바꿔주세요.", listOf(americano(1)), americano(1, hot)),
            yes(americano(1, hot)), show("주문 내역 다시 보여주세요.", americano(1, hot))
        )),
        DemoScenario("E · 취소 및 재주문", listOf(
            add("따뜻한 아메리카노 한 잔 보통 크기로 주세요.", americano(1, hot)),
            DemoStep("아니요, 그거 취소할게요.", NluIntent.CANCEL),
            add("대신 따뜻한 카페라떼 한 잔 보통 크기로 주세요.", latte(1)), yes(latte(1)),
            show("이제 주문한 것만 보여주세요.", latte(1))
        )),
        DemoScenario("F · 예외 및 전체 취소", listOf(
            reject("라면 한 그릇 주세요."), reject("아메리카노 0잔 주세요."), reject("아메리카노 천 잔 주세요."),
            DemoStep("아메리카노 주세요.", NluIntent.ADD, PendingQuestion.TEMPERATURE,
                drafts = listOf(americano(1, null, null))),
            DemoStep("아이스로 주세요.", NluIntent.CHANGE, PendingQuestion.SIZE, drafts = listOf(americano(1, ice, null))),
            quantity("보통 크기로 주세요.", emptyList(), americano(1)), yes(americano(1)),
            DemoStep("아까 주문한 것 전부 없애 주세요.", NluIntent.CANCEL_ALL, PendingQuestion.CANCEL_ALL, listOf(americano(1))),
            yes(), show("장바구니 보여주세요.")
        ))
    )
}
