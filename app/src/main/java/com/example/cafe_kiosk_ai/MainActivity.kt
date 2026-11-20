package com.example.cafe_kiosk_ai

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
    private var recordedPlayer: RecordedPromptPlayer? = null
    private var returningFromVoiceSettings = false
    private val testTextInput = TestTextInput()
    private var page: Page? = null
    private var transcript: ChatTranscript? = null
    private var savedChatOffset: Int? = null
    private var displayedSessionId: String? = null
    private var foreground = false
    private var permissionRequested = false
    private var permissionRequestInFlight = false
    private var textMode = false
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
        textMode = savedInstanceState?.getBoolean("textMode") == true
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
        outState.putBoolean("textMode", textMode)
        outState.putInt("chatOffset", transcript?.takeUnless { it.isNearLatest() }?.offset ?: savedChatOffset ?: -1)
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
        session.captureSpokenResponses(!textMode)
        render()
    }

    override fun onPause() {
        foreground = false
        session.captureSpokenResponses(false)
        stopAudio(releaseTts = true)
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::session.isInitialized && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            session.trimIdleModels()
        }
    }

    override fun onDestroy() {
        testTextInput.detach()
        stopAudio(releaseTts = true)
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
                screenContainer.findViewById<View>(R.id.send_text_order).isEnabled = !session.isProcessingInput
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
        if (destination == Page.START) {
            textMode = false
            session.captureSpokenResponses(foreground)
        }
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
            Page.START -> {
                screen.findViewById<View>(R.id.btn_start_order).setOnClickListener { session.startSession() }
                screen.findViewById<View>(R.id.btn_start_text_order).setOnClickListener {
                    textMode = true
                    session.captureSpokenResponses(false)
                    session.startSession()
                }
            }
            Page.CONVERSATION -> {
                screen.findViewById<View>(R.id.home_icon).setOnClickListener { session.goHome() }
                transcript = ChatTranscript(screen, ::submitOrder)
                fun selectInput(keyboard: Boolean) {
                    textMode = keyboard
                    screen.findViewById<View>(R.id.voice_footer).isVisible = !keyboard
                    screen.findViewById<View>(R.id.text_footer).isVisible = keyboard
                    session.captureSpokenResponses(foreground && !keyboard)
                    if (keyboard) {
                        stopAudio()
                    } else {
                        syncAudio()
                    }
                }
                screen.findViewById<View>(R.id.type_order).setOnClickListener { selectInput(true) }
                val editor = screen.findViewById<EditText>(R.id.order_text)
                fun hideKeyboard() {
                    getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(editor.windowToken, 0)
                }
                fun sendText() {
                    if (submitOrder(editor.text.toString().trim())) {
                        editor.text.clear()
                        hideKeyboard()
                    }
                }
                screen.findViewById<View>(R.id.send_text_order).setOnClickListener { sendText() }
                screen.findViewById<View>(R.id.resume_voice).setOnClickListener {
                    hideKeyboard()
                    selectInput(false)
                }
                editor.setOnEditorActionListener { _, action, _ ->
                    if (action != EditorInfo.IME_ACTION_SEND) return@setOnEditorActionListener false
                    sendText()
                    true
                }
                screen.findViewById<View>(R.id.voice_footer).isVisible = !textMode
                screen.findViewById<View>(R.id.text_footer).isVisible = textMode
                testTextInput.attach(this) { text ->
                    if (!textMode) selectInput(true)
                    submitOrder(text)
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
        if (textMode) return
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
        } else if (session.hasSpokenResponses) {
            showVoiceState(VoiceState.INITIALIZING_TTS)
        } else if (inConversation) {
            if (session.canStartVoiceInput) syncVoiceInput() else showVoiceState(VoiceState.PREPARING)
        }
    }

    private fun speakResponse(response: KioskSessionViewModel.SpokenResponse, destination: Page) {
        cancelVoiceTurn()
        val speech = ++speechSequence
        activeSpeech = speech
        val recording = response.recording
        val tracePrefix = if (recording == null) "TTS" else "RECORDED_PROMPT"
        val detail = recording?.let { "prompt=${it.name}" }.orEmpty()
        var playbackStarted = false
        val onEvent: (TtsPlaybackEvent) -> Unit = onEvent@{ event ->
            if (activeSpeech == speech && isCurrentScreen(destination)) {
                when (event) {
                    TtsPlaybackEvent.Started -> {
                        playbackStarted = true
                        showVoiceState(VoiceState.SPEAKING)
                        trace(speech, "${tracePrefix}_STARTED", detail)
                    }
                    TtsPlaybackEvent.Completed, TtsPlaybackEvent.Stopped -> {
                        val terminal = if (event == TtsPlaybackEvent.Completed) "COMPLETED" else "STOPPED"
                        trace(speech, "${tracePrefix}_$terminal", detail)
                        lastTtsError = null
                        finishSpeech()
                    }
                    is TtsPlaybackEvent.Error -> {
                        if (recording != null && !playbackStarted) {
                            trace(speech, "RECORDED_PROMPT_FALLBACK", detail)
                            session.retryWithTts(response)
                            finishSpeech()
                            return@onEvent
                        }
                        trace(speech, "${tracePrefix}_ERROR", "reason=${event.reason} $detail")
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
                        finishSpeech(releaseTts = recording == null)
                    }
                }
            }
        }
        if (recording != null) {
            val player = RecordedPromptPlayer(this).also { recordedPlayer = it }
            showVoiceState(VoiceState.INITIALIZING_TTS)
            trace(speech, "RECORDED_PROMPT_REQUESTED", detail)
            player.play(recording, onEvent)
        } else {
            val engine = tts ?: AndroidTtsEngine(this).also { tts = it }
            showVoiceState(if (engine.isInitializing) VoiceState.INITIALIZING_TTS else VoiceState.SPEAKING)
            engine.speak(response.text, onEvent)
        }
    }

    private fun finishSpeech(releaseTts: Boolean = false) {
        clearSpeech()
        if (releaseTts) releaseTtsEngine()
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
                showVoiceState(VoiceState.PREPARING, R.string.voice_usb_awaiting_speech)
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
                if (submitOrder(event.text)) {
                    transcript?.followAcceptedVoiceInput()
                } else {
                    syncAudio()
                }
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
                    VoiceRecognitionError.MIC_NO_DATA -> R.string.voice_mic_no_data
                    VoiceRecognitionError.MIC_ZERO_SIGNAL -> R.string.voice_mic_zero_signal
                    VoiceRecognitionError.MIC_CLEANUP_TIMEOUT -> R.string.voice_mic_cleanup_timeout
                    else -> R.string.voice_error
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

    private fun clearSpeech() {
        activeSpeech = null
        recordedPlayer?.release()
        recordedPlayer = null
    }

    private fun releaseTtsEngine() {
        val engine = tts
        tts = null
        engine?.release()
    }

    private fun stopAudio(releaseTts: Boolean = false) {
        releaseVoice()
        clearSpeech()
        if (releaseTts) releaseTtsEngine() else tts?.stop()
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
