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
import androidx.appcompat.app.AlertDialog
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
        PREPARING(R.string.voice_ready), LISTENING(R.string.voice_listening),
        PROCESSING(R.string.voice_processing), INITIALIZING_TTS(R.string.voice_initializing_tts),
        SPEAKING(R.string.voice_speaking), ERROR(R.string.voice_error), STOPPED(R.string.voice_stopped)
    }

    private lateinit var screenContainer: FrameLayout
    private lateinit var session: KioskSessionViewModel

    private var voice: VoiceController? = null
    private var tts: AndroidTtsEngine? = null
    private var genieTextInput: GenieTextInput? = null
    private var returningFromVoiceSettings = false
    private val testTextInput = TestTextInput()
    private var demoOpen = false
    private var page: Page? = null
    private var transcript: ChatTranscript? = null
    private var savedChatOffset: Int? = null
    private var savedChatMessageCount = 0
    private var displayedSessionId: String? = null
    private var foreground = false
    private var permissionRequested = false
    private var permissionRequestInFlight = false
    private val voiceTurn = VoiceTurn()
    private var voicePreparation: Job? = null
    private var voiceState = VoiceState.STOPPED
    private var voiceRestart: Job? = null
    private var consecutiveVoiceErrors = 0
    private var activeSpeech: Long? = null
    private var speechSequence = 0L
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
        if (!session.usesGenieTextInput) tts = AndroidTtsEngine(this)
        savedChatOffset = savedInstanceState?.getInt("chatOffset", -1)?.takeIf { it >= 0 }
        savedChatMessageCount = savedInstanceState?.getInt("chatMessageCount") ?: 0
        permissionRequested = savedInstanceState?.getBoolean("voicePermissionRequested") == true
        permissionRequestInFlight = savedInstanceState?.getBoolean("voicePermissionPending") == true
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val system = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            val padding = insets.getInsets(if (session.usesGenieTextInput) system or WindowInsetsCompat.Type.ime() else system)
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
        session.genie?.let { genie ->
            lifecycleScope.launch { genie.model.collect { renderGenieInput() } }
            lifecycleScope.launch { genie.turn.collect { renderGenieInput() } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("chatOffset", transcript?.takeUnless { it.isNearLatest() }?.offset ?: savedChatOffset ?: -1)
        outState.putInt("chatMessageCount", transcript?.messageCount ?: savedChatMessageCount)
        outState.putBoolean("voicePermissionRequested", page == Page.CONVERSATION && permissionRequested)
        outState.putBoolean("voicePermissionPending", permissionRequestInFlight)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (returningFromVoiceSettings) {
            returningFromVoiceSettings = false
            tts?.reload()
            lastTtsError = null
        } else {
            tts?.refreshIfUnavailable()
        }
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
        releaseVoice()
        tts?.release()
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
                val messages = session.transcriptMessages
                transcript?.render(
                    messages, conversation.pending,
                    savedChatOffset.takeIf { savedChatMessageCount == messages.size },
                    actionMessageIndex = messages.lastIndex - if (session.hasTrailingTranscriptNotice) 1 else 0
                )
                renderGenieInput()
                savedChatOffset = null
            }
            Page.PAYMENT -> renderPayment(screenContainer.getChildAt(0))
        }
        syncAudio()
    }

    private fun showPage(destination: Page) {
        stopAudio()
        if (page != null) permissionRequested = false
        if (page != null) savedChatOffset = null
        transcript = null
        genieTextInput = null
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
                val home = screen.findViewById<View>(R.id.home_icon)
                home.setOnClickListener { session.goHome() }
                transcript = ChatTranscript(screen, submit = ::submitOrder)
                if (session.usesGenieTextInput) {
                    screen.findViewById<View>(R.id.voice_footer).isVisible = false
                    genieTextInput = GenieTextInput(screen, ::submitOrder)
                }
                testTextInput.attach(this, home, screen.findViewById(R.id.genie_demo), ::submitOrder, session.genie) { open ->
                    demoOpen = open
                    renderGenieInput()
                    if (open) stopAudio() else syncAudio()
                }
                screen.findViewById<View>(R.id.voice_retry).setOnClickListener {
                    consecutiveVoiceErrors = 0
                    permissionRequested = false
                    showVoiceState(VoiceState.PREPARING)
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
        if (session.usesGenieTextInput || demoOpen) return
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
        val tts = tts ?: return
        cancelVoiceTurn()
        val speech = ++speechSequence
        activeSpeech = speech
        showVoiceState(if (tts.isInitializing) VoiceState.INITIALIZING_TTS else VoiceState.SPEAKING)
        tts.speak(text) { event ->
            if (activeSpeech == speech && isCurrentScreen(destination)) {
                when (event) {
                    TtsPlaybackEvent.Started -> {
                        showVoiceState(VoiceState.SPEAKING)
                        trace(speech, "TTS_STARTED")
                    }
                    TtsPlaybackEvent.Completed, TtsPlaybackEvent.Stopped -> {
                        trace(speech, if (event == TtsPlaybackEvent.Completed) "TTS_COMPLETED" else "TTS_STOPPED")
                        lastTtsError = null
                        finishSpeech()
                    }
                    is TtsPlaybackEvent.Error -> {
                        trace(speech, "TTS_ERROR", "reason=${event.reason}")
                        if (lastTtsError != event.reason) {
                            val message = when (event.reason) {
                                TtsError.NO_ENGINE -> R.string.tts_no_engine
                                TtsError.KOREAN_UNAVAILABLE -> R.string.tts_korean_unavailable
                                TtsError.INITIALIZATION, TtsError.PLAYBACK -> R.string.tts_error
                            }
                            if (event.reason == TtsError.NO_ENGINE || event.reason == TtsError.KOREAN_UNAVAILABLE) {
                                AlertDialog.Builder(this)
                                    .setMessage(R.string.tts_install_korean_prompt)
                                    .setPositiveButton(R.string.tts_install_korean) { _, _ ->
                                        returningFromVoiceSettings = true
                                        openKoreanVoiceSettings(this)
                                    }
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show()
                            } else {
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            }
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
        if (page != Page.CONVERSATION) showVoiceState(VoiceState.STOPPED)
        syncAudio()
    }

    private fun syncVoiceInput() {
        if (voiceTurn.id != null || voiceRestart != null || permissionRequestInFlight) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (permissionRequested) {
                showVoiceState(VoiceState.ERROR, R.string.voice_permission_required)
            } else {
                permissionRequested = true
                permissionRequestInFlight = true
                showVoiceState(VoiceState.PREPARING)
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        if (voiceState == VoiceState.ERROR) return
        showVoiceState(VoiceState.PREPARING)
        val turn = voiceTurn.start()
        trace(turn, "STT_PREPARING")

        val controller = voice ?: createVoiceController(this, displayedSessionId, onEvent = ::onVoiceEvent).also { voice = it }
        watchVoicePreparation(turn, VoiceTurn.Phase.QUEUED)
        trace(turn, "STT_START_REQUESTED")
        controller.startListening(turn)
    }

    private fun watchVoicePreparation(turn: Long, phase: VoiceTurn.Phase) {
        voicePreparation?.cancel()
        voicePreparation = lifecycleScope.launch {
            delay(12_000L)
            if (voiceTurn.id != turn || voiceTurn.phase != phase) return@launch
            val queued = phase == VoiceTurn.Phase.QUEUED
            trace(turn, if (queued) "STT_CAPTURE_QUEUE_TIMEOUT" else "STT_PREPARATION_TIMEOUT")
            val reason = if (queued) VoiceRecognitionError.MIC_CLEANUP_TIMEOUT else VoiceRecognitionError.PREPARATION
            onVoiceEvent(turn, VoiceRecognitionEvent.Error(reason))
        }
    }

    private fun onVoiceEvent(turn: Long, event: VoiceRecognitionEvent) {
        if (voiceTurn.id != turn || !isCurrentScreen(Page.CONVERSATION)) return
        when (event) {
            VoiceRecognitionEvent.PreparationStarted -> if (voiceTurn.preparing(turn)) {
                trace(turn, "STT_PREPARATION_STARTED")
                watchVoicePreparation(turn, VoiceTurn.Phase.PREPARING)
            }
            VoiceRecognitionEvent.AwaitingSpeech -> if (voiceTurn.phase == VoiceTurn.Phase.PREPARING) {
                voicePreparation?.cancel()
                voicePreparation = null
                showVoiceState(VoiceState.PREPARING, if (AndroidRuntimeEnvironment.isEmulator)
                    R.string.voice_listening else R.string.voice_usb_awaiting_speech)
                trace(turn, "STT_AWAITING_SPEECH")
            }
            VoiceRecognitionEvent.Listening -> if (voiceTurn.ready(turn)) {
                voicePreparation?.cancel()
                voicePreparation = null
                showVoiceState(VoiceState.LISTENING)
                trace(turn, "STT_READY")
            }
            VoiceRecognitionEvent.Processing -> if (voiceTurn.processing(turn)) showVoiceState(VoiceState.PROCESSING)
            is VoiceRecognitionEvent.Result -> {
                if (voiceTurn.phase == VoiceTurn.Phase.QUEUED || voiceTurn.phase == VoiceTurn.Phase.PREPARING) {
                    onVoiceEvent(turn, VoiceRecognitionEvent.Error(VoiceRecognitionError.PREPARATION))
                    return
                }
                trace(turn, "STT_RESULT")
                cancelVoiceTurn()
                if (!submitOrder(event.text)) syncAudio()
            }
            is VoiceRecognitionEvent.Error -> {
                val preparing = voiceTurn.phase == VoiceTurn.Phase.PREPARING
                trace(turn, "STT_ERROR", "reason=${event.reason}")
                cancelVoiceTurn()
                if (event.reason.canRetry && consecutiveVoiceErrors < 3) {
                    restartVoiceAfter(turn, if (preparing) 200L shl consecutiveVoiceErrors else 1_000L shl consecutiveVoiceErrors)
                    consecutiveVoiceErrors++
                    if (preparing) {
                        showVoiceState(VoiceState.PREPARING)
                        return
                    }
                }
                val message = when (event.reason) {
                    VoiceRecognitionError.NO_SPEECH, VoiceRecognitionError.NO_RESULT -> R.string.voice_no_speech
                    VoiceRecognitionError.PERMISSION -> R.string.voice_permission_required
                    VoiceRecognitionError.UNAVAILABLE -> R.string.voice_unavailable
                    VoiceRecognitionError.PREPARATION -> R.string.voice_preparation_failed
                    VoiceRecognitionError.MIC_NO_DATA -> if (AndroidRuntimeEnvironment.isEmulator)
                        R.string.voice_emulator_mic_no_data else R.string.voice_mic_no_data
                    VoiceRecognitionError.MIC_ZERO_SIGNAL -> if (AndroidRuntimeEnvironment.isEmulator)
                        R.string.voice_emulator_mic_zero_signal else R.string.voice_mic_zero_signal
                    VoiceRecognitionError.MIC_CLEANUP_TIMEOUT -> R.string.voice_mic_cleanup_timeout
                    else -> R.string.voice_error
                }
                showVoiceState(VoiceState.ERROR, message)
                if (message == R.string.voice_no_speech) session.showRecognitionRetry(getString(message))
            }
        }
    }

    private fun submitOrder(text: String): Boolean {
        if (demoOpen || text.isBlank() || !isCurrentScreen(Page.CONVERSATION) || session.isProcessingInput ||
            activeSpeech != null || session.hasSpokenResponses
        ) return false

        if (session.usesGenieTextInput) {
            if (session.genie == null) return false
            return session.submit(text).also { if (it) renderGenieInput() }
        }

        cancelVoiceTurn()
        consecutiveVoiceErrors = 0
        showVoiceState(VoiceState.PROCESSING)
        return session.submit(text)
    }

    private fun restartVoiceAfter(turn: Long, delayMillis: Long) {
        voiceRestart = lifecycleScope.launch {
            delay(delayMillis)
            voiceRestart = null
            if (!isCurrentScreen(Page.CONVERSATION)) return@launch
            trace(turn, "STT_RESTART")
            showVoiceState(VoiceState.PREPARING)
            syncAudio()
        }
    }

    private fun cancelVoiceTurn() {
        val turn = voiceTurn.id
        voiceTurn.stop()
        voicePreparation?.cancel()
        voicePreparation = null
        voiceRestart?.cancel()
        voiceRestart = null

        voice?.stopListening()
        turn?.let { trace(it, "STT_STOPPED") }
    }

    private fun trace(turn: Long, event: String, detail: String = "") =
        audioLog(voiceEngineName, displayedSessionId, turn, voiceState.name, event, detail)

    private fun releaseVoice() {
        cancelVoiceTurn()
        voice?.release()
        voice = null
    }

    private fun stopAudio() {
        releaseVoice()
        activeSpeech = null
        tts?.stop()
        consecutiveVoiceErrors = 0
        showVoiceState(VoiceState.STOPPED)
    }

    private fun showVoiceState(state: VoiceState, @StringRes message: Int = state.message) {
        if (session.usesGenieTextInput) return
        voiceState = state
        if (page != Page.CONVERSATION) return
        screenContainer.findViewById<TextView>(R.id.voice_status)?.apply {
            val label = getString(message)
            if (text != label) text = label
        }
        screenContainer.findViewById<View>(R.id.voice_retry)?.isVisible = state == VoiceState.ERROR && voiceRestart == null
    }

    private fun renderGenieInput() {
        val genie = session.genie
        genieTextInput?.render(
            model = genie?.model?.value,
            turn = genie?.turn?.value?.takeIf { it.sessionId == session.conversation.currentSessionId },
            busy = session.isProcessingInput || demoOpen
        )
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
