package dev.airpulse.fitbit_air_example

import org.junit.Assert.*
import org.junit.Test

class RelayCallRulesTest {
    private fun classify(
        category: String?,
        ongoing: Boolean,
        fullScreen: Boolean,
        channelId: String?,
    ) = RelayAlertClassifier.classify(category, ongoing, fullScreen, channelId)

    // Channel and category values below were captured from a real Pixel dialer
    // and WhatsApp on Android 15.
    @Test fun `a ringing call is recognised by category, full screen or channel`() {
        assertEquals(RelayAlertKind.CALL, classify("call", true, true, "phone_incoming_call"))
        assertEquals(RelayAlertKind.CALL, classify("call", true, true, "voip_notification_7"))
        assertEquals(RelayAlertKind.CALL, classify(null, true, true, null))
        assertEquals(RelayAlertKind.CALL, classify(null, true, false, "voip_notification"))
        assertEquals(RelayAlertKind.CALL, classify("call", true, false, "messages"))
    }

    @Test fun `a call that is no longer ringing never rings the wrist`() {
        // Answering leaves a sticky notification whose channel still says "call".
        assertEquals(RelayAlertKind.MESSAGE, classify(null, true, false, "phone_ongoing_call"))
        assertEquals(RelayAlertKind.MESSAGE, classify("call", true, true, "phone_ongoing_call"))
        assertEquals(RelayAlertKind.MESSAGE, classify("missed_call", false, false, "phone_missed_call"))
        assertEquals(RelayAlertKind.MESSAGE, classify(null, false, false, "phone_missed_call"))
    }

    @Test fun `a call that is not held up is an event, not a ringing phone`() {
        assertEquals(RelayAlertKind.MESSAGE, classify("call", false, false, null))
    }

    @Test fun `an ordinary sticky notification never rings`() {
        assertEquals(RelayAlertKind.MESSAGE, classify(null, true, false, "media_playback"))
        assertEquals(RelayAlertKind.MESSAGE, classify("msg", false, false, "whatsapp_calls"))
    }

    @Test fun `calls use the call preset and ignore message rules`() {
        val config = RelayRuleConfig(
            defaultPreset = RelayPreset.DOUBLE,
            defaultCallPreset = RelayPreset.RING,
            apps = mapOf(
                "com.whatsapp" to RelayAppRule(true, RelayPreset.LOW),
                "com.instagram" to RelayAppRule(true, RelayPreset.LOW, RelayPreset.HIGH),
            ),
            // A muted chat must not silence that person's incoming call.
            chats = mapOf("alice" to SavedChat("com.whatsapp", "Alice", preset = RelayPreset.MUTE)),
            everyoneElseSilent = true,
        )
        val call = RelayAlertKind.CALL
        assertEquals(RelayPreset.RING, config.match("com.whatsapp", setOf("alice"), null, call)?.preset)
        assertEquals(RelayPreset.HIGH, config.match("com.instagram", emptySet(), null, call)?.preset)
        assertEquals(RelayPreset.LOW, config.match("com.instagram", emptySet(), null)?.preset)
        // The same unsaved WhatsApp contact is silenced as a message and still
        // rings as a call: "everyone else: silent" is a rule about messages.
        assertNull(config.match("com.whatsapp", emptySet(), null))
        assertEquals(RelayPreset.RING, config.match("com.whatsapp", emptySet(), null, call)?.preset)
    }

    @Test fun `a muted call preset drops the alert rather than ringing silently`() {
        val config = RelayRuleConfig(
            defaultCallPreset = RelayPreset.MUTE,
            apps = mapOf("com.whatsapp" to RelayAppRule(true)),
        )
        assertNull(config.match("com.whatsapp", emptySet(), null, RelayAlertKind.CALL))
    }

    @Test fun `a disabled app stays silent for calls too`() {
        val config = RelayRuleConfig(apps = mapOf("com.whatsapp" to RelayAppRule(false)))
        assertNull(config.match("com.whatsapp", emptySet(), null, RelayAlertKind.CALL))
    }

    @Test fun `the ring filter accepts an ongoing call but still drops other sticky alerts`() {
        val filter = CalendarAlertFilter(0, emptyMap(), setOf("com.whatsapp"))
        assertTrue(filter.accept("com.whatsapp", "call-key", 10, 20, true, false, null, RelayAlertKind.CALL))
        assertFalse(filter.accept("com.whatsapp", "sticky-key", 10, 20, true, false, null, RelayAlertKind.MESSAGE))
    }

    @Test fun `a dialer reusing one key still rings on every later call`() {
        // The regression that silenced every call after the first: a dialer
        // posts each incoming call under the same key, with no timestamp to
        // tell the calls apart.
        val filter = CalendarAlertFilter(0, emptyMap(), setOf("com.google.android.dialer"))
        repeat(4) { attempt ->
            val postedAt = 10L + attempt * 1_000
            assertTrue(
                "call ${attempt + 1} was swallowed",
                filter.accept("com.google.android.dialer", "call-key", postedAt, postedAt + 10,
                    true, false, null, RelayAlertKind.CALL),
            )
        }
        // Messages keep their dedupe: the same chat key cannot buzz twice.
        assertTrue(filter.accept("com.google.android.dialer", "chat", 10, 20, false, false, null))
        assertFalse(filter.accept("com.google.android.dialer", "chat", 12, 22, false, false, null))
    }

    @Test fun `ring timing is bounded so pulses stay distinguishable`() {
        assertEquals(700, RelayRingSettings().gapMillis)
        assertEquals(45_000L, RelayRingSettings().maxMillis)
        val tooFast = RelayRingSettings.MINIMUM_GAP_MILLIS - 1
        assertThrows(IllegalArgumentException::class.java) { RelayRingSettings(tooFast, 45) }
        assertThrows(IllegalArgumentException::class.java) { RelayRingSettings(5_001, 45) }
        // Bounded below the relay's 240 s watchdog.
        assertThrows(IllegalArgumentException::class.java) { RelayRingSettings(700, 181) }
        assertThrows(IllegalArgumentException::class.java) { RelayRingSettings(700, 9) }
    }

    @Test fun `a ring always pulses at the stronger of the two strengths`() {
        assertEquals(3, RelayPreset.RING.intensity)
        assertEquals(3, RelayPreset.HIGH.intensity)
        assertEquals(RelayPreset.HIGH, RelayRuleConfig().defaultPreset)
    }

    @Test fun `only calls may repeat`() {
        assertTrue(RelayPreset.RING.callsOnly)
        assertFalse(RelayPreset.DOUBLE.callsOnly)
        assertFalse(RelayPreset.MUTE.callsOnly)
    }
}
