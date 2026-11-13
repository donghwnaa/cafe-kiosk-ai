package com.example.cafe_kiosk_ai

import android.util.Log

enum class NluIntent {
    ADD, ADD_MORE, REMOVE, CHANGE, MULTI_ACTION, SHOW_MENU, MENU_PRICE,
    SHOW_CART, PAY, CANCEL, CANCEL_ALL, CONFIRM, DENY, HELP, KEEP, UNKNOWN
}
enum class ItemReference { LAST_CONFIRMED, UNSPECIFIED }
data class NluResult(
    val intent: NluIntent, val menu: String? = null, val quantity: Int? = null,
    val temperature: Temperature? = null, val size: DrinkSize? = null,
    val ambiguous: String? = null, val actions: List<NluResult> = emptyList(),
    val removeAll: Boolean = false, val targetId: String? = null, val targetOrdinal: Int? = null,
    val reference: ItemReference? = null, val targetTemperature: Temperature? = null,
    val targetSize: DrinkSize? = null
)

data class NluContext(
    val sessionId: String,
    val state: DialogueState,
    val history: List<ChatMessage>,
    val drafts: List<DraftOrder>,
    val cart: List<CartItem>,
    val question: QuestionContext?,
    val lastConfirmedId: String?
)

fun interface NluEngine { suspend fun analyze(text: String, context: NluContext): NluResult }
fun interface LlmEngine { suspend fun functionCallJson(text: String, context: NluContext): String }
fun interface AiResponseOutput { fun onResponse(message: ChatMessage) }
interface VoiceController { fun startListening(); fun stopListening(); fun release() }
interface TtsEngine {
    val isInitializing: Boolean
    fun speak(text: String, onEvent: (TtsPlaybackEvent) -> Unit)
    fun stop()
    fun release()
}

sealed interface TtsPlaybackEvent {
    data object Started : TtsPlaybackEvent
    data object Completed : TtsPlaybackEvent
    data object Stopped : TtsPlaybackEvent
    data class Error(val reason: TtsError) : TtsPlaybackEvent
}

enum class TtsError { NO_ENGINE, KOREAN_UNAVAILABLE, INITIALIZATION, PLAYBACK }

class FakeNluEngine : NluEngine {
    override suspend fun analyze(text: String, context: NluContext): NluResult =
        NluJsonAdapter.decode(NluJsonAdapter.toDebugJson(analyze(text)))

    fun analyze(text: String): NluResult = parse(text, true).also { result ->
        runCatching { Log.d("MALLO/FakeNlu", NluJsonAdapter.toDebugJson(result)) }
    }

    private fun parse(text: String, allowBatch: Boolean): NluResult {
        val normalized = text.filterNot(Char::isWhitespace).trimEnd('.', '!', '?', '。')
        val menu = findMenu(normalized)
        val changing = listOf("바꿔", "변경", "수정", "말고", "바꿀", "바꿀게", "→").any(normalized::contains)
        val correction = if (changing && normalized.contains("말고")) normalized.substringAfterLast("말고") else normalized
        val quantities = findQuantities(correction)
        val quantity = quantities.firstOrNull()
        val temperatureText = correction.replace("복숭아아이스티", "").replace("복숭아아티", "")
        val temperature = findTemperature(temperatureText)
        val size = findSize(correction)
        val hasReference = listOf("방금", "아까그거", "그거", "그건", "이건").any(normalized::contains)
        val ordinal = when {
            normalized.contains("첫번째") || normalized.contains("1번째") -> 1
            normalized.contains("두번째") || normalized.contains("2번째") -> 2
            normalized.contains("세번째") || normalized.contains("3번째") -> 3
            else -> null
        }
        val actions = if (allowBatch) parseBatch(text, normalized) else emptyList()
        if (actions.isNotEmpty()) return NluResult(NluIntent.MULTI_ACTION, actions = actions)
        val orderedOptions = if (changing) temperatureMatches(temperatureText) else emptyList()
        val optionChange = orderedOptions.size >= 2 && orderedOptions.first() != orderedOptions.last()
        val menuLookup = listOf("메뉴보여", "메뉴다시보여", "음료뭐있", "메뉴뭐있", "메뉴판").any(normalized::contains)
        val priceLookup = listOf("얼마", "가격").any(normalized::contains) && menu != null
        val ambiguity = when {
            menuLookup || priceLookup -> null
            countMentionedMenus(normalized) > 1 -> "여러 메뉴를 들었습니다. 각 메뉴의 옵션을 구분해 말씀해 주세요."
            quantities.distinct().size > 1 -> "수량을 몇 잔으로 할까요? 한 가지 수량을 말씀해 주세요."
            temperature.first && temperature.second == null && !optionChange -> "따뜻한 것과 차가운 것 중 어떤 것으로 할까요?"
            size.first && size.second == null -> "보통과 큰 사이즈 중 어떤 것으로 할까요?"
            menu == "AMBIGUOUS" -> "어떤 라떼를 원하세요? 카페라떼, 바닐라라떼, 녹차라떼, 초코라떼 중 말씀해 주세요."
            else -> null
        }
        val intent = when {
            listOf("전부취소", "전체취소", "전체주문취소", "모두취소").any(normalized::contains) -> NluIntent.CANCEL_ALL
            menuLookup -> NluIntent.SHOW_MENU
            priceLookup -> NluIntent.MENU_PRICE
            normalized.contains("계산") || normalized.contains("결제") -> NluIntent.PAY
            normalized.contains("빼줘") || normalized.contains("빼주세요") || normalized.contains("삭제") || normalized.contains("제거") -> NluIntent.REMOVE
            changing -> NluIntent.CHANGE
            normalized.contains("취소") -> NluIntent.CANCEL
            normalized.contains("보여줘") || normalized.contains("보여주세요") ||
                normalized.contains("장바구니") || normalized == "방금주문한거" -> NluIntent.SHOW_CART
            normalized.contains("그대로두") -> NluIntent.KEEP
            normalized.startsWith("아니") -> NluIntent.DENY
            normalized == "네" || normalized == "예" || normalized.contains("맞아요") || normalized.contains("맞습니다") -> NluIntent.CONFIRM
            normalized.contains("도움") -> NluIntent.HELP
            menu == null && listOf("다른메뉴", "다른것", "다른음료", "추가주문").any(normalized::contains) -> NluIntent.ADD_MORE
            menu != null || quantity != null || temperature.first || size.first -> NluIntent.ADD
            else -> NluIntent.UNKNOWN
        }
        return NluResult(
            intent, menu?.takeUnless { it == "AMBIGUOUS" }, quantity,
            if (optionChange) orderedOptions.last() else temperature.second, size.second, ambiguity,
            removeAll = normalized.contains("삭제") || normalized.contains("제거"),
            targetOrdinal = ordinal,
            reference = when {
                normalized.contains("방금") -> ItemReference.LAST_CONFIRMED
                hasReference -> ItemReference.UNSPECIFIED
                else -> null
            },
            targetTemperature = orderedOptions.firstOrNull()?.takeIf { optionChange }
        )
    }

