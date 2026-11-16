package com.example.cafe_kiosk_ai

import android.app.AlertDialog
import android.os.SystemClock
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.cafe_kiosk_ai.llm.GENIE_TAG
import com.example.cafe_kiosk_ai.llm.GenieLlmEngine
import com.example.cafe_kiosk_ai.llm.LlmUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal object GenieDiagnostics {
    private data class Item(val menu: String?, val quantity: Int?, val temperature: Temperature?)
    private data class Sample(val text: String, val items: List<Item>)

    private val americano = Item("아메리카노", 2, Temperature.ICE)
    private val samples = listOf(
        Sample("아이스 아메리카노 두 잔 주세요.", listOf(americano)),
        Sample("아아 두 개랑 따뜻한 라떼 하나 주세요.", listOf(americano, Item("카페라떼", 1, Temperature.HOT))),
        Sample("아이스 아메리카노 두 잔이랑 카페라떼 한 잔 주세요.", listOf(americano, Item("카페라떼", 1, null)))
    )

    fun show(activity: ComponentActivity, engine: GenieLlmEngine?): AlertDialog {
        val selector = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, samples.map { it.text })
        }
        val status = TextView(activity).apply {
            text = if (engine == null) "이 빌드 또는 기기에서는 Genie HTP를 실행할 수 없습니다."
                else "테스트 문장을 선택하세요."
            setTextIsSelectable(true)
        }
        val margin = (16 * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin, margin, margin)
            addView(selector)
            addView(status)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Genie LLM 테스트")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton("닫기", null)
            .setPositiveButton("실행", null)
            .show()

        var job: Job? = null
        dialog.setOnDismissListener { job?.cancel() }
        val run = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        run.setOnClickListener {
            if (job?.isActive == true) return@setOnClickListener
            if (engine == null) {
                Log.w(GENIE_TAG, "GENIE_TEST_FAILED code=GENIE_UNSUPPORTED_DEVICE_OR_BUILD")
                status.text = "실행 불가 · GENIE_UNSUPPORTED_DEVICE_OR_BUILD"
                return@setOnClickListener
            }
            val sample = samples[selector.selectedItemPosition]
            job = activity.lifecycleScope.launch {
                run.isEnabled = false
                selector.isEnabled = false
                status.text = "모델 확인 및 추론 중…"
                val start = SystemClock.elapsedRealtime()
                fun elapsed() = SystemClock.elapsedRealtime() - start
                try {
                    val context = NluContext("genie-debug", DialogueState.WAITING_FOR_ORDER, emptyList(),
                        emptyList(), emptyList(), null, null)
                    val json = engine.diagnosticQuery(sample.text, context)
                    val result = try {
                        NluJsonAdapter.decodeGenieOrThrow(json)
                    } catch (error: Exception) {
                        throw LlmUnavailableException("Function Call JSON이 유효하지 않습니다.", "JSON_VALIDATION_FAILED", error)
                    }
                    Log.i(GENIE_TAG, "JSON_VALIDATION_PASS")
                    val actions = if (result.intent == NluIntent.MULTI_ACTION) result.actions else listOf(result)
                    val actual = actions.map { Item(it.menu, it.quantity, it.temperature) }
                    if (actions.any { it.intent != NluIntent.ADD || it.size != null } ||
                        actual.size != sample.items.size || actual.toSet() != sample.items.toSet()) {
                        throw LlmUnavailableException("테스트 주문의 메뉴, 수량 또는 옵션이 예상과 다릅니다.\n$json",
                            "ORDER_VALIDATION_FAILED")
                    }
                    Log.i(GENIE_TAG, "GENIE_TEST_SUCCESS ms=${elapsed()}")
                    status.text = "성공 · ${elapsed()}ms\n\n$json"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: LlmUnavailableException) {
                    Log.w(GENIE_TAG, "GENIE_TEST_FAILED code=${error.code} ms=${elapsed()}")
                    val detail = error.cause?.message?.let { "\n$it" }.orEmpty()
                    status.text = "실패 · ${elapsed()}ms · ${error.code}\n${error.kioskMessage}$detail"
                } finally {
                    run.isEnabled = true
                    selector.isEnabled = true
                }
            }
        }
        return dialog
    }
}
