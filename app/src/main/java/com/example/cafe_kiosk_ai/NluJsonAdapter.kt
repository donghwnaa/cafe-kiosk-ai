package com.example.cafe_kiosk_ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

fun interface FunctionCallAdapter { fun decode(json: String): NluResult }

/** Swap the wire adapter when the deployed MALLO v9 function schema is available. */
class LlmNluEngine(
    private val llm: LlmEngine,
    private val adapter: FunctionCallAdapter = NluJsonAdapter
) : NluEngine {
    override suspend fun analyze(text: String, context: NluContext): NluResult =
        adapter.decode(llm.functionCallJson(text, context))
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
        require(json.length <= 16_384)
        val reader = JsonReader(json.reader()).apply { strictness = Strictness.STRICT }
        val root = JsonParser.parseReader(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        decodeCall(root.asJsonObject, allowBatch = true)
    }.getOrElse { NluResult(NluIntent.UNKNOWN, ambiguous = "주문 내용을 확인하지 못했습니다. 다시 말씀해 주세요.") }

    private fun decodeCall(call: JsonObject, allowBatch: Boolean): NluResult {
        val intent = when (val function = call.string("function")) {
            "dialogue_response" -> when (call.string("response")) {
                "confirm" -> NluIntent.CONFIRM
                "deny" -> NluIntent.DENY
                else -> error("Invalid response")
            }
            else -> functions[function] ?: error("Invalid function")
        }
        val actions = call.get("actions")?.takeUnless { it.isJsonNull }?.asJsonArray
        val batch = if (intent == NluIntent.MULTI_ACTION) {
            require(allowBatch && actions != null && actions.size() in 2..10)
            actions.map { decodeCall(it.asJsonObject, allowBatch = false) }
        } else {
            require(actions == null || actions.isEmpty)
            emptyList()
        }
        val removeAll = call.get("remove_all")?.takeUnless { it.isJsonNull }?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean)
            it.asBoolean
        } ?: false
        return NluResult(
            intent = intent,
            menu = call.string("menu"),
            quantity = call.integer("quantity"),
            temperature = call.string("temperature")?.let(Temperature::valueOf),
            size = call.string("size")?.let(DrinkSize::valueOf),
            ambiguous = call.string("ambiguous"),
            actions = batch,
            removeAll = removeAll,
            targetId = call.string("target_id"),
            targetOrdinal = call.integer("target_index"),
            reference = call.string("reference")?.let(ItemReference::valueOf),
            targetTemperature = call.string("target_temperature")?.let(Temperature::valueOf),
            targetSize = call.string("target_size")?.let(DrinkSize::valueOf)
        )
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

    private fun encodeCall(result: NluResult): JsonObject = JsonObject().apply {
        val function = when (result.intent) {
            NluIntent.CONFIRM, NluIntent.DENY -> "dialogue_response"
            NluIntent.CHANGE -> if (result.quantity != null) "change_quantity" else "change_option"
            else -> functions.entries.first { it.value == result.intent }.key
        }
        addProperty("function", function)
        if (result.intent == NluIntent.CONFIRM) addProperty("response", "confirm")
        if (result.intent == NluIntent.DENY) addProperty("response", "deny")
        addProperty("menu", result.menu)
        addProperty("quantity", result.quantity)
        addProperty("temperature", result.temperature?.name)
        addProperty("size", result.size?.name)
        addProperty("ambiguous", result.ambiguous)
        addProperty("remove_all", result.removeAll)
        addProperty("target_id", result.targetId)
        addProperty("target_index", result.targetOrdinal)
        addProperty("reference", result.reference?.name)
        addProperty("target_temperature", result.targetTemperature?.name)
        addProperty("target_size", result.targetSize?.name)
        if (result.actions.isNotEmpty()) {
            add("actions", com.google.gson.JsonArray().apply { result.actions.forEach { add(encodeCall(it)) } })
        }
    }
}
