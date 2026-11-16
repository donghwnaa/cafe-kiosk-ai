package com.example.cafe_kiosk_ai

import android.os.Build

internal object AndroidRuntimeEnvironment {
    val isEmulator: Boolean
        get() = Build.HARDWARE.lowercase() in setOf("ranchu", "goldfish", "cuttlefish") ||
            Build.PRODUCT.startsWith("sdk_") || Build.DEVICE.startsWith("emu")

    val hasQualcommPlatform: Boolean
        get() {
            val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.lowercase() else ""
            return !isEmulator && (soc.contains("qualcomm") || soc == "qti" ||
                Build.HARDWARE.equals("qcom", ignoreCase = true) ||
                Build.BOARD.startsWith("qcs6490", ignoreCase = true) ||
                Build.BOARD.startsWith("qcm6490", ignoreCase = true))
        }
}
