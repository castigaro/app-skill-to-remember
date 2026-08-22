package de.skilltoremember.app.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Liest Skills aus ZIP-Archiven (einzelne Skill-ZIPs der Bibliothek genauso
 * wie GitHubs "Download ZIP"-Export): Jeder Eintrag namens SKILL.md wird
 * geparst, egal wie tief er im Archiv liegt.
 */
object SkillZip {

    /** Mehr ist keine SKILL.md — schützt vor Zip-Bomben und versehentlich gepackten Binärdateien. */
    const val MAX_SKILL_MD_BYTES = 2 * 1024 * 1024

    fun parse(input: InputStream, fallbackUntitled: String): List<Skill> {
        val found = mutableListOf<Skill>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('/').equals("SKILL.md", ignoreCase = true)) {
                    // Übergroße Einträge still überspringen statt sie in den Speicher zu lesen.
                    val text = runCatching { readCapped(zip) }.getOrNull()
                    if (text != null) {
                        val fallback = SkillMarkdown.fallbackNameFromPath(entry.name) ?: fallbackUntitled
                        found.add(SkillMarkdown.parse(text, fallback))
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return found
    }

    /** Liest höchstens [MAX_SKILL_MD_BYTES]; schließt den Stream nicht. */
    fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_SKILL_MD_BYTES) throw IOException("Datei ist zu groß für eine SKILL.md.")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}
