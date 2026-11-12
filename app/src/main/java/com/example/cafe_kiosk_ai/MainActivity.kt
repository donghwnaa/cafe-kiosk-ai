package com.example.cafe_kiosk_ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private lateinit var screenContainer: FrameLayout
    private val navigationIds = listOf(
        R.id.nav_start,
        R.id.nav_conversation,
        R.id.nav_product,
        R.id.nav_order,
        R.id.nav_payment,
    )

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        screenContainer = findViewById(R.id.screen_container)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        navigationIds.forEachIndexed { index, id ->
            findViewById<View>(id).setOnClickListener { showScreen(index) }
        }
        showScreen(0)
    }

    private fun showScreen(index: Int) {
        stopListening()
        navigationIds.forEachIndexed { navIndex, id ->
            findViewById<TextView>(id).apply {
                setTextColor(if (navIndex == index) 0xFF514BCB.toInt() else 0xFF424242.toInt())
                setBackgroundResource(if (navIndex == index) R.drawable.bg_nav_selected else android.R.color.transparent)
            }
        }

        val layout = when (index) {
            0 -> R.layout.screen_start
            1 -> R.layout.conversation_ui
            2 -> R.layout.screen_product_confirm
            3 -> R.layout.screen_order_confirm
            else -> R.layout.activity_payment_complete
        }
        screenContainer.removeAllViews()
        val screen = layoutInflater.inflate(layout, screenContainer, false)
        screenContainer.addView(screen)

        when (index) {
            0 -> screen.findViewById<View>(R.id.btn_start_order).setOnClickListener { showScreen(1) }
            1 -> {
                screen.findViewById<View>(R.id.home_icon).setOnClickListener { showScreen(0) }
                val micButton = screen.findViewById<View>(R.id.talking_status_image)
                val resultText = screen.findViewById<TextView>(R.id.recognized_text)
                micButton.setOnClickListener {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@setOnClickListener
                    }
                    if (!isListening) {
                        startListening(resultText)
                    } else {
                        stopListening()
                    }
                }
            }
            2 -> {
                screen.findViewById<View>(R.id.product_home).setOnClickListener { showScreen(0) }
                screen.findViewById<View>(R.id.btn_product_confirm).setOnClickListener { showScreen(3) }
            }
            3 -> {
                screen.findViewById<View>(R.id.order_home).setOnClickListener { showScreen(0) }
                screen.findViewById<View>(R.id.btn_order_pay).setOnClickListener { showScreen(4) }
                val totalMessage = SpannableString("총 결제 금액 : 9,000원입니다.\n확인 부탁드려요")
                val amountStart = totalMessage.indexOf("9,000")
                totalMessage.setSpan(
                    ForegroundColorSpan(0xFFFF0C0C.toInt()),
                    amountStart,
                    amountStart + "9,000".length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                screen.findViewById<TextView>(R.id.order_total_message).text = totalMessage
            }
            else -> {
                screen.findViewById<TextView>(R.id.tvOrderNumber).text = "주문번호 001"
                screen.findViewById<View>(R.id.btnHome).setOnClickListener { showScreen(0) }
                screen.findViewById<View>(R.id.btnReset).setOnClickListener { showScreen(0) }
            }
        }
    }

    private fun startListening(resultText: TextView) {
        stopListening()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    resultText.text = "듣고 있습니다..."
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    resultText.text = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성을 인식하지 못했습니다"
                        else -> "음성 인식 오류 ($error)"
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    resultText.text = matches?.firstOrNull().orEmpty().ifEmpty { "인식 결과 없음" }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrEmpty()) {
                        resultText.text = partial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        isListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
    }
}