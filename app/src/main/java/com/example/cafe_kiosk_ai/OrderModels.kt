package com.example.cafe_kiosk_ai

import java.util.UUID

enum class Temperature { HOT, ICE }
enum class DrinkSize { REGULAR, TALL }
enum class DialogueState {
    WAITING_FOR_ORDER, WAITING_FOR_MENU, WAITING_FOR_QUANTITY,
    WAITING_FOR_TEMPERATURE, WAITING_FOR_SIZE, WAITING_FOR_ITEM_CONFIRMATION,
    WAITING_FOR_CART_CONFIRMATION, WAITING_FOR_CANCEL_CONFIRMATION,
    PAYMENT_PENDING, ORDER_COMPLETED
}
enum class PendingQuestion { NONE, MENU, QUANTITY, TEMPERATURE, SIZE, ITEM_CONFIRMATION, DRAFT_TARGET, CART_TARGET, CART_CHANGE, CANCEL_ALL }
enum class PaymentState { WAITING_PAYMENT, PROCESSING_PAYMENT, PAYMENT_SUCCESS, PAYMENT_FAILED }

/** 시연용 결과만 반환하며 실제 카드 단말기나 결제망에는 연결하지 않습니다. */
class MockPaymentProcessor {
    fun begin(): PaymentState = PaymentState.PROCESSING_PAYMENT
    fun finish(success: Boolean): PaymentState = if (success) PaymentState.PAYMENT_SUCCESS else PaymentState.PAYMENT_FAILED
}

data class MenuItem(
    val name: String, val priceRegular: Int, val priceTall: Int,
    val temperatures: Set<Temperature>, val sizes: Set<DrinkSize>, val image: Int
) {
    // 화면에 있던 예시값을 바탕으로 한 시연용 임시 가격. 실제 매장 가격으로 교체해야 합니다.
    fun price(size: DrinkSize): Int = if (size == DrinkSize.TALL) priceTall else priceRegular
}

object MenuCatalog {
    private val both = setOf(Temperature.HOT, Temperature.ICE)
    private val allSizes = setOf(DrinkSize.REGULAR, DrinkSize.TALL)
    val items = listOf(
        MenuItem("아메리카노", 3000, 3500, both, allSizes, R.drawable.americano),
        MenuItem("카페라떼", 4000, 4500, both, allSizes, R.drawable.cafelatte),
        MenuItem("바닐라라떼", 4500, 5000, both, allSizes, R.drawable.vanillalatte),
        MenuItem("카푸치노", 4000, 4500, both, allSizes, R.drawable.cappuccino),
        MenuItem("카페모카", 4500, 5000, both, allSizes, R.drawable.cafemocha),
        MenuItem("녹차라떼", 4500, 5000, both, allSizes, R.drawable.greentealatte),
        MenuItem("초코라떼", 4500, 5000, both, allSizes, R.drawable.chocolatte),
        MenuItem("레몬에이드", 4500, 5000, setOf(Temperature.ICE), allSizes, R.drawable.lemonade),
        MenuItem("복숭아아이스티", 4000, 4500, both, allSizes, R.drawable.icetea),
        MenuItem("유자차", 4000, 4500, both, allSizes, R.drawable.uzatea)
    )
    fun find(name: String?) = items.find { it.name == name }
}

data class DraftOrder(
    val menu: MenuItem,
    val quantity: Int? = null,
    val temperature: Temperature? = null,
    val size: DrinkSize? = null,
    val replaceCartId: String? = null,
    val id: String = UUID.randomUUID().toString()
) {
    fun item(): CartItem? {
        return CartItem(menu, quantity ?: return null, temperature ?: return null, size ?: return null, replaceCartId ?: id)
    }
}