    private fun parseBatch(text: String, normalized: String): List<NluResult> {
        if (normalized.contains("빼고") && (normalized.contains("추가") || normalized.contains("주세요"))) {
            val parts = text.split("빼고", limit = 2)
            return listOf(parse(parts[0] + " 빼줘", false), parse(parts[1] + " 추가해줘", false))
        }
        if (countMentionedMenus(normalized) < 2) return emptyList()
        val parts = text.split(Regex("\\s*(?:그리고|하고|랑|와|과|,)\\s*"))
        if (parts.size !in 2..10) return emptyList()
        val parsed = parts.map { parse(it, false) }
        return parsed.takeIf { batch -> batch.all { it.intent == NluIntent.ADD && it.menu != null } } ?: emptyList()
    }

    private fun findMenu(text: String): String? {
        if (text.contains("아아") || text.contains("뜨아")) return "아메리카노"
        if (text.contains("복숭아아이스티") || text.contains("복숭아아티")) return "복숭아아이스티"
        for (name in listOf("바닐라라떼", "바닐라라테", "녹차라떼", "녹차라테", "초코라떼", "초코라테", "카페라떼", "카페라테", "아메리카노", "카푸치노", "카페모카", "레몬에이드", "유자차")) {
            if (text.contains(name)) return name.replace("라테", "라떼")
        }
        return if (text.contains("라떼") || text.contains("라테")) "AMBIGUOUS" else null
    }

    private fun countMentionedMenus(text: String): Int {
        val mentioned = MenuCatalog.items.count { text.contains(it.name) || text.contains(it.name.replace("라떼", "라테")) }
        return mentioned + if ((text.contains("아아") || text.contains("뜨아")) && !text.contains("아메리카노")) 1 else 0
    }

    private fun findQuantities(text: String): List<Int> =
        Regex("(?<!\\d)(-?\\d+(?:\\.\\d+)?|하나|한|두|둘|세|셋|네|넷)(?=잔|개|컵|더|추가|빼|으로|로|주세요|줘|$)")
            .findAll(text)
            .map { match ->
                when (match.value) {
                    "한", "하나" -> 1
                    "두", "둘" -> 2
                    "세", "셋" -> 3
                    "네", "넷" -> 4
                    else -> match.value.toIntOrNull() ?: Int.MAX_VALUE
                }
            }.toList()

    private fun findTemperature(text: String): Pair<Boolean, Temperature?> {
        val matches = temperatureMatches(text).distinct()
        return matches.isNotEmpty() to matches.singleOrNull()
    }

    private fun temperatureMatches(text: String): List<Temperature> =
        Regex("뜨아|따뜻|뜨겁|뜨거|데워|핫|HOT|아아|아이스|차갑|차가|시원|ICE", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { match ->
                if (match.value.matches(Regex("뜨아|따뜻|뜨겁|뜨거|데워|핫|HOT", RegexOption.IGNORE_CASE))) Temperature.HOT
                else Temperature.ICE
            }.toList()

    private fun findSize(text: String): Pair<Boolean, DrinkSize?> {
        val regular = listOf("보통", "레귤러", "REGULAR").any { text.contains(it, true) }
        val tall = listOf("큰", "톨", "TALL").any { text.contains(it, true) }
        return (regular || tall) to when { regular && tall -> null; regular -> DrinkSize.REGULAR; tall -> DrinkSize.TALL; else -> null }
    }
}
