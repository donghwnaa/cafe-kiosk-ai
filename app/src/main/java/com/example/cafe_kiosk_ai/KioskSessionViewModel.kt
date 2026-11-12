package com.example.cafe_kiosk_ai

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class KioskSessionViewModel : ViewModel() {
    val conversation = ConversationManager()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inputJob: Job? = null
    private var paymentJob: Job? = null
    private val revision = MutableStateFlow(0)
    val updates = revision.asStateFlow()

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
        if (inputJob?.isActive == true || conversation.stage in setOf(OrderStage.HOME, OrderStage.PAYMENT)) return false
        inputJob = sessionScope.launch {
            conversation.handleUserInput(text)
            publish()
        }
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
        inputJob?.cancel()
        inputJob = null
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