data class CartItem(
    val menu: MenuItem, val quantity: Int, val temperature: Temperature, val size: DrinkSize,
    val id: String = UUID.randomUUID().toString()
) {
    val total: Int get() = quantity * menu.price(size)
    fun label() = "${if (temperature == Temperature.ICE) "아이스" else "따뜻한"} ${menu.name} ${if (size == DrinkSize.TALL) "큰" else "보통"} 사이즈 ${quantity}잔"
}

data class CartRemoval(val itemId: String, val quantity: Int)

class OrderValidator {
    fun validate(draft: DraftOrder): String? {
        if (draft.quantity == null || draft.quantity !in 1..99) return "수량은 1잔부터 99잔까지 말씀해 주세요."
        if (draft.temperature == null) return "따뜻하게 드릴까요, 차갑게 드릴까요?"
        if (draft.temperature !in draft.menu.temperatures) return "${draft.menu.name}에는 ${draft.temperature} 옵션이 없습니다. 다시 말씀해 주세요."
        if (draft.size == null) return "크기는 보통과 큰 사이즈 중 어떤 걸로 드릴까요?"
        if (draft.size !in draft.menu.sizes) return "${draft.menu.name}에는 해당 크기가 없습니다. 다시 말씀해 주세요."
        return null
    }

    fun validateOptions(menu: MenuItem, result: NluResult): String? = when {
        result.quantity != null && result.quantity !in 1..99 -> "수량은 1잔부터 99잔까지 말씀해 주세요."
        result.temperature != null && result.temperature !in menu.temperatures ->
            "${menu.name}에는 ${result.temperature} 옵션이 없습니다. 가능한 온도는 ${menu.temperatures.joinToString(" / ")}입니다."
        result.size != null && result.size !in menu.sizes -> "${menu.name}에는 해당 크기가 없습니다."
        else -> null
    }
}

class OrderManager(private val validator: OrderValidator = OrderValidator()) {
    var cart: List<CartItem> = emptyList()
        private set
    var lastConfirmedId: String? = null
        private set
    val total: Int get() = cart.sumOf { it.total }
    fun clear() {
        cart = emptyList()
        lastConfirmedId = null
    }

    /** Validates and applies the entire change to a copy before publishing the cart. */
    fun commit(drafts: List<DraftOrder>, removals: List<CartRemoval> = emptyList()): String? {
        drafts.firstNotNullOfOrNull(validator::validate)?.let { return it }
        val changedIds = removals.map { it.itemId } + drafts.mapNotNull { it.replaceCartId }
        if (changedIds.size != changedIds.distinct().size) return "같은 장바구니 항목에 여러 변경을 적용할 수 없습니다."
        val next = cart.toMutableList()
        for (removal in removals) {
            val index = next.indexOfFirst { it.id == removal.itemId }
            val item = next.getOrNull(index) ?: return "장바구니 항목이 바뀌었습니다. 다시 확인해 주세요."
            if (removal.quantity !in 1..item.quantity) return "현재 ${item.quantity}잔입니다. 뺄 수량을 다시 말씀해 주세요."
            if (removal.quantity == item.quantity) next.removeAt(index)
            else next[index] = item.copy(quantity = item.quantity - removal.quantity)
        }
        // Remove all replaced variants before merging, so one edit cannot overwrite another.
        for (id in drafts.mapNotNull { it.replaceCartId }) {
            if (!next.removeAll { it.id == id }) return "변경할 장바구니 항목을 다시 확인해 주세요."
        }
        var lastId = lastConfirmedId
        for (draft in drafts) {
            val item = draft.item() ?: return "상품 옵션을 다시 확인해 주세요."
            val same = next.indexOfFirst { it.menu == item.menu && it.temperature == item.temperature && it.size == item.size }
            if (same >= 0) {
                val previous = next[same]
                next[same] = previous.copy(quantity = previous.quantity + item.quantity)
                lastId = previous.id
            } else {
                next.add(item)
                lastId = item.id
            }
        }
        cart = next.toList()
        lastConfirmedId = lastId?.takeIf { id -> cart.any { it.id == id } }
        return null
    }
}
