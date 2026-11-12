package com.example.cafe_kiosk_ai

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ChatCard {
    data object Menu : ChatCard
    data class ItemConfirmation(val items: List<CartItem>, val removals: List<String> = emptyList()) : ChatCard
    data class CartSummary(val items: List<CartItem>, val total: Int) : ChatCard
}

data class ChatMessage(val fromUser: Boolean, val text: String, val card: ChatCard? = null)

enum class OrderStage(val screen: Int) { HOME(0), CHAT(1), ITEM_CONFIRM(2), CART_REVIEW(3), PAYMENT(4) }
data class ConversationUpdate(val stage: OrderStage, val response: String) {
    val screen: Int get() = stage.screen
}

data class QuestionContext(
    val kind: PendingQuestion,
    val sessionId: String,
    val previousState: DialogueState,
    val resumeState: DialogueState,
    val draftIds: List<String> = emptyList(),
    val cartIds: List<String> = emptyList(),
    val paused: Boolean = false
)

class ConversationManager(
    private val nlu: NluEngine = FakeNluEngine(),
    val orders: OrderManager = OrderManager(),
    private val validator: OrderValidator = OrderValidator(),
    private val paymentProcessor: MockPaymentProcessor = MockPaymentProcessor(),
    private val responseOutput: AiResponseOutput = AiResponseOutput {}
) {
    private val history = mutableListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = history.toList()
    private val drafts = mutableListOf<DraftOrder>()
    val draft: DraftOrder? get() = drafts.find { it.id == activeDraftId } ?: drafts.singleOrNull()
    private var activeDraftId: String? = null
    private val stagedRemovals = mutableListOf<CartRemoval>()
    private var targetAction: NluResult? = null
    private var sessionId: String? = null
    private var analyzingSessionId: String? = null

    var state = DialogueState.WAITING_FOR_ORDER
        private set
    var question: QuestionContext? = null
        private set
    val pending: PendingQuestion get() = question?.kind ?: PendingQuestion.NONE
    var paymentState = PaymentState.WAITING_PAYMENT
        private set
    var orderNumber: String? = null
        private set
    val stage: OrderStage get() = if (sessionId == null) OrderStage.HOME else when (state) {
        DialogueState.WAITING_FOR_ITEM_CONFIRMATION -> OrderStage.ITEM_CONFIRM
        DialogueState.WAITING_FOR_CART_CONFIRMATION, DialogueState.WAITING_FOR_CANCEL_CONFIRMATION -> OrderStage.CART_REVIEW
        DialogueState.PAYMENT_PENDING, DialogueState.ORDER_COMPLETED -> OrderStage.PAYMENT
        else -> OrderStage.CHAT
    }

    fun startNewSession(): ConversationUpdate {
        resetSession()
        sessionId = UUID.randomUUID().toString()
        return say("무엇을 주문하시겠어요?", ChatCard.Menu)
    }

    fun resetSession() {
        sessionId = null
        orders.clear()
        history.clear()
        clearDrafts()
        clearQuestion()
        state = DialogueState.WAITING_FOR_ORDER
        paymentState = PaymentState.WAITING_PAYMENT
        orderNumber = null
    }

    fun contextSnapshot(): NluContext? = sessionId?.let {
        NluContext(it, state, messages, drafts.toList(), orders.cart.toList(), question, orders.lastConfirmedId)
    }

    /** Shared entry point for debug text, buttons, and a future STT transcript. */
    suspend fun handleUserInput(text: String): ConversationUpdate {
        val input = text.trim()
        val context = contextSnapshot() ?: return ConversationUpdate(stage, "주문하기 버튼을 먼저 눌러주세요.")
        if (input.isEmpty()) return ConversationUpdate(stage, "말씀을 입력해 주세요.")
        if (stage == OrderStage.PAYMENT || analyzingSessionId == context.sessionId) {
            return ConversationUpdate(stage, "현재 주문을 변경할 수 없습니다.")
        }
        analyzingSessionId = context.sessionId
        try {
            val result = try {
                withContext(Dispatchers.Default) { nlu.analyze(input, context) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NluResult(NluIntent.UNKNOWN, ambiguous = "주문 내용을 확인하지 못했습니다. 다시 말씀해 주세요.")
            }
            if (context.sessionId != sessionId || stage == OrderStage.PAYMENT) {
                return ConversationUpdate(stage, "주문 세션이 바뀌었습니다.")
            }
            history.add(ChatMessage(true, input))
            return applyResult(result)
        } finally {
            if (analyzingSessionId == context.sessionId) analyzingSessionId = null
        }
    }

    private fun applyResult(result: NluResult): ConversationUpdate {
        result.ambiguous?.let { return say(it) }
        when (result.intent) {
            NluIntent.SHOW_MENU -> return pauseFor("주문할 수 있는 메뉴를 보여드릴게요.", ChatCard.Menu)
            NluIntent.MENU_PRICE -> return showPrice(result)
            NluIntent.SHOW_CART -> return showCart()
            NluIntent.HELP -> return pauseFor("메뉴와 온도, 크기를 말씀해 주세요. 예를 들어 ‘아아 두 잔 주세요’라고 하실 수 있어요.")
            NluIntent.KEEP -> return pauseFor("기존 주문은 그대로 두겠습니다.")
            else -> Unit
        }
        question = question?.copy(paused = false)
        if (pending == PendingQuestion.CANCEL_ALL) {
            return when (result.intent) {
                NluIntent.CONFIRM -> {
                    orders.clear()
                    clearDrafts()
                    clearQuestion()
                    state = DialogueState.WAITING_FOR_ORDER
                    say("전체 주문을 취소했습니다. 무엇을 주문하시겠어요?", cartCard())
                }
                NluIntent.DENY -> {
                    state = question!!.resumeState
                    question = suspendedQuestion
                    suspendedQuestion = null
                    say("전체 취소를 중단했습니다. 기존 주문을 유지합니다.", cartCard())
                }
                else -> say("장바구니 전체를 취소할까요? 네 또는 아니요로 말씀해 주세요.")
            }
        }
        when (result.intent) {
            NluIntent.CANCEL_ALL -> return askCancelAll()
            NluIntent.CANCEL -> return cancelDraftOrCart(result)
            NluIntent.PAY -> return requestPayment()
            NluIntent.ADD_MORE -> return askForAnotherMenu()
            NluIntent.MULTI_ACTION -> return stageBatch(result.actions)
            else -> Unit
        }
        if (pending == PendingQuestion.CART_TARGET || pending == PendingQuestion.CART_CHANGE) {
            // A new named order takes precedence over a parked cart selection.
            if (result.intent == NluIntent.ADD && result.menu != null && result.menu != targetAction?.menu &&
                question!!.cartIds.none { id -> orders.cart.any { it.id == id && it.menu.name == result.menu } }) {
                clearQuestion()
                return addOrder(result)
            }
            return answerCartQuestion(result)
        }
        if (pending == PendingQuestion.DRAFT_TARGET) return answerDraftTarget(result)
        if (result.intent == NluIntent.CONFIRM) {
            return if (pending == PendingQuestion.ITEM_CONFIRMATION) confirmDrafts()
            else say("먼저 확인할 메뉴와 옵션을 말씀해 주세요.")
        }
        if (result.intent == NluIntent.DENY) {
            return if (pending == PendingQuestion.ITEM_CONFIRMATION)
                say("어떤 내용을 바꿀까요? 메뉴 이름과 수량, 온도 또는 크기를 말씀해 주세요.")
            else say("주문할 메뉴나 바꿀 내용을 말씀해 주세요.")
        }
        if (result.intent == NluIntent.REMOVE) {
            if (drafts.isNotEmpty() || stagedRemovals.isNotEmpty())
                return say("임시 주문을 확인하거나 취소한 뒤 장바구니를 변경해 주세요.")
            return cartAction(result)
        }
        if (result.intent == NluIntent.CHANGE) {
            if (drafts.isEmpty() && stagedRemovals.isNotEmpty())
                return say("확인 중인 변경을 먼저 확정하거나 취소해 주세요.")
            return if (drafts.isEmpty()) cartAction(result) else editDraft(result)
        }
        if (result.intent == NluIntent.ADD) {
            if (result.menu == null && hasTarget(result)) {
                return cartAction(result)
            }
            if (result.menu != null && (pending == PendingQuestion.MENU || drafts.isEmpty() ||
                    drafts.none { it.menu.name == result.menu })) return addOrder(result)
            if (drafts.isNotEmpty() && hasOptions(result)) return editDraft(result)
        }
        return say(when {
            pending == PendingQuestion.MENU -> "추가할 메뉴 이름을 말씀해 주세요."
            pending in optionQuestions -> validator.validate(draft ?: return say("메뉴 이름을 다시 말씀해 주세요.")) ?: "옵션을 다시 말씀해 주세요."
            stage == OrderStage.CART_REVIEW -> "추가할 메뉴나 변경할 장바구니 항목을 말씀해 주세요."
            else -> "메뉴를 알아듣지 못했습니다. 메뉴 이름을 다시 말씀해 주세요."
        })
    }

    private fun addOrder(result: NluResult): ConversationUpdate {
        val menu = MenuCatalog.find(result.menu) ?: return say("등록되지 않은 메뉴입니다. 메뉴판에서 골라주세요.")
        validator.validateOptions(menu, result)?.let { return say(it) }
        val order = updatedDraft(DraftOrder(menu), result)
        drafts.add(order)
        activeDraftId = order.id
        return reviewDrafts()
    }

    private fun editDraft(result: NluResult): ConversationUpdate {
        if (!hasOptions(result)) return say("어떤 내용을 바꿀까요? 수량, 온도 또는 크기를 말씀해 주세요.")
        val choices = drafts.filter {
            (result.targetId == null || result.targetId == it.id) &&
                (result.menu == null || result.menu == it.menu.name) &&
                (result.targetTemperature == null || result.targetTemperature == it.temperature) &&
                (result.targetSize == null || result.targetSize == it.size)
        }
        val selected = when {
            result.targetOrdinal != null -> drafts.getOrNull(result.targetOrdinal - 1)?.takeIf { it in choices }
            choices.size == 1 -> choices.single()
            pending in optionQuestions && !hasTarget(result) && result.menu == null -> choices.find { it.id == activeDraftId }
            else -> null
        }
        if (selected != null) return amend(selected, result)
        if (choices.isEmpty()) return say("변경할 임시 주문을 찾지 못했습니다.")
        targetAction = result
        ask(PendingQuestion.DRAFT_TARGET, DialogueState.WAITING_FOR_ITEM_CONFIRMATION, draftIds = choices.map { it.id })
        return say("어떤 상품을 바꿀까요? 메뉴 이름이나 확인 카드의 순서를 말씀해 주세요.")
    }

    private fun answerDraftTarget(result: NluResult): ConversationUpdate {
        val action = targetAction ?: return reviewDrafts()
        if (result.intent !in selectionIntents) return say("변경할 상품의 메뉴 이름이나 순서를 말씀해 주세요.")
        val choices = drafts.filterIndexed { index, order ->
            order.id in question!!.draftIds &&
                (result.targetOrdinal == null || result.targetOrdinal == index + 1) &&
                (result.targetId == null || result.targetId == order.id) &&
                (result.menu == null || result.menu == order.menu.name) &&
                (result.temperature == null || result.temperature == order.temperature) &&
                (result.size == null || result.size == order.size)
        }
        if (choices.size != 1 || (!hasTarget(result) && result.menu == null && result.temperature == null && result.size == null))
            return say("변경할 상품의 메뉴 이름이나 순서를 말씀해 주세요.")
        return amend(choices.single(), action)
    }

    private fun amend(current: DraftOrder, result: NluResult): ConversationUpdate {
        validator.validateOptions(current.menu, result)?.let { return say(it) }
        drafts[drafts.indexOf(current)] = updatedDraft(current, result)
        activeDraftId = current.id
        return reviewDrafts()
    }

    private fun updatedDraft(draft: DraftOrder, result: NluResult): DraftOrder = draft.copy(
        quantity = result.quantity ?: draft.quantity ?: 1,
        temperature = result.temperature ?: draft.temperature ?: draft.menu.temperatures.singleOrNull(),
        size = result.size ?: draft.size ?: draft.menu.sizes.singleOrNull()
    )

    private fun reviewDrafts(): ConversationUpdate {
        targetAction = null
        val incomplete = drafts.find { validator.validate(it) != null }
        if (incomplete != null) {
            activeDraftId = incomplete.id
            val (kind, next) = when {
                incomplete.quantity == null -> PendingQuestion.QUANTITY to DialogueState.WAITING_FOR_QUANTITY
                incomplete.temperature == null -> PendingQuestion.TEMPERATURE to DialogueState.WAITING_FOR_TEMPERATURE
                else -> PendingQuestion.SIZE to DialogueState.WAITING_FOR_SIZE
            }
            ask(kind, next, draftIds = listOf(incomplete.id), resume = DialogueState.WAITING_FOR_ITEM_CONFIRMATION)
            val prefix = if (drafts.size > 1) "${incomplete.menu.name}의 옵션을 확인할게요. " else ""
            return say(prefix + validator.validate(incomplete))
        }
        val items = drafts.mapNotNull(DraftOrder::item)
        ask(PendingQuestion.ITEM_CONFIRMATION, DialogueState.WAITING_FOR_ITEM_CONFIRMATION,
            draftIds = drafts.map { it.id }, resume = DialogueState.WAITING_FOR_CART_CONFIRMATION)
        val removals = stagedRemovals.map { removal ->
            val item = orders.cart.find { it.id == removal.itemId }
            "${item?.menu?.name ?: "장바구니 항목"} ${item?.temperature ?: ""} ${item?.size ?: ""} ${removal.quantity}잔 빼기"
        }
        val text = if (items.size == 1 && removals.isEmpty()) "${items.single().label()}이 맞으세요?"
        else "아래 주문 및 변경 사항을 확인해 주세요. 모두 맞으세요?"
        return say(text, ChatCard.ItemConfirmation(items, removals))
    }

    private fun confirmDrafts(): ConversationUpdate {
        if (drafts.isEmpty() && stagedRemovals.isEmpty()) return say("확인할 상품이 없습니다.")
        orders.commit(drafts.toList(), stagedRemovals.toList())?.let { return say(it) }
        clearDrafts()
        clearQuestion()
        state = DialogueState.WAITING_FOR_CART_CONFIRMATION
        return say("장바구니에 반영했습니다. 현재 주문 내역을 확인해 주세요.", cartCard())
    }

    private fun stageBatch(actions: List<NluResult>): ConversationUpdate {
        if (actions.size !in 2..10 || actions.any { it.ambiguous != null }) return say("복합 주문을 다시 확인해 주세요.")
        val proposed = mutableListOf<DraftOrder>()
        val removals = mutableListOf<CartRemoval>()
        for (action in actions) {
            when (action.intent) {
                NluIntent.ADD -> {
                    val menu = MenuCatalog.find(action.menu) ?: return say("복합 주문에 등록되지 않은 메뉴가 있습니다.")
                    validator.validateOptions(menu, action)?.let { return say(it) }
                    proposed.add(updatedDraft(DraftOrder(menu), action))
                }
                NluIntent.CHANGE, NluIntent.REMOVE -> {
                    val item = cartTargets(action).singleOrNull() ?: return say("복합 주문의 변경 대상이 불분명합니다. 해당 메뉴의 옵션이나 순서를 말씀해 주세요.")
                    if (action.intent == NluIntent.REMOVE) {
                        val removal = removalFor(item, action) ?: return say("뺄 수량이 현재 장바구니 수량과 맞지 않습니다.")
                        removals.add(removal)
                    } else {
                        if (!hasOptions(action)) return say("복합 주문에서 바꿀 옵션을 말씀해 주세요.")
                        validator.validateOptions(item.menu, action)?.let { return say(it) }
                        proposed.add(updatedDraft(replacementFor(item), action))
                    }
                }
                else -> return say("복합 주문은 추가, 변경, 삭제만 함께 확인할 수 있습니다.")
            }
        }
        val ids = (stagedRemovals + removals).map { it.itemId } + (drafts + proposed).mapNotNull { it.replaceCartId }
        if (ids.size != ids.distinct().size) return say("같은 장바구니 항목의 변경은 한 번씩 말씀해 주세요.")
        stagedRemovals.addAll(removals)
        drafts.addAll(proposed)
        return reviewDrafts()
    }

    private fun cartTargets(result: NluResult): List<CartItem> {
        val filterTemperature = result.targetTemperature ?: result.temperature.takeIf { result.intent == NluIntent.REMOVE }
        val filterSize = result.targetSize ?: result.size.takeIf { result.intent == NluIntent.REMOVE }
        return orders.cart.filterIndexed { index, item ->
            (result.targetId == null || item.id == result.targetId) &&
                (result.targetOrdinal == null || index + 1 == result.targetOrdinal) &&
                (result.reference != ItemReference.LAST_CONFIRMED || item.id == orders.lastConfirmedId) &&
                (result.menu == null || item.menu.name == result.menu) &&
                (filterTemperature == null || item.temperature == filterTemperature) &&
                (filterSize == null || item.size == filterSize)
        }
    }

    private fun cartAction(result: NluResult): ConversationUpdate {
        val matches = cartTargets(result)
        if (matches.isEmpty()) return say("장바구니에서 해당 상품을 찾지 못했습니다.")
        if (matches.size == 1) return performCartAction(result, matches.single())
        targetAction = result
        ask(PendingQuestion.CART_TARGET, DialogueState.WAITING_FOR_CART_CONFIRMATION, cartIds = matches.map { it.id })
        return say("어떤 장바구니 항목인지 순서, 메뉴 이름, 온도 또는 크기를 말씀해 주세요.", cartCard())
    }

    private fun answerCartQuestion(result: NluResult): ConversationUpdate {
        val action = targetAction ?: return say("장바구니에서 변경할 항목을 다시 말씀해 주세요.")
        if (result.intent !in selectionIntents) return say("장바구니 항목의 메뉴 이름, 순서 또는 옵션을 말씀해 주세요.")
        if (pending == PendingQuestion.CART_CHANGE) {
            val item = orders.cart.find { it.id == question!!.cartIds.singleOrNull() }
                ?: return say("장바구니 항목을 다시 확인해 주세요.")
            if (!hasOptions(result)) return say("바꿀 수량, 온도 또는 크기를 말씀해 주세요.")
            if ((result.menu != null || hasTarget(result)) && item !in cartTargets(result))
                return say("선택한 항목의 옵션만 말씀해 주세요.")
            return performCartAction(action.copy(quantity = result.quantity, temperature = result.temperature, size = result.size), item)
        }
        val selectors = result.copy(intent = NluIntent.REMOVE)
        val selection = cartTargets(selectors).filter { it.id in question!!.cartIds }
        if (selection.size != 1 || (!hasTarget(result) && result.menu == null && result.temperature == null && result.size == null))
            return say("어떤 항목인지 순서나 구별되는 옵션을 말씀해 주세요.")
        return performCartAction(action, selection.single())
    }

    private fun performCartAction(action: NluResult, item: CartItem): ConversationUpdate {
        if (action.intent == NluIntent.REMOVE) {
            val removal = removalFor(item, action) ?: return say("현재 ${item.quantity}잔입니다. 뺄 수량을 다시 말씀해 주세요.")
            orders.commit(emptyList(), listOf(removal))?.let { return say(it) }
            clearQuestion()
            state = DialogueState.WAITING_FOR_CART_CONFIRMATION
            return say("${item.menu.name} ${removal.quantity}잔을 장바구니에서 뺐습니다.", cartCard())
        }
        if (action.intent == NluIntent.CHANGE && !hasOptions(action)) {
            targetAction = action
            ask(PendingQuestion.CART_CHANGE, DialogueState.WAITING_FOR_CART_CONFIRMATION, cartIds = listOf(item.id))
            return say("${item.menu.name}의 어떤 내용을 바꿀까요? 수량, 온도 또는 크기를 말씀해 주세요.")
        }
        validator.validateOptions(item.menu, action)?.let { return say(it) }
        val next = if (action.intent == NluIntent.ADD) {
            updatedDraft(DraftOrder(item.menu, 1, item.temperature, item.size), action)
        } else updatedDraft(replacementFor(item), action)
        drafts.add(next)
        activeDraftId = next.id
        return reviewDrafts()
    }

    private fun replacementFor(item: CartItem) = DraftOrder(item.menu, item.quantity, item.temperature, item.size, replaceCartId = item.id)
    private fun removalFor(item: CartItem, action: NluResult): CartRemoval? {
        val amount = if (action.removeAll) item.quantity else action.quantity ?: 1
        return CartRemoval(item.id, amount).takeIf { amount in 1..item.quantity }
    }

    private var suspendedQuestion: QuestionContext? = null

    private fun askCancelAll(): ConversationUpdate {
        if (orders.cart.isEmpty() && drafts.isEmpty() && stagedRemovals.isEmpty()) return say("취소할 주문이 없습니다.")
        suspendedQuestion = question
        ask(PendingQuestion.CANCEL_ALL, DialogueState.WAITING_FOR_CANCEL_CONFIRMATION, resume = state)
        return say("장바구니 전체를 취소할까요? 네 또는 아니요로 말씀해 주세요.")
    }

    private fun cancelDraftOrCart(result: NluResult): ConversationUpdate {
        if (drafts.isEmpty() && stagedRemovals.isEmpty()) {
            if (orders.cart.isEmpty()) return say("취소할 주문이 없습니다.")
            if (hasTarget(result) || result.menu != null)
                return cartAction(result.copy(intent = NluIntent.REMOVE, removeAll = true))
            return askCancelAll()
        }
        if (stagedRemovals.isNotEmpty()) clearDrafts()
        else {
            val explicit = result.targetId != null || result.targetOrdinal != null || result.menu != null
            val selected = if (!explicit) draft else drafts.filterIndexed { index, item ->
                (result.targetId == null || item.id == result.targetId) &&
                    (result.targetOrdinal == null || index + 1 == result.targetOrdinal) &&
                    (result.menu == null || item.menu.name == result.menu)
            }.singleOrNull()
            if (selected == null) return say("취소할 임시 상품의 메뉴 이름이나 순서를 말씀해 주세요.")
            drafts.remove(selected)
            activeDraftId = null
        }
        clearQuestion()
        if (drafts.isNotEmpty()) {
            say("현재 임시 상품을 취소했습니다. 남은 임시 주문을 확인해 주세요.")
            return reviewDrafts()
        }
        state = if (orders.cart.isEmpty()) DialogueState.WAITING_FOR_ORDER else DialogueState.WAITING_FOR_CART_CONFIRMATION
        return say("현재 임시 주문을 취소했습니다.")
    }

    private fun askForAnotherMenu(): ConversationUpdate {
        activeDraftId = null
        targetAction = null
        ask(PendingQuestion.MENU, DialogueState.WAITING_FOR_MENU, draftIds = drafts.map { it.id })
        return say(if (drafts.isEmpty()) "어떤 메뉴를 추가할까요?" else "확인 중인 상품은 보관했습니다. 어떤 메뉴를 더 주문하시겠어요?")
    }

    private fun showPrice(result: NluResult): ConversationUpdate {
        val menu = MenuCatalog.find(result.menu) ?: return pauseFor("메뉴 이름을 정확히 말씀해 주세요.")
        return pauseFor("${menu.name}은 보통 ${menu.priceRegular}원, 큰 사이즈 ${menu.priceTall}원입니다. 시연용 임시 가격입니다.")
    }

    fun showCart(): ConversationUpdate {
        if (stage == OrderStage.HOME || stage == OrderStage.PAYMENT) return ConversationUpdate(stage, "")
        if (question == null && drafts.isEmpty()) state = DialogueState.WAITING_FOR_CART_CONFIRMATION
        return pauseFor(if (orders.cart.isEmpty()) "장바구니가 비어 있습니다." else "현재 장바구니를 확인해 주세요.", cartCard())
    }

    private fun requestPayment(): ConversationUpdate {
        if (orders.cart.isEmpty()) return say("장바구니가 비어 있어 결제할 수 없습니다.")
        if (drafts.isNotEmpty() || stagedRemovals.isNotEmpty()) return say("확인 중인 임시 주문을 먼저 확정하거나 취소해 주세요.")
        clearQuestion()
        state = DialogueState.PAYMENT_PENDING
        paymentState = PaymentState.WAITING_PAYMENT
        return say("개발용 테스트 결제입니다. 카드를 결제기에 넣어주세요.")
    }

    fun returnFromPayment(): ConversationUpdate {
        if (state != DialogueState.PAYMENT_PENDING || paymentState == PaymentState.PROCESSING_PAYMENT)
            return ConversationUpdate(stage, "")
        state = DialogueState.WAITING_FOR_CART_CONFIRMATION
        paymentState = PaymentState.WAITING_PAYMENT
        return showCart()
    }

    fun beginPaymentEvent(): Boolean {
        if (state != DialogueState.PAYMENT_PENDING ||
            paymentState !in setOf(PaymentState.WAITING_PAYMENT, PaymentState.PAYMENT_FAILED)) return false
        paymentState = paymentProcessor.begin()
        return true
    }

    fun paymentEvent(success: Boolean): ConversationUpdate {
        if (state != DialogueState.PAYMENT_PENDING || paymentState != PaymentState.PROCESSING_PAYMENT)
            return ConversationUpdate(stage, "결제 대기 상태가 아닙니다.")
        paymentState = paymentProcessor.finish(success)
        if (success) {
            state = DialogueState.ORDER_COMPLETED
            orderNumber = "TEST-${System.currentTimeMillis().toString().takeLast(6)}"
            return say("주문이 완료되었습니다. 테스트 주문번호 $orderNumber")
        }
        return say("테스트 결제에 실패했습니다. 다시 시도하거나 장바구니로 돌아가 주세요.")
    }

    private fun ask(kind: PendingQuestion, next: DialogueState, draftIds: List<String> = emptyList(),
                    cartIds: List<String> = emptyList(), resume: DialogueState = next) {
        question = QuestionContext(kind, checkNotNull(sessionId), state, resume, draftIds, cartIds)
        state = next
    }
    private fun clearQuestion() {
        question = null
        targetAction = null
        suspendedQuestion = null
    }
    private fun clearDrafts() {
        drafts.clear()
        activeDraftId = null
        stagedRemovals.clear()
    }
    private fun hasTarget(result: NluResult): Boolean = result.targetId != null || result.targetOrdinal != null || result.reference != null
    private fun hasOptions(result: NluResult): Boolean = result.quantity != null || result.temperature != null || result.size != null
    private fun cartCard() = ChatCard.CartSummary(orders.cart.toList(), orders.total)
    private fun pauseFor(text: String, card: ChatCard? = null): ConversationUpdate {
        question = question?.copy(paused = true)
        return say(text, card)
    }
    private fun say(text: String, card: ChatCard? = null): ConversationUpdate {
        val message = ChatMessage(false, text, card)
        history.add(message)
        runCatching { responseOutput.onResponse(message) }
        return ConversationUpdate(stage, text)
    }

    private companion object {
        val optionQuestions = setOf(PendingQuestion.QUANTITY, PendingQuestion.TEMPERATURE, PendingQuestion.SIZE)
        val selectionIntents = setOf(NluIntent.ADD, NluIntent.CHANGE, NluIntent.REMOVE, NluIntent.UNKNOWN)
    }
}
