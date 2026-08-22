package de.skilltoremember.app.api

import android.content.Intent
import android.provider.CalendarContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceActionsTest {

    @Test
    fun `Kalender-Nachricht der Uhr wird zum Insert-Intent`() {
        val payload = JSONObject()
            .put("type", "calendar")
            .put("title", "Max anrufen")
            .put("startMillis", 1_766_400_000_000L)
            .put("location", "Büro")
        val intent = DeviceActions.intentFor(payload)!!
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals("Max anrufen", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(1_766_400_000_000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L))
        assertEquals("Büro", intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
    }

    @Test
    fun `E-Mail-Nachricht der Uhr wird zum mailto-Intent`() {
        val payload = JSONObject()
            .put("type", "email")
            .put("to", "max@example.org")
            .put("subject", "Komme später")
            .put("body", "Bin gegen 18 Uhr da.")
        val intent = DeviceActions.intentFor(payload)!!
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertTrue(intent.dataString!!.startsWith("mailto:max%40example.org"))
        assertEquals("Komme später", intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `Unbekannter Nachrichtentyp wird verworfen`() {
        assertNull(DeviceActions.intentFor(JSONObject().put("type", "raketenstart")))
    }
}
