package com.example.cafe_kiosk_ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.app.Dialog
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.example.cafe_kiosk_ai.llm.GenieLlmEngine

// Genie 비활성화/테스트 환경에서 에러가 나지 않도록 안전한 Fake NLU 엔진 정의 추가
internal class FakeNluEngine : NluEngine {
    override suspend fun analyze(text: String, context: NluContext): NluResult {
        return NluResult(intent = NluIntent.UNKNOWN, ambiguous = "개발/테스트 모드입니다.")
    }
}

internal fun createDevelopmentNlu(): NluEngine = FakeNluEngine()

internal class TestTextInput {
    private var unregisterInputReceiver: (() -> Unit)? = null
    private var dialog: Dialog? = null

    fun attach(
        activity: ComponentActivity, trigger: View, demoButton: View, submit: (String) -> Boolean,
        genie: GenieLlmEngine?, onDemoOpen: (Boolean) -> Unit
    ) {
        if (genie != null) {
            demoButton.visibility = View.VISIBLE
            demoButton.setOnClickListener {
                if (dialog?.isShowing == true) return@setOnClickListener
                onDemoOpen(true)
                dialog = GenieDemoDialog.show(activity, genie) { onDemoOpen(false) }
            }
        }
        trigger.setOnLongClickListener {
            val input = EditText(activity).apply {
                hint = "아이스 아메리카노 두 잔 주세요."
                setSingleLine()
            }
            dialog = AlertDialog.Builder(activity)
                .setTitle(if (genie != null) "Genie 주문 테스트" else "DEBUG 주문 테스트")
                .setView(input)
                .setNegativeButton("닫기", null)
                .setPositiveButton("분석", null)
                .setNeutralButton("Genie LLM 테스트") { _, _ -> dialog = GenieDiagnostics.show(activity, genie) }
                .show().apply {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (submit(input.text.toString())) dismiss()
                        else input.error = "문장을 입력하고 진행 중인 안내가 끝난 뒤 다시 시도해 주세요."
                    }
                }
            true
        }
        // Keep the ADB test entry point behind the existing DUMP permission.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val encoded = intent.getStringExtra("text_b64") ?: return
                val text = try {
                    Base64.decode(encoded, Base64.DEFAULT).decodeToString(throwOnInvalidSequence = true)
                } catch (_: IllegalArgumentException) {
                    return
                } catch (_: java.nio.charset.CharacterCodingException) {
                    return
                }
                if (submit(text) && isOrderedBroadcast) {
                    resultCode = Activity.RESULT_OK
                }
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
        dialog?.dismiss()
        dialog = null
        unregisterInputReceiver?.invoke()
        unregisterInputReceiver = null
    }
}