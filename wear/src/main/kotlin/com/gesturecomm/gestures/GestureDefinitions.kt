package com.gesturecomm.gestures

import kotlin.math.abs

/**
 * The 6 gestures the app recognises.
 */
enum class Gesture(val displayName: String, val emoji: String, val legacyPhrase: String) {
    FLICK_UP(
        displayName = "Flick Up",
        emoji       = "👋",
        legacyPhrase = "Hi! My name is ___."
    ),
    FLICK_DOWN(
        displayName = "Flick Down",
        emoji       = "🙏",
        legacyPhrase = "Can you help me with this?"
    ),
    TWIST_CW(
        displayName = "Twist Right",
        emoji       = "😊",
        legacyPhrase = "How are you?"
    ),
    TWIST_CCW(
        displayName = "Twist Left",
        emoji       = "🙌",
        legacyPhrase = "Thank you so much!"
    ),
    SHAKE(
        displayName = "Shake",
        emoji       = "✋",
        legacyPhrase = "Please wait a moment."
    ),
    DOUBLE_TAP(
        displayName = "Double Tap",
        emoji       = "👍",
        legacyPhrase = "Yes, I understand."
    );
}

data class PredictionResult(
    val topWord: String?,
    val candidates: List<String>
)

/**
 * Defines how single gestures and 2-gesture sequences resolve to phoneme-like tokens.
 */
object PhoneticLexicon {
    const val SEQUENCE_TIMEOUT_MS = 1200L  // Increased from 700ms to allow more time for deliberate 2-gesture sequences
    const val WORD_COMMIT_TIMEOUT_MS = 1800L

    private val tokenMap: Map<List<Gesture>, String> = mapOf(
        listOf(Gesture.FLICK_UP) to "AH",
        listOf(Gesture.FLICK_DOWN) to "EH",
        listOf(Gesture.TWIST_CW) to "S",
        listOf(Gesture.TWIST_CCW) to "T",
        listOf(Gesture.SHAKE) to "K",
        listOf(Gesture.DOUBLE_TAP) to "N",

        listOf(Gesture.FLICK_UP, Gesture.TWIST_CW) to "SH",
        listOf(Gesture.FLICK_UP, Gesture.TWIST_CCW) to "CH",
        listOf(Gesture.TWIST_CW, Gesture.DOUBLE_TAP) to "R",
        listOf(Gesture.TWIST_CCW, Gesture.DOUBLE_TAP) to "L",
        listOf(Gesture.SHAKE, Gesture.FLICK_UP) to "P",
        listOf(Gesture.SHAKE, Gesture.FLICK_DOWN) to "B",
        listOf(Gesture.FLICK_DOWN, Gesture.TWIST_CW) to "M",
        listOf(Gesture.FLICK_DOWN, Gesture.TWIST_CCW) to "D",
        listOf(Gesture.TWIST_CW, Gesture.FLICK_UP) to "W",
        listOf(Gesture.TWIST_CCW, Gesture.FLICK_UP) to "Y"
    )

    fun resolveToken(sequence: List<Gesture>): String? = tokenMap[sequence]
}

/**
 * Lightweight rule-based predictor over a small demo dictionary.
 */
object PhoneticPredictor {

    private data class WordEntry(val word: String, val phonemes: List<String>)

    private val dictionary = listOf(
        // High-value demo words that align with the current token set.
        WordEntry("show", listOf("SH", "AH")),
        WordEntry("cat", listOf("K", "AH", "T")),
        WordEntry("help", listOf("Y", "EH", "L", "P")),
        WordEntry("yes", listOf("Y", "EH", "S")),
        WordEntry("no", listOf("N", "AH")),
        WordEntry("name", listOf("N", "AH", "M")),
        WordEntry("wait", listOf("W", "EH", "T")),
        WordEntry("thanks", listOf("T", "AH", "N", "K", "S")),
        WordEntry("need", listOf("N", "EH", "D")),
        WordEntry("clear", listOf("K", "L", "EH", "R")),
        WordEntry("right", listOf("R", "AH", "T")),
        WordEntry("left", listOf("L", "EH", "T")),
        WordEntry("word", listOf("W", "R", "D")),
        WordEntry("sound", listOf("S", "AH", "N", "D")),
        WordEntry("step", listOf("S", "T", "EH", "P")),
        WordEntry("beat", listOf("B", "EH", "T")),
        WordEntry("seed", listOf("S", "EH", "D")),
        WordEntry("dash", listOf("D", "AH", "SH")),
        WordEntry("speak", listOf("S", "P", "EH", "K")),
        WordEntry("chat", listOf("CH", "AH", "T")),
        WordEntry("case", listOf("K", "AH", "S")),
        WordEntry("best", listOf("B", "EH", "S", "T")),
        WordEntry("shake", listOf("SH", "EH", "K"))
    )

    fun predict(tokens: List<String>, topK: Int = 3): PredictionResult {
        if (tokens.isEmpty()) return PredictionResult(topWord = null, candidates = emptyList())

        val ranked = dictionary
            .map { entry -> entry.word to score(tokens, entry.phonemes) }
            .sortedBy { it.second }
            .take(topK)
            .map { it.first }

        return PredictionResult(
            topWord = ranked.firstOrNull(),
            candidates = ranked
        )
    }

    private fun score(input: List<String>, target: List<String>): Int {
        val prefixLen = minOf(input.size, target.size)
        val targetPrefix = target.take(prefixLen)
        val inputPrefix = input.take(prefixLen)

        val edit = levenshtein(inputPrefix, targetPrefix)
        val lengthPenalty = abs(target.size - input.size)
        val prefixBonus = if (inputPrefix == targetPrefix) -2 else 0
        return (edit * 3) + lengthPenalty + prefixBonus
    }

    private fun levenshtein(a: List<String>, b: List<String>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size

        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) dp[i][0] = i
        for (j in 0..b.size) dp[0][j] = j

        for (i in 1..a.size) {
            for (j in 1..b.size) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.size][b.size]
    }
}
