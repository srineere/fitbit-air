package dev.airpulse.fitbit_air_example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RelayVaderTest {
    @Test fun `compound scores match the Python reference implementation`() {
        // Scores from polarity_scores()["compound"] in github.com/cjhutto/vaderSentiment.
        mapOf(
            "I'm so sorry, it failed" to -0.6318,
            "Bad news about the flat" to -0.5423,
            "not good" to -0.3412,
            "not bad at all" to 0.431,
            "I'm VERY upset!!!" to -0.6707,
            "no problem" to 0.3089,
            "great job but the demo crashed" to 0.3716,
            "I'm not happy with this" to -0.4585,
            "kind of annoying" to -0.4019,
            "the old printer died" to -0.5574,
            "this plan is hopeless" to -0.4588,
            "We had no issues at all" to -0.296,
            "It is extremely good but the ending was terrible" to -0.4684,
            "never so happy" to 0.6948,
            "at least it works" to 0.0,
            "ok" to 0.296,
        ).forEach { (text, expected) -> assertEquals(text, expected, vader.compound(text), 0.0005) }
    }
    @Test fun `hell yeah is not negative and empty text is neutral`() {
        // The reference scores this -0.527; only "yeah" counts here.
        assertTrue(vader.compound("Hell yeah") > 0)
        assertEquals(0.0, vader.compound("   "), 0.0)
    }

    @Test fun `German rules handle inflection compounds negation and aber`() {
        assertTrue(german.compound("Die Präsentation war schlecht") < -0.35)
        assertTrue(german.compound("Das waren schlechte Nachrichten") < -0.35)
        assertTrue(german.compound("Wir haben ein Riesenproblem") < -0.35)
        assertTrue(german.compound("Heute ist ein Scheißtag") < -0.35)
        assertTrue(german.compound("Das Hotel war unschön") < 0)
        assertTrue(german.compound("Das war nicht schlecht") > 0)
        assertTrue(german.compound("Das ist sehr schlecht") < german.compound("Das ist schlecht"))
        assertTrue(german.compound("Das Essen war gut, aber der Service war furchtbar") < 0)
        assertTrue(german.compound("Lust auf ein Abenteuer?") == 0.0)
        // Negation after the verb, but not across a comma.
        assertTrue(german.compound("Das Update funktioniert nicht") < 0)
        assertTrue(german.compound("Der Plan klappt leider gar nicht") < -0.35)
        assertTrue(german.compound("Das war gut, nicht?") > 0)
    }

    companion object {
        // Unit tests run from the app module directory, where the asset lives.
        private fun lexicon(path: String) = File("src/main/assets/$path").useLines { RelayVader.parse(it) }
        internal val vader by lazy { RelayVader(lexicon("vader/vader_lexicon.txt")) }
        internal val german by lazy { RelayVader(lexicon("tone/de_lexicon.txt"), RelayVader.Language.GERMAN) }
        internal val lexicons by lazy { RelayToneLexicons(vader, german) }
    }
}
