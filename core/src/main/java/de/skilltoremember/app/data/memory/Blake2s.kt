package de.skilltoremember.app.data.memory

import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * BLAKE2s (RFC 7693), keyless, konfigurierbare Digest-Größe. Selbst
 * implementiert statt einer Krypto-Bibliothek (Bouncy Castle o. Ä.) für
 * diesen einen Hash — `java.security.MessageDigest` kennt BLAKE2 nicht.
 *
 * Wird für die Content-Hash-IDs des Gedächtnisses gebraucht (siehe
 * [MemoryEngine.makeId]) und muss exakt dieselben Werte liefern wie Pythons
 * `hashlib.blake2s(seed, digest_size=5)` in `memory.py` — sonst würde ein am
 * Handy gelernter Fakt nicht mit demselben, unabhängig am Desktop gelernten
 * Fakt zusammengeführt. Gegen echte, aus dem Python-Original erzeugte
 * Referenzwerte verifiziert, siehe `Blake2sTest`.
 */
object Blake2s {

    // Unsigned Literale + toInt(), um Vorzeichen-Tippfehler beim Übertragen
    // der 32-Bit-Konstanten (RFC 7693) auszuschließen.
    private val IV = intArrayOf(
        0x6a09e667u.toInt(), 0xbb67ae85u.toInt(), 0x3c6ef372u.toInt(), 0xa54ff53au.toInt(),
        0x510e527fu.toInt(), 0x9b05688cu.toInt(), 0x1f83d9abu.toInt(), 0x5be0cd19u.toInt(),
    )

    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
    )

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    private class Digest(private val digestSize: Int) {
        private val h = IV.copyOf()
        private val buffer = ByteArray(64)
        private var bufferLen = 0
        private var totalLen = 0L

        init {
            h[0] = h[0] xor (0x01010000 xor digestSize) // key_length = 0
        }

        private fun g(v: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
            v[a] = v[a] + v[b] + x
            v[d] = rotr(v[d] xor v[a], 16)
            v[c] = v[c] + v[d]
            v[b] = rotr(v[b] xor v[c], 12)
            v[a] = v[a] + v[b] + y
            v[d] = rotr(v[d] xor v[a], 8)
            v[c] = v[c] + v[d]
            v[b] = rotr(v[b] xor v[c], 7)
        }

        private fun compress(block: ByteArray, t: Long, last: Boolean) {
            val m = IntArray(16)
            for (i in 0 until 16) {
                m[i] = (block[i * 4].toInt() and 0xFF) or
                    ((block[i * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((block[i * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((block[i * 4 + 3].toInt() and 0xFF) shl 24)
            }
            val v = IntArray(16)
            System.arraycopy(h, 0, v, 0, 8)
            System.arraycopy(IV, 0, v, 8, 8)
            v[12] = v[12] xor (t and 0xFFFFFFFFL).toInt()
            v[13] = v[13] xor ((t ushr 32) and 0xFFFFFFFFL).toInt()
            if (last) v[14] = v[14] xor -0x1

            for (round in 0 until 10) {
                val s = SIGMA[round]
                g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
                g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
                g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
                g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
                g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
                g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
                g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
                g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
            }
            for (i in 0 until 8) {
                h[i] = h[i] xor v[i] xor v[i + 8]
            }
        }

        fun update(data: ByteArray) {
            var offset = 0
            while (offset < data.size) {
                if (bufferLen == 64) {
                    totalLen += 64
                    compress(buffer, totalLen, false)
                    bufferLen = 0
                }
                val n = minOf(64 - bufferLen, data.size - offset)
                System.arraycopy(data, offset, buffer, bufferLen, n)
                bufferLen += n
                offset += n
            }
        }

        fun digest(): ByteArray {
            totalLen += bufferLen
            for (i in bufferLen until 64) buffer[i] = 0
            compress(buffer, totalLen, true)
            val out = ByteArray(32)
            for (i in 0 until 8) {
                out[i * 4] = (h[i] and 0xFF).toByte()
                out[i * 4 + 1] = ((h[i] ushr 8) and 0xFF).toByte()
                out[i * 4 + 2] = ((h[i] ushr 16) and 0xFF).toByte()
                out[i * 4 + 3] = ((h[i] ushr 24) and 0xFF).toByte()
            }
            return out.copyOf(digestSize)
        }
    }

    /** Hex-Digest von [text] (UTF-8), [digestSize] Byte lang. */
    fun hashHex(text: String, digestSize: Int): String {
        val d = Digest(digestSize)
        d.update(text.toByteArray(StandardCharsets.UTF_8))
        // Locale.ROOT: %x nutzt sonst die Ziffern der Geräte-Sprache — bei
        // Nicht-ASCII-Ziffernsystemen wären die Content-Hash-IDs kaputt.
        return d.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
