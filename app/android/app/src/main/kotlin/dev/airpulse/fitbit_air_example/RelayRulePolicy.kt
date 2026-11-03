package dev.airpulse.fitbit_air_example

import java.util.Calendar
import java.util.TimeZone

/** What a vibration does, independent of how hard it does it. */
internal enum class RelayPattern(val id: String, val pulses: Int?) {
    ONE("one", 1),
    TWO("two", 2),
    /** Repeats until the phone stops ringing, so it suits calls only. */
    RING("ring", null),
    SILENT("silent", 0);

    companion object {
        fun parse(id: String): RelayPattern = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown vibration pattern")
    }
}

/**
 * A pattern and the strength it plays at, stored as `pattern:strength`.
 *
 * The Air has exactly two strengths, [LOW_STRENGTH] and [FULL_STRENGTH], and
 * every pattern can use either, so the two are chosen separately rather than
 * enumerated as a combined list.
 */
internal data class RelayPreset(
    val pattern: RelayPattern,
    val strength: Int = FULL_STRENGTH,
) {
    init {
        require(strength == LOW_STRENGTH || strength == FULL_STRENGTH) {
            "The Air has two vibration strengths."
        }
    }

    val id: String get() = if (silent) RelayPattern.SILENT.id else "${pattern.id}:$strength"

    /** The strength to ask the Air for, or null when nothing is sent. */
    val intensity: Int? get() = if (silent) null else strength

    val silent: Boolean get() = pattern == RelayPattern.SILENT

    /** A repeat has nothing to stop it unless a call is still ringing. */
    val callsOnly: Boolean get() = pattern == RelayPattern.RING

    companion object {
        const val LOW_STRENGTH = 2
        const val FULL_STRENGTH = 3

        val MUTE = RelayPreset(RelayPattern.SILENT)
        val LOW = RelayPreset(RelayPattern.ONE, LOW_STRENGTH)
        val HIGH = RelayPreset(RelayPattern.ONE, FULL_STRENGTH)
        val DOUBLE = RelayPreset(RelayPattern.TWO, FULL_STRENGTH)
        val RING = RelayPreset(RelayPattern.RING, FULL_STRENGTH)

        /** Settings written before a vibration had a strength of its own. */
        private val legacy = mapOf(
            "single" to HIGH, "low" to LOW, "high" to HIGH,
            "double" to DOUBLE, "ring" to RING, "mute" to MUTE,
        )

        fun parse(id: String): RelayPreset {
            legacy[id]?.let { return it }
            val parts = id.split(':')
            val pattern = RelayPattern.parse(parts.firstOrNull().orEmpty())
            if (pattern == RelayPattern.SILENT) return MUTE
            val strength = parts.getOrNull(1)?.toIntOrNull()
                ?: throw IllegalArgumentException("Unknown vibration strength")
            return RelayPreset(pattern, strength)
        }
    }
}

/** [callPreset] null uses [RelayRuleConfig.defaultCallPreset] for this app's calls. */
internal data class RelayAppRule(
    val enabled: Boolean,
    val preset: RelayPreset? = null,
    val callPreset: RelayPreset? = null,
)
/**
 * A WhatsApp chat the user saved. [kind] is "shortcut" (stable conversation ID) or
 * "name" (exact chat name); [preset] null uses the app vibration. Labels stay on this phone.
 */
internal data class SavedChat(
    val packageName: String,
    val label: String,
    val kind: String = "shortcut",
    val preset: RelayPreset? = null,
)
/** [tone] is the message's category when message tone is on, for history. */
internal data class RelayMatch(val preset: RelayPreset, val specificity: Int, val tone: RelayTone? = null)
/** Per-tone presets; null keeps the app or chat rule. Off unless the user opts in. */
internal data class RelayToneRules(
    val enabled: Boolean = false,
    val urgent: RelayPreset? = null,
    val negative: RelayPreset? = null,
    val neutral: RelayPreset? = null,
) {
    fun preset(tone: RelayTone): RelayPreset? = when (tone) {
        RelayTone.URGENT -> urgent; RelayTone.NEGATIVE -> negative; RelayTone.NEUTRAL -> neutral
    }
}

