package com.example.cafe_kiosk_ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

internal val microphoneWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "MalloMicrophone") }

internal data class CaptureProfile(val source: Int, val sampleRate: Int, val preferredDeviceId: Int? = null) {
    override fun toString(): String {
        val name = if (source == MediaRecorder.AudioSource.MIC) "MIC" else "VOICE_RECOGNITION"
        return "source=$name($source) sampleRate=$sampleRate preferredDevice=${preferredDeviceId ?: "system"}"
    }
}

internal enum class CaptureReadMode(val flag: Int) {
    READ_NON_BLOCKING(AudioRecord.READ_NON_BLOCKING),
    READ_BLOCKING(AudioRecord.READ_BLOCKING)
}

internal val AudioDeviceInfo.isUsbInput: Boolean
    get() = isSource && (type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE)

internal fun AudioDeviceInfo.description(): String {
    val name = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        else -> type.toString()
    }
    val addressLabel = if (Build.VERSION.SDK_INT >= 28) ", address=$address" else ""
    return "$name(id=$id, name=$productName$addressLabel)"
}

internal fun microphoneProfiles(context: Context): List<CaptureProfile> {
    val audio = context.getSystemService(AudioManager::class.java)
    val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
    if (BuildConfig.DEBUG) {
        for (device in inputs) {
            Log.d("MalloMic", "AUDIO_INPUT_DEVICE ${device.description()} rates=${device.sampleRates.contentToString()} " +
                "channels=${device.channelCounts.contentToString()} encodings=${device.encodings.contentToString()}")
        }
    }
    val recognition = MediaRecorder.AudioSource.VOICE_RECOGNITION
    val mic = MediaRecorder.AudioSource.MIC
    val standard = CaptureProfile(recognition, 16_000)
    val usb = inputs.firstOrNull { it.isUsbInput }
    if (usb == null) return listOf(standard, CaptureProfile(mic, 16_000))
    return usbCaptureProfiles(usb)
}

internal fun usbCaptureProfiles(device: AudioDeviceInfo): List<CaptureProfile> {
    val pcmRates = listOf(48_000, 16_000, 44_100)
    val advertised = pcmRates.filter { it in device.sampleRates }
    val selected = (advertised.ifEmpty { pcmRates }).take(2)
    return selected.flatMap { rate ->
        listOf(CaptureProfile(MediaRecorder.AudioSource.MIC, rate, device.id),
            CaptureProfile(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, device.id))
    }
}

internal fun audioStatus(code: Int): String {
    val name = when (code) {
        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        AudioRecord.ERROR -> "ERROR"
        else -> "UNKNOWN"
    }
    return "$name ($code)"
}

internal class AudioInputException(
    message: String,
    cause: Throwable? = null,
    val reason: VoiceRecognitionError? = null,
    val readCode: Int? = null
) :
    RuntimeException(message, cause)

internal class PcmCapture private constructor(
    val record: AudioRecord,
    val profile: CaptureProfile,
    private val bufferDetail: String
) : AutoCloseable {
    fun start(): String {
        try {
            record.startRecording()
        } catch (error: IllegalStateException) {
            throw AudioInputException("AudioRecord.startRecording failed for $profile", error)
        }
        val state = record.recordingState
        if (state != AudioRecord.RECORDSTATE_RECORDING) {
            throw AudioInputException("AudioRecord recordingState=$state for $profile")
        }
        return "$profile state=${record.state} recordingState=$state actualRate=${record.sampleRate} " +
            "channels=${record.channelCount} format=${record.audioFormat} session=${record.audioSessionId} $bufferDetail"
    }

    fun read(pcm: ShortArray, total: Int, mode: CaptureReadMode): Int {
        val count = record.read(pcm, 0, pcm.size, mode.flag)
        if (count < 0) throw AudioInputException(
            "AudioRecord.read: ${audioStatus(count)} mode=$mode totalSamples=$total $profile ${routeStatus()}",
            readCode = count
        )
        return count
    }

    fun verifyRoute() {
        val expected = profile.preferredDeviceId ?: return
        val actual = record.routedDevice
        if (actual?.id != expected) {
            throw AudioInputException("Requested input id=$expected, routed to ${actual?.description() ?: "none"}")
        }
    }

    fun routeStatus(): String {
        val configuration = if (Build.VERSION.SDK_INT >= 29) record.activeRecordingConfiguration else null
        val format = configuration?.format
        val clientFormat = configuration?.clientFormat
        return "route=${record.routedDevice?.description() ?: "none"} clientSilenced=${configuration?.isClientSilenced} " +
            "configSession=${configuration?.clientAudioSessionId} configSource=${configuration?.clientAudioSource} " +
            "configRoute=${configuration?.audioDevice?.description() ?: "unknown"} " +
            "clientFormat=${clientFormat?.sampleRate}/${clientFormat?.channelCount}/${clientFormat?.encoding} " +
            "deviceFormat=${format?.sampleRate}/${format?.channelCount}/${format?.encoding}"
    }

    fun stop() {
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        } catch (error: IllegalStateException) {
            Log.w("MalloMic", "AudioRecord.stop failed", error)
        }
    }

    override fun close() = closeWithTrace { }

    fun closeWithTrace(trace: (String) -> Unit) {
        trace("AUDIO_RECORDING_STOP_BEGIN")
        try {
            stop()
        } finally {
            trace("AUDIO_RECORDING_STOP_END")
            trace("AUDIO_RECORDING_RELEASE_BEGIN")
            record.release()
            trace("AUDIO_RECORDING_RELEASED")
        }
    }

    companion object {
        fun open(context: Context, profile: CaptureProfile): PcmCapture {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("RECORD_AUDIO permission is missing")
            }
            val channels = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minimum = AudioRecord.getMinBufferSize(profile.sampleRate, channels, encoding)
            if (minimum <= 0) throw AudioInputException("getMinBufferSize: ${audioStatus(minimum)} $profile")
            val bufferBytes = maxOf(minimum, profile.sampleRate * 128 / 1_000)
            val record = try {
                AudioRecord(profile.source, profile.sampleRate, channels, encoding, bufferBytes)
            } catch (error: IllegalArgumentException) {
                throw AudioInputException("AudioRecord rejected $profile, bufferBytes=$bufferBytes", error)
            }
            try {
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw AudioInputException("AudioRecord initialization state=${record.state} $profile")
                }
                var preferredAccepted: Boolean? = null
                profile.preferredDeviceId?.let { id ->
                    val audio = context.getSystemService(AudioManager::class.java)
                    val device = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
                        ?: throw AudioInputException("Input device id=$id is no longer connected")
                    preferredAccepted = record.setPreferredDevice(device)
                }
                val detail = "minBufferBytes=$minimum bufferBytes=$bufferBytes bufferFrames=${record.bufferSizeInFrames} " +
                    "preferredAccepted=$preferredAccepted"
                return PcmCapture(record, profile, detail)
            } catch (error: Exception) {
                record.release()
                throw error
            }
        }
    }
}
