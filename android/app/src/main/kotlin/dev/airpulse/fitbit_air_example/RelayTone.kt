package dev.airpulse.fitbit_air_example

import java.util.Locale

internal enum class RelayTone(val id: String) { URGENT("urgent"), NEGATIVE("negative"), NEUTRAL("neutral") }

// English and German lexicons on lowercase text with umlauts folded (ä -> ae, ß -> ss).
// Words match whole tokens, so German inflections are listed explicitly.
// Strong signals alone make a message urgent; weak ones need a second signal.
private val strongUrgent = setOf(
    "urgent", "urgently", "asap", "emergency", "immediately", "hospital", "accident", "ambulance",
    "police", "sos", "critical", "outage", "paramedic", "paramedics", "bleeding", "injured", "unconscious",
    "collapsed", "fainted", "overdose", "evacuate", "evacuation", "intruder", "burglary",
    "dringend", "dringende", "dringender", "dringendes", "dringenden", "dringlich", "sofort", "umgehend",
    "unverzueglich", "notfall", "notfaelle", "notaufnahme", "notruf", "notarzt", "krankenhaus", "klinik",
    "unfall", "eilig", "eilt", "polizei", "krankenwagen", "rettungswagen", "feuerwehr", "sanitaeter",
    "blutet", "bewusstlos", "zusammengebrochen", "ohnmaechtig", "herzinfarkt", "schlaganfall", "einbruch",
    "eingebrochen", "evakuierung",
)
// Phrases are written as tokens: "can't" becomes "can t".
private val strongPhrases = listOf(
    "right now", "right away", "call me", "call me back", "call back", "call asap", "call 911", "call 112",
    "please call", "pls call", "plz call", "answer your phone", "answer the phone", "pick up the phone",
    "as soon as possible", "time sensitive", "where are you", "are you ok", "are you okay", "are you alright",
    "is everything ok", "is everything okay", "come home now", "need you now", "need help", "need your help",
    "come quick", "come quickly", "get here", "it s urgent", "something happened", "something s wrong",
    "something is wrong", "reply asap", "heart attack", "had a stroke", "life or death",
    "melde dich", "ruf mich an", "ruf an", "ruf mal an", "ruf bitte an", "ruf mich bitte an", "ruf zurueck",
    "ruf mich zurueck", "bitte ruf", "ruf sofort", "geh ran", "geh mal ran", "nimm ab", "so schnell wie moeglich",
    "so bald wie moeglich", "wo bist du", "bist du ok", "alles ok bei dir", "ist was passiert",
    "es ist was passiert", "etwas ist passiert", "irgendwas stimmt nicht", "stimmt was nicht", "komm schnell",
    "komm bitte", "brauche hilfe", "brauch hilfe", "brauche deine hilfe", "antworte bitte", "bitte antworten",
    "schreib zurueck", "schwer verletzt",
)
private val weakUrgent = setOf(
    "help", "important", "importantly", "crucial", "deadline", "overdue", "quick", "quickly", "hurry", "rush",
    "now", "reminder", "missed", "attention", "alert", "warning", "priority", "escalate",
    "escalation", "blocker", "blocking", "stuck", "down", "respond", "waiting", "missing",
    "hilfe", "wichtig", "wichtige", "wichtiger", "wichtiges", "wichtigen", "schnell", "schnellstens",
    "beeil", "beeile", "beeilen", "beeilung", "jetzt", "frist", "zeitnah", "verpasst", "achtung", "warnung",
    "prioritaet", "erinnerung", "flott", "zuegig", "antworte", "rueckmeldung", "bescheid", "warte",
    "vermisst", "festgesteckt",
)
private val weakPhrases = listOf(
    "help me", "pick up", "need you", "missed call", "missed calls", "any update", "running out",
    "brauche dich", "brauch dich", "verpasster anruf", "verpasste anrufe", "gib bescheid", "sag bescheid",
)
private val strongUrgentMarks = listOf("🚨", "‼", "🆘", "🚑", "🚒", "🚓", "⛑")
private val weakUrgentMarks = listOf("⚠", "❗", "❕", "⁉", "⏰", "⏳", "⌛", "📞", "☎", "📢", "📣", "🔴", "🏃")
private val negativeWords = setOf(
    "angry", "furious", "livid", "pissed", "sad", "sorry", "bad", "worse", "worst", "wrong", "problem", "problems",
    "issue", "issues", "hate", "worried", "worry", "scared", "afraid", "anxious", "anxiety", "nervous", "panic",
    "cancel", "cancelled", "canceled", "fail", "failed", "failure", "upset", "unhappy", "miserable",
    "depressed", "lonely", "devastated", "heartbroken", "gutted", "terrible", "awful", "horrible",
    "disappointed", "disappointing", "annoyed", "annoying", "frustrated", "frustrating", "disgusted",
    "ridiculous", "unacceptable", "unfortunately", "sick", "flu", "fever", "vomiting", "hurt", "pain",
    "broken", "crashed", "error", "errors", "lost", "stolen", "robbed", "died", "dead", "death", "funeral",
    "crying", "cry", "stressed", "exhausted", "rejected", "declined", "delayed", "postponed", "fired",
    "divorce", "breakup", "argument", "fight", "complaint", "refund", "mistake", "regret", "apologies",
    "apologize", "apologise", "screwed", "ruined", "disaster", "sucks", "damn", "crap", "shit",
    "bullshit", "fuck", "wtf", "ugh", "stupid", "idiot",
    "sauer", "stinksauer", "veraergert", "beleidigt", "traurig", "schlecht", "schlechte", "schlechter",
    "schlechtes", "schlechten", "leider", "leid", "probleme", "abgesagt", "absagen", "absage", "storniert",
    "verschoben", "enttaeuscht", "enttaeuschend", "mist", "scheisse", "scheiss", "beschissen", "kacke", "mies",
    "sorgen", "wuetend", "schade", "schrecklich", "furchtbar", "katastrophe", "desaster", "nervt", "nervig",
    "genervt", "aerger", "aergerlich", "frustriert", "einsam", "deprimiert", "depressiv", "aengstlich",
    "nervoes", "panik", "verzweifelt", "ungluecklich", "unzufrieden", "krank", "fieber", "grippe", "erkaeltet",
    "erkaeltung", "uebel", "kotzen", "kaputt", "defekt", "ausgefallen", "verloren", "gestohlen", "geklaut",
    "gestorben", "tot", "beerdigung", "weinen", "weine", "angst", "gestresst", "erschoepft", "anstrengend",
    "verletzt", "schmerzen", "verspaetet", "verspaetung", "abgelehnt", "fehler", "gekuendigt", "kuendigung",
    "streit", "gestritten", "trennung", "getrennt", "scheidung", "pleite", "schulden", "beschwerde",
    "reklamation", "entschuldigung", "verdammt", "doof", "bloed", "dumm", "bescheuert", "idiot",
    "unverschaemt", "frechheit",
)
// Matched as whole phrases, so the negation check below does not undo them.
private val negativePhrases = listOf(
    "not good", "not great", "not ok", "not okay", "not happy", "not well", "not working", "not feeling well",
    "feel sick", "feeling sick", "fed up", "bad news", "laid off", "broke up", "went wrong", "fell through",
    "had a fight", "pissed off", "can t make it", "won t make it", "doesn t work", "didn t work",
    "nicht gut", "nicht so gut", "geht mir nicht gut", "nicht ok", "nicht okay", "nicht schoen",
    "nicht so toll", "schlechte nachrichten", "tut mir leid", "schluss gemacht", "geht nicht", "klappt nicht",
    "funktioniert nicht", "kann nicht kommen", "schaffe es nicht", "schaff es nicht", "wird nichts",
)
private val negativeMarks = listOf(
    "😠", "😡", "🤬", "💢", "😤", "😒", "🙄", "😢", "😭", "😿", "😞", "😔", "😟", "😕", "🙁", "☹", "😣", "😖",
    "😩", "😫", "😓", "😥", "😰", "😨", "😧", "😦", "😱", "😮‍💨", "🤒", "🤕", "🤢", "🤮", "😷", "💔", "🥀",
    "👎", "🖕", "⚰", "🪦",
)
private val negators = setOf(
    "not", "no", "never", "nothing", "nobody", "none", "without", "don", "doesn", "didn", "isn", "wasn",
    "aren", "weren", "won", "haven", "hasn", "couldn", "shouldn", "wouldn",
    "nicht", "kein", "keine", "keinen", "keinem", "keiner", "keines", "nie", "niemals", "nichts", "ohne",
)

