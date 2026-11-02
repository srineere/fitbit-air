package dev.airpulse.fitbit_air_example

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayToneClassifierTest {
    private fun tone(text: String?) = RelayToneClassifier.classify(text)

    @Test fun `urgent keywords and phrases in English and German`() {
        assertEquals(RelayTone.URGENT, tone("Call me ASAP"))
        assertEquals(RelayTone.URGENT, tone("Bitte ruf mich sofort an"))
        assertEquals(RelayTone.URGENT, tone("Need you here right now"))
        assertEquals(RelayTone.URGENT, tone("Notfall im Büro"))
        assertEquals(RelayTone.URGENT, tone("🚨 server down"))
    }
    @Test fun `structural signals need to combine`() {
        assertEquals(RelayTone.NEUTRAL, tone("See you later!!"))
        assertEquals(RelayTone.URGENT, tone("WHERE ARE YOU!!"))
        assertEquals(RelayTone.URGENT, tone("wichtig!!"))
        assertEquals(RelayTone.NEUTRAL, tone("OK"))
    }
    @Test fun `negative words with negation and umlaut folding`() {
        assertEquals(RelayTone.NEGATIVE, tone("I'm so sorry, it failed"))
        assertEquals(RelayTone.NEGATIVE, tone("Leider abgesagt 😢"))
        assertEquals(RelayTone.NEGATIVE, tone("Ich bin echt enttäuscht"))
        assertEquals(RelayTone.NEUTRAL, tone("not bad at all"))
        assertEquals(RelayTone.NEUTRAL, tone("Das war nicht schlecht"))
        assertEquals(RelayTone.NEUTRAL, tone("Keine Sorgen, alles gut"))
        assertEquals(RelayTone.NEUTRAL, tone("no problem"))
    }
    @Test fun `expanded lexicon covers inflections phrases and marks`() {
        assertEquals(RelayTone.URGENT, tone("Eine dringende Frage"))
        assertEquals(RelayTone.URGENT, tone("Geh ran, ist was passiert?"))
        assertEquals(RelayTone.URGENT, tone("Are you okay?"))
        assertEquals(RelayTone.URGENT, tone("🆘"))
        assertEquals(RelayTone.URGENT, tone("⚠ server down, help"))
        assertEquals(RelayTone.URGENT, tone("Wichtige Frist morgen!!"))
        assertEquals(RelayTone.NEUTRAL, tone("Er kommt morgen vorbei"))
        assertEquals(RelayTone.NEUTRAL, tone("Can you pick up milk"))
        assertEquals(RelayTone.NEUTRAL, tone("I'm home now"))
        assertEquals(RelayTone.NEGATIVE, tone("Das sieht nicht gut aus"))
        assertEquals(RelayTone.NEGATIVE, tone("That's not good"))
        assertEquals(RelayTone.NEGATIVE, tone("Bad news about the flat"))
        assertEquals(RelayTone.NEGATIVE, tone("Der Zug hat Verspätung 😤"))
        assertEquals(RelayTone.NEUTRAL, tone("We had no issues at all"))
        assertEquals(RelayTone.NEUTRAL, tone("Kein Fehler gefunden"))
    }
    @Test fun `larger word phrase and emoji lists`() {
        assertEquals(RelayTone.URGENT, tone("Probealarm: Evakuierung um 14 Uhr"))
        assertEquals(RelayTone.URGENT, tone("Something's wrong with the build"))
        assertEquals(RelayTone.URGENT, tone("Brauche Hilfe, wo bist du"))
        assertEquals(RelayTone.URGENT, tone("🚒 drill at noon"))
        assertEquals(RelayTone.URGENT, tone("📞 still waiting"))
        assertEquals(RelayTone.NEUTRAL, tone("See you soon!!"))
        assertEquals(RelayTone.NEUTRAL, tone("Thanks for the reply!!"))
        assertEquals(RelayTone.NEGATIVE, tone("Can't make it tonight"))
        assertEquals(RelayTone.NEGATIVE, tone("Tut mir echt leid"))
        assertEquals(RelayTone.NEGATIVE, tone("Der Drucker ist kaputt"))
        assertEquals(RelayTone.NEGATIVE, tone("Klappt leider nicht"))
        assertEquals(RelayTone.NEGATIVE, tone("ok 🙄"))
        assertEquals(RelayTone.NEGATIVE, tone("😮‍💨"))
        assertEquals(RelayTone.NEUTRAL, tone("Hell yeah 🎉"))
    }
    private fun english(text: String) = RelayToneClassifier.classify(text, lexicons = RelayVaderTest.lexicons)

    @Test fun `VADER adds English words and vetoes clearly positive messages`() {
        assertEquals(RelayTone.NEGATIVE, english("this plan is hopeless"))
        assertEquals(RelayTone.NEGATIVE, english("the battery could die"))
        assertEquals(RelayTone.NEGATIVE, english("The meeting was cancelled"))
        assertEquals(RelayTone.NEGATIVE, english("I'm VERY upset!!!"))
        assertEquals(RelayTone.NEUTRAL, english("great job but the demo crashed"))
        assertEquals(RelayTone.NEUTRAL, english("We had no issues at all"))
        assertEquals(RelayTone.NEUTRAL, english("not bad at all"))
        assertEquals(RelayTone.NEUTRAL, english("Hell yeah 🎉"))
        assertEquals(RelayTone.NEUTRAL, english("thanks, sounds good"))
    }
    @Test fun `German messages skip VADER so homographs are not misread`() {
        assertEquals(RelayTone.NEUTRAL, english("Das Kind war heute im Bad"))
        assertEquals(RelayTone.NEUTRAL, english("Die Bahn war heute pünktlich"))
        assertEquals(RelayTone.NEUTRAL, english("Ich bin echt arm dran"))
        assertEquals(RelayTone.NEGATIVE, english("Die Bahn ist leider ausgefallen"))
        // No marker words: German compounds still count, English homograph handling is unchanged.
        assertEquals(RelayTone.URGENT, english("Autounfall, Notrufnummer?"))
        assertEquals(RelayTone.NEGATIVE, english("Bad news"))
    }
    @Test fun `burst window counts only recent timestamps`() {
        val now = 1_000_000L
        val window = RelayNotificationSignals.BURST_WINDOW_MILLIS
        assertEquals(2, RelayNotificationSignals.recentCount(listOf(now, now - window, now - window - 1, now + 1), now))
    }
    @Test fun `Android signals add urgency and silent channels stay neutral`() {
        val burst = RelayNotificationSignals(recentMessages = 3)
        assertEquals(RelayTone.URGENT, RelayToneClassifier.classify("ok", burst))
        assertEquals(RelayTone.URGENT, RelayToneClassifier.classify(null, burst))
        assertEquals(RelayTone.NEUTRAL, RelayToneClassifier.classify("ok", RelayNotificationSignals(recentMessages = 2)))
        val priority = RelayNotificationSignals(priorityConversation = true)
        assertEquals(RelayTone.NEUTRAL, RelayToneClassifier.classify("are you free?", priority))
        assertEquals(RelayTone.URGENT, RelayToneClassifier.classify("are you free?!!", priority))
        assertEquals(RelayTone.URGENT, RelayToneClassifier.classify(null,
            RelayNotificationSignals(priorityConversation = true, urgentCategory = true)))
        assertEquals(RelayTone.NEUTRAL, RelayToneClassifier.classify("URGENT sale ends now!!",
            RelayNotificationSignals(silentChannel = true)))
    }
    @Test fun `urgent outranks negative`() {
        assertEquals(RelayTone.URGENT, tone("Sorry, server outage, call me"))
    }
    @Test fun `missing blank and long text`() {
        assertEquals(RelayTone.NEUTRAL, tone(null))
        assertEquals(RelayTone.NEUTRAL, tone("   "))
        assertEquals(RelayTone.NEUTRAL, tone("ok 👍"))
        assertEquals(RelayTone.NEUTRAL, tone("a ".repeat(600) + "urgent"))
    }
}
