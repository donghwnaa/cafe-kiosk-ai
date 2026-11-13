package com.example.cafe_kiosk_ai

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import androidx.core.content.ContextCompat

internal class TestTextInput {
    private var unregisterInputReceiver: (() -> Unit)? = null

    fun attach(activity: Activity, submit: (String) -> Boolean) {
        // Keep the ADB test entry point behind the existing DUMP permission.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val encoded = intent.getStringExtra("text_b64") ?: return
                val bytes = try {
                    Base64.decode(encoded, Base64.DEFAULT)
                } catch (_: IllegalArgumentException) {
                    return
                }
                if (submit(bytes.toString(Charsets.UTF_8)) && isOrderedBroadcast) {
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
        unregisterInputReceiver?.invoke()
        unregisterInputReceiver = null
    }
}
