package de.skilltoremember.app.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import de.skilltoremember.app.data.Message
import de.skilltoremember.app.data.Skill
import de.skilltoremember.app.data.memory.MemoryEngine
import de.skilltoremember.app.data.memory.MemoryStore
import java.nio.file.Files
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
    fun `Skill-Autor hebt kleine Modelle an, laesst grosse in Ruhe`() {
        assertEquals("claude-sonnet-5", ChatApi.skillAuthorModel("anthropic", "claude-haiku-4-5"))
        assertEquals("claude-sonnet-5", ChatApi.skillAuthorModel("anthropic", "claude-sonnet-5"))
        assertEquals("claude-opus-4-8", ChatApi.skillAuthorModel("anthropic", "claude-opus-4-8"))
        assertEquals("gpt-4o", ChatApi.skillAuthorModel("openai", "gpt-4o-mini"))
        assertEquals("gpt-4o", ChatApi.skillAuthorModel("openai", "gpt-4o"))
    }

    @Test
    fun `Code-Zaeune um den Skill-Entwurf werden entfernt`() {
        assertEquals("---\nname: test\n---\nBody", ChatApi.stripCodeFence("---\nname: test\n---\nBody"))
        assertEquals("---\nname: test\n---\nBody", ChatApi.stripCodeFence("```markdown\n---\nname: test\n---\nBody\n```"))
        assertEquals("---\nname: test\n---\nBody", ChatApi.stripCodeFence("```\n---\nname: test\n---\nBody\n```"))
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

    // ---- Verlaufsbegrenzung ----

    @Test
    fun `kurzer Verlauf geht unveraendert an die API`() {
        val verlauf = (1..5).map { Message(Message.ROLE_USER, "Nachricht $it") }
        assertEquals(verlauf, ChatApi.letzteNachrichten(verlauf))
    }

    @Test
    fun `langer Verlauf wird auf die juengsten Nachrichten gekuerzt`() {
        val verlauf = (1..50).map { Message(Message.ROLE_USER, "Nachricht $it") }
        val gekuerzt = ChatApi.letzteNachrichten(verlauf)

        assertEquals(ChatApi.MAX_VERLAUF_NACHRICHTEN, gekuerzt.size)
        assertEquals("Nachricht 50", gekuerzt.last().text)
        assertEquals("Nachricht 31", gekuerzt.first().text)
    }

    // ---- recall-Tool: limit und bump kommen bei der Engine an ----

    private fun storeMitListe(artikel: Int): MemoryStore {
        val store = MemoryStore(Files.createTempDirectory("memory").toFile())
        MemoryEngine.createFresh(store, gitUrl = null, branch = "main")
        for (i in 1..artikel) {
            MemoryEngine.remember(store, "sem", "list.einkauf.artikel$i", "Artikel $i", 0.6)
        }
        return store
    }

    @Test
    fun `recall liefert ohne limit hoechstens acht Treffer`() {
        val store = storeMitListe(12)
        val text = ChatApi.executeRecall(JSONObject().put("query", "").put("topic", "list.einkauf"), store)
        assertEquals(8, text.lines().size)
    }

    @Test
    fun `recall liefert mit hohem limit die ganze Liste`() {
        val store = storeMitListe(12)
        val text = ChatApi.executeRecall(
            JSONObject().put("query", "").put("topic", "list.einkauf").put("limit", 50),
            store,
        )
        assertEquals(12, text.lines().size)
    }

    @Test
    fun `recall mit bump false ist ein reiner Lesezugriff`() {
        val store = storeMitListe(3)
        val vorher = store.loadEntries().values.map { it.la to it.f }.toSet()
        ChatApi.executeRecall(
            JSONObject().put("query", "").put("topic", "list.einkauf").put("limit", 50).put("bump", false),
            store,
        )
        val nachher = store.loadEntries().values.map { it.la to it.f }.toSet()
        assertEquals(vorher, nachher)
    }

    // ---- Systemprompt: die Regel gegen das Schein-Speichern ----

    @Test
    fun `Prompt verbietet Speicherbestaetigungen ohne Werkzeug-Aufruf`() {
        val prompt = ChatApi.buildSystemPrompt(emptyList(), memoryActive = true, digest = "Digest", concise = false)
        assertTrue(prompt.contains("NIEMALS"))
        assertTrue(prompt.contains("remember"))
    }

    @Test
    fun `Skill-Block verlangt das Laden vor dem Merken`() {
        val skill = Skill(id = "s1", name = "einkaufsliste", description = "Einkaufsliste", body = "...")
        val prompt = ChatApi.buildSystemPrompt(listOf(skill), memoryActive = true, digest = null, concise = false)
        assertTrue(prompt.contains("bevor du etwas merkst"))
    }

    @Test
    fun `Knapp-Modus nimmt Werkzeuge von der Kuerze aus`() {
        val prompt = ChatApi.buildSystemPrompt(emptyList(), memoryActive = false, digest = null, concise = true)
        assertTrue(prompt.contains("NUR für den gesprochenen Text"))
    }
}
