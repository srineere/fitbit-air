package dev.airpulse.fitbit_air_example

import java.util.Locale
import kotlin.math.sqrt

/**
 * Kotlin port of the VADER sentiment rules (Hutto & Gilbert, ICWSM 2014; MIT,
 * see assets/vader/LICENSE.txt), parameterised by [Language] so the same rules
 * score English with the VADER lexicon and German with our own. Deviations from
 * the Python original: emojis are not translated to descriptions, idiom special
 * cases are skipped, and "hell yeah" is not scored as negative.
 */
internal class RelayVader(private val lexicon: Map<String, Float>, private val language: Language = Language.ENGLISH) {

    /** Normalised sentiment in -1..1; negative values are negative valence. [ignore] holds lowercase words to skip. */
    fun compound(text: String, ignore: Set<String> = emptySet()): Double {
        val raw = text.split(whitespace).filter { stripPunctuation(it).isNotEmpty() }
        val words = raw.map(::stripPunctuation)
        if (words.isEmpty()) return 0.0
        // A comma or full stop ends a clause; German look-ahead negation stops there.
        val clauseEnd = raw.map { it.last() in ",.;:!?" }
        val lower = words.map(language::normalize)
        val scores = lower.map { if (it in ignore) null else language.score(lexicon, it) }
        val capDiff = words.count(::isUpper) in 1 until words.size
        val sentiments = DoubleArray(words.size)
        for (i in words.indices) {
            if (lower[i] in language.boosters) continue
            if (language == Language.ENGLISH) {
                if (lower[i] == "kind" && lower.getOrNull(i + 1) == "of") continue
                if (lower[i] == "hell" && lower.getOrNull(i + 1) in setOf("yeah", "yes", "ya")) continue
            }
            sentiments[i] = valence(words, lower, scores, i, capDiff)
            if (language == Language.GERMAN && lower[i] !in commentWords && negatedAfter(lower, scores, clauseEnd, i)) {
                sentiments[i] *= N_SCALAR
            }
        }
        // Weight shifts to the clause after "but".
        val but = lower.indexOfFirst { it in language.contrast }
        if (but >= 0) for (i in sentiments.indices) {
            if (i < but) sentiments[i] *= 0.5 else if (i > but) sentiments[i] *= 1.5
        }
        var sum = sentiments.sum()
        val emphasis = punctuationEmphasis(text)
        if (sum > 0) sum += emphasis else if (sum < 0) sum -= emphasis
        return (sum / sqrt(sum * sum + ALPHA)).coerceIn(-1.0, 1.0)
    }

    private fun valence(words: List<String>, lower: List<String>, scores: List<Double?>, i: Int, capDiff: Boolean): Double {
        val base = scores[i] ?: return 0.0
        var v = base
        if (language == Language.ENGLISH) {
            if (lower[i] == "no" && i < words.lastIndex && scores[i + 1] != null) v = 0.0
            if ((i > 0 && lower[i - 1] == "no") || (i > 1 && lower[i - 2] == "no") ||
                (i > 2 && lower[i - 3] == "no" && lower[i - 1] in setOf("or", "nor"))) v = base * N_SCALAR
        }
        if (isUpper(words[i]) && capDiff) v += if (v > 0) C_INCR else -C_INCR
        for (start in 0..2) {
            val before = i - start - 1
            if (before < 0 || scores[before] != null) continue
            var s = booster(words[before], lower[before], v, capDiff)
            if (start == 1) s *= 0.95
            if (start == 2) s *= 0.9
            v = negation(v + s, lower, start, i)
        }
        if (language == Language.ENGLISH) {
            if (i > 1 && lower[i - 1] == "least" && scores[i - 1] == null) {
                if (lower[i - 2] != "at" && lower[i - 2] != "very") v *= N_SCALAR
            } else if (i == 1 && lower[0] == "least" && scores[0] == null) v *= N_SCALAR
        }
        return v
    }

