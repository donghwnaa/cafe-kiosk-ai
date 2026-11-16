package com.example.usbaudioprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs AudioRecord directly, in a package with no MALLO or speech engine dependencies. */
public final class ProbeActivity extends Activity {
    private static final String TAG = "UsbAudioProbe";
    private static final int SECONDS = 5;
    private static final int[] RATES = {48000, 44100};
    private static final int[] SOURCES = {MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION};

    private final ExecutorService captureWorker = Executors.newSingleThreadExecutor();
    private volatile int generation;
    private Spinner ratePicker, sourcePicker, readPicker, routingPicker;
    private TextView status;
    private Button start;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        controls.setPadding(padding, padding * 3, padding, padding);
        ratePicker = picker(controls, "48000 Hz", "44100 Hz");
        sourcePicker = picker(controls, "MIC", "VOICE_RECOGNITION");
        readPicker = picker(controls, "READ_NON_BLOCKING", "READ_BLOCKING");
        routingPicker = picker(controls, "명시적 USB 입력", "시스템 경로 확인");
        start = new Button(this);
        start.setText("5초 녹음");
        start.setOnClickListener(view -> withPermission());
        controls.addView(start);
        status = new TextView(this);
        status.setText("마이크에 가까이 말하고 녹음 결과를 확인하세요.");
        status.setTextIsSelectable(true);
        controls.addView(status);
        ScrollView screen = new ScrollView(this);
        screen.addView(controls);
        setContentView(screen);

