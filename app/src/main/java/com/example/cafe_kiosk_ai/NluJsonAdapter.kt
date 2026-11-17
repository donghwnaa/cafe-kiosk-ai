package com.example.cafe_kiosk_ai

import android.util.Log
import com.example.cafe_kiosk_ai.llm.GENIE_TAG
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.util.UUID

fun interface FunctionCallAdapter { fun decode(json: String): NluResult }

class LlmNluEngine(
    private val llm: LlmEngine,
    private val adapter: FunctionCallAdapter = NluJsonAdapter
) : NluEngine {
    override suspend fun analyze(text: String, context: NluContext): NluResult {
        val result = adapter.decode(llm.functionCallJson(text, context))
        val response = text.filterNot(Char::isWhitespace).trimEnd('.', '!', '?', '。', '！', '？')
        val waitingForConsent = context.question?.kind in setOf(PendingQuestion.ITEM_CONFIRMATION, PendingQuestion.CANCEL_ALL)
        if (waitingForConsent && response in refusals) return NluResult(NluIntent.DENY)
        if ((result.intent == NluIntent.CONFIRM && response !in affirmations) ||
            (result.intent == NluIntent.PAY && response !in paymentRequests)) {
            return NluResult(NluIntent.UNKNOWN, ambiguous = "확인하려면 ‘맞아요’, 결제하려면 ‘계산해 주세요’라고 말씀해 주세요.")
        }
        return result
    }

    private companion object {
        val affirmations = setOf("네", "예", "맞아요", "맞습니다", "네맞아요", "네맞습니다", "예맞아요", "예맞습니다",
            "네,맞아요", "네,맞습니다", "예,맞아요", "예,맞습니다")
        val refusals = setOf("아니", "아니요", "아니에요", "아닙니다")
        val paymentRequests = setOf("결제", "결제할게", "결제할게요", "결제해주세요", "결제해줘", "계산",
            "계산할게", "계산할게요", "계산해주세요", "계산해줘", "결제할래요", "계산할래요")
    }
}

object NluJsonAdapter : FunctionCallAdapter {
    private val functions = mapOf(
        "add_menu" to NluIntent.ADD, "add_more" to NluIntent.ADD_MORE,
        "remove_menu" to NluIntent.REMOVE, "change_option" to NluIntent.CHANGE,
        "change_quantity" to NluIntent.CHANGE, "multi_action" to NluIntent.MULTI_ACTION,
        "menu_query" to NluIntent.SHOW_MENU, "menu_price" to NluIntent.MENU_PRICE,
        "show_cart" to NluIntent.SHOW_CART, "confirm_order" to NluIntent.PAY,
        "cancel_order" to NluIntent.CANCEL, "cancel_all" to NluIntent.CANCEL_ALL,
        "help_request" to NluIntent.HELP, "keep_order" to NluIntent.KEEP,
        "request_clarification" to NluIntent.UNKNOWN
    )

    override fun decode(json: String): NluResult = runCatching {
        decodeCall(parse(json), allowBatch = true)
    }.getOrElse { invalidResult() }

    internal fun decodeGenie(json: String): NluResult = decodeGenie(json) {}

    internal fun decodeGenie(json: String, onValidation: (Boolean) -> Unit): NluResult {
        val result = try {
            decodeGenieOrThrow(json)
        } catch (_: Exception) {
            if (BuildConfig.DEBUG) runCatching { Log.w(GENIE_TAG, "JSON_VALIDATION_REJECTED") }
            onValidation(false)
            return invalidResult()
        }
        onValidation(true)
        if (BuildConfig.DEBUG) runCatching {
            Log.d(GENIE_TAG, "JSON_VALIDATION_PASS intent=${result.intent} actions=${result.actions.size}")
            if (Log.isLoggable("MALLO/GenieJson", Log.DEBUG)) {
                Log.d("MALLO/GenieJson", encodeCall(result, redactIds = true).toString())
            }
        }
        return result
    }

    internal fun decodeGenieOrThrow(json: String): NluResult =
        decodeCall(parse(json), allowBatch = true, validateModel = true)

    private fun invalidResult() =
        NluResult(NluIntent.UNKNOWN, ambiguous = "주문 내용을 확인하지 못했습니다. 다시 말씀해 주세요.")

    private fun parse(json: String): JsonObject {
        require(json.length <= 16_384)
        val reader = JsonReader(json.reader()).apply { strictness = Strictness.STRICT }
        val root = readCall(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        return root
    }

    private fun readCall(reader: JsonReader, allowBatch: Boolean = true): JsonObject = JsonObject().apply {
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(!has(name)) { "Duplicate field" }
            if (name == "actions" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                require(allowBatch)
                val calls = com.google.gson.JsonArray()
                reader.beginArray()
                while (reader.hasNext()) {
                    require(calls.size() < 10)
                    calls.add(readCall(reader, allowBatch = false))
                }
                reader.endArray()
                add(name, calls)
            } else {
                require(reader.peek() in setOf(JsonToken.STRING, JsonToken.NUMBER, JsonToken.BOOLEAN, JsonToken.NULL))
                add(name, JsonParser.parseReader(reader))
            }
        }
        reader.endObject()
    }