    /** German puts "nicht" after the verb: "Das klappt nicht". Up to three words, within the clause. */
    private fun negatedAfter(lower: List<String>, scores: List<Double?>, clauseEnd: List<Boolean>, i: Int): Boolean {
        if (clauseEnd[i]) return false
        for (j in i + 1..minOf(i + 3, lower.lastIndex)) {
            if (language.negated(lower[j])) return true
            if ((scores[j] != null && lower[j] !in commentWords) || clauseEnd[j]) return false
        }
        return false
    }

    private fun booster(word: String, lower: String, v: Double, capDiff: Boolean): Double {
        var s = language.boosters[lower] ?: return 0.0
        if (v < 0) s = -s
        if (isUpper(word) && capDiff) s += if (v > 0) C_INCR else -C_INCR
        return s
    }

    private fun negation(v: Double, lower: List<String>, start: Int, i: Int): Double {
        val previous = lower[i - start - 1]
        if (language != Language.ENGLISH) return if (language.negated(previous)) v * N_SCALAR else v
        return when (start) {
            0 -> if (language.negated(previous)) v * N_SCALAR else v
            1 -> when {
                lower[i - 2] == "never" && lower[i - 1] in soThis -> v * 1.25
                lower[i - 2] == "without" && lower[i - 1] == "doubt" -> v
                language.negated(previous) -> v * N_SCALAR
                else -> v
            }
            else -> when {
                (lower[i - 3] == "never" && lower[i - 2] in soThis) || lower[i - 1] in soThis -> v * 1.25
                lower[i - 3] == "without" && (lower[i - 2] == "doubt" || lower[i - 1] == "doubt") -> v
                language.negated(previous) -> v * N_SCALAR
                else -> v
            }
        }
    }

    private fun punctuationEmphasis(text: String): Double {
        val exclamations = minOf(text.count { it == '!' }, 4) * 0.292
        val questions = text.count { it == '?' }
        return exclamations + when { questions <= 1 -> 0.0; questions <= 3 -> questions * 0.18; else -> 0.96 }
    }

    /** Word handling that differs between languages; the scoring rules are shared. */
    internal enum class Language(
        val negations: Set<String>,
        val boosters: Map<String, Double>,
        val contrast: Set<String>,
    ) {
        ENGLISH(
            setOf(
                "aint", "arent", "cannot", "cant", "couldnt", "darent", "didnt", "doesnt", "dont", "hadnt", "hasnt",
                "havent", "isnt", "mightnt", "mustnt", "neither", "neednt", "never", "none", "nope", "nor", "not",
                "nothing", "nowhere", "oughtnt", "shant", "shouldnt", "uhuh", "wasnt", "werent", "without", "wont",
                "wouldnt", "rarely", "seldom", "despite", "uh-uh",
            ),
            listOf(
                "absolutely", "amazingly", "awfully", "completely", "considerable", "considerably", "decidedly",
                "deeply", "effing", "enormous", "enormously", "entirely", "especially", "exceptional",
                "exceptionally", "extreme", "extremely", "fabulously", "flipping", "flippin", "frackin", "fracking",
                "fricking", "frickin", "frigging", "friggin", "fully", "fuckin", "fucking", "fuggin", "fugging",
                "greatly", "hella", "highly", "hugely", "incredible", "incredibly", "intensely", "major", "majorly",
                "more", "most", "particularly", "purely", "quite", "really", "remarkably", "so", "substantially",
                "thoroughly", "total", "totally", "tremendous", "tremendously", "uber", "unbelievably", "unusually",
                "utter", "utterly", "very",
            ).associateWith { B_INCR } + listOf(
                "almost", "barely", "hardly", "kinda", "kindof", "kind-of", "less", "little", "marginal",
                "marginally", "occasional", "occasionally", "partly", "scarce", "scarcely", "slight", "slightly",
                "somewhat", "sorta", "sortof", "sort-of",
            ).associateWith { B_DECR },
            setOf("but"),
        ),
        GERMAN(
            setOf(
                "nicht", "kein", "keine", "keinen", "keinem", "keiner", "keines", "nie", "niemals", "nichts", "ohne",
                "weder", "net", "ned", "nix", "garnicht", "nirgends",
            ),
            listOf(
                "sehr", "total", "voll", "mega", "echt", "richtig", "ziemlich", "extrem", "wirklich", "absolut",
                "komplett", "voellig", "unglaublich", "so", "besonders", "unheimlich", "ultra", "arg", "wahnsinnig",
                "zu", "viel",
            ).associateWith { B_INCR } + listOf(
                "etwas", "bisschen", "leicht", "kaum", "wenig", "eher", "halbwegs", "einigermassen", "fast", "relativ",
                "teilweise",
            ).associateWith { B_DECR },
            setOf("aber", "jedoch", "allerdings", "trotzdem"),
        );

        fun normalize(word: String): String = word.lowercase(Locale.ROOT).let {
            if (this == GERMAN) foldUmlauts(it) else it
        }

        fun negated(word: String) = word in negations || (this == ENGLISH && ("n't" in word || "n’t" in word))

        fun score(lexicon: Map<String, Float>, word: String): Double? {
            lexicon[word]?.let { return it.toDouble() }
            if (this != GERMAN) return null
            germanStem(word)?.let { stem -> lexicon[stem]?.let { return it.toDouble() } }
            // "unschön" negates "schön"; listed words such as "unfair" matched above.
            if (word.length > 6 && word.startsWith("un")) score(lexicon, word.substring(2))?.let { return it * N_SCALAR }
            germanCompoundPrefixes.entries.firstOrNull { word.length > it.key.length + 2 && word.startsWith(it.key) }
                ?.let { return it.value }
            // A compound's last part carries its meaning: "Riesenproblem", "Zahnschmerzen".
            for (cut in 1..word.length - MIN_COMPOUND_PART) {
                lexicon[word.substring(cut)]?.let { return it.toDouble() }
            }
            return null
        }
    }

