package com.example.cafe_kiosk_ai

import android.app.AlertDialog
import android.app.Dialog
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.cafe_kiosk_ai.llm.GENIE_TAG
import com.example.cafe_kiosk_ai.llm.GenieLlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal object GenieDemoDialog {
    fun show(activity: ComponentActivity, engine: GenieLlmEngine?, onDismiss: () -> Unit): Dialog {
        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
        val content = activity.layoutInflater.inflate(R.layout.conversation_ui, null)
        content.findViewById<View>(R.id.home_icon).setOnClickListener { dialog.dismiss() }
        content.findViewById<View>(R.id.voice_footer).visibility = View.GONE
        val entry = content.findViewById<View>(R.id.genie_demo)
        val parent = entry.parent as LinearLayout
        content.findViewById<TextView>(R.id.conversation_prompt).text = "MALLO LLM 시연"

        val ready = if (engine == null) "실행 불가 · Qualcomm Genie 백엔드를 사용할 수 없습니다."
            else "엔진: Qualcomm Genie · llama32_hf_v68.bin"
        val status = TextView(activity).apply {
            text = ready
            textSize = 16f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val scenarios = GenieDemoScenarios.all
        val selector = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("전체 시나리오") + scenarios.map { it.label })
        }
        fun button(label: String) = Button(activity).apply { text = label; textSize = 16f }
        val start = button("예시 대화 자동 실행").apply { isEnabled = engine != null }
        val stop = button("중지").apply { isEnabled = false }
        val details = button("원본 JSON·상세").apply { isEnabled = false }
        val buttons = LinearLayout(activity).apply {
            addView(start, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(stop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        }
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(selector)
            addView(buttons)
        }
        parent.addView(controls, parent.indexOfChild(entry))
        dialog.setContentView(content)

        val messages = mutableListOf<ChatMessage>()
        val turns = mutableListOf<DemoTurn>()
        val transcript = ChatTranscript(content, showActions = false) { false }
        fun render() = transcript.render(messages, PendingQuestion.NONE, restoreOffset = null)
        var job: Job? = null
        var detailDialog: AlertDialog? = null
        val runner = engine?.let { GenieDemoRunner(it::demoQuery, pauseMs = 500) }

        details.setOnClickListener {
            val entries = turns.toList()
            detailDialog = AlertDialog.Builder(activity).setTitle("실제 Genie 원본 응답")
                .setItems(entries.map { "${it.requestId} · ${it.text}" }.toTypedArray()) { _, index ->
                    val turn = entries[index]
                    detailDialog = AlertDialog.Builder(activity).setTitle(turn.requestId)
                        .setMessage(buildString {
                            appendLine(turn.text)
                            appendLine("Function: ${turn.function ?: "—"}")
                            appendLine("JSON 검증: ${jsonStatus(turn)}")
                            appendLine("추론 시간: ${turn.inference?.let { "${it.inferenceMs} ms" } ?: "—"}")
                            appendLine("앱 처리: ${turn.outcome ?: "—"}")
                            turn.failure?.let { appendLine("실패: $it") }
                            appendLine()
                            append(turn.inference?.json ?: "Genie 원본 응답 없음")
                        }).setPositiveButton("닫기", null).show()
                }.setNegativeButton("닫기", null).show()
        }
        stop.setOnClickListener { job?.cancel() }
        start.setOnClickListener {
            if (runner == null || job?.isActive == true) return@setOnClickListener
            val selected = if (selector.selectedItemPosition == 0) scenarios
                else listOf(scenarios[selector.selectedItemPosition - 1])
            val total = selected.sumOf { it.steps.size }
            messages.clear()
            turns.clear()
            render()
            details.isEnabled = false
            start.isEnabled = false
            stop.isEnabled = true
            selector.isEnabled = false
            job = activity.lifecycleScope.launch {
                try {
                    Log.i(GENIE_TAG, "DEMO_REQUESTED target=GENIE steps=$total")
                    runner.run(selected,
                        onScenario = { scenario ->
                            messages.add(ChatMessage(false, "${scenario.label} · 새 테스트 장바구니"))
                            render()
                        },
                        onStart = { id, text ->
                            status.text = "${turns.size + 1} / $total · $id · Genie 처리 중"
                            messages.add(ChatMessage(true, text))
                            render()
                        },
                        onTurn = { turn ->
                            turns.add(turn)
                            details.isEnabled = true
                            messages.addAll(turn.messages.map { it.copy(text = "앱 안내 · ${it.text}") })
                            val elapsed = turn.inference?.let { " · ${it.inferenceMs} ms" }.orEmpty()
                            val verdict = if (turn.failure == null) "시나리오 PASS" else "실패 · ${turn.failure}"
                            messages.add(ChatMessage(false, "${turn.requestId} · ${turn.function ?: "함수 없음"}$elapsed\n" +
                                "JSON ${jsonStatus(turn)} · $verdict"))
                            render()
                            Log.i(GENIE_TAG, "DEMO_RESULT request=${turn.requestId} function=${turn.function} " +
                                "json=${jsonStatus(turn)} result=$verdict ms=${turn.inference?.inferenceMs} app=${turn.outcome}")
                            turn.inference?.json?.chunked(500)?.forEachIndexed { index, part ->
                                Log.d(GENIE_TAG, "DEMO_RAW request=${turn.requestId} part=${index + 1} $part")
                            }
                        }
                    )
                    status.text = summary(if (turns.size == total) "완료" else "실패로 종료", turns, total)
                } catch (cancelled: CancellationException) {
                    status.text = summary("중지", turns, total)
                    throw cancelled
                } catch (error: Exception) {
                    status.text = "시연 오류 · ${error.message}"
                    Log.e(GENIE_TAG, "DEMO_FAILED", error)
                } finally {
                    start.isEnabled = true
                    stop.isEnabled = false
                    selector.isEnabled = true
                }
            }
        }
        dialog.setOnDismissListener {
            job?.cancel()
            detailDialog?.dismiss()
            onDismiss()
        }
        dialog.show()
        dialog.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        return dialog
    }

    private fun summary(label: String, turns: List<DemoTurn>, total: Int): String =
        "$label · 성공 ${turns.count { it.failure == null }} · 실패 ${turns.count { it.failure != null }} · 미실행 ${total - turns.size}"

    private fun jsonStatus(turn: DemoTurn): String = when (turn.jsonValid) {
        true -> "통과"
        false -> "거부"
        null -> "미실행"
    }
}
