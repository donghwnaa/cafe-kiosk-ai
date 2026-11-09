package com.example.cafe_kiosk_ai

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** XML 화면을 교체해 표시하는 UI 전용 호스트. 통신과 주문 처리는 연결하지 않습니다. */
class MainActivity : AppCompatActivity() {
    private lateinit var screenContainer: FrameLayout
    private val navigationIds = listOf(
        R.id.nav_start,
        R.id.nav_conversation,
        R.id.nav_product,
        R.id.nav_order,
        R.id.nav_payment,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        screenContainer = findViewById(R.id.screen_container)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        navigationIds.forEachIndexed { index, id ->
            findViewById<View>(id).setOnClickListener { showScreen(index) }
        }
        showScreen(0)
    }

    private fun showScreen(index: Int) {
        navigationIds.forEachIndexed { navIndex, id ->
            findViewById<TextView>(id).apply {
                setTextColor(if (navIndex == index) 0xFF514BCB.toInt() else 0xFF424242.toInt())
                setBackgroundResource(if (navIndex == index) R.drawable.bg_nav_selected else android.R.color.transparent)
            }
        }

        val layout = when (index) {
            0 -> R.layout.screen_start
            1 -> R.layout.conversation_ui
            2 -> R.layout.screen_product_confirm
            3 -> R.layout.screen_order_confirm
            else -> R.layout.activity_payment_complete
        }
        screenContainer.removeAllViews()
        val screen = layoutInflater.inflate(layout, screenContainer, false)
        screenContainer.addView(screen)

        when (index) {
            0 -> screen.findViewById<View>(R.id.btn_start_order).setOnClickListener { showScreen(1) }
            1 -> screen.findViewById<View>(R.id.home_icon).setOnClickListener { showScreen(0) }
            2 -> {
                screen.findViewById<View>(R.id.product_home).setOnClickListener { showScreen(0) }
                screen.findViewById<View>(R.id.btn_product_confirm).setOnClickListener { showScreen(3) }
            }
            3 -> {
                screen.findViewById<View>(R.id.order_home).setOnClickListener { showScreen(0) }
                screen.findViewById<View>(R.id.btn_order_pay).setOnClickListener { showScreen(4) }
                val totalMessage = SpannableString("총 결제 금액 : 9,000원입니다.\n확인 부탁드려요")
                val amountStart = totalMessage.indexOf("9,000")
                totalMessage.setSpan(
                    ForegroundColorSpan(0xFFFF0C0C.toInt()),
                    amountStart,
                    amountStart + "9,000".length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                screen.findViewById<TextView>(R.id.order_total_message).text = totalMessage
            }
            else -> {
                screen.findViewById<TextView>(R.id.tvOrderNumber).text = "주문번호 001"
                screen.findViewById<View>(R.id.btnHome).setOnClickListener { showScreen(0) }
                screen.findViewById<View>(R.id.btnReset).setOnClickListener { showScreen(0) }
            }
        }
    }
}
