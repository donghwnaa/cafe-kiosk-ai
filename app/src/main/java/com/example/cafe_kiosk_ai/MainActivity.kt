package com.example.cafe_kiosk_ai

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private enum class Page { START, CONVERSATION, PAYMENT }
    private lateinit var screenContainer: FrameLayout
    private lateinit var session: KioskSessionViewModel
    private val testTextInput = TestTextInput()
    private var page: Page? = null
    private var transcript: ChatTranscript? = null
    private var savedChatOffset: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        screenContainer = findViewById(R.id.screen_container)
        session = ViewModelProvider(this)[KioskSessionViewModel::class.java]
        savedChatOffset = savedInstanceState?.getInt("chatOffset", -1)?.takeIf { it >= 0 }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val showArtwork = !keyboardVisible && resources.configuration.screenHeightDp >= 520
            val artwork = screenContainer.findViewById<View>(R.id.voice_artwork)
            if (artwork != null && artwork.isVisible != showArtwork) {
                val current = transcript
                if (current?.isNearLatest() == true) view.doOnNextLayout { current.scrollToLatest() }
                artwork.isVisible = showArtwork
            }
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
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        testTextInput.detach()
        super.onDestroy()
    }

    private fun render() {
        val conversation = session.conversation
        val destination = when (conversation.stage) {
            OrderStage.HOME -> Page.START
            OrderStage.PAYMENT -> Page.PAYMENT
            else -> Page.CONVERSATION
        }
        if (page != destination) showPage(destination)
        when (destination) {
            Page.START -> Unit
            Page.CONVERSATION -> {
                transcript?.render(conversation.messages, conversation.pending, savedChatOffset)
                savedChatOffset = null
            }
            Page.PAYMENT -> renderPayment(screenContainer.getChildAt(0))
        }
    }

    private fun showPage(destination: Page) {
        savedChatOffset = if (destination == Page.START) null else
            transcript?.takeUnless { it.isNearLatest() }?.offset ?: savedChatOffset
        transcript = null
        testTextInput.detach()
        page = destination
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
                transcript = ChatTranscript(screen, session::submit)
                val slot = screen.findViewById<FrameLayout>(R.id.test_input_slot)
                testTextInput.attach(this, slot, { session.conversation.pending }, { session.conversation.stage.screen }, session::submit)
            }
            Page.PAYMENT -> Unit
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.main))
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
