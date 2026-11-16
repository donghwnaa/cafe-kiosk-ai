package com.example.cafe_kiosk_ai

enum class NluIntent {
    ADD, ADD_MORE, REMOVE, CHANGE, MULTI_ACTION, SHOW_MENU, MENU_PRICE,
    SHOW_CART, PAY, CANCEL, CANCEL_ALL, CONFIRM, DENY, HELP, KEEP, UNKNOWN
}
enum class ItemReference { LAST_CONFIRMED, UNSPECIFIED }
data class NluResult(
    val intent: NluIntent, val menu: String? = null, val quantity: Int? = null,
    val temperature: Temperature? = null, val size: DrinkSize? = null,
    val ambiguous: String? = null, val actions: List<NluResult> = emptyList(),
    val removeAll: Boolean = false, val targetId: String? = null, val targetOrdinal: Int? = null,
    val reference: ItemReference? = null, val targetTemperature: Temperature? = null,
    val targetSize: DrinkSize? = null, val quantityDelta: Int? = null
)

data class NluContext(
    val sessionId: String,
    val state: DialogueState,
    val history: List<ChatMessage>,
    val drafts: List<DraftOrder>,
    val cart: List<CartItem>,
    val question: QuestionContext?,
    val lastConfirmedId: String?
)

fun interface NluEngine { suspend fun analyze(text: String, context: NluContext): NluResult }
fun interface LlmEngine { suspend fun functionCallJson(text: String, context: NluContext): String }
fun interface AiResponseOutput { fun onResponse(message: ChatMessage) }
interface VoiceController { fun startListening(turnId: Long); fun stopListening(); fun release() }
interface TtsEngine {
    val isInitializing: Boolean
    fun speak(text: String, onEvent: (TtsPlaybackEvent) -> Unit)
    fun stop()
    fun release()
}

sealed interface TtsPlaybackEvent {
    data object Started : TtsPlaybackEvent
    data object Completed : TtsPlaybackEvent
    data object Stopped : TtsPlaybackEvent
    data class Error(val reason: TtsError) : TtsPlaybackEvent
}

enum class TtsError { NO_ENGINE, KOREAN_UNAVAILABLE, INITIALIZATION, PLAYBACK }
