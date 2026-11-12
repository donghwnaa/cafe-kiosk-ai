package com.example.cafe_kiosk_ai

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle

internal class TestTextInput {
    private var unregisterInputReceiver: (() -> Unit)? = null
    private var koreanHardwareInput = false

    fun attach(
        activity: AppCompatActivity,
        slot: FrameLayout,
        pending: () -> PendingQuestion,
        screen: () -> Int,
        submit: (String) -> Boolean
    ) {
        activity.layoutInflater.inflate(R.layout.debug_order_input, slot, true)
        slot.isVisible = true
        val input = slot.findViewById<EditText>(R.id.debug_input)
        val hangul = HangulKeyInput(input)
        val koreanToggle = slot.findViewById<ToggleButton>(R.id.debug_korean_keys)
        koreanToggle.isChecked = koreanHardwareInput
        input.showSoftInputOnFocus = !koreanHardwareInput
        input.setOnKeyListener { _, keyCode, event ->
            koreanHardwareInput && hangul.handleKey(keyCode, event)
        }
        koreanToggle.setOnCheckedChangeListener { _, enabled ->
            koreanHardwareInput = enabled
            hangul.reset()
            input.showSoftInputOnFocus = !enabled
            input.requestFocus()
            val ime = ViewCompat.getWindowInsetsController(input)
            if (enabled) ime?.hide(WindowInsetsCompat.Type.ime()) else ime?.show(WindowInsetsCompat.Type.ime())
        }

        fun send() {
            val text = input.text.toString().trim()
            if (text.isBlank()) return
            if (submit(text)) input.text.clear()
        }

        slot.findViewById<Button>(R.id.debug_send).setOnClickListener { send() }
        input.setOnEditorActionListener { _, actionId, event ->
            when {
                event == null && actionId == EditorInfo.IME_ACTION_SEND -> {
                    send()
                    true
                }
                event?.keyCode == KeyEvent.KEYCODE_ENTER || event?.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) send()
                    true
                }
                else -> false
            }
        }
        slot.findViewById<Button>(R.id.debug_examples).setOnClickListener {
            val examples = examples(pending(), screen())
            val next = (examples.indexOf(input.text.toString()) + 1) % examples.size
            replaceText(input, examples[next])
        }

        // ADB shell can deliver the Windows IME's completed text to this debug-only input.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                val encoded = intent.getStringExtra("text_b64") ?: return
                val bytes = try {
                    Base64.decode(encoded, Base64.DEFAULT)
                } catch (_: IllegalArgumentException) {
                    return
                }
                replaceText(input, bytes.toString(Charsets.UTF_8))
                if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
            }
        }
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter("com.example.cafe_kiosk_ai.DEBUG_TEXT_INPUT"),
            Manifest.permission.DUMP,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
        unregisterInputReceiver = { activity.unregisterReceiver(receiver) }
    }

    fun detach() {
        unregisterInputReceiver?.invoke()
        unregisterInputReceiver = null
    }

    private fun replaceText(input: EditText, text: String) {
        input.setText(text)
        input.setSelection(input.length())
    }

    private fun examples(pending: PendingQuestion, screen: Int): Array<String> = when (pending) {
        PendingQuestion.TEMPERATURE -> arrayOf("따뜻하게 주세요", "차갑게 주세요")
        PendingQuestion.SIZE -> arrayOf("보통으로 주세요", "큰 걸로 주세요")
        PendingQuestion.ITEM_CONFIRMATION -> arrayOf("맞아요", "아니요", "한 잔으로 바꿔줘", "취소할게요")
        PendingQuestion.CANCEL_ALL -> arrayOf("네", "아니요")
        PendingQuestion.CART_TARGET -> arrayOf("차갑게", "따뜻하게", "보통 크기", "큰 사이즈")
        else -> if (screen == 3) {
            arrayOf("아아 하나 더 추가해줘", "아메리카노 하나 빼줘", "전부 취소할게요", "계산할게요")
        } else {
            arrayOf("아아 두 잔 주세요", "아메리카노 한 잔 주세요", "유자차 한 잔 주세요", "주문한 거 보여줘")
        }
    }
}
