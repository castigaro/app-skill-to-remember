package de.skilltoremember.app.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

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

    @Test
    fun `Websuche-Tool steht nur bei eingeschalteter Websuche in der Werkzeugliste`() {
        val with = ChatApi.anthropicTools(emptyList(), null, webSearch = true, location = false)
        assertEquals(1, with.length())
        assertEquals("web_search_20250305", with.getJSONObject(0).getString("type"))
        assertEquals("web_search", with.getJSONObject(0).getString("name"))

        assertEquals(0, ChatApi.anthropicTools(emptyList(), null, webSearch = false, location = false).length())
    }

    @Test
    fun `Standort-Tool steht nur bei eingeschaltetem Standort in der Werkzeugliste`() {
        val with = ChatApi.anthropicTools(emptyList(), null, webSearch = false, location = true)
        assertEquals(1, with.length())
        assertEquals("get_location", with.getJSONObject(0).getString("name"))
    }

    @Test
    fun `Kalender- und Mail-Werkzeug erscheinen nur mit deviceActions`() {
        val with = ChatApi.anthropicTools(emptyList(), null, webSearch = false, location = false, deviceActions = true)
        assertEquals(2, with.length())
        assertEquals("add_calendar_event", with.getJSONObject(0).getString("name"))
        assertEquals("draft_email", with.getJSONObject(1).getString("name"))

        assertEquals(0, ChatApi.anthropicTools(emptyList(), null, webSearch = false, location = false).length())
    }

    @Test
    fun `ISO-Zeiten werden mit und ohne Uhrzeit verstanden`() {
        val dateOnly = ChatApi.parseLocalDateTimeMillis("2026-09-20")
        val midnight = ChatApi.parseLocalDateTimeMillis("2026-09-20T00:00")
        val morning = ChatApi.parseLocalDateTimeMillis(" 2026-09-20T10:00 ")
        assertEquals(midnight, dateOnly)
        assertTrue(morning!! > midnight!!)
        assertEquals(null, ChatApi.parseLocalDateTimeMillis("morgen um zehn"))
        assertEquals(null, ChatApi.parseLocalDateTimeMillis(""))
    }

    @Test
    fun `System-Prompt-Zeile nennt Wochentag, Datum, Uhrzeit und Zeitzone`() {
        val line = ChatApi.dateTimeLine(ZonedDateTime.of(2026, 8, 22, 9, 30, 0, 0, ZoneId.of("Europe/Berlin")))
        assertTrue(line.contains("Samstag"))
        assertTrue(line.contains("22. August 2026"))
        assertTrue(line.contains("09:30"))
        assertTrue(line.contains("Europe/Berlin"))
    }

    @Test
    fun `Antwort wird aus allen Text-Bloecken zusammengesetzt, Such-Bloecke uebersprungen`() {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Es sind "))
            .put(JSONObject().put("type", "server_tool_use").put("name", "web_search"))
            .put(JSONObject().put("type", "web_search_tool_result").put("content", JSONArray()))
            .put(JSONObject().put("type", "text").put("text", "12 Grad."))
        assertEquals("Es sind 12 Grad.", ChatApi.extractAnthropicText(content))
    }

    @Test
    fun `Zitierte Quellen werden dedupliziert als Block angehaengt`() {
        val citation = JSONObject().put("url", "https://example.org/wetter").put("title", "Wetterbericht")
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Sonnig. ").put("citations", JSONArray().put(citation)))
            .put(JSONObject().put("type", "text").put("text", "Morgen Regen.").put("citations", JSONArray().put(citation)))
        val text = ChatApi.extractAnthropicText(content)
        assertTrue(text.startsWith("Sonnig. Morgen Regen."))
        assertTrue(text.endsWith("${ChatApi.SOURCES_HEADING}\n• Wetterbericht — https://example.org/wetter"))
    }

    @Test
    fun `Ohne Zitate kein Quellen-Block`() {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "Hallo!"))
        assertEquals("Hallo!", ChatApi.extractAnthropicText(content))
    }
}
