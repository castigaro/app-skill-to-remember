package de.skilltoremember.app.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Referenzwerte erzeugt mit dem echten Python-Original:
 * `python3 -c "import hashlib; print(hashlib.blake2s(b'...', digest_size=5).hexdigest())"`
 * — inklusive Block-Grenzfällen (63/64/65 Byte) und UTF-8-Mehrbyte-Zeichen,
 * weil genau dort BLAKE2s-Implementierungen typischerweise brechen.
 */
@RunWith(RobolectricTestRunner::class)
class Blake2sTest {

    private fun hex5(text: String) = Blake2s.hashHex(text, 5)

    @Test
    fun `leerer String`() {
        assertEquals("0c58705f4f", hex5(""))
    }

    @Test
    fun `einzelne Zeichen`() {
        assertEquals("cc5b699b0e", hex5("a"))
        assertEquals("fe4d57ba07", hex5("abc"))
    }

    @Test
    fun `echte Entry-Seeds aus memory-model md`() {
        assertEquals("1b41e5effa", hex5("idt|self.name|works with torsten"))
        assertEquals("d375bd2223", hex5("sem|pref.shell|powershell 5.1 direkt, nie pwsh"))
    }

    @Test
    fun `Block-Grenzfaelle 63, 64 und 65 Byte`() {
        assertEquals("302a2a15ca", hex5("x".repeat(63)))
        assertEquals("af1ef3b313", hex5("x".repeat(64)))
        assertEquals("f4cf236663", hex5("x".repeat(65)))
    }

    @Test
    fun `mehrere Bloecke, 200 Byte`() {
        assertEquals("e75c79840f", hex5("x".repeat(200)))
    }

    @Test
    fun `UTF-8-Mehrbyte-Zeichen`() {
        assertEquals("2fa01207fb", hex5("Prefers German — with an em dash and ä ö ü ß"))
    }
}
