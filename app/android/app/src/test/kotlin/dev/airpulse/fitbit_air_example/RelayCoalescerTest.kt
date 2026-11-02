package dev.airpulse.fitbit_air_example

import dev.airpulse.fitbit_air_example.RelayCoalescer.Decision
import org.junit.Assert.*
import org.junit.Test

class RelayCoalescerTest {
    @Test fun firstAlertDispatchesImmediately() {
        assertEquals(Decision.DispatchNow, RelayCoalescer(20_000).onAlert("a", 0))
    }

    @Test fun burstDuringCommandBecomesOneFollowUpAfterCooldown() {
        val coalescer = RelayCoalescer(20_000)
        coalescer.onAlert("a", 0)
        assertEquals(Decision.Merged(null), coalescer.onAlert("b", 1_000))
        assertEquals(Decision.Merged(null), coalescer.onAlert("c", 2_000))
        assertEquals(28_000L, coalescer.onFinished(8_000))
        assertEquals(listOf("b", "c"), coalescer.takeFollowUp())
        assertNull(coalescer.onFinished(35_000))
        assertFalse(coalescer.hasFollowUp)
    }

    @Test fun alertDuringCooldownSchedulesFollowUpOnce() {
        val coalescer = RelayCoalescer(20_000)
        coalescer.onAlert("a", 0)
        assertNull(coalescer.onFinished(8_000))
        assertEquals(Decision.Merged(28_000), coalescer.onAlert("b", 10_000))
        assertEquals(Decision.Merged(null), coalescer.onAlert("c", 12_000))
        assertEquals(listOf("b", "c"), coalescer.takeFollowUp())
    }

    @Test fun alertAfterCooldownDispatchesImmediately() {
        val coalescer = RelayCoalescer(20_000)
        coalescer.onAlert("a", 0)
        coalescer.onFinished(8_000)
        assertEquals(Decision.DispatchNow, coalescer.onAlert("b", 28_000))
    }

    @Test fun alertWhileFollowUpRunsJoinsTheNextOne() {
        val coalescer = RelayCoalescer(20_000)
        coalescer.onAlert("a", 0)
        coalescer.onAlert("b", 1_000)
        coalescer.onFinished(8_000)
        coalescer.takeFollowUp()
        assertEquals(Decision.Merged(null), coalescer.onAlert("c", 29_000))
        assertEquals(55_000L, coalescer.onFinished(35_000))
    }

    @Test fun cancelDropsWaitingAlertsAndAllowsImmediateDispatchLater() {
        val coalescer = RelayCoalescer(20_000)
        coalescer.onAlert("a", 0)
        coalescer.onFinished(8_000)
        coalescer.onAlert("b", 10_000)
        assertEquals(listOf("b"), coalescer.cancelFollowUp())
        assertFalse(coalescer.hasFollowUp)
        assertEquals(Decision.DispatchNow, coalescer.onAlert("c", 40_000))
    }
}
