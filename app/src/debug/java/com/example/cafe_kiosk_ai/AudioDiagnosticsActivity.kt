package com.example.cafe_kiosk_ai

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

class AudioDiagnosticsActivity : AppCompatActivity() {
    private data class Recording(val rate: Int, val pcm: ShortArray)

    private val main = Handler(Looper.getMainLooper())
    private val buttons = mutableListOf<Button>()
    private lateinit var controls: LinearLayout
    private lateinit var status: TextView
    private lateinit var profilePicker: Spinner
    private lateinit var readModePicker: Spinner
    private lateinit var playRecording: Button
    private var profiles = emptyList<CaptureProfile>()
    private var recording: Recording? = null
    private var voice: VoiceController? = null
    private var tts: AndroidTtsEngine? = null
    private var pendingPermission: (() -> Unit)? = null
    @Volatile private var operation = 0L
    private var busy = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingPermission
        pendingPermission = null
        if (granted) pending?.invoke() else show("마이크 권한이 거부되었습니다.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset * 2, inset, inset)
        }
        setContentView(ScrollView(this).apply { addView(controls) })
        controls.addView(TextView(this).apply { text = "MALLO 오디오 진단"; textSize = 24f })
        profilePicker = Spinner(this).also(controls::addView)
        refreshProfiles()
        readModePicker = Spinner(this).also(controls::addView)
        readModePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, CaptureReadMode.entries)
        if (intent.getStringExtra("read_mode") == "blocking") readModePicker.setSelection(1)
        addButton("입력 장치 새로 고침", ::refreshProfiles)
        addButton("마이크 5초 녹음") { withMicrophone(::recordMicrophone) }
        playRecording = addButton("녹음 재생") { recording?.let { play(it.pcm, it.rate, "WAV") } }
        playRecording.isEnabled = false
        addButton("SenseVoice 테스트") { withMicrophone { startRecognition(begin("SenseVoice 준비 중…")) } }
        addButton("스피커 테스트", ::testSpeaker)
        addButton("한국어 TTS 테스트") { testTts(followWithStt = false) }
        addButton("한국어 TTS → SenseVoice") { withMicrophone { testTts(followWithStt = true) } }
        addButton("한국어 음성 설치") { openKoreanVoiceSettings(this) }
        controls.addView(Button(this).apply { text = "중지"; setOnClickListener { stop(); show("중지했습니다.") } })
        status = TextView(this).also(controls::addView)
        show("테스트를 선택하세요.")
        profilePicker.setSelection(intent.getIntExtra("profile", 0).coerceIn(profiles.indices))
        if (savedInstanceState == null) main.post {
            when (intent.getStringExtra("test")) {
                "mic" -> withMicrophone(::recordMicrophone)
                "stt" -> withMicrophone { startRecognition(begin("SenseVoice 준비 중…")) }
                "speaker" -> testSpeaker()
                "tts" -> testTts(false)
                "tts-stt" -> withMicrophone { testTts(true) }
            }
        }
    }

    override fun onPause() {
        stop()
        super.onPause()
    }

    override fun onDestroy() {
        tts?.release()
        super.onDestroy()
    }

    private fun addButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { if (!busy) action() }
        controls.addView(this)
        buttons += this
    }

    private fun refreshProfiles() {
        val audio = getSystemService(AudioManager::class.java)
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val additional = inputs.flatMap { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> listOf(CaptureProfile(MediaRecorder.AudioSource.MIC, 16_000, device.id))
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> usbCaptureProfiles(device)
                else -> emptyList()
            }
        }
        profiles = (microphoneProfiles(this) + additional).distinct()
        val labels = profiles.map { profile ->
            val device = inputs.firstOrNull { it.id == profile.preferredDeviceId }
            "${device?.description() ?: "시스템 입력"} • $profile"
        }
        profilePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private fun withMicrophone(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingPermission = action
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun begin(message: String): Long {
        operation++
        setBusy(true)
        show(message)
        return operation
    }

    private fun setBusy(active: Boolean) {
        busy = active
        buttons.forEach { it.isEnabled = !active }
        profilePicker.isEnabled = !active
        readModePicker.isEnabled = !active
        playRecording.isEnabled = !active && recording != null
    }

    private fun stop() {
        operation++
        voice?.release()
        voice = null
        tts?.stop()
        setBusy(false)
    }

    private fun show(text: String) {
        status.text = text
    }

    private fun update(id: Long, text: String) {
        main.post { if (id == operation) show(text) }
    }

    private fun finish(id: Long, text: String) {
        main.post {
            if (id != operation) return@post
            stop()
            show(text)
        }
    }

    private fun recordMicrophone() {
        val profile = profiles[profilePicker.selectedItemPosition]
        val mode = CaptureReadMode.entries[readModePicker.selectedItemPosition]
        recording = null
        val id = begin("마이크를 여는 중…\n$profile $mode")
        microphoneWorker.execute {
            if (id != operation) return@execute
            try {
                val file = File(cacheDir, "mallo-microphone.wav")
                check(!file.exists() || file.delete()) { "이전 WAV 파일을 지우지 못했습니다." }
                val result = PcmCapture.open(applicationContext, profile).use { capture ->
                    Log.i(TAG, "MIC_TEST_STARTED mode=$mode ${capture.start()}")
                    readProbe(capture, mode, id)
                }
                if (id != operation) return@execute
                if (result.status != "SIGNAL_PRESENT") {
                    Log.w(TAG, "MIC_TEST_ERROR status=${result.status} $profile mode=$mode ${result.detail}")
                    finish(id, "${result.message}\n$profile $mode\n${result.detail}\nUSB 입력이면 사운드카드와 마이크 케이블을 확인하세요.")
                    return@execute
                }
                writeWave(file, result.pcm, profile.sampleRate)
                val durationMs = result.pcm.size * 1_000L / profile.sampleRate
                val summary = "입력 변화 감지 (${durationMs}ms). 재생해서 실제 목소리를 확인하세요.\n${result.detail}\n${file.absolutePath}"
                Log.i(TAG, "MIC_TEST_RESULT status=${result.status} mode=$mode durationMs=$durationMs ${result.detail} wav=${file.name}")
                main.post {
                    if (id != operation) return@post
                    recording = Recording(profile.sampleRate, result.pcm)
                    setBusy(false)
                    show(summary)
                }
            } catch (error: Exception) {
                Log.w(TAG, "MIC_TEST_ERROR ${error.message}", error)
                finish(id, "마이크 테스트 실패\n${error.message}")
            }
        }
    }

    private data class ProbeResult(val pcm: ShortArray, val status: String, val message: String, val detail: String)

    private fun readProbe(capture: PcmCapture, mode: CaptureReadMode, id: Long): ProbeResult {
        val rate = capture.profile.sampleRate
        val target = ShortArray(rate * 5)
        val chunk = ShortArray(rate / 25)
        val total = PcmLevels()
        var window = PcmLevels()
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        val finished = AtomicBoolean()
        val stopRequested = AtomicBoolean()
        val stopper = if (mode == CaptureReadMode.READ_BLOCKING) {
            Thread({
                while (!finished.get() && id == operation && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(10)
                }
                if (!finished.get()) {
                    stopRequested.set(true)
                    Log.i(TAG, "MIC_PROBE_STOP cause=${if (id == operation) "READ_DEADLINE" else "CANCELLED"}")
                    capture.stop()
                }
            }, "MalloBlockingReadStop").apply { start() }
        } else null
        var nextLog = 0L
        var readCalls = 0
        var zeroReads = 0
        var route = capture.routeStatus()
        try {
            while (id == operation && total.samples < target.size && SystemClock.elapsedRealtime() < deadline) {
                readCalls++
                val count = try {
                    capture.read(chunk, total.samples, mode)
                } catch (error: AudioInputException) {
                    if (stopRequested.get()) break
                    throw error
                }
                if (count == 0) zeroReads++
                val copied = minOf(count, target.size - total.samples)
                if (copied > 0) {
                    if (total.samples == 0 && !stopRequested.get()) capture.verifyRoute()
                    chunk.copyInto(target, total.samples, 0, copied)
                    total.add(chunk, copied)
                    window.add(chunk, copied)
                } else {
                    SystemClock.sleep(10)
                }
                val now = SystemClock.elapsedRealtime()
                if (!stopRequested.get() && now >= nextLog) {
                    route = capture.routeStatus()
                    val detail = "readSamples=$count totalSamples=${total.samples} window=$window $route"
                    Log.i(TAG, "MIC_TEST_STATS mode=$mode ${capture.profile} $detail")
                    update(id, "녹음 중…\n${capture.profile} $mode\n$detail")
                    nextLog = now + 500L
                    window = PcmLevels()
                }
            }
        } finally {
            finished.set(true)
            // Only the capture worker waits. Release follows both read() and the watchdog's stop().
            stopper?.join()
        }
        val (status, message) = when {
            total.samples == 0 && stopRequested.get() -> "READ_TIMEOUT" to "5초 동안 블로킹 읽기에서 PCM을 받지 못했습니다."
            total.samples == 0 -> "NO_FRAMES" to "5초 동안 수신한 PCM이 0바이트입니다."
            total.nonZero == 0 -> "SILENT_PCM" to "PCM은 수신했지만 샘플이 모두 0입니다. 조용했다면 말하면서 다시 시험해 보세요."
            !total.varies -> "CONSTANT_PCM" to "PCM 값이 일정합니다. 말하면서 다시 시험해 보세요."
            else -> "SIGNAL_PRESENT" to ""
        }
        return ProbeResult(target.copyOf(total.samples), status, message,
            "totalSamples=${total.samples} $total readCalls=$readCalls zeroReads=$zeroReads " +
                "deadlineStoppedRead=${stopRequested.get()} $route")
    }

    private fun writeWave(file: File, pcm: ShortArray, rate: Int) {
        val size = pcm.size * 2
        val data = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16)
        data.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(size)
        pcm.forEach(data::putShort)
        file.writeBytes(data.array())
    }

    private fun startRecognition(id: Long) {
        show("$voiceEngineName 준비 중…")
        var phase: VoiceTurn.Phase? = VoiceTurn.Phase.QUEUED
        fun watch(expected: VoiceTurn.Phase, reason: String) {
            main.postDelayed({
                if (id == operation && phase == expected) {
                    Log.w(TAG, "DIAGNOSTIC_STT_ERROR reason=$reason")
                    finish(id, reason)
                }
            }, 12_000)
        }
        watch(VoiceTurn.Phase.QUEUED, "STT_CAPTURE_QUEUE_TIMEOUT")
        voice = createVoiceController(this, "diagnostics", profiles[profilePicker.selectedItemPosition]) { turn, event ->
            if (id != operation || turn != id) return@createVoiceController
            when (event) {
                VoiceRecognitionEvent.PreparationStarted -> {
                    phase = VoiceTurn.Phase.PREPARING
                    watch(VoiceTurn.Phase.PREPARING, "STT_PREPARATION_TIMEOUT")
                }
                VoiceRecognitionEvent.AwaitingSpeech -> {
                    phase = null // The capture deadline is satisfied before VAD detects speech.
                    show(getString(R.string.voice_usb_awaiting_speech))
                }
                VoiceRecognitionEvent.Listening -> {
                    phase = null
                    Log.i(TAG, "DIAGNOSTIC_STT_READY engine=$voiceEngineName")
                    show("듣는 중… 한국어로 말씀해 주세요.")
                }
                VoiceRecognitionEvent.Processing -> show("음성을 인식하고 있습니다.")
                is VoiceRecognitionEvent.Result -> {
                    Log.i(TAG, "DIAGNOSTIC_STT_RESULT engine=$voiceEngineName")
                    finish(id, "인식 결과\n${event.text}")
                }
                is VoiceRecognitionEvent.Error -> {
                    Log.w(TAG, "DIAGNOSTIC_STT_ERROR reason=${event.reason}")
                    finish(id, "인식 실패\n${event.reason}")
                }
            }
        }.also { it.startListening(id) }
    }

    private fun testTts(followWithStt: Boolean) {
        val id = begin("한국어 안내 음성을 준비 중…")
        val engine = tts ?: AndroidTtsEngine(this).also { tts = it }
        engine.refreshIfUnavailable()
        engine.speak("안녕하세요. 무엇을 주문하시겠어요?") { event ->
            if (operation != id) return@speak
            when (event) {
                TtsPlaybackEvent.Started -> show("한국어 안내를 재생하고 있습니다.")
                TtsPlaybackEvent.Completed -> {
                    Log.i(TAG, "DIAGNOSTIC_TTS_COMPLETED followWithStt=$followWithStt")
                    if (followWithStt) startRecognition(id) else finish(id, "한국어 안내 재생 완료")
                }
                TtsPlaybackEvent.Stopped -> finish(id, "한국어 안내 재생 중지")
                is TtsPlaybackEvent.Error -> finish(id, "한국어 안내 실패\n${event.reason}")
            }
        }
    }

    private fun testSpeaker() {
        val rate = 48_000
        val pcm = ShortArray(rate) { sample ->
            val gain = minOf(1.0, sample / 480.0, (rate - sample) / 480.0)
            (sin(sample * 2 * PI * 440 / rate) * 6_000 * gain).toInt().toShort()
        }
        play(pcm, rate, "440 Hz")
    }

    private fun play(pcm: ShortArray, rate: Int, kind: String) {
        val id = begin("스피커 재생을 준비 중…")
        microphoneWorker.execute {
            if (id != operation) return@execute
            val audio = getSystemService(AudioManager::class.java)
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val focusListener = AudioManager.OnAudioFocusChangeListener { change -> Log.i(TAG, "OUTPUT_FOCUS_CHANGE value=$change") }
            val request = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener, main).build() else null
            @Suppress("DEPRECATION")
            val focus = if (Build.VERSION.SDK_INT >= 26 && request != null) audio.requestAudioFocus(request)
                else audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            Log.i(TAG, "OUTPUT_FOCUS result=$focus mediaVolume=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/" +
                "${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
            if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                finish(id, "스피커 재생 실패: 오디오 포커스를 얻지 못했습니다 ($focus).")
                return@execute
            }
            var track: AudioTrack? = null
            try {
                val format = AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                val player = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                track = player
                val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val usb = outputs.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                }
                val preferred = usb?.let(player::setPreferredDevice)
                val written = player.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                check(written == pcm.size) { "AudioTrack.write=$written, expected=${pcm.size}" }
                if (id != operation) return@execute
                player.play()
                val deadline = SystemClock.elapsedRealtime() + pcm.size * 1_000L / rate + 2_000L
                var route: AudioDeviceInfo? = null
                while (id == operation && player.playbackHeadPosition < written && SystemClock.elapsedRealtime() < deadline) {
                    route = player.routedDevice ?: route
                    SystemClock.sleep(20)
                }
                if (id != operation) return@execute
                val detail = "kind=$kind frames=${player.playbackHeadPosition}/$written route=${route?.description()} " +
                    "preferredAccepted=$preferred outputs=${outputs.joinToString { it.description() }}"
                check(player.playbackHeadPosition >= written) { "스피커 재생 시간 초과 $detail" }
                Log.i(TAG, "OUTPUT_TEST_RESULT $detail")
                finish(id, "재생 완료. 실제로 소리가 들렸는지 확인하세요.\n$detail")
            } catch (error: Exception) {
                Log.w(TAG, "OUTPUT_TEST_ERROR ${error.message}", error)
                finish(id, "스피커 재생 실패\n${error.message}")
            } finally {
                track?.release()
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 26 && request != null) audio.abandonAudioFocusRequest(request)
                else audio.abandonAudioFocus(focusListener)
            }
        }
    }

    private companion object { const val TAG = "MalloAudioTest" }
}
