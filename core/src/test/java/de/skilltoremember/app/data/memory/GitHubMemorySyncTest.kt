package de.skilltoremember.app.data.memory

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class GitHubMemorySyncTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var store: MemoryStore
    private lateinit var config: GitHubMemoryConfig

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        GitHubMemorySync.apiBaseUrl = server.url("").toString().trimEnd('/')

        root = File.createTempFile("sync-test", "").apply { delete(); mkdirs() }
        store = MemoryStore(root)
        MemoryEngine.createFresh(store, gitUrl = null, branch = "main")
        config = GitHubMemoryConfig("owner", "repo", "main", "token-123")
    }

    @After
    fun tearDown() {
        MemoryClock.freeze(null)
        server.shutdown()
        root.deleteRecursively()
    }

    private fun notFound() = MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}""")

    private fun contentsResponse(text: String) = MockResponse().setResponseCode(200).setBody(
        JSONObject()
            .put("content", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
            .put("encoding", "base64")
            .toString(),
    )

    @Test
    fun `pull gegen ein leeres Remote-Repo liefert false und aendert nichts lokal`() {
        repeat(7) { server.enqueue(notFound()) } // character + 3 layer + edges + archive + journal

        val sawRemoteFile = GitHubMemorySync.pull(store, config)

        assertFalse(sawRemoteFile)
        assertTrue(store.loadEntries().isEmpty())
    }

    @Test
    fun `pull uebernimmt Eintraege aus dem Remote-Repo`() {
        val remoteCharacter = JSONObject().apply {
            put("schema", 1)
            put("entries", org.json.JSONArray().put(
                JSONObject().apply {
                    put("id", "idt_1b41e5effa"); put("l", "idt"); put("t", "self.name")
                    put("v", "Works with Torsten"); put("s", 0.95); put("c", 0.95); put("f", 0)
                    put("ts", "2026-08-20T09:00:00Z"); put("la", "2026-08-20T09:00:00Z")
                    put("src", ""); put("k", org.json.JSONArray()); put("lk", org.json.JSONArray())
                },
            ))
        }
        server.enqueue(contentsResponse(remoteCharacter.toString())) // character.json
        repeat(6) { server.enqueue(notFound()) } // sem/epi/prc layers, edges, archive, journal

        val sawRemoteFile = GitHubMemorySync.pull(store, config)

        assertTrue(sawRemoteFile)
        val entries = store.loadEntries()
        assertEquals(1, entries.size)
        assertEquals("Works with Torsten", entries.getValue("idt_1b41e5effa").v)
    }

    @Test
    fun `pull belebt einen lokal vergessenen Eintrag nicht wieder`() {
        MemoryClock.freeze(Instant.parse("2026-08-20T09:00:00Z"))
        val entry = MemoryEngine.remember(store, "sem", "pref.shell", "PowerShell 5.1", salience = 0.9).entry!!
        val staleRemoteLine = CanonicalJson.of(entry.toJson())
        MemoryClock.freeze(Instant.parse("2026-08-20T10:00:00Z"))
        MemoryEngine.forget(store, ids = listOf(entry.id))

        server.enqueue(notFound()) // character.json
        server.enqueue(contentsResponse(staleRemoteLine + "\n")) // semantic.jsonl — remote kennt die Löschung noch nicht
        repeat(5) { server.enqueue(notFound()) } // epi, prc, edges, archive, journal

        GitHubMemorySync.pull(store, config)

        assertFalse(store.loadEntries().containsKey(entry.id))
        // Der Tombstone bleibt erhalten, damit der folgende Push die Löschung propagiert.
        assertTrue(store.archive().any { it.optString("id") == entry.id && it.has("tomb") })
    }

    @Test
    fun `pull toetet einen nach der Loeschung neu gelernten Eintrag nicht`() {
        MemoryClock.freeze(Instant.parse("2026-08-20T09:00:00Z"))
        val first = MemoryEngine.remember(store, "sem", "pref.shell", "PowerShell 5.1", salience = 0.9).entry!!
        MemoryClock.freeze(Instant.parse("2026-08-20T10:00:00Z"))
        MemoryEngine.forget(store, ids = listOf(first.id))
        val remoteArchiveText = store.archive().joinToString("\n") { CanonicalJson.of(it) } + "\n"
        MemoryClock.freeze(Instant.parse("2026-08-20T11:00:00Z"))
        val relearned = MemoryEngine.remember(store, "sem", "pref.shell", "PowerShell 5.1", salience = 0.9).entry!!
        assertEquals(first.id, relearned.id)

        repeat(5) { server.enqueue(notFound()) } // character, 3 layer, edges
        server.enqueue(contentsResponse(remoteArchiveText)) // archive.jsonl mit dem Tombstone von 10:00
        server.enqueue(notFound()) // journal

        GitHubMemorySync.pull(store, config)

        assertTrue(store.loadEntries().containsKey(first.id))
        // Der überholte Tombstone ist auch aus dem lokalen Archiv verschwunden.
        assertTrue(store.archive().none { it.optString("id") == first.id && it.has("tomb") })
    }

    @Test
    fun `readRemoteMeta laedt Dateien ueber 1 MB als Raw nach`() {
        // Ab 1 MB liefert die Contents-API "encoding": "none" ohne Inhalt.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                JSONObject().put("content", "").put("encoding", "none").put("size", 2_000_000).toString(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"schema":1,"root_id":"r"}"""))

        val meta = GitHubMemorySync.readRemoteMeta(config)!!

        assertEquals(1, meta.getInt("schema"))
        server.takeRequest()
        assertEquals("application/vnd.github.raw+json", server.takeRequest().getHeader("Accept"))
    }

    @Test
    fun `push auf ein frisches Repo legt den Ref per POST an, nicht per PATCH`() {
        server.enqueue(notFound()) // GET /git/ref/heads/main -- existiert noch nicht
        repeat(9) { i -> server.enqueue(MockResponse().setResponseCode(201).setBody("""{"sha":"blob-$i"}""")) }
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"sha":"tree-1"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"sha":"commit-1"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ref":"refs/heads/main"}"""))

        val pushed = GitHubMemorySync.push(store, config, "memory: init")

        assertTrue(pushed)
        // Erste Anfrage: Ref-Abfrage. Letzte Anfrage muss ein POST auf /git/refs sein
        // (Ref-Erstellung), kein PATCH auf /git/refs/heads/main (das wäre ein Update).
        // GET ref + 9x POST blob + POST tree + POST commit + POST refs = 13 Anfragen.
        val requests = (0 until 13).map { server.takeRequest() }
        assertEquals("GET", requests[0].method)
        assertTrue(requests[0].path!!.contains("/git/ref/heads/main"))
        val last = requests.last()
        assertEquals("POST", last.method)
        assertTrue(last.path!!.endsWith("/git/refs"))
    }

    // Vorher schrieb jeder Sync einen Commit, auch wenn sich keine Datei
    // geändert hatte — im Gedächtnis-Repo als "0 files changed" aufgetaucht.
    @Test
    fun `push committet nicht, wenn der Baum unveraendert bleibt`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":{"sha":"parent-1"}}"""))
        repeat(9) { i -> server.enqueue(MockResponse().setResponseCode(201).setBody("""{"sha":"blob-$i"}""")) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"tree":{"sha":"tree-alt"}}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"sha":"tree-alt"}""")) // dieselbe SHA

        val pushed = GitHubMemorySync.push(store, config, "memory: ohne Aenderung")

        assertFalse(pushed)
        // GET ref + 9x Blob + GET commit + POST tree = 12; danach nichts mehr.
        val pfade = (0 until 12).map { server.takeRequest().path!! }
        assertTrue(pfade.none { it.endsWith("/git/commits") })
        assertNull(server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS))
    }
}
