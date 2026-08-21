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
        val with = ChatApi.anthropicTools(emptyList(), null, webSearch = true)
        assertEquals(1, with.length())
        assertEquals("web_search_20250305", with.getJSONObject(0).getString("type"))
        assertEquals("web_search", with.getJSONObject(0).getString("name"))

        assertEquals(0, ChatApi.anthropicTools(emptyList(), null, webSearch = false).length())
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
