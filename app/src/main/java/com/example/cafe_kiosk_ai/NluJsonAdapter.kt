package com.example.cafe_kiosk_ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

fun interface FunctionCallAdapter {
    fun decode(json: String): NluResult
    fun decode(json: String, context: NluContext?): NluResult = decode(json)
}

/** Maps a model function call to the existing order dialogue. */
class LlmNluEngine(
    private val llm: LlmEngine,
    private val adapter: FunctionCallAdapter = NluJsonAdapter
) : NluEngine {
    private val affirmative = Regex("(?:네|예|응)(?:맞아요|맞습니다)?|맞아요|맞습니다")

    override suspend fun analyze(text: String, context: NluContext): NluResult {
        val question = context.question
        if (question?.kind == PendingQuestion.ITEM_CONFIRMATION && !question.paused) {
            val answer = text.filter { it.isLetterOrDigit() }
            if (affirmative.matches(answer)) return NluResult(NluIntent.CONFIRM)
        }
        return adapter.decode(llm.functionCallJson(text, context), context)
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

    override fun decode(json: String): NluResult = decode(json, null)

    override fun decode(json: String, context: NluContext?): NluResult = runCatching {
        require(json.length <= 16_384)
        val reader = JsonReader(json.reader()).apply { strictness = Strictness.STRICT }
        val root = JsonParser.parseReader(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        val call = root.asJsonObject
        if (call.has("parameters")) decodeV9(call, context, allowBatch = true)
        else decodeCall(call, allowBatch = true)
    }.getOrElse { NluResult(NluIntent.UNKNOWN, ambiguous = "주문 내용을 확인하지 못했습니다. 다시 말씀해 주세요.") }

    private fun decodeV9(call: JsonObject, context: NluContext?, allowBatch: Boolean): NluResult {
        val parameters = call.getAsJsonObject("parameters")
        val function = call.string("function")
        val menu = parameters.string("menu_name")
        when (function) {
            "multi_action" -> {
                require(allowBatch)
                val actions = parameters.getAsJsonArray("actions")
                require(actions.size() in 2..10)
                val expanded = actions.flatMap {
                    val action = decodeV9(it.asJsonObject, context, allowBatch = false)
                    if (action.intent == NluIntent.MULTI_ACTION) action.actions else listOf(action)
                }
                require(expanded.size <= 10)
                return NluResult(NluIntent.MULTI_ACTION, actions = expanded)
            }
            "add_menu" -> {
                val items = parameters.getAsJsonArray("items")
                require(items.size() in 1..10)
                val orders = items.map {
                    val item = it.asJsonObject
                    require(!item.string("menu_name").isNullOrBlank())
                    item.v9Options(NluIntent.ADD)
                }
                return orders.singleOrNull() ?: NluResult(NluIntent.MULTI_ACTION, actions = orders)
            }
            "dialogue_response" -> return NluResult(when (parameters.string("value")) {
                "YES" -> NluIntent.CONFIRM
                "NO" -> NluIntent.DENY
                "KEEP_CURRENT" -> NluIntent.KEEP
                else -> error("Unknown dialogue response")
            })
            "cancel_order" -> return NluResult(NluIntent.CANCEL)
            "change_quantity" -> {
                val delta = parameters.integer("delta")
                if (delta == null) {
                    require(parameters.integer("quantity") != null)
                    return parameters.v9Options(NluIntent.CHANGE)
                }
                require(delta in -99..99 && delta != 0 && !parameters.has("quantity"))
                val draft = context?.drafts?.filter { it.menu.name == menu }?.singleOrNull()
                if (draft == null && delta < 0) return NluResult(NluIntent.REMOVE, menu, -delta)
                val quantity = draft?.quantity ?: context?.cart?.singleOrNull { it.menu.name == menu }?.quantity
                    ?: return NluResult(NluIntent.UNKNOWN, ambiguous = "어떤 메뉴의 수량을 바꿀까요?")
                return NluResult(NluIntent.CHANGE, menu, quantity + delta)
            }
            "change_option" -> {
                require(!parameters.has("split_items"))
                return parameters.v9Options(NluIntent.CHANGE)
            }
            "remove_menu" -> return NluResult(NluIntent.REMOVE, menu = menu, removeAll = true)
            "request_clarification" -> {
                val options = parameters.v9Options(NluIntent.ADD)
                val pendingDraft = context?.question?.kind in setOf(
                    PendingQuestion.QUANTITY, PendingQuestion.TEMPERATURE, PendingQuestion.SIZE
                )
                if (pendingDraft && parameters.string("reason") == "MISSING_TARGET_MENU" &&
                    (options.quantity != null || options.temperature != null || options.size != null)) return options
                return NluResult(NluIntent.UNKNOWN, ambiguous = "메뉴 이름과 원하시는 옵션을 다시 말씀해 주세요.")
            }
            "confirm_order" -> return NluResult(NluIntent.PAY)
            "show_cart" -> return NluResult(NluIntent.SHOW_CART)
            "menu_query" -> return NluResult(NluIntent.SHOW_MENU)
            "help_request" -> return NluResult(NluIntent.HELP)
            else -> error("Invalid MALLO function")
        }
    }

    private fun JsonObject.v9Options(intent: NluIntent) = NluResult(
        intent = intent, menu = string("menu_name"), quantity = integer("quantity"),
        temperature = string("temperature")?.let(Temperature::valueOf),
        size = string("size")?.let(DrinkSize::valueOf)
    )
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
