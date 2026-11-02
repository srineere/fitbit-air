package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirWarmLinkTest {
    @Test fun `a freshly subscribed link is not reused until it has settled`() {
        assertFalse(AirWarmLink.settled(readyAtMillis = 10_000, nowMillis = 10_500))
        assertFalse(AirWarmLink.settled(10_000, 10_000 + AirWarmLink.SETTLE_MILLIS - 1))
        assertTrue(AirWarmLink.settled(10_000, 10_000 + AirWarmLink.SETTLE_MILLIS))
    }
}
