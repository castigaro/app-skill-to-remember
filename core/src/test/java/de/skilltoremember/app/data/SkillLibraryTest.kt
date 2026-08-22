package de.skilltoremember.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class SkillLibraryTest {

    @Test
    fun `Katalog-Index wird geparst, kaputte Eintraege uebersprungen`() {
        val json = """
            [
              {"name": "einkaufsliste", "description": "Liste im Gedächtnis", "zipUrl": "https://example.org/e.zip"},
              {"name": "", "description": "ohne Namen", "zipUrl": "https://example.org/x.zip"},
              {"name": "ohne-url", "description": "d"}
            ]
        """.trimIndent()
        val entries = SkillLibrary.parseIndex(json)
        assertEquals(1, entries.size)
        assertEquals("einkaufsliste", entries.first().name)
        assertEquals("https://example.org/e.zip", entries.first().zipUrl)
    }

    @Test
    fun `Skill-ZIP wird geparst, fremde Dateien ignoriert`() {
        val skillMd = "---\nname: test-skill\ndescription: Testet Dinge.\n---\n\n# Anleitung\nTu dies.\n"
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("test-skill/SKILL.md"))
                zip.write(skillMd.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("test-skill/README.md"))
                zip.write("nicht importieren".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()

        val skills = SkillZip.parse(ByteArrayInputStream(bytes), fallbackUntitled = "Neuer Skill")
        assertEquals(1, skills.size)
        assertEquals("test-skill", skills.first().name)
        assertEquals("Testet Dinge.", skills.first().description)
    }
}