// Words that mark a message's language; a word shared by both languages is in neither.
private val germanMarkers = setOf(
    "ich", "du", "und", "ist", "nicht", "der", "das", "dem", "ein", "eine", "einen", "einem", "mit", "auf", "fuer",
    "von", "zu", "wir", "ihr", "sie", "es", "noch", "schon", "auch", "ja", "nein", "doch", "mal", "bitte", "danke",
    "heute", "morgen", "gerade", "wie", "wann", "warum", "dass", "mir", "dich", "dir", "mich", "uns", "euch",
    "kann", "kannst", "wird", "sehr", "alles", "nur", "hab", "habe", "bist", "sind", "oder", "aber", "jetzt",
    "gleich", "kein", "keine", "leider", "hallo", "moin", "servus", "tschuess", "net", "ned", "nix",
)
private val englishMarkers = setOf(
    "the", "and", "is", "are", "you", "your", "i", "it", "its", "that", "this", "to", "of", "my", "me", "we",
    "for", "with", "have", "has", "not", "but", "what", "can", "just", "be", "at", "on", "do", "don", "didn",
    "there", "they", "he", "she", "if", "how", "why", "when", "where", "please", "thanks", "thank", "hey", "hi",
    "hello", "yes", "would", "could", "should", "been", "get", "got", "from", "about", "today", "tomorrow",
)
// English list words that are ordinary German words ("Bad" is a bathroom), ignored in German messages.
private val germanHomographs = setOf("bad", "die", "war", "hell", "kind", "gift", "arm", "rat", "fast", "also")
// German stems that keep their meaning inside compounds: "Autounfall", "Notfallnummer", "Abgabefrist".
private val strongGermanStems = listOf(
    "notfall", "notruf", "notarzt", "notaufnahme", "krankenhaus", "krankenwagen", "rettungswagen", "feuerwehr",
    "polizei", "unfall", "einbruch", "evakuierung", "dringend",
)
private val weakGermanStems = listOf("frist", "wichtig", "deadline", "erinnerung")

