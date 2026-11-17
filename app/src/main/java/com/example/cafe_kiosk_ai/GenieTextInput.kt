package com.example.cafe_kiosk_ai

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.cafe_kiosk_ai.llm.GenieModelState
import com.example.cafe_kiosk_ai.llm.GenieTurn

internal class GenieTextInput(screen: View, submit: (String) -> Boolean, retryInitialization: () -> Unit) {
    private val panel = screen.findViewById<View>(R.id.genie_text_input)
    private val input = panel.findViewById<EditText>(R.id.genie_order_text)
    private val send = panel.findViewById<Button>(R.id.genie_send)
    private val status = panel.findViewById<TextView>(R.id.genie_status)
    private val details = panel.findViewById<Button>(R.id.genie_details)
    private val retry = panel.findViewById<Button>(R.id.genie_retry)
    private var canSubmit = false
    private var detailText: String? = null

    init {
        panel.isVisible = true
        input.doAfterTextChanged { updateSend() }
        retry.setOnClickListener { retryInitialization() }
        send.setOnClickListener {
            if (submit(input.text.toString())) {
                input.text.clear()
                ViewCompat.getWindowInsetsController(input)?.hide(WindowInsetsCompat.Type.ime())
            } else {
                input.error = panel.context.getString(R.string.genie_submit_rejected)
            }
        }
        panel.findViewById<Button>(R.id.genie_clear).setOnClickListener { input.text.clear() }
        panel.findViewById<Button>(R.id.genie_examples).setOnClickListener {
            val examples = panel.resources.getStringArray(R.array.genie_example_utterances)
            AlertDialog.Builder(panel.context)
                .setTitle(R.string.genie_examples)
                .setItems(examples) { _, index ->
                    input.setText(examples[index])
                    input.setSelection(input.length())
                }
                .show()
        }
        details.setOnClickListener {
            detailText?.let { text ->
                AlertDialog.Builder(panel.context)
                    .setTitle(R.string.genie_details)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                    .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
            }
        }
    }

    fun render(model: GenieModelState?, initializationError: String?, turn: GenieTurn?, busy: Boolean) {
        canSubmit = model != null && !busy
        updateSend()
        retry.isVisible = model == GenieModelState.FAILED
        retry.isEnabled = !busy
        detailText = initializationError ?: turn?.error ?: turn?.inference?.json
        details.isVisible = detailText != null
        if (model == null) {
            status.setText(R.string.genie_setup_required)
            return
        }
        val modelLabel = when (model) {
            GenieModelState.UNCHECKED -> R.string.genie_model_unchecked
            GenieModelState.CHECKING -> R.string.genie_model_checking
            GenieModelState.FILE_VERIFIED -> R.string.genie_model_verified
            GenieModelState.LIBRARY_LOADED -> R.string.genie_library_loaded
            GenieModelState.READY -> R.string.genie_model_ready
            GenieModelState.FAILED -> R.string.genie_model_failed
        }
        val context = status.context
        val result = when {
            model == GenieModelState.FAILED -> initializationError.orEmpty()
            busy && model != GenieModelState.READY -> context.getString(R.string.genie_initializing)
            turn?.error != null && !busy -> context.getString(R.string.genie_inference_failed)
            turn?.inference != null -> {
                val validation = when (turn.validated) {
                    true -> R.string.genie_json_valid
                    false -> R.string.genie_json_invalid
                    null -> R.string.genie_json_waiting
                }
                context.getString(R.string.genie_inference_done, turn.inference.inferenceMs, context.getString(validation))
            }
            busy && turn?.running == true -> context.getString(R.string.genie_inference_running)
            busy -> context.getString(R.string.genie_inference_running)
            else -> context.getString(R.string.genie_inference_waiting)
        }
        val label = context.getString(R.string.genie_status_format, context.getString(modelLabel), result)
        if (status.text != label) status.text = label
    }

    private fun updateSend() {
        send.isEnabled = canSubmit && input.text.isNotBlank()
    }
}
