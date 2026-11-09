package com.example.cafe_kiosk_ai

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.cafe_kiosk_ai.screens.JuhyukReviewScreen
import com.example.cafe_kiosk_ai.screens.StartScreen
import com.example.cafe_kiosk_ai.ui.page4.OrderConfirmScreen
import com.example.cafe_kiosk_ai.ui.page4.Page4CartItem
import com.example.cafe_kiosk_ai.ui.page4.Page4ChatMessage
import com.example.cafe_kiosk_ai.ui.page4.Page4VoiceState

/** UI-only screen host. Each branch's design is mounted here without API or order processing. */
class MainActivity : AppCompatActivity() {
    private lateinit var container: FrameLayout
    private lateinit var navigation: LinearLayout
    private val screenTitles = listOf("시작", "대화", "상품 확인", "주문 확인", "결제 완료")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.screen_container)
        navigation = findViewById(R.id.screen_navigation)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        buildNavigation()
        showScreen(0)
    }

    private fun buildNavigation() {
        screenTitles.forEachIndexed { index, title ->
            val tab = TextView(this).apply {
                text = title
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(16, 0, 16, 0)
                setOnClickListener { showScreen(index) }
            }
            navigation.addView(tab, LinearLayout.LayoutParams(-2, -1))
        }
    }

    private fun showScreen(index: Int) {
        for (i in 0 until navigation.childCount) {
            (navigation.getChildAt(i) as TextView).apply {
                setTextColor(if (i == index) 0xFF514BCB.toInt() else 0xFF424242.toInt())
                setBackgroundColor(if (i == index) 0xFFE7E5FF.toInt() else 0xFFF6F2F2.toInt())
            }
        }
        container.removeAllViews()
        when (index) {
            0 -> showCompose { StartScreen(onStartClick = { showScreen(1) }) }
            1 -> {
                val view = layoutInflater.inflate(R.layout.conversation_ui, container, false)
                view.findViewById<View>(R.id.home_icon)?.setOnClickListener { showScreen(0) }
                container.addView(view)
            }
            2 -> showCompose {
                JuhyukReviewScreen(onConfirm = { showScreen(3) }, onHome = { showScreen(0) })
            }
            3 -> showCompose {
                OrderConfirmScreen(
                    items = listOf(Page4CartItem("아메리카노", "ICE", 4500, 2, R.drawable.page4_americano, "R")),
                    messages = listOf(Page4ChatMessage("총 결제 금액 : 9,000원입니다.\n확인 부탁드려요", false, "9,000")),
                    voiceState = Page4VoiceState.IDLE,
                    onMicClick = {},
                    onPayClick = { showScreen(4) },
                    onHomeClick = { showScreen(0) },
                )
            }
            else -> {
                val view = layoutInflater.inflate(R.layout.activity_payment_complete, container, false)
                view.findViewById<TextView>(R.id.tvOrderNumber)?.text = "주문번호 001"
                view.findViewById<View>(R.id.btnHome)?.setOnClickListener { showScreen(0) }
                view.findViewById<View>(R.id.btnReset)?.setOnClickListener { showScreen(0) }
                container.addView(view)
            }
        }
    }

    private fun showCompose(content: @androidx.compose.runtime.Composable () -> Unit) {
        val composeView = ComposeView(this).apply {
            setContent { MaterialTheme { content() } }
        }
        container.addView(composeView, FrameLayout.LayoutParams(-1, -1))
    }
}
