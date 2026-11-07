package com.example.cafe_kiosk_ai.order

/** Local-process contract matching the design document. No network transport is implied. */
interface AiModule {
    suspend fun initialize(locale: String, menuCatalog: MenuCatalog): Result<String>
    fun startListening(requestId: String, cartContext: CartContext?): Boolean
    suspend fun stopListening(requestId: String): Boolean
    fun setInferenceListener(listener: (Result<InferenceResult>) -> Unit)
    suspend fun speak(text: String): Boolean
}

/** Deterministic demo adapter. Replace this class with the on-device/JNI adapter later. */
class MockAiModule : AiModule {
    private var listener: ((Result<InferenceResult>) -> Unit)? = null
    override suspend fun initialize(locale: String, menuCatalog: MenuCatalog): Result<String> =
        if (locale == "ko-KR" && menuCatalog.items.isNotEmpty()) Result.success("READY")
        else Result.failure(IllegalArgumentException("AI-E006 INVALID_REQUEST"))

    override fun startListening(requestId: String, cartContext: CartContext?): Boolean = requestId.isNotBlank()

    override suspend fun stopListening(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        listener?.invoke(Result.success(InferenceResult(
            requestId = requestId,
            transcript = "아이스 아메리카노 두 잔 주세요",
            commands = listOf(AiCommand(CommandType.ADD_ITEM, "M001", 2, mapOf("temperature" to "ICE", "size" to "R"))),
        )))
        return true
    }

    override fun setInferenceListener(listener: (Result<InferenceResult>) -> Unit) { this.listener = listener }
    override suspend fun speak(text: String): Boolean = text.isNotBlank()
}
