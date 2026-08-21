package de.skilltoremember.app.data.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

/**
 * Der erwartete Digest-Text stammt wortwörtlich vom echten `memory.py`
 * (gleiches Szenario, gleiche eingefrorene Uhr) — reproduzierbar mit dem
 * Skript in `castigaro/my-ai-skills/skills/humanoid-behavior/scripts/`.
 * Byte-für-Byte-Gleichheit ist hier der Punkt: Nur so bleibt ein Speicher
 * zwischen der Python-Engine (Desktop) und dieser App (Handy) austauschbar.
 */
@RunWith(RobolectricTestRunner::class)
class DigestRenderTest {

    private lateinit var root: File
    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        root = File.createTempFile("digest-test", "").apply { delete(); mkdirs() }
        store = MemoryStore(root)
        MemoryClock.freeze(Instant.parse("2026-08-20T09:00:00Z"))
        MemoryEngine.createFresh(store, gitUrl = null, branch = "main")
    }

    @After
    fun tearDown() {
        MemoryClock.freeze(null)
        root.deleteRecursively()
    }

    @Test
    fun `Digest entspricht wortwoertlich der Referenz aus dem Python-Original`() {
        MemoryEngine.remember(store, "idt", "self.name", "Works with Torsten", salience = 0.95, confidence = 0.95)
        MemoryEngine.remember(store, "sem", "pref.shell", "PowerShell 5.1 direkt, nie pwsh", salience = 0.9, confidence = 0.95)
        MemoryEngine.remember(store, "sem", "pref.lang", "Prefers German in conversation", salience = 0.85, confidence = 0.9)
        MemoryEngine.remember(store, "epi", "proj.demo.deploy", "The deploy failed on a missing credential", salience = 0.55, confidence = 0.7)
        MemoryEngine.remember(store, "sem", "chatter.test", "Said good morning", salience = 0.05) // unter der Schwelle, verworfen

        MemoryClock.freeze(Instant.parse("2026-08-20T09:05:00Z"))
        MemoryEngine.recall(store, query = "powershell shell") // rehearsed sem_d375bd2223 -> f=1

        MemoryClock.freeze(Instant.parse("2026-08-20T09:10:00Z"))
        val boot = MemoryEngine.boot(store)

        val expected = """
            # MEMORY DIGEST — 2026-08-20 · 4 entries · character age 0 day(s)

            ## WHO (identity)
            - [idt_1b41e5effa] self.name :: Works with Torsten (0.95/0)

            ## KNOWN (semantic)
            - [sem_d375bd2223] pref.shell :: PowerShell 5.1 direkt, nie pwsh (0.99/1)
            - [sem_0598c4c30d] pref.lang :: Prefers German in conversation (0.85/0)

            ## LIVED (episodic)
            - [epi_1a4382094b] proj.demo.deploy :: The deploy failed on a missing credential (0.55/0)

            ## TOPIC MAP
            pref(2) · self(1) · proj(1)
        """.trimIndent() + "\n"

        assertEquals(expected, boot.digest)
    }

    @Test
    fun `leerer Speicher zeigt EMPTY CHARACTER`() {
        val boot = MemoryEngine.boot(store)
        assertEquals(true, boot.digest.contains("EMPTY CHARACTER"))
    }
}
