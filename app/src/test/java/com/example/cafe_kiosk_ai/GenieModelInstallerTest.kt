package com.example.cafe_kiosk_ai

import com.example.cafe_kiosk_ai.llm.LlmModelInstaller
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GenieModelInstallerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun cachedModelMustMatchUpdatedManifestEvenWhenByteCountIsUnchanged() {
        val destination = folder.newFile("model.bin")
        destination.writeText("version1")
        val original = identity("version1")
        val updated = identity("version2")
        assertTrue(original.matches(destination) {})
        assertFalse(updated.matches(destination) {})
        destination.writeText("version2")
        assertTrue(updated.matches(destination) {})
        destination.writeText("corrupt!")
        assertFalse(updated.matches(destination) {})
    }

    @Test fun bundledConfigurationUsesOwnedAbsolutePathsAndPreservesRuntimeSettings() {
        val asset = File("src/main/assets/llm/genie_config_npu.json")
        val config = asset.reader().use { JsonParser.parseReader(it).asJsonObject }
        val directory = folder.newFolder("no_backup", "llm")
        LlmModelInstaller.resolveConfiguration(config, directory)
        val dialog = config.getAsJsonObject("dialog")
        val backend = dialog.getAsJsonObject("engine").getAsJsonObject("backend")
        val model = dialog.getAsJsonObject("engine").getAsJsonObject("model")
        assertEquals(2048, dialog.getAsJsonObject("context").get("size").asInt)
        assertEquals("QnnHtp", backend.get("type").asString)
        assertEquals(File(directory, "tokenizer.json").absolutePath, dialog.getAsJsonObject("tokenizer").get("path").asString)
        assertEquals(File(directory, "htp_backend_ext_config.json").absolutePath, backend.get("extensions").asString)
        assertEquals(File(directory, "llama32_hf_v68.bin").absolutePath,
            model.getAsJsonObject("binary").getAsJsonArray("ctx-bins")[0].asString)
    }

    private fun identity(contents: String): LlmModelInstaller.ModelIdentity {
        val bytes = contents.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return LlmModelInstaller.ModelIdentity(bytes.size.toLong(), sha)
    }
}
