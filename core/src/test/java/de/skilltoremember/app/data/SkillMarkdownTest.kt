package de.skilltoremember.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkillMarkdownTest {

    @Test
    fun `parst Frontmatter im Agent-Skills-Format`() {
        val text = """
            ---
            name: n8n-workflow-bootstrap
            description: Onboarding und sichere Copy-Edit-Publish-Test-Flows in n8n-Projekten.
            ---

            # n8n Workflow Bootstrap

            Nutze diesen Skill, um schnell orientiert zu sein.
        """.trimIndent()

        val skill = SkillMarkdown.parse(text)
        assertEquals("n8n-workflow-bootstrap", skill.name)
        assertEquals("Onboarding und sichere Copy-Edit-Publish-Test-Flows in n8n-Projekten.", skill.description)
        assertTrue(skill.body.startsWith("# n8n Workflow Bootstrap"))
    }

    @Test
    fun `ohne Frontmatter greift der Fallback-Name`() {
        val skill = SkillMarkdown.parse("Nur ein Fließtext ohne Kopfbereich.", fallbackName = "mein-skill")
        assertEquals("mein-skill", skill.name)
        assertEquals("", skill.description)
        assertEquals("Nur ein Fließtext ohne Kopfbereich.", skill.body)
    }

    @Test
    fun `render und parse sind ein Roundtrip`() {
        val original = Skill(name = "beispiel", description = "Kurzbeschreibung", body = "# Anleitung\n\nSchritt 1.")
        val parsed = SkillMarkdown.parse(SkillMarkdown.render(original))
        assertEquals(original.name, parsed.name)
        assertEquals(original.description, parsed.description)
        assertEquals(original.body, parsed.body)
    }

    @Test
    fun `Fallback-Name kommt aus dem Elternordner eines ZIP-Eintrags`() {
        assertEquals(
            "n8n-workflow-bootstrap",
            SkillMarkdown.fallbackNameFromPath("my-ai-skills-main/skills/n8n-workflow-bootstrap/SKILL.md"),
        )
    }

    @Test
    fun `Fallback-Name funktioniert auch ohne Unterordner`() {
        assertEquals("meine-skills", SkillMarkdown.fallbackNameFromPath("meine-skills/SKILL.md"))
    }

    @Test
    fun `Fallback-Name ist null ohne verwertbaren Pfad`() {
        assertEquals(null, SkillMarkdown.fallbackNameFromPath("SKILL.md"))
    }
}
