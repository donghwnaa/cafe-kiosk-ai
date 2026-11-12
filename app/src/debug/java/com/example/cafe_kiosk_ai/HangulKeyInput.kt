package com.example.cafe_kiosk_ai

import android.view.KeyEvent
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged

/** Replaces the current run of physical letter keys without changing the Android IME. */
internal class HangulKeyInput(private val input: EditText) {
    private val keys = StringBuilder()
    private var start = 0
    private var end = 0
    private var replacing = false

    init {
        input.doAfterTextChanged {
            if (!replacing) reset()
        }
        input.setOnFocusChangeListener { _, focused ->
            if (!focused) reset()
        }
    }

    fun reset() {
        keys.clear()
    }

    fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
            reset()
            return false
        }
        val letterKey = keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
        val continuing = keys.isNotEmpty() && input.selectionStart == end && input.selectionEnd == end
        val backspace = keyCode == KeyEvent.KEYCODE_DEL && continuing
        if (!letterKey && !backspace) {
            if (event.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(keyCode)) reset()
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return letterKey

        if (!continuing) {
            reset()
            val first = input.selectionStart.takeIf { it >= 0 } ?: input.length()
            val last = input.selectionEnd.takeIf { it >= 0 } ?: first
            start = minOf(first, last)
            end = maxOf(first, last)
        }
        if (backspace) {
            keys.deleteCharAt(keys.lastIndex)
        } else {
            val base = if (event.isShiftPressed) 'A' else 'a'
            keys.append(base + (keyCode - KeyEvent.KEYCODE_A))
        }
        val text = TwoSetHangul.compose(keys)
        replacing = true
        try {
            input.text.replace(start, end, text)
            end = start + text.length
            input.setSelection(end)
        } finally {
            replacing = false
        }
        return true
    }
}
