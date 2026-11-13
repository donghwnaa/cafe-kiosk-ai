package com.example.cafe_kiosk_ai

import androidx.lifecycle.ViewModel
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

internal class KioskSessionViewModel : ViewModel() {
    private data class SpokenResponse(val text: String, val forPayment: Boolean)

    private val spokenResponses = ArrayDeque<SpokenResponse>()
    private var captureSpokenResponses = false
    val conversation = ConversationManager(responseOutput = AiResponseOutput(::enqueueResponse))
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inputJob: Job? = null
    val isProcessingInput: Boolean get() = inputJob != null
    private var paymentJob: Job? = null
    private val revision = MutableStateFlow(0)
    val updates = revision.asStateFlow()

    val hasSpokenResponses: Boolean get() = spokenResponses.isNotEmpty()

    fun captureSpokenResponses(enabled: Boolean) {
        captureSpokenResponses = enabled
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
        if (text.isBlank() || isProcessingInput || conversation.stage in setOf(OrderStage.HOME, OrderStage.PAYMENT)) return false
        val job = sessionScope.launch(start = CoroutineStart.LAZY) {
            try {
                conversation.handleUserInput(text)
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
        super.onCleared()
    }
}