    private fun decodeCall(call: JsonObject, allowBatch: Boolean, validateModel: Boolean = false): NluResult {
        val name = call.string("function")
        val intent = when (name) {
            "dialogue_response" -> when (call.string("response")) {
                "confirm" -> NluIntent.CONFIRM
                "deny" -> NluIntent.DENY
                else -> error("Invalid response")
            }
            else -> functions[name] ?: error("Invalid function")
        }
        val actions = call.get("actions")?.takeUnless { it.isJsonNull }?.asJsonArray
        val batch = if (intent == NluIntent.MULTI_ACTION) {
            require(allowBatch && actions != null && actions.size() in 2..10)
            actions.map { decodeCall(it.asJsonObject, allowBatch = false, validateModel = validateModel) }
        } else {
            require(actions == null || actions.isEmpty)
            emptyList()
        }
        val removeAll = call.get("remove_all")?.takeUnless { it.isJsonNull }?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean)
            it.asBoolean
        } ?: false
        val result = NluResult(
            intent = intent,
            menu = call.string("menu"),
            quantity = call.integer("quantity"),
            temperature = call.string("temperature")?.let(Temperature::valueOf),
            size = call.string("size")?.let(DrinkSize::valueOf),
            ambiguous = if (validateModel && intent == NluIntent.UNKNOWN) invalidResult().ambiguous else call.string("ambiguous"),
            actions = batch,
            removeAll = removeAll,
            targetId = call.string("target_id"),
            targetOrdinal = call.integer("target_index"),
            reference = call.string("reference")?.let(ItemReference::valueOf),
            targetTemperature = call.string("target_temperature")?.let(Temperature::valueOf),
            targetSize = call.string("target_size")?.let(DrinkSize::valueOf),
            quantityDelta = call.integer("delta")
        )
        if (validateModel) validateGenieCall(call, checkNotNull(name), result, allowBatch)
        return result
    }

    private val selectors = setOf("menu", "target_id", "target_index", "reference", "target_temperature", "target_size")

    private fun validateGenieCall(call: JsonObject, name: String, result: NluResult, allowBatch: Boolean) {
        val allowed = when (name) {
            "add_menu" -> selectors + setOf("quantity", "temperature", "size")
            "remove_menu" -> selectors + setOf("quantity", "remove_all")
            "change_quantity" -> selectors + setOf("quantity", "delta")
            "change_option" -> selectors + setOf("temperature", "size")
            "cancel_order" -> selectors
            "menu_price" -> setOf("menu")
            "dialogue_response" -> setOf("response")
            "multi_action" -> setOf("actions")
            else -> emptySet()
        }
        require(call.keySet().all { it == "function" || it in allowed })
        require(result.menu == null || MenuCatalog.find(result.menu) != null)
        require(result.quantity == null || result.quantity in 1..99)
        require(result.targetOrdinal == null || result.targetOrdinal > 0)
        result.targetId?.let { require(UUID.fromString(it).toString() == it) }
        require(!result.removeAll || result.quantity == null)
        val targeted = result.menu != null || result.targetOrdinal != null || result.targetId != null ||
                result.reference == ItemReference.LAST_CONFIRMED
        when (name) {
            "add_menu" -> require(if (allowBatch) targeted || result.quantity != null ||
                    result.temperature != null || result.size != null else result.menu != null)
            "remove_menu" -> require(targeted && (result.quantity != null || result.removeAll))
            "change_quantity" -> {
                require((result.quantity == null) != (result.quantityDelta == null))
                result.quantityDelta?.let { require(it != 0 && it in -98..98) }
            }
            "change_option" -> require(result.temperature != null || result.size != null)
            "menu_price" -> require(result.menu != null)
            "multi_action" -> require(result.actions.distinct().size == result.actions.size)
        }
        if (!allowBatch) require(targeted && result.intent in setOf(NluIntent.ADD, NluIntent.CHANGE, NluIntent.REMOVE))
        MenuCatalog.find(result.menu)?.let { require(OrderValidator().validateOptions(it, result) == null) }
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
        it.asString
    }

    private fun JsonObject.integer(key: String): Int? = get(key)?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
        it.asString.toInt()
    }

    fun toDebugJson(result: NluResult): String = encodeCall(result).toString()

    private fun encodeCall(result: NluResult, redactIds: Boolean = false): JsonObject = JsonObject().apply {
        val function = when (result.intent) {
            NluIntent.CONFIRM, NluIntent.DENY -> "dialogue_response"
            NluIntent.CHANGE -> if (result.quantity != null || result.quantityDelta != null) "change_quantity" else "change_option"
            else -> functions.entries.first { it.value == result.intent }.key
        }
        addProperty("function", function)
        if (result.intent == NluIntent.CONFIRM) addProperty("response", "confirm")
        if (result.intent == NluIntent.DENY) addProperty("response", "deny")
        addProperty("menu", result.menu)
        addProperty("quantity", result.quantity)
        result.quantityDelta?.let { addProperty("delta", it) }
        addProperty("temperature", result.temperature?.name)
        addProperty("size", result.size?.name)
        addProperty("ambiguous", result.ambiguous)
        addProperty("remove_all", result.removeAll)
        addProperty("target_id", if (redactIds) result.targetId?.let { "[redacted]" } else result.targetId)
        addProperty("target_index", result.targetOrdinal)
        addProperty("reference", result.reference?.name)
        addProperty("target_temperature", result.targetTemperature?.name)
        addProperty("target_size", result.targetSize?.name)
        if (result.actions.isNotEmpty()) {
            add("actions", com.google.gson.JsonArray().apply { result.actions.forEach { add(encodeCall(it, redactIds)) } })
        }
    }
}
