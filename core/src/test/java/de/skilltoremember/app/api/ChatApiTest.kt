package de.skilltoremember.app.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class ChatApiTest {

    private val now = Instant.parse("2026-08-21T12:00:00Z")

    @Test
    fun `Pull ist faellig ohne oder mit unlesbarem letzten Sync`() {
        assertTrue(ChatApi.isMemoryPullDue(null, now))
        assertTrue(ChatApi.isMemoryPullDue("kein datum", now))
    }

    @Test
    fun `Pull ist faellig, wenn der letzte Sync aelter als das Intervall ist`() {
        assertTrue(ChatApi.isMemoryPullDue("2026-08-21T11:54:59Z", now)) // > 5 Minuten
        assertTrue(ChatApi.isMemoryPullDue("2026-08-21T11:55:00Z", now)) // genau 5 Minuten
    }

    @Test
    fun `Pull wird innerhalb des Intervalls uebersprungen`() {
        assertFalse(ChatApi.isMemoryPullDue("2026-08-21T11:58:00Z", now))
        assertFalse(ChatApi.isMemoryPullDue("2026-08-21T12:00:00Z", now))
    }
}
