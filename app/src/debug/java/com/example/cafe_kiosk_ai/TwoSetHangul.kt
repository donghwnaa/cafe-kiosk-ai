package com.example.cafe_kiosk_ai

/** Standard Korean two-set layout for the raw letter keys forwarded by the emulator. */
internal object TwoSetHangul {
    private const val KEYS = "abcdefghijklmnopqrstuvwxyz"
    private const val LETTERS = "ㅁㅠㅊㅇㄷㄹㅎㅗㅑㅓㅏㅣㅡㅜㅐㅔㅂㄱㄴㅅㅕㅍㅈㅌㅛㅋ"
    private const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private const val FINALS = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
    private val shifted = mapOf('q' to 'ㅃ', 'w' to 'ㅉ', 'e' to 'ㄸ', 'r' to 'ㄲ', 't' to 'ㅆ', 'o' to 'ㅒ', 'p' to 'ㅖ')
    private val compoundVowels = mapOf(
        "ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ',
        "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ'
    )
    private val compoundFinals = mapOf(
        "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ',
        "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ',
        "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ'
    )
    private val finalParts = compoundFinals.entries.associate { (parts, final) -> final to parts }

    fun compose(keys: CharSequence): String = buildString {
        var initial: Char? = null
        var vowel: Char? = null
        var final: Char? = null

        fun flush() {
            val initialIndex = INITIALS.indexOf(initial ?: ' ')
            val vowelIndex = VOWELS.indexOf(vowel ?: ' ')
            if (initialIndex >= 0 && vowelIndex >= 0) {
                val finalIndex = FINALS.indexOf(final ?: ' ')
                append((0xAC00 + (initialIndex * 21 + vowelIndex) * 28 + finalIndex).toChar())
            } else {
                initial?.let { append(it) }
                vowel?.let { append(it) }
            }
            initial = null
            vowel = null
            final = null
        }

        for (key in keys) {
            val lower = key.lowercaseChar()
            val keyIndex = KEYS.indexOf(lower)
            if (keyIndex < 0) {
                flush()
                append(key)
                continue
            }
            val letter = (if (key.isUpperCase()) shifted[lower] else null) ?: LETTERS[keyIndex]
            if (letter in VOWELS) {
                val previousFinal = final
                val combined = compoundVowels["$vowel$letter"]
                when {
                    previousFinal != null -> {
                        val parts = finalParts[previousFinal]
                        final = parts?.first()
                        flush()
                        initial = parts?.last() ?: previousFinal
                        vowel = letter
                    }
                    vowel == null -> vowel = letter
                    combined != null -> vowel = combined
                    else -> {
                        flush()
                        vowel = letter
                    }
                }
            } else {
                val combined = compoundFinals["$final$letter"]
                when {
                    initial != null && vowel != null && final == null && letter in FINALS -> final = letter
                    combined != null -> final = combined
                    else -> {
                        flush()
                        initial = letter
                    }
                }
            }
        }
        flush()
    }
}