private enum class MessageLanguage { ENGLISH, GERMAN, MIXED }

/** Local heuristic only; the text is neither kept nor logged. */
internal object RelayToneClassifier {
    private const val MAX_CHARS = 1000
    private const val BURST_MESSAGES = 3
    // Compound sentiment score: clearly negative at or below, clearly positive at or above.
    private const val SCORE_NEGATIVE = -0.35
    private const val SCORE_POSITIVE = 0.3
    private val tokenSplit = Regex("[^a-z0-9]+")
    private val paddedStrong = strongPhrases.map { " $it " }
    private val paddedWeak = weakPhrases.map { " $it " }
    private val paddedNegative = negativePhrases.map { " $it " }

    /**
     * [lexicons] add graded sentiment scores; without them, only the word lists
     * are used (as in JVM tests that do not load the lexicons).
     */
    fun classify(
        text: String?,
        signals: RelayNotificationSignals = RelayNotificationSignals(),
        lexicons: RelayToneLexicons? = null,
    ): RelayTone {
        // A silent channel is the posting app's own judgement that this can wait.
        if (signals.silentChannel) return RelayTone.NEUTRAL
        val raw = text?.take(MAX_CHARS) ?: ""
        val tokens = RelayVader.foldUmlauts(raw.lowercase(Locale.ROOT)).split(tokenSplit).filter { it.isNotEmpty() }
        val joined = tokens.joinToString(" ", " ", " ")
        val language = language(raw, tokens)
        val german = language != MessageLanguage.ENGLISH
        // German endings and compounds also apply when no marker word decides the language.
        val germanForms = german || tokens.none { it in englishMarkers }
        var urgency = 0
        if (signals.recentMessages >= BURST_MESSAGES) urgency += 2
        if (signals.priorityConversation) urgency++
        if (signals.urgentCategory) urgency++
        if (tokens.any { matches(it, strongUrgent, germanForms, strongGermanStems) } || paddedStrong.any { it in joined } ||
            strongUrgentMarks.any { it in raw }) urgency += 2
        if (tokens.any { matches(it, weakUrgent, germanForms, weakGermanStems) } || paddedWeak.any { it in joined }) urgency++
        if (weakUrgentMarks.any { it in raw }) urgency++
        if ("!!" in raw) urgency++
        if ("???" in raw) urgency++
        if (shouting(raw)) urgency++
        if (urgency >= 2) return RelayTone.URGENT
        if (raw.isBlank()) return RelayTone.NEUTRAL
        val listed = tokens.indices.any { i ->
            !(german && tokens[i] in germanHomographs) && matches(tokens[i], negativeWords, german) &&
                (maxOf(0, i - 2) until i).none { tokens[it] in negators }
        } || paddedNegative.any { it in joined } || negativeMarks.any { it in raw }
        if (lexicons == null) return if (listed) RelayTone.NEGATIVE else RelayTone.NEUTRAL
        // Graded scores add words the lists lack, and veto list hits in clearly positive messages.
        val scores = when (language) {
            MessageLanguage.ENGLISH -> listOf(lexicons.english.compound(raw))
            MessageLanguage.GERMAN -> listOf(lexicons.german.compound(raw))
            MessageLanguage.MIXED -> listOf(lexicons.english.compound(raw, germanHomographs), lexicons.german.compound(raw))
        }
        val negative = scores.any { it <= SCORE_NEGATIVE } || (listed && scores.all { it < SCORE_POSITIVE })
        return if (negative) RelayTone.NEGATIVE else RelayTone.NEUTRAL
    }

    /** German umlauts or marker words decide; both kinds of marker make a mixed message. */
    private fun language(raw: String, tokens: List<String>): MessageLanguage {
        val german = raw.any { it in "äöüßÄÖÜ" } || tokens.any { it in germanMarkers }
        val english = tokens.any { it in englishMarkers }
        return when {
            german && english -> MessageLanguage.MIXED
            german -> MessageLanguage.GERMAN
            else -> MessageLanguage.ENGLISH
        }
    }

    /** Exact match, or in German messages also an inflected form or a compound of a listed stem. */
    private fun matches(token: String, words: Set<String>, german: Boolean, stems: List<String> = emptyList()): Boolean {
        if (token in words) return true
        if (!german) return false
        if (RelayVader.germanStem(token)?.let { it in words } == true) return true
        return stems.any { token.length > it.length && (token.startsWith(it) || token.endsWith(it)) }
    }

    private fun shouting(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length >= 4 && letters.count { it.isUpperCase() } * 10 >= letters.length * 7
    }
}
