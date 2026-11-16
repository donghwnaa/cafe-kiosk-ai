package com.example.cafe_kiosk_ai.llm

import android.content.res.AssetManager
import com.example.cafe_kiosk_ai.NluContext
import com.google.gson.Gson
import com.google.gson.JsonParser

internal class GeniePrompt(private val prefix: String, private val suffix: String) {
    fun format(text: String, context: NluContext): String {
        val state = buildString {
            append("state=${context.state} question=${context.question?.kind ?: "NONE"}")
            append("\nQUESTION_TARGET marks the items asked about in the current question.")
            append("\nDraft (target_index,menu,quantity,temperature,size):")
            context.drafts.withIndex().sortedByDescending { it.value.id in context.question?.draftIds.orEmpty() }
                .take(6).forEach { (index, item) ->
                val target = if (item.id in context.question?.draftIds.orEmpty()) ",QUESTION_TARGET" else ""
                append("\n${index + 1},${item.menu.name},${item.quantity},${item.temperature},${item.size}$target")
            }
            append("\nCart (target_index,menu,quantity,temperature,size):")
            context.cart.withIndex().sortedByDescending { it.value.id in context.question?.cartIds.orEmpty() }
                .take(6).forEach { (index, item) ->
                val recent = if (item.id == context.lastConfirmedId) ",LAST_CONFIRMED" else ""
                val target = if (item.id in context.question?.cartIds.orEmpty()) ",QUESTION_TARGET" else ""
                append("\n${index + 1},${item.menu.name},${item.quantity},${item.temperature},${item.size}$recent$target")
            }
            if (context.cart.size > 6 || context.drafts.size > 6) append("\nMore items exist. Ask for menu/options if needed.")
        }
        // JSON escaping plus angle bracket escaping prevents injected Llama chat delimiters.
        return prefix + state + "\nUtterance: " + gson.toJson(text) + suffix
    }

    companion object {
        private val gson = Gson()

        fun load(assets: AssetManager): GeniePrompt {
            val format = assets.open("llm/prompt_format.json").bufferedReader().use {
                JsonParser.parseReader(it).asJsonObject
            }
            val system = assets.open("llm/MALLO_system_prompt.txt").bufferedReader().use { it.readText().trim() }
            val template = format.get("prompt_prefix").asString.replace("\\n", "\n")
            val systemHeader = "<|start_header_id|>system<|end_header_id|>\n\n"
            val userHeader = "<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n"
            check(template.startsWith(systemHeader) && template.endsWith(userHeader)) { "Invalid Genie chat template" }
            val systemPrefix = template.removeSuffix(userHeader)
            check(systemPrefix.endsWith(system)) { "Genie chat template and system prompt differ" }
            val suffix = format.get("prompt_suffix").asString.replace("\\n", "\n")
            check(suffix == "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n") { "Invalid Genie chat suffix" }
            return GeniePrompt(systemPrefix + "\n\n" + appSchema + userHeader, suffix)
        }

        private val appSchema = """
            Output schema for this Android app (takes precedence over earlier format rules):
            Flat keys only: function, menu, quantity, temperature, size, target_index,
            target_temperature, target_size, reference (LAST_CONFIRMED), remove_all.
            Omit unknown fields. No parameters or items. For multiple items or mixed changes output
            {"function":"multi_action","actions":[{"function":"add_menu","menu":"아메리카노","quantity":2,"temperature":"ICE"},{"function":"add_menu","menu":"카페라떼","quantity":1}]}.
            Each action is add_menu, change_quantity, change_option or remove_menu.
            Never repeat identical actions. If the user wants identical drinks, give their total quantity once.
            change_quantity uses either quantity (final) or delta (signed). change_option uses new size or temperature.
            remove_menu requires quantity or remove_all:true. Use remove_all only for explicit whole-item removal.
            For changes, identify the target by menu or 1-based target_index (draft if present, otherwise cart),
            and use target_temperature/target_size for old options. In multi_action edit targets are cart items.
            Answer an option question with change_option (size/temperature) or change_quantity (quantity).
            Use show_cart for current order summaries and total cup counts. Use cancel_order for a draft cancellation.
            {"function":"dialogue_response","response":"confirm"} for yes, "deny" for no.
            confirm_order requests payment. cancel_all requests entire cancellation, cancel_order cancels an item/draft,
            keep_order preserves the order, menu_price asks price with menu, add_more requests another menu.
            For uncertainty emit {"function":"request_clarification"}.
        """.trimIndent()
    }
}
