@file:Suppress("UNUSED_PARAMETER")

package com.example.cafe_kiosk_ai

import android.app.Activity

internal class TestTextInput {
    fun attach(activity: Activity, submit: (String) -> Boolean) = Unit

    fun detach() = Unit
}