        Intent intent = getIntent();
        if (intent.getIntExtra("rate", 48000) == 44100) ratePicker.setSelection(1);
        if (intent.getIntExtra("source", SOURCES[0]) == SOURCES[1]) sourcePicker.setSelection(1);
        if (intent.getBooleanExtra("blocking", false)) readPicker.setSelection(1);
        if (intent.getBooleanExtra("system_route", false)) routingPicker.setSelection(1);
        if (state == null && intent.getBooleanExtra("run", false)) status.post(this::withPermission);
    }

    private Spinner picker(LinearLayout controls, String... labels) {
        Spinner picker = new Spinner(this);
        picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        controls.addView(picker);
        return picker;
    }

    private void withPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runProbe();
        } else {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 1) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) runProbe();
        else status.setText("녹음 권한이 거부되었습니다.");
    }

    @Override protected void onStop() {
        generation++;
        start.setEnabled(true);
        super.onStop();
    }

    @Override protected void onDestroy() {
        captureWorker.shutdown();
        super.onDestroy();
    }

    private void runProbe() {
        if (!start.isEnabled()) return;
        int id = ++generation;
        int rate = RATES[ratePicker.getSelectedItemPosition()];
        int source = SOURCES[sourcePicker.getSelectedItemPosition()];
        boolean blocking = readPicker.getSelectedItemPosition() == 1;
        boolean preferUsb = routingPicker.getSelectedItemPosition() == 0;
        int usbCard = getIntent().getIntExtra("usb_card", -1);
        int usbDevice = getIntent().getIntExtra("usb_device", -1);
        if ((usbCard < 0) != (usbDevice < 0)) {
            report(id, "PROBE_ERROR usb_card와 usb_device를 함께 지정하세요.");
            return;
        }
        String usbAddress = preferUsb && usbCard >= 0 ? "card=" + usbCard + ";device=" + usbDevice : null;
        start.setEnabled(false);
        status.setText("AudioRecord를 여는 중…");
        captureWorker.execute(() -> {
            if (id != generation) return;
            File wav = new File(getCacheDir(), "usb-capture.wav");
            try {
                if (wav.exists() && !wav.delete()) throw new IOException("이전 WAV를 지우지 못했습니다.");
                Result result = capture(id, rate, source, blocking, preferUsb, usbAddress);
                if (id == generation && result.error == AudioRecord.ERROR_DEAD_OBJECT) {
                    Log.w(TAG, "PROBE_RECREATE reason=ERROR_DEAD_OBJECT");
                    result = capture(id, rate, source, blocking, preferUsb, usbAddress);
                }
                if (id != generation) return;
                if (result.samples > 0) result.writeWave(wav, rate);
                String outcome = result.routeLoss != null ? "ROUTE_LOST" :
                    result.error == AudioRecord.ERROR_DEAD_OBJECT ? "DEAD_OBJECT" :
                    result.error < 0 ? "READ_ERROR" : result.samples == 0 ?
                    (result.deadlineStop ? "READ_TIMEOUT" : "NO_FRAMES") :
                    (result.nonZero == 0 ? "SILENT_PCM" : "PCM_RECEIVED");
                report(id, "PROBE_RESULT status=" + outcome + " capturedMs=" + (result.samples * 1000L / rate) +
                    " requestedMs=" + (SECONDS * 1000) + " " + result.summary() +
                    (result.samples > 0 ? " wav=" + wav.getAbsolutePath() : ""));
            } catch (RuntimeException | IOException error) {
                report(id, "PROBE_ERROR " + error);
            }
        });
    }

    private Result capture(int id, int rate, int source, boolean blocking, boolean preferUsb, String usbAddress) {
        AudioManager manager = getSystemService(AudioManager.class);
        if (usbAddress != null && Build.VERSION.SDK_INT < 28) {
            throw new IllegalStateException("USB 주소 확인에는 Android 9 이상이 필요합니다.");
        }
        AudioDeviceInfo usb = null;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.getType() != AudioDeviceInfo.TYPE_USB_HEADSET &&
                device.getType() != AudioDeviceInfo.TYPE_USB_DEVICE) continue;
            Log.i(TAG, "PROBE_USB_INPUT " + describe(device) + " rates=" + Arrays.toString(device.getSampleRates()));
            if (usb == null && (usbAddress == null || usbAddress.equals(device.getAddress()) ||
                (usbAddress + ";").equals(device.getAddress()))) usb = device;
        }
        if (preferUsb && usb == null) {
            throw new IllegalStateException("USB 입력 장치가 없습니다. address=" + usbAddress);
        }
        int channel = AudioFormat.CHANNEL_IN_MONO;
        int format = AudioFormat.ENCODING_PCM_16BIT;
        int minBytes = AudioRecord.getMinBufferSize(rate, channel, format);
        if (minBytes <= 0) throw new IllegalStateException("getMinBufferSize=" + code(minBytes));
        int bufferBytes = Math.max(minBytes, rate / 5 * 2);
        AudioRecord recorder = new AudioRecord.Builder().setAudioSource(source)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(channel).setEncoding(format).build())
            .setBufferSizeInBytes(bufferBytes).build();
        try {
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord state=" + recorder.getState());
            }
            boolean accepted = preferUsb && recorder.setPreferredDevice(usb);
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord recordingState=" + recorder.getRecordingState());
            }
            Log.i(TAG, "PROBE_STARTED pid=" + android.os.Process.myPid() + " session=" + recorder.getAudioSessionId() +
                " source=" + source + " rate=" + recorder.getSampleRate() + " mode=" +
                (blocking ? "READ_BLOCKING" : "READ_NON_BLOCKING") + " bufferBytes=" + bufferBytes +
                " preferred=" + describe(preferUsb ? usb : null) + " preferredAccepted=" + accepted + " " + route(recorder));
            return read(id, recorder, blocking, preferUsb ? usb : null);
        } finally {
            try {
                stop(recorder, "captureWorker");
            } finally {
                long started = SystemClock.elapsedRealtime();
                Log.i(TAG, "PROBE_RELEASE_BEGIN session=" + recorder.getAudioSessionId());
                recorder.release();
                Log.i(TAG, "PROBE_RELEASED elapsedMs=" + (SystemClock.elapsedRealtime() - started));
            }
        }
    }

    private Result read(int id, AudioRecord recorder, boolean blocking, AudioDeviceInfo expected) {
        int rate = recorder.getSampleRate();
        Result result = new Result(rate * SECONDS);
        result.route = route(recorder);
        short[] buffer = new short[rate / 50]; // Compare both modes with the same 20 ms request.
        int mode = blocking ? AudioRecord.READ_BLOCKING : AudioRecord.READ_NON_BLOCKING;
        long started = SystemClock.elapsedRealtime();
        long deadline = started + SECONDS * 1000L;
        AudioDeviceInfo initialRoute = recorder.getRoutedDevice();
        boolean routeVerified = expected == null || matchesDevice(initialRoute, expected);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean stopRequested = new AtomicBoolean();
        Thread stopper = null;
        if (blocking) {
            stopper = new Thread(() -> {
                while (!finished.get() && generation == id && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10);
                if (!finished.get()) {
                    stopRequested.set(true);
                    Log.i(TAG, "PROBE_STOP cause=" + (generation == id ? "READ_DEADLINE" : "ACTIVITY_STOPPED"));
                    stop(recorder, "watchdog");
                }
            }, "UsbProbeStop");
            stopper.start();
        }
        long nextLog = 0;
        try {
            while (generation == id && result.samples < result.pcm.length && SystemClock.elapsedRealtime() < deadline) {
                int count = recorder.read(buffer, 0, buffer.length, mode);
                if (result.readCalls == 0) {
                    Log.i(TAG, "PROBE_FIRST_READ_RETURNED read=" + code(count) +
                        " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
                }
                result.lastRead = count;
                result.readCalls++;
                if (count == 0) result.zeroReads++;
                boolean stopping = stopRequested.get();
                if (generation != id) break;
                if (count < 0) {
                    if (stopping) break;
                    result.error = count;
                    Log.w(TAG, "PROBE_READ_ERROR code=" + code(count) + " " + route(recorder));
                    break;
                }
                long now = SystemClock.elapsedRealtime();
                if (!stopping && expected != null && (count > 0 || (routeVerified && now >= nextLog))) {
                    AudioDeviceInfo actual = recorder.getRoutedDevice();
                    if (!matchesDevice(actual, expected)) {
                        result.routeLoss = "expected=" + describe(expected) + " actual=" + describe(actual) +
                            " excludedRead=" + count;
                        Log.w(TAG, "PROBE_ROUTE_LOST " + result.routeLoss);
                        break;
                    }
                    routeVerified = true;
                }
                if (count > 0) {
                    // A blocking read interrupted by stop can still return PCM copied before the interruption.
                    if (!routeVerified) {
                        result.routeLoss = "USB route unverified at stop excludedRead=" + count;
                        break;
                    }
                    result.add(buffer, Math.min(count, result.pcm.length - result.samples), now - started);
                }
                if (now >= nextLog || (count > 0 && result.positiveReads == 1)) {
                    if (!stopping) result.route = route(recorder);
                    Log.i(TAG, "PROBE_READ " + result.summary());
                    nextLog = now + 500;
                }
                if (stopping) break;
                if (count == 0) SystemClock.sleep(10);
            }
        } finally {
            finished.set(true);
            if (stopper != null) {
                // Keep release ordered after both the read and the native stop. Never wait on the UI thread.
                boolean interrupted = false;
                while (stopper.isAlive()) {
                    try { stopper.join(); } catch (InterruptedException error) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        result.deadlineStop = stopRequested.get();
        if (!result.deadlineStop && result.routeLoss == null) result.route = route(recorder);
        return result;
    }

    private static boolean matchesDevice(AudioDeviceInfo actual, AudioDeviceInfo expected) {
        return actual != null && actual.getId() == expected.getId();
    }

    private static void stop(AudioRecord recorder, String caller) {
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) return;
        long started = SystemClock.elapsedRealtime();
        Log.i(TAG, "PROBE_STOP_BEGIN caller=" + caller);
        try {
            recorder.stop();
        } catch (IllegalStateException error) {
            Log.w(TAG, "PROBE_STOP_ERROR", error);
        } finally {
            Log.i(TAG, "PROBE_STOP_END caller=" + caller + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
        }
    }

    private static String route(AudioRecord recorder) {
        AudioRecordingConfiguration config = Build.VERSION.SDK_INT >= 29 ? recorder.getActiveRecordingConfiguration() : null;
        AudioTimestamp timestamp = new AudioTimestamp();
        int timestampStatus = recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC);
        return "route=" + describe(recorder.getRoutedDevice()) + " state=" + recorder.getState() +
            " recordingState=" + recorder.getRecordingState() + " clientSilenced=" +
            (config == null ? "unknown" : config.isClientSilenced()) +
            " configSession=" + (config == null ? "unknown" : config.getClientAudioSessionId()) +
            " configSource=" + (config == null ? "unknown" : config.getClientAudioSource()) +
            " configRoute=" + (config == null ? "unknown" : describe(config.getAudioDevice())) +
            " clientFormat=" + (config == null ? "unknown" : format(config.getClientFormat())) +
            " deviceFormat=" + (config == null ? "unknown" : format(config.getFormat())) +
            " timestampStatus=" + timestampStatus +
            " framePosition=" + (timestampStatus == AudioRecord.SUCCESS ? timestamp.framePosition : "n/a");
    }

    private static String format(AudioFormat format) {
        return format.getSampleRate() + "Hz/" + format.getChannelCount() + "ch/" + format.getEncoding();
    }

    private static String describe(AudioDeviceInfo device) {
        return device == null ? "none" : "id=" + device.getId() + ",type=" + device.getType() +
            ",name=" + device.getProductName() +
            (Build.VERSION.SDK_INT >= 28 ? ",address=" + device.getAddress() : "");
    }

    private static String code(int code) {
        switch (code) {
            case AudioRecord.ERROR_DEAD_OBJECT: return "ERROR_DEAD_OBJECT(-6)";
            case AudioRecord.ERROR_INVALID_OPERATION: return "ERROR_INVALID_OPERATION(-3)";
            case AudioRecord.ERROR_BAD_VALUE: return "ERROR_BAD_VALUE(-2)";
            case AudioRecord.ERROR: return "ERROR(-1)";
            default: return Integer.toString(code);
        }
    }

    private void report(int id, String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> {
            if (id == generation) {
                status.setText(message);
                start.setEnabled(true);
            }
        });
    }

    private static final class Result {
        final short[] pcm;
        int samples, nonZero, peak, readCalls, zeroReads, lastRead, error;
        int positiveReads, zeroOnlyReads;
        int chunkSamples, chunkPeak, chunkNonZero;
        long sumSquares;
        long chunkSquares;
        long firstPcmMs = -1, lastPcmMs = -1;
        boolean deadlineStop;
        String route = "route=unavailable";
        String routeLoss;

        Result(int capacity) { pcm = new short[capacity]; }

        void add(short[] chunk, int count, long elapsedMs) {
            System.arraycopy(chunk, 0, pcm, samples, count);
            chunkSamples = count;
            chunkPeak = 0;
            chunkNonZero = 0;
            chunkSquares = 0;
            for (int i = 0; i < count; i++) {
                int value = chunk[i];
                if (value != 0) chunkNonZero++;
                chunkPeak = Math.max(chunkPeak, Math.abs(value));
                chunkSquares += (long) value * value;
            }
            if (positiveReads++ == 0) firstPcmMs = elapsedMs;
            lastPcmMs = elapsedMs;
            if (chunkNonZero == 0) zeroOnlyReads++;
            nonZero += chunkNonZero;
            peak = Math.max(peak, chunkPeak);
            sumSquares += chunkSquares;
            samples += count;
        }

        String summary() {
            String current = lastRead > 0
                ? " chunkSamples=" + chunkSamples + " chunkRms=" + rms(chunkSquares, chunkSamples) +
                    " chunkPeak=" + chunkPeak + " chunkNonZero=" + chunkNonZero
                : "";
            return "read=" + code(lastRead) + current + " calls=" + readCalls + " zeroReads=" + zeroReads +
                " positiveReads=" + positiveReads + " zeroOnlyReads=" + zeroOnlyReads + " samples=" + samples +
                " rms=" + rms(sumSquares, samples) + " peak=" + peak + " nonZero=" + nonZero +
                " firstPcmMs=" + firstPcmMs + " lastPcmMs=" + lastPcmMs + " deadlineStop=" + deadlineStop +
                " " + route + (routeLoss == null ? "" : " routeLoss={" + routeLoss + "}");
        }

        private static String rms(long squares, int count) {
            return count == 0 ? "n/a" : Double.toString(Math.sqrt((double) squares / count) / 32768);
        }

        void writeWave(File file, int rate) throws IOException {
            int bytes = samples * 2;
            ByteBuffer wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
            wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(bytes + 36)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
                .putShort((short) 2).putShort((short) 16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
            for (int i = 0; i < samples; i++) wav.putShort(pcm[i]);
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(wav.array()); }
        }
    }
}
