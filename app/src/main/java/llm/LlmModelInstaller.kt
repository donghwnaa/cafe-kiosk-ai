package com.example.cafe_kiosk_ai.llm

import android.content.Context
import android.system.Os
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal object LlmModelInstaller {
    private const val MODEL = "llama32_hf_v68.bin"
    private const val TOKENIZER = "tokenizer.json"
    private const val CONFIG = "genie_config_npu.json"
    private const val EXTENSIONS = "htp_backend_ext_config.json"
    private const val SKEL = "libQnnHtpV68Skel.so"

    data class Installed(val configuration: String, val dspDirectory: File)

    internal data class ModelIdentity(val bytes: Long, val sha256: String) {
        init {
            require(bytes > 0 && sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid Genie model identity" }
        }

        fun matches(file: File, checkActive: () -> Unit): Boolean {
            if (!file.isFile || file.length() != bytes) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> stream(input, checkActive) { buffer, count -> digest.update(buffer, 0, count) } }
            return digest.hex() == sha256
        }
    }

    @Synchronized
    fun prepare(context: Context, checkActive: () -> Unit, onModelVerified: () -> Unit): Installed {
        val directory = File(context.noBackupFilesDir, "llm").apply {
            check(isDirectory || mkdirs()) { "Cannot create LLM directory" }
        }
        val dsp = File(directory, "dsp").apply {
            check(isDirectory || mkdirs()) { "Cannot create DSP directory" }
        }
        val model = File(directory, MODEL)
        val manifest = context.assets.open("llm/npu_manifest.json").bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        val identity = ModelIdentity(manifest.get("context_bytes").asLong, manifest.get("context_sha256").asString)
        val reused = identity.matches(model, checkActive)

        if (!reused) {
            val openModel: () -> InputStream = { context.assets.open("llm/$MODEL") }
            check(directory.usableSpace >= identity.bytes + 64L * 1024L * 1024L) { "Insufficient space for Genie model" }
            copyFile(model, checkActive, identity, openModel)
        }

        Log.i(GENIE_TAG, "MODEL_FILE_VERIFIED bytes=${identity.bytes} cached=$reused")
        onModelVerified()
        copyAsset(context, TOKENIZER, File(directory, TOKENIZER), checkActive)
        copyAsset(context, EXTENSIONS, File(directory, EXTENSIONS), checkActive)
        copyAsset(context, SKEL, File(dsp, SKEL), checkActive)

        val configuration = context.assets.open("llm/$CONFIG").bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        resolveConfiguration(configuration, directory)
        val dialog = configuration.getAsJsonObject("dialog")
        Log.i(GENIE_TAG, "Config QnnHtp soc=93 dsp=v68 ctx=${dialog.getAsJsonObject("context").get("size")} " +
                "maxTokens=${dialog.get("max-num-tokens")} model=$MODEL tokenizer=$TOKENIZER extensions=$EXTENSIONS")
        return Installed(configuration.toString(), dsp)
    }

    internal fun resolveConfiguration(configuration: JsonObject, directory: File) {
        val dialog = configuration.getAsJsonObject("dialog")
        val engine = dialog.getAsJsonObject("engine")
        val tokenizer = dialog.getAsJsonObject("tokenizer")
        check(tokenizer.get("path").asString == TOKENIZER)
        tokenizer.addProperty("path", File(directory, TOKENIZER).absolutePath)
        val backend = engine.getAsJsonObject("backend")
        check(backend.get("type").asString == "QnnHtp" && backend.get("extensions").asString == EXTENSIONS)
        backend.addProperty("extensions", File(directory, EXTENSIONS).absolutePath)
        val bins = engine.getAsJsonObject("model").getAsJsonObject("binary").getAsJsonArray("ctx-bins")
        check(bins.size() == 1 && bins[0].asString == MODEL)
        bins.set(0, com.google.gson.JsonPrimitive(File(directory, MODEL).absolutePath))
    }

    private fun copyAsset(
        context: Context,
        name: String,
        output: File,
        checkActive: () -> Unit
    ) = copyFile(output, checkActive) { context.assets.open("llm/$name") }

    private fun copyFile(
        output: File,
        checkActive: () -> Unit,
        identity: ModelIdentity? = null,
        open: () -> InputStream
    ) {
        val temporary = File(output.parentFile, "${output.name}.part")
        val digest = identity?.let { MessageDigest.getInstance("SHA-256") }
        try {
            open().use { input ->
                FileOutputStream(temporary).use { destination ->
                    stream(input, checkActive) { buffer, bytes ->
                        destination.write(buffer, 0, bytes)
                        digest?.update(buffer, 0, bytes)
                    }
                    destination.fd.sync()
                }
            }
            check(temporary.length() > 0) { "Empty Genie file ${output.name}" }
            if (identity != null && digest != null) {
                check(temporary.length() == identity.bytes && digest.hex() == identity.sha256) { "Genie model checksum mismatch" }
            }
            checkActive()
            Os.rename(temporary.absolutePath, output.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    private inline fun stream(input: InputStream, checkActive: () -> Unit, consume: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) return
            consume(buffer, count)
        }
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}