package com.example.cafe_kiosk_ai

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.cafe_kiosk_ai.databinding.ActivityPaymentCompleteBinding
import java.util.Locale

class PaymentCompleteActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityPaymentCompleteBinding
    private var tts: TextToSpeech? = null
    private val autoResetHandler = Handler(Looper.getMainLooper())
    private var orderNumber: String = "105"

    private val resetRunnable = Runnable {
        navigateToHome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaymentCompleteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 전달받은 주문번호 추출 (기본값 "105")
        orderNumber = intent.getStringExtra("EXTRA_ORDER_NUMBER") ?: "105"
        binding.tvOrderNumber.text = orderNumber

        // TTS 초기화
        tts = TextToSpeech(this, this)

        binding.btnReset.setOnClickListener { navigateToHome() }
        binding.btnHome.setOnClickListener { navigateToHome() }

        // 8초 후 자동 복귀
        autoResetHandler.postDelayed(resetRunnable, 8000)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.KOREAN)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setSpeechRate(0.85f) // 노년층 대상 발화 속도 완화
                tts?.setPitch(1.0f)

                val announcementText = "주문이 완료되었습니다. 주문번호는 ${orderNumber}번 입니다. 번호를 확인해 주세요."
                speak(announcementText)
            }
        } else {
            Log.e("PaymentComplete", "TTS 초기화 실패 (AI-E005)")
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AI-005-COMPLETE")
    }

    private fun navigateToHome() {
        autoResetHandler.removeCallbacks(resetRunnable)
        tts?.stop()

        val homeIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    override fun onDestroy() {
        autoResetHandler.removeCallbacks(resetRunnable)
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}