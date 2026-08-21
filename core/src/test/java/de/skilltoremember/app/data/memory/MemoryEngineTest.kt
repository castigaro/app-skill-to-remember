package de.skilltoremember.app.data.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import kotlin.math.abs

/**
 * Szenario und erwartete Werte reproduzierbar mit dem echten
 * `memory.py`-Original (siehe Kommentare je Test) — nicht nur gegen die
 * eigene Portierung geprüft, sondern gegen das Verhalten, das nachgebaut
 * werden soll.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryEngineTest {

    private lateinit var root: File
    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        root = File.createTempFile("memory-test", "").apply { delete(); mkdirs() }
        store = MemoryStore(root)
        MemoryEngine.createFresh(store, gitUrl = null, branch = "main")
    }

    @After
    fun tearDown() {
        MemoryClock.freeze(null)
        root.deleteRecursively()
    }

    @Test
    fun `makeId liefert layer-prefixierten Content-Hash`() {
        assertEquals("idt_1b41e5effa", MemoryEngine.makeId("idt", "self.name", "Works with Torsten"))
        assertEquals("sem_d375bd2223", MemoryEngine.makeId("sem", "pref.shell", "PowerShell 5.1 direkt, nie pwsh"))
    }

    @Test
    fun `strength verfaellt nach Halbwertszeit`() {
        val at = Instant.parse("2026-08-20T09:00:00Z")
        // sem hat Halbwertszeit 180 Tage: nach genau einer Halbwertszeit halbiert sich die Basis-Salience.
        val entry = MemoryEntry(
            id = "sem_x", l = "sem", t = "t", v = "v", s = 0.8, c = 0.9, f = 0,
            ts = "2026-02-21T09:00:00Z", la = "2026-02-21T09:00:00Z",
        )
        val strength = MemoryEngine.strength(entry, at)
        assertTrue("erwartet ~0.4, war $strength", abs(strength - 0.4) < 0.01)

        // idt verfällt nie (half-life null).
        val identity = MemoryEntry(id = "idt_x", l = "idt", t = "t", v = "v", s = 0.9, c = 0.9, ts = entry.ts, la = "2020-01-01T00:00:00Z")
        assertEquals(0.9, MemoryEngine.strength(identity, at), 0.0001)
    }

    @Test
    fun `strength haertet durch Abrufe, Alter zaehlt ab dem letzten Zugriff`() {
        val at = Instant.parse("2026-08-20T09:00:00Z")
        val neverRecalled = MemoryEntry(
            id = "sem_a", l = "sem", t = "t", v = "v", s = 0.8, c = 0.9, f = 0,
            ts = "2026-02-21T09:00:00Z", la = "2026-02-21T09:00:00Z",
        )
        // Gleiches Alter, aber zehnmal abgerufen (rehearsal-Faktor > 1).
        val oftenRecalled = MemoryEntry(
            id = "sem_b", l = "sem", t = "t", v = "v", s = 0.8, c = 0.9, f = 10,
            ts = "2026-02-21T09:00:00Z", la = "2026-02-21T09:00:00Z",
        )
        assertTrue(MemoryEngine.strength(oftenRecalled, at) > MemoryEngine.strength(neverRecalled, at))

        // Ein kürzlich abgerufener alter Eintrag zählt sein Alter ab `la`, nicht ab `ts`.
        val recentlyRehearsed = MemoryEntry(
            id = "sem_c", l = "sem", t = "t", v = "v", s = 0.8, c = 0.9, f = 0,
            ts = "2020-01-01T00:00:00Z", la = "2026-08-19T09:00:00Z",
        )
        assertTrue(MemoryEngine.strength(recentlyRehearsed, at) > 0.79)
    }

    @Test
    fun `remember unterhalb der Schwelle wird verworfen`() {
        val result = MemoryEngine.remember(store, "sem", "chatter.test", "Said good morning", salience = 0.05)
        assertEquals(false, result.stored)
        assertEquals("skipped", result.outcome)
        assertTrue(store.loadEntries().isEmpty())
    }

    @Test
    fun `remember verstaerkt eine bestehende ID statt sie zu duplizieren`() {
        MemoryEngine.remember(store, "sem", "pref.shell", "PowerShell 5.1 direkt, nie pwsh", salience = 0.9, confidence = 0.9)
        val second = MemoryEngine.remember(store, "sem", "pref.shell", "PowerShell 5.1 direkt, nie pwsh", salience = 0.9, confidence = 0.95)
        assertEquals("reinforced", second.outcome)
        assertEquals(1, store.loadEntries().size)
        assertEquals(1, store.loadEntries().values.first().f)
    }

    // Reproduziert exakt das Szenario aus dem echten memory.py (siehe DigestRenderTest):
    // vier gespeicherte Einträge, ein fünfter unterhalb der Schwelle verworfen,
    // dann `recall("powershell shell")`.
    @Test
    fun `recall liefert den erwarteten Treffer`() {
        MemoryClock.freeze(Instant.parse("2026-08-20T09:00:00Z"))
        MemoryEngine.remember(store, "idt", "self.name", "Works with Torsten", salience = 0.95, confidence = 0.95)
        MemoryEngine.remember(store, "sem", "pref.shell", "PowerShell 5.1 direkt, nie pwsh", salience = 0.9, confidence = 0.95)
        MemoryEngine.remember(store, "sem", "pref.lang", "Prefers German in conversation", salience = 0.85, confidence = 0.9)
        MemoryEngine.remember(store, "epi", "proj.demo.deploy", "The deploy failed on a missing credential", salience = 0.55, confidence = 0.7)
        MemoryEngine.remember(store, "sem", "chatter.test", "Said good morning", salience = 0.05)

        MemoryClock.freeze(Instant.parse("2026-08-20T09:05:00Z"))
        val result = MemoryEngine.recall(store, query = "powershell shell")

        assertEquals(1, result.hits.size)
        assertEquals("sem_d375bd2223", result.hits.first().id)
    }

    @Test
    fun `forget archiviert mit Tombstone und ein Sync von einer anderen Maschine haelt es nicht zurueck`() {
        MemoryEngine.remember(store, "sem", "pref.editor", "Uses VS Code", salience = 0.6)
        val id = MemoryEngine.makeId("sem", "pref.editor", "Uses VS Code")

        val forgetResult = MemoryEngine.forget(store, ids = listOf(id))
        assertEquals(listOf(id), forgetResult.forgottenIds)
        assertTrue(store.loadEntries().isEmpty())

        val archived = store.archive()
        assertEquals(1, archived.size)
        assertTrue("Tombstone-Feld muss gesetzt sein", archived.first().has("tomb"))
    }

    @Test
    fun `consolidate merged Duplikate und verdichtet wiederkehrende Episoden`() {
        MemoryEngine.remember(store, "epi", "proj.demo.deploy", "Deploy failed: missing credential", salience = 0.5)
        MemoryEngine.remember(store, "epi", "proj.demo.deploy", "Deploy failed: missing token", salience = 0.5)
        MemoryEngine.remember(store, "epi", "proj.demo.deploy", "Deploy failed: expired cert", salience = 0.5)

        val result = MemoryEngine.consolidate(store)

        assertEquals(1, result.promoted.size)
        val entries = store.loadEntries()
        val gist = entries[result.promoted.first()]!!
        assertEquals("sem", gist.l)
        assertTrue(gist.v.startsWith("recurring:"))
    }

    @Test
    fun `consolidate laesst schwache, nie abgerufene Eintraege verblassen`() {
        MemoryEngine.remember(store, "sem", "trivia.one", "Ein sehr schwacher Fakt", salience = 0.36)
        // Kurz nach der Halbwertszeit-Schwelle in die Zukunft springen, ohne recall (f bleibt 0).
        MemoryClock.freeze(Instant.parse("2027-08-20T09:00:00Z"))

        val result = MemoryEngine.consolidate(store)

        assertTrue(result.archived.isNotEmpty())
        assertTrue(store.loadEntries().isEmpty())
        assertEquals(1, store.archive().size)
    }
}
