package dev.airpulse.fitbit_air_example

import org.junit.Assert.*
import org.junit.Test

class RelayActivityTest {
    @Test fun retainsOnlyTheNewestHundredEventsInOrder() {
        val buffer = RelayActivityBuffer()
        repeat(120) { buffer.add(RelayActivity("command-$it", "com.whatsapp", "queued", it.toLong())) }
        assertEquals(100, buffer.snapshot().size)
        assertEquals("command-20", buffer.snapshot().first().commandId)
        assertEquals("command-119", buffer.snapshot().last().commandId)
    }

    @Test fun historyOnlyExposesMetadataFields() {
        val value = RelayActivity("command-1", "com.whatsapp", "verified", 100, 20).toMap()
        assertEquals(setOf("commandId", "sourcePackage", "stage", "atMillis", "latencyMillis", "errorCode", "preset", "tone"), value.keys)
        assertEquals(20L, value["latencyMillis"])
    }

    @Test fun protocolCompletionIsExplicitAndUnknownRepliesFailClosed() {
        assertEquals("verified", RelayWorkerResult.decode(mapOf("status" to "completed")).stage)
        assertEquals("failed", RelayWorkerResult.decode("completed").stage)
        assertEquals("WORKER_PROTOCOL", RelayWorkerResult.decode(mapOf("status" to "felt_it")).errorCode)
        assertEquals("expired", RelayWorkerResult.decode(mapOf("status" to "expired")).stage)
    }

    @Test fun errorMessagesAreNeverCopiedIntoHistory() {
        val value = RelayWorkerResult.decode(mapOf("status" to "failed", "errorCode" to "TIMEOUT", "error" to "private data"))
        assertEquals("TIMEOUT", value.errorCode)
        assertNull(RelayWorkerResult.decode(mapOf("status" to "failed", "errorCode" to "private message!")).errorCode)
    }
}
