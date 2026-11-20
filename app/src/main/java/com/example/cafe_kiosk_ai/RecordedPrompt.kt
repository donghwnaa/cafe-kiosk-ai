package com.example.cafe_kiosk_ai

internal enum class RecordedPrompt(val text: String, val fileName: String) {
    WELCOME("무엇을 주문하시겠어요?", "prompt_welcome"),
    CART_HAS_ITEMS("현재 장바구니를 확인해 주세요.", "prompt_cart_has_items"),
    CART_EMPTY("장바구니가 비어 있습니다.", "prompt_cart_empty"),
    CART_UPDATED("장바구니에 반영했습니다. 현재 주문 내역을 확인해 주세요.", "prompt_cart_updated");

    companion object {
        fun forText(text: String): RecordedPrompt? = entries.firstOrNull { it.text == text }
    }
}