internal data class RelaySilence(
    val pauseUntil: Long = 0,
    val quietEnabled: Boolean = false,
    val startMinute: Int = 1320,
    val endMinute: Int = 420,
) {
    init {
        require(pauseUntil >= -1 && startMinute in 0..1439 && endMinute in 0..1439)
        require(!quietEnabled || startMinute != endMinute) { "Choose different start and end times" }
    }
    fun reason(now: Long, zone: TimeZone = TimeZone.getDefault()): String? {
        if (pauseUntil == -1L || pauseUntil > now) return "paused"
        if (!quietEnabled) return null
        val local = Calendar.getInstance(zone).apply { timeInMillis = now }
        val minute = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE)
        val quiet = if (startMinute < endMinute) minute >= startMinute && minute < endMinute
            else minute >= startMinute || minute < endMinute
        return if (quiet) "quiet_hours" else null
    }
}

internal data class RelayRuleConfig(
    // The Air has two strengths; a notification you want to feel uses the
    // stronger one, and a call never uses anything else.
    val defaultPreset: RelayPreset = RelayPreset.HIGH,
    val defaultCallPreset: RelayPreset = RelayPreset.RING,
    val apps: Map<String, RelayAppRule> = emptyMap(),
    val chats: Map<String, SavedChat> = emptyMap(),
    val silence: RelaySilence = RelaySilence(),
    val tone: RelayToneRules = RelayToneRules(),
    val ring: RelayRingSettings = RelayRingSettings(),
    val everyoneElseSilent: Boolean = false,
) {
    val selectedPackages: Set<String> get() = apps.filterValues { it.enabled }.keys

    fun match(
        packageName: String,
        identities: Set<String>,
        tone: RelayTone? = null,
        kind: RelayAlertKind = RelayAlertKind.MESSAGE,
    ): RelayMatch? {
        val app = apps[packageName]?.takeIf { it.enabled } ?: return null
        if (kind == RelayAlertKind.CALL) {
            // A call is from whoever is calling: saved-chat, burst and tone rules
            // all describe messages and never apply here.
            val preset = app.callPreset ?: defaultCallPreset
            return if (preset.silent) null else RelayMatch(preset, 2)
        }
        val whatsapp = packageName in RelayConversationPolicy.packages
        val saved = if (whatsapp) identities.mapNotNull { chats[it] } else emptyList()
        // "Everyone else: Silent" lets only saved chats through.
        if (whatsapp && everyoneElseSilent && saved.isEmpty()) return null
        val overrides = saved.mapNotNull { it.preset }
        // A saved mute always wins if Android supplies multiple identity forms.
        if (overrides.any { it.silent }) return null
        val base = overrides.firstOrNull() ?: app.preset ?: defaultPreset
        if (base.silent) return null
        val category = tone?.takeIf { this.tone.enabled }
        val toned = category?.let { this.tone.preset(it) }
        val preset = toned ?: base
        // An urgent message outranks person-specific rules within a burst.
        val specificity = if (toned != null && tone == RelayTone.URGENT) 2 else if (overrides.isEmpty()) 0 else 1
        return if (preset.silent) null else RelayMatch(preset, specificity, category)
    }
}

/** Latest wins ties; a person-specific rule outranks an app default. */
internal data class RelayBurstChoice(val match: RelayMatch, val receivedAt: Long) {
    fun prefer(other: RelayBurstChoice): RelayBurstChoice =
        if (other.match.specificity > match.specificity ||
            (other.match.specificity == match.specificity && other.receivedAt >= receivedAt)) other else this
}

/** Migration also fails closed after backup/transfer omitted private rule settings. */
internal fun migrateRelayRules(packages: Set<String>, style: String, migratedBefore: Boolean): RelayRuleConfig =
    if (migratedBefore) RelayRuleConfig() else RelayRuleConfig(
        defaultPreset = if (style == "double") RelayPreset.DOUBLE else RelayPreset.HIGH,
        apps = packages.associateWith { RelayAppRule(true) },
    )
