package com.example.cafe_kiosk_ai

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.example.cafe_kiosk_ai.llm.GENIE_TAG
import com.example.cafe_kiosk_ai.llm.GenieLlmEngine
import com.example.cafe_kiosk_ai.llm.LlmUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class KioskSessionViewModel(application: Application) : AndroidViewModel(application) {
    private data class SpokenResponse(val text: String, val forPayment: Boolean)
    private data class TranscriptNotice(val afterMessageCount: Int, val message: ChatMessage)

    private val spokenResponses = ArrayDeque<SpokenResponse>()
    private val transcriptNotices = mutableListOf<TranscriptNotice>()
    private var captureSpokenResponses = false
    val usesGenieTextInput = AndroidRuntimeEnvironment.hasQualcommPlatform
    internal val genie = if (GenieLlmEngine.canRunOnDevice()) GenieLlmEngine(application) else null
    private val developmentNlu = if (usesGenieTextInput) null else createDevelopmentNlu()?.also {
        Log.i(GENIE_TAG, "ENGINE=FAKE_NLU DEBUG_ONLY")
    }
    private val nlu = when {
        genie != null -> LlmNluEngine(genie, FunctionCallAdapter { json ->
            NluJsonAdapter.decodeGenie(json, genie::recordValidation)
        })
        usesGenieTextInput -> NluEngine { _, _ ->
            throw LlmUnavailableException("Genie를 실행할 수 없습니다. 보드용 ARM64 Genie 빌드를 설치해 주세요.")
        }
        developmentNlu != null -> developmentNlu
        else -> NluEngine { _, _ ->
            throw LlmUnavailableException("이 기기에서는 주문 분석 모델을 실행할 수 없습니다. 관리자에게 문의해 주세요.")
        }
    }
    val conversation = ConversationManager(
        nlu = nlu,
        responseOutput = AiResponseOutput(::enqueueResponse)
    )
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inputJob: Job? = null
    val isProcessingInput: Boolean get() = inputJob != null
    private var paymentJob: Job? = null
    private val revision = MutableStateFlow(0)
    val updates = revision.asStateFlow()

    val hasSpokenResponses: Boolean get() = spokenResponses.isNotEmpty()
    val transcriptMessages: List<ChatMessage>
        get() {
            val messages = conversation.messages
            if (transcriptNotices.isEmpty()) return messages
            return messages.toMutableList().apply {
                transcriptNotices.forEachIndexed { index, notice ->
                    add(notice.afterMessageCount + index, notice.message)
                }
            }
        }
    val hasTrailingTranscriptNotice: Boolean
        get() = transcriptNotices.lastOrNull()?.afterMessageCount == conversation.messages.size

    fun showRecognitionRetry(text: String) {
        val count = conversation.messages.size
        // Automatic retries in the same turn share one visible notice.
        if (transcriptNotices.lastOrNull()?.afterMessageCount == count) return
        transcriptNotices.add(TranscriptNotice(count, ChatMessage(false, text)))
        publish()
    }

    fun captureSpokenResponses(enabled: Boolean) {
        captureSpokenResponses = enabled && !usesGenieTextInput
        if (!enabled) spokenResponses.clear()
    }

    fun takeSpokenResponse(forPayment: Boolean): String? {
        // A response belongs to the screen it was generated for.
        spokenResponses.removeAll { it.forPayment != forPayment }
        return spokenResponses.removeFirstOrNull()?.text
    }

    private fun enqueueResponse(message: ChatMessage) {
        if (captureSpokenResponses) {
            spokenResponses.addLast(SpokenResponse(message.text, conversation.stage == OrderStage.PAYMENT))
        }
    }

    fun startSession() {
        resetSession()
        conversation.startNewSession()
        publish()
    }

    fun goHome() {
        resetSession()
        publish()
    }

    fun submit(text: String): Boolean {
        if (text.isBlank()) return false
        return runInput {
            val before = conversation.orders.cart
            val update = conversation.handleUserInput(text, onInputRecorded = ::publish)
            if (genie != null && BuildConfig.DEBUG) {
                Log.d(GENIE_TAG, "ORDER_APPLY stage=${update.stage} pending=${conversation.pending} " +
                    "cartChanged=${before != conversation.orders.cart} cartLines=${conversation.orders.cart.size}")
            }
        }
    }

    fun retryGenieInitialization(): Boolean {
        val engine = genie ?: return false
        return runInput {
            try {
                engine.retryInitialization()
            } catch (_: LlmUnavailableException) {
                // The engine publishes the failed stage for the order screen.
            }
        }
    }

    private fun runInput(action: suspend () -> Unit): Boolean {
        if (isProcessingInput || conversation.stage in setOf(OrderStage.HOME, OrderStage.PAYMENT)) return false
        val job = sessionScope.launch(start = CoroutineStart.LAZY) {
            try {
                action()
            } finally {
                if (inputJob === coroutineContext[Job]) {
                    inputJob = null
                    publish()
                }
            }
        }
        inputJob = job
        publish()
        job.start()
        return true
    }

    fun returnFromPayment() {
        conversation.returnFromPayment()
        publish()
    }

    fun runPaymentTest(success: Boolean) {
        if (!conversation.beginPaymentEvent()) return
        publish()
        paymentJob = sessionScope.launch {
            delay(350)
            conversation.paymentEvent(success)
            publish()
        }
    }

    private fun resetSession() {
        spokenResponses.clear()
        transcriptNotices.clear()
        val previousInput = inputJob
        inputJob = null
        previousInput?.cancel()
        paymentJob?.cancel()
        paymentJob = null
        conversation.resetSession()
    }

    private fun publish() { revision.value += 1 }

    override fun onCleared() {
        sessionScope.cancel()
        genie?.close()
        super.onCleared()
    }
}
