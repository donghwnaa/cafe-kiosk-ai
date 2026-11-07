package com.example.cafe_kiosk_ai.order

data class MenuItem(val id: String, val name: String, val price: Int, val aliases: List<String>, val temperatures: Set<String>, val sizes: Set<String>)
data class OrderOptions(val temperature: String, val size: String)
data class CartLine(val menu: MenuItem, val quantity: Int, val options: OrderOptions)
data class MenuCatalog(val items: List<MenuItem>)
data class CartContext(val lines: List<CartLine>)
data class InferenceResult(val requestId: String, val transcript: String, val commands: List<AiCommand>)

enum class CommandType { ADD_ITEM, REMOVE_ITEM, CHANGE_QUANTITY, CHANGE_OPTION, CANCEL_ORDER, CONFIRM_ORDER, SHOW_CART }
data class AiCommand(val type: CommandType, val menuId: String? = null, val quantity: Int? = null, val options: Map<String, String> = emptyMap())

sealed interface AiError {
    data object InitializationFailed : AiError
    data object AudioInputFailed : AiError
    data object SttFailed : AiError
    data object InferenceFailed : AiError
    data object TtsFailed : AiError
    data object InvalidRequest : AiError
}

object MockCatalog {
    val default = MenuCatalog(listOf(
        MenuItem("M001", "아메리카노", 4500, listOf("아아", "아메"), setOf("HOT", "ICE"), setOf("R", "L")),
        MenuItem("M002", "카페라떼", 5000, listOf("라떼"), setOf("HOT", "ICE"), setOf("R", "L")),
    ))
}
