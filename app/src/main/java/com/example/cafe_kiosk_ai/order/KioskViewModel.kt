package com.example.cafe_kiosk_ai.order

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class KioskStage { PRODUCT_REVIEW, ORDER_REVIEW }
enum class VoiceStage { IDLE, LISTENING, PROCESSING }
data class KioskUiState(
    val stage: KioskStage = KioskStage.PRODUCT_REVIEW,
    val voice: VoiceStage = VoiceStage.IDLE,
    val transcript: String? = null,
    val lines: List<CartLine> = emptyList(),
    val total: Int = 0,
    val message: String? = null,
)

class KioskViewModel(
    private val ai: AiModule = MockAiModule(),
    private val menus: MenuRepository = InMemoryMenuRepository(),
    private val validator: CommandValidator = CommandValidator(menus),
    private val cart: CartStore = CartStore(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(KioskUiState())
    val state: StateFlow<KioskUiState> = mutableState.asStateFlow()
    private var initialized = false
    private var activeRequestId: String? = null

    init {
        ai.setInferenceListener { result ->
            viewModelScope.launch {
                val inference = result.getOrElse {
                    mutableState.value = mutableState.value.copy(voice = VoiceStage.IDLE, message = "AI-E004 주문을 분석하지 못했습니다.")
                    return@launch
                }
                if (inference.requestId != activeRequestId) return@launch
                val error = inference.commands.firstNotNullOfOrNull(validator::validate)
                if (error != null) {
                    mutableState.value = mutableState.value.copy(voice = VoiceStage.IDLE, message = error)
                } else {
                    inference.commands.forEach { cart.apply(it, menus) }
                    mutableState.value = mutableState.value.copy(
                        voice = VoiceStage.IDLE, transcript = inference.transcript,
                        lines = cart.lines(), total = cart.total(), message = "주문하신 상품이 맞나요?",
                    )
                    ai.speak("아이스 아메리카노 두 잔 맞으신가요?")
                }
                activeRequestId = null
            }
        }
        loadMockPreview()
    }

    private fun loadMockPreview() {
        viewModelScope.launch {
            val init = if (initialized) Result.success("READY") else ai.initialize("ko-KR", menus.catalog())
            initialized = init.isSuccess
            if (init.isFailure) {
                mutableState.value = mutableState.value.copy(message = "AI-E001 예시 데이터를 준비하지 못했습니다.")
                return@launch
            }
            val requestId = "mock-preview-${UUID.randomUUID()}"
            activeRequestId = requestId
            if (!ai.startListening(requestId, CartContext(cart.lines())) || !ai.stopListening(requestId)) {
                activeRequestId = null
                mutableState.value = mutableState.value.copy(message = "예시 주문 데이터를 불러오지 못했습니다.")
            }
        }
    }

    fun startVoiceOrder() {
        if (mutableState.value.voice != VoiceStage.IDLE || mutableState.value.stage != KioskStage.PRODUCT_REVIEW) return
        val requestId = UUID.randomUUID().toString()
        mutableState.value = mutableState.value.copy(voice = VoiceStage.PROCESSING, message = "준비하고 있어요...")
        viewModelScope.launch {
            val init = if (initialized) Result.success("READY") else ai.initialize("ko-KR", menus.catalog())
            initialized = init.isSuccess
            if (init.isFailure) {
                mutableState.value = mutableState.value.copy(voice = VoiceStage.IDLE, message = "AI-E001 AI 모듈을 준비하지 못했습니다.")
                return@launch
            }
            if (!ai.startListening(requestId, CartContext(cart.lines()))) {
                mutableState.value = mutableState.value.copy(voice = VoiceStage.IDLE, message = "AI-E006 음성 요청을 시작하지 못했습니다.")
                return@launch
            }
            activeRequestId = requestId
            mutableState.value = mutableState.value.copy(voice = VoiceStage.LISTENING, message = "예시 주문을 듣고 있어요.")
            delay(700)
            mutableState.value = mutableState.value.copy(voice = VoiceStage.PROCESSING, message = "주문을 확인하고 있어요...")
            if (!ai.stopListening(requestId)) {
                activeRequestId = null
                mutableState.value = mutableState.value.copy(voice = VoiceStage.IDLE, message = "AI-E006 요청을 종료하지 못했습니다.")
            }
        }
    }

    fun confirmOrder() {
        if (cart.lines().isEmpty()) {
            mutableState.value = mutableState.value.copy(message = "먼저 음성으로 주문해 주세요.")
            return
        }
        mutableState.value = mutableState.value.copy(stage = KioskStage.ORDER_REVIEW, message = "주문 내역을 확인해 주세요.")
    }

    fun returnToProductReview() { mutableState.value = mutableState.value.copy(stage = KioskStage.PRODUCT_REVIEW, message = null) }
    fun resetHome() {
        cart.apply(AiCommand(CommandType.CANCEL_ORDER), menus)
        mutableState.value = KioskUiState(message = "처음 화면으로 돌아왔습니다.")
        initialized = false
        loadMockPreview()
    }
}