    companion object {
        private const val B_INCR = 0.293
        private const val B_DECR = -0.293
        private const val C_INCR = 0.733
        private const val N_SCALAR = -0.74
        private const val ALPHA = 15.0
        internal const val MIN_COMPOUND_PART = 5
        private val whitespace = Regex("\\s+")
        private val soThis = setOf("so", "this")
        // Comment on the whole sentence, so a following "nicht" negates the verb, not them.
        private val commentWords = setOf(
            "leider", "schade", "danke", "bitte", "sorry", "entschuldigung", "haha", "hahaha", "lol", "yay", "wow",
            "juhu", "hurra", "ok", "okay", "gerne", "gern",
        )
        private val germanSuffixes = listOf("en", "em", "er", "es", "e", "n", "s")
        // First parts that colour a whole compound: "Scheißtag", "Mistwetter", "Horrorfahrt".
        private val germanCompoundPrefixes = mapOf(
            "scheiss" to -2.5, "mist" to -2.2, "kack" to -2.4, "drecks" to -2.4, "horror" to -2.3, "hass" to -2.6,
            "frust" to -2.0, "traum" to 2.2,
        )

        fun foldUmlauts(word: String) = word.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")

        /** Strips one inflection ending, keeping at least four letters: "schlechten" -> "schlecht". */
        fun germanStem(word: String): String? = germanSuffixes.firstOrNull { word.length - it.length >= 4 && word.endsWith(it) }
            ?.let { word.dropLast(it.length) }

        /** Python's str.isupper(): has cased letters and all of them are upper case. */
        private fun isUpper(word: String) = word.any { it.isLetter() } && word.none { it.isLowerCase() }

        /** Strip edge punctuation unless that leaves two or fewer characters (likely an emoticon). */
        private fun stripPunctuation(token: String): String {
            val stripped = token.trim { it in "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~" }
            return if (stripped.length <= 2) token else stripped
        }

        fun parse(lines: Sequence<String>): Map<String, Float> {
            val lexicon = HashMap<String, Float>(8192)
            lines.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) line.substring(tab + 1).toFloatOrNull()?.let { lexicon[line.substring(0, tab)] = it }
            }
            return lexicon
        }
    }
}
