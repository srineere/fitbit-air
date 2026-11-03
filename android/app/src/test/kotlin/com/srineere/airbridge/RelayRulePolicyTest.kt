package com.srineere.airbridge

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RelayRulePolicyTest {
    private val whatsapp = "com.whatsapp"
    private val config = RelayRuleConfig(apps = mapOf(whatsapp to RelayAppRule(true), "calendar" to RelayAppRule(true, RelayPreset.DOUBLE)),
        chats = mapOf("alice" to SavedChat(whatsapp, "Alice", preset = RelayPreset.HIGH),
            "muted" to SavedChat(whatsapp, "Muted", preset = RelayPreset.MUTE), "bob" to SavedChat(whatsapp, "Bob")))

    @Test fun `migration preserves selection and style but backup restore does not reenable stale apps`() {
        val migrated = migrateRelayRules(setOf("com.whatsapp"), "double", false)
        assertEquals(setOf("com.whatsapp"), migrated.selectedPackages)
        assertEquals(RelayPreset.DOUBLE, migrated.defaultPreset)
        assertNull(migrated.copy(everyoneElseSilent = true).match("com.whatsapp", emptySet()))
        assertTrue(migrateRelayRules(setOf("com.whatsapp"), "double", true).selectedPackages.isEmpty())
    }
    @Test fun `chat overrides app and mute wins across identity forms`() {
        assertEquals(RelayMatch(RelayPreset.HIGH, 1), config.match(whatsapp, setOf("alice")))
        assertNull(config.match(whatsapp, setOf("alice", "muted")))
        assertEquals(RelayMatch(RelayPreset.DOUBLE, 0), config.match("calendar", setOf("alice")))
        assertNull(config.match("unknown", emptySet()))
    }
    @Test fun `everyone else silent lets only saved chats through`() {
        val silent = config.copy(everyoneElseSilent = true)
        assertNull(silent.match(whatsapp, emptySet()))
        assertNull(silent.match(whatsapp, setOf("carol")))
        assertNull(silent.copy(chats = emptyMap()).match(whatsapp, setOf("alice")))
        assertEquals(RelayMatch(RelayPreset.HIGH, 1), silent.match(whatsapp, setOf("alice")))
        // A saved chat without its own vibration uses the app vibration, which
        // falls through to the shipped default of the stronger strength.
        assertEquals(RelayMatch(RelayPreset.HIGH, 0), silent.match(whatsapp, setOf("bob")))
        assertEquals(RelayMatch(RelayPreset.HIGH, 0), config.match(whatsapp, setOf("carol")))
        assertNull(config.copy(apps = mapOf(whatsapp to RelayAppRule(false))).match(whatsapp, setOf("alice")))
    }
    @Test fun `a person rule wins a burst over an app rule`() {
        val person = RelayBurstChoice(config.match(whatsapp, setOf("alice"))!!, 2)
        val app = RelayBurstChoice(config.match(whatsapp, emptySet())!!, 1)
        assertEquals(person, app.prefer(person))
    }
    private fun time(hour: Int, minute: Int = 0): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(2026, Calendar.OCTOBER, 17, hour, minute, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    @Test fun `overnight quiet hours honor inclusive start exclusive end and timezone`() {
        val silence = RelaySilence(quietEnabled = true)
        val utc = TimeZone.getTimeZone("UTC")
        assertNull(silence.reason(time(21, 59), utc))
        assertEquals("quiet_hours", silence.reason(time(22), utc))
        assertEquals("quiet_hours", silence.reason(time(6, 59), utc))
        assertNull(silence.reason(time(7), utc))
        assertEquals("quiet_hours", silence.reason(time(20), TimeZone.getTimeZone("GMT+03:00")))
    }
    @Test fun `pause expires without replay and takes precedence over quiet hours`() {
        val now = time(23)
        assertEquals("paused", RelaySilence(-1).reason(now))
        assertEquals("paused", RelaySilence(now + 1).reason(now))
        assertNull(RelaySilence(now).reason(now))
        assertEquals("quiet_hours", RelaySilence(now, true).reason(now, TimeZone.getTimeZone("UTC")))
    }
    @Test fun `daytime quiet window and disabled equal times`() {
        val silence = RelaySilence(quietEnabled = true, startMinute = 540, endMinute = 1020)
        assertEquals("quiet_hours", silence.reason(time(12), TimeZone.getTimeZone("UTC")))
        assertNull(silence.reason(time(18), TimeZone.getTimeZone("UTC")))
        assertNull(RelaySilence(startMinute = 0, endMinute = 0).reason(time(12)))
    }
    @Test(expected = IllegalArgumentException::class) fun `equal enabled times rejected`() { RelaySilence(quietEnabled = true, startMinute = 0, endMinute = 0) }
    @Test fun `silenced notifications advance duplicate baseline and are not replayed`() {
        val filter = CalendarAlertFilter(100, selectedPackages = setOf(whatsapp))
        assertTrue(filter.accept(whatsapp, "id", 101, 102, false, false, 101))
        // Caller suppresses the accepted alert during pause, but freshness is retained.
        assertFalse(filter.accept(whatsapp, "id", 101, 105, false, false, 101))
        assertTrue(filter.accept(whatsapp, "id", 106, 107, false, false, 106))
    }
}
