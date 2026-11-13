package com.example.cafe_kiosk_ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private enum class Page { START, CONVERSATION, PAYMENT }
    private enum class VoiceState(@StringRes val message: Int) {
        READY(R.string.voice_ready), LISTENING(R.string.voice_listening),
        PROCESSING(R.string.voice_processing), INITIALIZING_TTS(R.string.voice_initializing_tts),
        SPEAKING(R.string.voice_speaking), ERROR(R.string.voice_error), STOPPED(R.string.voice_stopped)
    }

    private lateinit var screenContainer: FrameLayout
    private lateinit var session: KioskSessionViewModel
    private lateinit var voice: VoiceController
    private lateinit var tts: TtsEngine
    private val testTextInput = TestTextInput()
    private var page: Page? = null
    private var transcript: ChatTranscript? = null
    private var savedChatOffset: Int? = null
    private var displayedSessionId: String? = null
    private var foreground = false
    private var permissionRequested = false
    private var permissionRequestInFlight = false
    private var voiceTurnActive = false
    private var voiceState = VoiceState.STOPPED
    private var voiceRestart: Job? = null
    private var consecutiveVoiceErrors = 0
    private var activeSpeech: Any? = null
    private var lastTtsError: TtsError? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionRequestInFlight = false
            syncAudio()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        screenContainer = findViewById(R.id.screen_container)
        session = ViewModelProvider(this)[KioskSessionViewModel::class.java]
        voice = AndroidSpeechRecognizerController(this, ::onVoiceEvent)
        tts = AndroidTtsEngine(this)
        savedChatOffset = savedInstanceState?.getInt("chatOffset", -1)?.takeIf { it >= 0 }
        permissionRequested = savedInstanceState?.getBoolean("voicePermissionRequested") == true
        permissionRequestInFlight = savedInstanceState?.getBoolean("voicePermissionPending") == true
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        findViewById<View>(R.id.nav_start).setOnClickListener { session.goHome() }
        findViewById<View>(R.id.nav_conversation).setOnClickListener {
            when (session.conversation.stage) {
                OrderStage.HOME -> session.startSession()
                OrderStage.PAYMENT -> session.returnFromPayment()
                else -> Unit
            }
        }
        findViewById<View>(R.id.nav_payment).setOnClickListener {
            if (page != Page.PAYMENT) Toast.makeText(this, "대화창에서 계산을 진행해 주세요.", Toast.LENGTH_SHORT).show()
        }
        render()
        lifecycleScope.launch {
            session.updates.collect { render() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("chatOffset", transcript?.takeUnless { it.isNearLatest() }?.offset ?: savedChatOffset ?: -1)
        outState.putBoolean("voicePermissionRequested", page == Page.CONVERSATION && permissionRequested)
        outState.putBoolean("voicePermissionPending", permissionRequestInFlight)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        session.captureSpokenResponses(true)
        render()
    }

    override fun onPause() {
        foreground = false
        session.captureSpokenResponses(false)
        stopAudio()
        super.onPause()
    }

    override fun onDestroy() {
        testTextInput.detach()
        voice.release()
        tts.release()
        super.onDestroy()
    }

    private fun render() {
        val conversation = session.conversation
        val destination = when (conversation.stage) {
            OrderStage.HOME -> Page.START
            OrderStage.PAYMENT -> Page.PAYMENT
            else -> Page.CONVERSATION
        }
        if (page != destination || displayedSessionId != conversation.currentSessionId) {
            showPage(destination)
        }
        when (destination) {
            Page.START -> Unit
            Page.CONVERSATION -> {
                transcript?.render(conversation.messages, conversation.pending, savedChatOffset)
                savedChatOffset = null
            }
            Page.PAYMENT -> renderPayment(screenContainer.getChildAt(0))
        }
        syncAudio()
    }

    private fun showPage(destination: Page) {
        stopAudio()
        if (page != null) permissionRequested = false
        savedChatOffset = if (destination == Page.START || destination == page) null else
            transcript?.takeUnless { it.isNearLatest() }?.offset ?: savedChatOffset
        transcript = null
        testTextInput.detach()
        page = destination
        displayedSessionId = session.conversation.currentSessionId
        listOf(R.id.nav_start, R.id.nav_conversation, R.id.nav_payment).forEachIndexed { index, id ->
            findViewById<TextView>(id).apply {
                val selected = index == destination.ordinal
                setTextColor(if (selected) 0xFF514BCB.toInt() else 0xFF424242.toInt())
                setBackgroundResource(if (selected) R.drawable.bg_nav_selected else android.R.color.transparent)
            }
        }
        val layout = when (destination) {
            Page.START -> R.layout.screen_start
            Page.CONVERSATION -> R.layout.conversation_ui
            Page.PAYMENT -> R.layout.activity_payment_complete
        }
        val screen = layoutInflater.inflate(layout, screenContainer, false)
        screenContainer.removeAllViews()
        screenContainer.addView(screen)
        when (destination) {
            Page.START -> screen.findViewById<View>(R.id.btn_start_order).setOnClickListener { session.startSession() }
            Page.CONVERSATION -> {
                screen.findViewById<View>(R.id.home_icon).setOnClickListener { session.goHome() }
                transcript = ChatTranscript(screen, ::submitOrder)
                testTextInput.attach(this, ::submitOrder)
                screen.findViewById<View>(R.id.voice_retry).setOnClickListener {
                    consecutiveVoiceErrors = 0
                    permissionRequested = false
                    showVoiceState(VoiceState.READY)
                    syncAudio()
                }
            }
            Page.PAYMENT -> Unit
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.main))
    }

    private fun isCurrentScreen(destination: Page): Boolean {
        val conversation = session.conversation
        return foreground && page == destination && displayedSessionId == conversation.currentSessionId && when (destination) {
            Page.START -> conversation.stage == OrderStage.HOME
            Page.CONVERSATION -> conversation.stage != OrderStage.HOME && conversation.stage != OrderStage.PAYMENT
            Page.PAYMENT -> conversation.stage == OrderStage.PAYMENT
        }
    }

    private fun syncAudio() {
        val inConversation = isCurrentScreen(Page.CONVERSATION)
        if (!inConversation && !isCurrentScreen(Page.PAYMENT)) return
        if (activeSpeech != null) return
        if (session.isProcessingInput) {
            cancelVoiceTurn()
            showVoiceState(VoiceState.PROCESSING)
            return
        }
        val response = session.takeSpokenResponse(forPayment = !inConversation)
        if (response != null) {
            speakResponse(response, if (inConversation) Page.CONVERSATION else Page.PAYMENT)
        } else if (inConversation) {
            syncVoiceInput()
        }
    }

    private fun speakResponse(text: String, destination: Page) {
        cancelVoiceTurn()
        val speech = Any()
        activeSpeech = speech
        showVoiceState(if (tts.isInitializing) VoiceState.INITIALIZING_TTS else VoiceState.SPEAKING)
        tts.speak(text) { event ->
            if (activeSpeech === speech && isCurrentScreen(destination)) {
                when (event) {
                    TtsPlaybackEvent.Started -> showVoiceState(VoiceState.SPEAKING)
                    TtsPlaybackEvent.Completed, TtsPlaybackEvent.Stopped -> {
                        lastTtsError = null
                        finishSpeech()
                    }
                    is TtsPlaybackEvent.Error -> {
                        if (lastTtsError != event.reason) {
                            val message = when (event.reason) {
                                TtsError.NO_ENGINE -> R.string.tts_no_engine
                                TtsError.KOREAN_UNAVAILABLE -> R.string.tts_korean_unavailable
                                TtsError.INITIALIZATION, TtsError.PLAYBACK -> R.string.tts_error
                            }
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            lastTtsError = event.reason
                        }
                        finishSpeech()
                    }
                }
            }
        }
    }

    private fun finishSpeech() {
        activeSpeech = null
        showVoiceState(if (page == Page.CONVERSATION) VoiceState.READY else VoiceState.STOPPED)
        render()
    }

    private fun syncVoiceInput() {
        if (voiceTurnActive || voiceRestart != null || permissionRequestInFlight) return
        if (voiceState == VoiceState.PROCESSING) {
            showVoiceState(VoiceState.READY)
            restartVoiceAfter(400L)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (permissionRequested) {
                showVoiceState(VoiceState.ERROR, R.string.voice_permission_required)
            } else {
                permissionRequested = true
                permissionRequestInFlight = true
                showVoiceState(VoiceState.READY)
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        if (voiceState == VoiceState.ERROR) return
        showVoiceState(VoiceState.READY)
        voiceTurnActive = true
        voice.startListening()
    }

    private fun onVoiceEvent(event: VoiceRecognitionEvent) {
        if (!voiceTurnActive || !isCurrentScreen(Page.CONVERSATION)) return
        when (event) {
            VoiceRecognitionEvent.Listening -> showVoiceState(VoiceState.LISTENING)
            VoiceRecognitionEvent.Processing -> showVoiceState(VoiceState.PROCESSING)
            is VoiceRecognitionEvent.Result -> {
                voiceTurnActive = false
                if (submitOrder(event.text)) {
                    transcript?.followAcceptedVoiceInput()
                } else {
                    syncAudio()
                }
            }
            is VoiceRecognitionEvent.Error -> {
                voiceTurnActive = false
                if (event.reason.canRetry && consecutiveVoiceErrors < 3) {
                    restartVoiceAfter(1_000L shl consecutiveVoiceErrors)
                    consecutiveVoiceErrors++
                }
                val message = when (event.reason) {
                    VoiceRecognitionError.NO_SPEECH -> R.string.voice_no_speech
                    VoiceRecognitionError.PERMISSION -> R.string.voice_permission_required
                    VoiceRecognitionError.UNAVAILABLE -> R.string.voice_unavailable
                    VoiceRecognitionError.TEMPORARY, VoiceRecognitionError.SERVICE -> R.string.voice_error
                }
                showVoiceState(VoiceState.ERROR, message)
            }
        }
    }

    private fun submitOrder(text: String): Boolean {
        if (text.isBlank() || !isCurrentScreen(Page.CONVERSATION) || session.isProcessingInput ||
            activeSpeech != null || session.hasSpokenResponses
        ) return false
        cancelVoiceTurn()
        consecutiveVoiceErrors = 0
        showVoiceState(VoiceState.PROCESSING)
        return session.submit(text)
    }

    private fun restartVoiceAfter(delayMillis: Long) {
        voiceRestart = lifecycleScope.launch {
            delay(delayMillis)
            voiceRestart = null
            if (!isCurrentScreen(Page.CONVERSATION)) return@launch
            showVoiceState(VoiceState.READY)
            syncAudio()
        }
    }

    private fun cancelVoiceTurn() {
        voiceTurnActive = false
        voiceRestart?.cancel()
        voiceRestart = null
        voice.stopListening()
    }

    private fun stopAudio() {
        cancelVoiceTurn()
        activeSpeech = null
        tts.stop()
        consecutiveVoiceErrors = 0
        showVoiceState(VoiceState.STOPPED)
    }

    private fun showVoiceState(state: VoiceState, @StringRes message: Int = state.message) {
        voiceState = state
        if (page != Page.CONVERSATION) return
        screenContainer.findViewById<TextView>(R.id.voice_status)?.apply {
            val label = getString(message)
            if (text != label) text = label
        }
        screenContainer.findViewById<View>(R.id.voice_retry)?.isVisible = state == VoiceState.ERROR && voiceRestart == null
    }

    private fun renderPayment(screen: View) {
        val conversation = session.conversation
        val state = conversation.paymentState
        val success = state == PaymentState.PAYMENT_SUCCESS
        val processing = state == PaymentState.PROCESSING_PAYMENT
        screen.findViewById<View>(R.id.ivCheck).isVisible = success
        screen.findViewById<View>(R.id.cardOrderComplete).isVisible = success
        screen.findViewById<TextView>(R.id.tvOrderNumber).text = conversation.orderNumber.orEmpty()
        screen.findViewById<TextView>(R.id.payment_status).apply {
            text = when (state) {
                PaymentState.WAITING_PAYMENT -> "개발용 테스트 결제 · 실제 결제되지 않습니다"
                PaymentState.PROCESSING_PAYMENT -> "테스트 결제 처리 중"
                PaymentState.PAYMENT_SUCCESS -> "테스트 결제 성공"
                PaymentState.PAYMENT_FAILED -> "테스트 결제 실패 · 다시 시도하거나 장바구니로 돌아가 주세요"
            }
            isVisible = !success
        }
        screen.findViewById<TextView>(R.id.payment_guide_text).text =
            if (success) "테스트 주문이 완료되었습니다" else "카드를 결제기에\n넣어주세요"
        screen.findViewById<View>(R.id.payment_test_controls).isVisible = !success && !processing
        screen.findViewById<View>(R.id.btn_payment_back).isVisible = !success && !processing
        screen.findViewById<View>(R.id.btnReset).isVisible = success
        screen.findViewById<View>(R.id.btnHome).setOnClickListener {
            if (success) session.goHome() else session.returnFromPayment()
        }
        screen.findViewById<View>(R.id.btnReset).setOnClickListener { session.goHome() }
        screen.findViewById<View>(R.id.btn_payment_back).setOnClickListener { session.returnFromPayment() }
        screen.findViewById<View>(R.id.btn_payment_success).setOnClickListener { session.runPaymentTest(true) }
        screen.findViewById<View>(R.id.btn_payment_failure).setOnClickListener { session.runPaymentTest(false) }
    }
}
