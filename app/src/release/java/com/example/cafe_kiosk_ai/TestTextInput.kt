@file:Suppress("UNUSED_PARAMETER")

package com.example.cafe_kiosk_ai

import android.view.View
import androidx.activity.ComponentActivity
import com.example.cafe_kiosk_ai.llm.GenieLlmEngine

internal fun createDevelopmentNlu(): NluEngine? = null

internal class TestTextInput {
    fun attach(
        activity: ComponentActivity, trigger: View, demoButton: View, submit: (String) -> Boolean,
        genie: GenieLlmEngine?, onDemoOpen: (Boolean) -> Unit
    ) = Unit

    fun detach() = Unit
}
