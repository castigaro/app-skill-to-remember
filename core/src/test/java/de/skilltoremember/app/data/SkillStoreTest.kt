package de.skilltoremember.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SkillStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        SkillStore.resetForTest()
        File(context.filesDir, "skills.json").delete()
        context.getSharedPreferences("skills_meta", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun `erster Start installiert den eingebauten humanoid-behavior-Skill`() {
        val all = SkillStore.getAll(context)
        assertEquals(1, all.size)
        assertEquals("humanoid-behavior", all.first().name)
        assertTrue(all.first().builtIn)
    }

    @Test
    fun `eingebauter Skill wird nach Loeschen nicht erneut geseedet`() {
        val builtIn = SkillStore.getAll(context).first { it.builtIn }
        SkillStore.delete(context, builtIn)

        SkillStore.resetForTest()
        assertTrue("darf nach bewusstem Loeschen nicht zurueckkommen", SkillStore.getAll(context).isEmpty())
    }

    @Test
    fun `Anleitung des eingebauten Skills wird bei App-Update nachgezogen`() {
        // Alte Installation simulieren: geseedet ohne Versions-Marke, alter
        // Anleitungstext, Name/Beschreibung/Schalter vom Nutzer angepasst.
        val old = Skill(
            id = BuiltInSkills.HUMANOID_BEHAVIOR.id, name = "mein-gedaechtnis",
            description = "eigene Beschreibung", body = "alter Anleitungstext",
            enabled = false, builtIn = true,
        )
        File(context.filesDir, "skills.json").writeText(org.json.JSONArray().put(old.toJson()).toString())
        context.getSharedPreferences("skills_meta", Context.MODE_PRIVATE)
            .edit().putBoolean("builtInsSeeded", true).apply()

        SkillStore.resetForTest()
        val upgraded = SkillStore.get(context, BuiltInSkills.HUMANOID_BEHAVIOR.id)!!
        assertEquals(BuiltInSkills.HUMANOID_BEHAVIOR.body, upgraded.body)
        assertEquals("mein-gedaechtnis", upgraded.name)
        assertEquals("eigene Beschreibung", upgraded.description)
        assertTrue(!upgraded.enabled)
        assertEquals(1, SkillStore.getAll(context).size)
    }

    @Test
    fun `Skills ueberleben einen Neustart`() {
        val skill = Skill(name = "eigener-skill", description = "Test", body = "Tu dies und das.")
        SkillStore.add(context, skill)

        SkillStore.resetForTest()

        val loaded = SkillStore.get(context, skill.id)!!
        assertEquals("eigener-skill", loaded.name)
        assertTrue(loaded.enabled)
    }

    @Test
    fun `getAll sortiert alphabetisch`() {
        SkillStore.add(context, Skill(name = "zebra", description = "d", body = "b"))
        SkillStore.add(context, Skill(name = "anfang", description = "d", body = "b"))
        val names = SkillStore.getAll(context).map { it.name }
        assertEquals(listOf("anfang", "humanoid-behavior", "zebra"), names)
    }

    @Test
    fun `getEnabled filtert deaktivierte Skills raus`() {
        SkillStore.add(context, Skill(name = "aktiv", description = "d", body = "b", enabled = true))
        SkillStore.add(context, Skill(name = "inaktiv", description = "d", body = "b", enabled = false))
        assertTrue(SkillStore.getEnabled(context).map { it.name }.containsAll(listOf("aktiv", "humanoid-behavior")))
        assertTrue(!SkillStore.getEnabled(context).map { it.name }.contains("inaktiv"))
    }

    @Test
    fun `replaceImported spiegelt die Handy-Skills, laesst eingebaute unangetastet`() {
        SkillStore.add(context, Skill(name = "alter-import", description = "d", body = "b"))
        val builtIn = SkillStore.getAll(context).first { it.builtIn }
        builtIn.enabled = false
        SkillStore.save(context)

        SkillStore.replaceImported(
            context,
            listOf(
                Skill(name = "einkaufsliste", description = "d", body = "b"),
                Skill(name = "vergissmeinnicht", description = "d", body = "b", enabled = false),
            ),
        )

        SkillStore.resetForTest()
        val names = SkillStore.getAll(context).map { it.name }
        assertEquals(listOf("einkaufsliste", "humanoid-behavior", "vergissmeinnicht"), names)
        assertTrue("alter Import muss ersetzt sein", !names.contains("alter-import"))
        assertTrue("eingebauter Skill behaelt seinen Schalter", !SkillStore.getAll(context).first { it.builtIn }.enabled)
        assertTrue("uebertragener Aktiv-Schalter bleibt erhalten", !SkillStore.getAll(context).first { it.name == "vergissmeinnicht" }.enabled)
    }

    @Test
    fun `delete entfernt einen selbst angelegten Skill dauerhaft`() {
        val skill = Skill(name = "weg damit", description = "d", body = "b")
        SkillStore.add(context, skill)
        SkillStore.delete(context, skill)

        SkillStore.resetForTest()
        assertNull(SkillStore.get(context, skill.id))
    }
}
