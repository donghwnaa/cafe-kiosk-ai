package com.example.cafe_kiosk_ai

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import java.text.NumberFormat
import java.util.Locale

/** Each response owns its card snapshot. The transcript only appends new rows. */
internal class ChatTranscript(screen: View, private val submit: (String) -> Boolean) {
    private val context = screen.context
    private val rows = screen.findViewById<LinearLayout>(R.id.chat_messages)
    private val scroll = screen.findViewById<ScrollView>(R.id.chat_scroll)
    private val currency = NumberFormat.getNumberInstance(Locale.KOREA)
    private var renderedCount = 0
    private var latestCartHeading: TextView? = null
    private var currentActions: LinearLayout? = null
    private var browsedInitialMenu = false

    init {
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            if (renderedCount == 1 && y > 0) browsedInitialMenu = true
        }
    }

    val offset: Int get() = scroll.scrollY
    fun isNearLatest(): Boolean = rows.bottom - (scroll.height + offset) <= dp(32)

    fun render(messages: List<ChatMessage>, pending: PendingQuestion, restoreOffset: Int?) {
        if (renderedCount >= messages.size) return
        val follow = renderedCount == 0 || isNearLatest() || (renderedCount == 1 && !browsedInitialMenu)
        currentActions?.removeAllViews()
        currentActions = null
        val lastIndex = messages.lastIndex
        for (index in renderedCount..lastIndex) {
            append(messages[index], pending.takeIf { index == lastIndex })
        }
        val initialGreeting = renderedCount == 0 && messages.size == 1
        renderedCount = messages.size
        scroll.doOnNextLayout {
            when {
                restoreOffset != null -> scroll.scrollTo(0, restoreOffset)
                initialGreeting -> scroll.scrollTo(0, 0)
                follow -> scrollToLatest()
            }
        }
    }

    fun scrollToLatest() {
        scroll.scrollTo(0, rows.bottom)
    }

    private fun append(message: ChatMessage, activeQuestion: PendingQuestion?) {
        val row = LinearLayout(context).apply {
            gravity = if (message.fromUser) Gravity.END else Gravity.START
            setPadding(0, dp(5), 0, dp(5))
        }
        if (!message.fromUser) {
            val avatar = ImageView(context).apply {
                setImageResource(R.drawable.ai_profile)
                contentDescription = "AI"
            }
            row.addView(avatar, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(6) })
        }
        val content = vertical()
        val bubble = text(message.text, 18f).apply {
            maxWidth = (rows.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels) * 3 / 4
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setBackgroundResource(if (message.fromUser) R.drawable.bg_user_bubble else R.drawable.bg_ai_bubble)
        }
        content.addView(bubble, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val contentParams = if (message.card == null) LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        else LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        row.addView(content, contentParams)
        rows.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        when (val card = message.card) {
            ChatCard.Menu -> content.addView(menuCard())
            is ChatCard.ItemConfirmation -> {
                content.addView(confirmationCard(card))
                if (activeQuestion == PendingQuestion.ITEM_CONFIRMATION) {
                    content.addView(actions("맞아요" to "맞아요", "수정할게요" to "수정할게요"))
                }
            }
            is ChatCard.CartSummary -> {
                content.addView(cartCard(card))
                if (activeQuestion == PendingQuestion.NONE && card.items.isNotEmpty()) {
                    content.addView(actions("메뉴 추가" to "다른 메뉴 추가해줘", "계산할게요" to "계산할게요"))
                }
            }
            null -> Unit
        }
    }

    private fun menuCard(): LinearLayout = card().apply {
        addView(text("메뉴 · 시연용 임시 가격", 20f, bold = true))
        for (menu in MenuCatalog.items) {
            addView(product(menu, menu.name,
                "${menu.temperatures.joinToString(" / ")} · ${menu.sizes.joinToString(" / ")}\n" +
                    "보통 ${money(menu.priceRegular)} · 큰 ${money(menu.priceTall)}"))
        }
    }

    private fun confirmationCard(value: ChatCard.ItemConfirmation): LinearLayout = card().apply {
        addView(text("상품 확인", 20f, bold = true))
        value.items.forEachIndexed { index, item ->
            addView(product(item.menu, "${index + 1}. ${item.menu.name}",
                "${item.temperature} · ${item.size} · ${item.quantity}잔\n${money(item.total)} (임시 가격)"))
        }
        value.removals.forEach { addView(text(it, 18f)) }
    }

    private fun cartCard(value: ChatCard.CartSummary): LinearLayout = card().apply {
        latestCartHeading?.text = "이전 장바구니 기록"
        val title = text("현재 장바구니", 20f, bold = true)
        latestCartHeading = title
        addView(title)
        if (value.items.isEmpty()) addView(text("장바구니가 비어 있습니다.", 18f))
        value.items.forEachIndexed { index, item ->
            val unitPrice = money(item.menu.price(item.size))
            addView(product(item.menu, "${index + 1}. ${item.menu.name}",
                "${item.temperature} · ${item.size} · ${item.quantity}잔\n$unitPrice × ${item.quantity} = ${money(item.total)}"))
        }
        addView(text("총 ${money(value.total)} (임시 가격)", 20f, bold = true))
    }

    private fun product(menu: MenuItem, title: String, details: String): LinearLayout =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            val image = ImageView(context).apply {
                setImageResource(menu.image)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "${menu.name} 이미지"
            }
            addView(image, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(8) })
            val body = vertical().apply {
                addView(text(title, 19f, bold = true))
                addView(text(details, 18f))
            }
            addView(body, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

    private fun actions(vararg options: Pair<String, String>): LinearLayout = LinearLayout(context).apply {
        currentActions = this
        for ((label, phrase) in options) {
            addView(Button(context).apply {
                text = label
                textSize = 18f
                setOnClickListener { submit(phrase) }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    private fun card(): LinearLayout = vertical().apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setBackgroundResource(R.drawable.bg_cart_card)
    }

    private fun vertical() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(Color.BLACK)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun money(value: Int) = "${currency.format(value)}원"
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
