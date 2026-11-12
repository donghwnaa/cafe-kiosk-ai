@file:Suppress("UNUSED_PARAMETER")

package com.example.cafe_kiosk_ai

import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

internal class TestTextInput {
    fun attach(
        activity: AppCompatActivity,
        slot: FrameLayout,
        pending: () -> PendingQuestion,
        screen: () -> Int,
        submit: (String) -> Boolean
    ) = Unit

    fun detach() = Unit
}
