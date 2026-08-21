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
    fun `delete entfernt einen selbst angelegten Skill dauerhaft`() {
        val skill = Skill(name = "weg damit", description = "d", body = "b")
        SkillStore.add(context, skill)
        SkillStore.delete(context, skill)

        SkillStore.resetForTest()
        assertNull(SkillStore.get(context, skill.id))
    }
}
