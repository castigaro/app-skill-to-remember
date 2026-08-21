package de.skilltoremember.app.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/** JSON-Datei-Speicher für die Skills, gleiche Bauart wie [ChatStore]. */
object SkillStore {

    private var skills: MutableList<Skill>? = null

    /** Nur für Tests: verwirft den In-Memory-Cache, erzwingt Neuladen. */
    internal fun resetForTest() {
        skills = null
    }

    private fun file(context: Context): File = File(context.filesDir, "skills.json")

    private fun prefs(context: Context) =
        context.getSharedPreferences("skills_meta", Context.MODE_PRIVATE)

    private fun load(context: Context): MutableList<Skill> {
        skills?.let { return it }
        val loaded = mutableListOf<Skill>()
        val f = file(context)
        if (f.exists()) {
            runCatching {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    loaded.add(Skill.fromJson(arr.getJSONObject(i)))
                }
            }.onFailure {
                // Nicht mehr parsebar: wegsichern, sonst würde der nächste save()
                // die noch reparierbare Datei stillschweigend überschreiben.
                FileIo.quarantine(f)
            }
        }
        skills = loaded
        seedBuiltInsOnce(context, loaded)
        return loaded
    }

    /** Installiert die eingebauten Skills genau einmal, beim allerersten Start. */
    private fun seedBuiltInsOnce(context: Context, loaded: MutableList<Skill>) {
        val p = prefs(context)
        if (p.getBoolean("builtInsSeeded", false)) return
        loaded.add(BuiltInSkills.HUMANOID_BEHAVIOR)
        p.edit().putBoolean("builtInsSeeded", true).apply()
        save(context)
    }

    @Synchronized
    fun save(context: Context) {
        val arr = JSONArray()
        load(context).forEach { arr.put(it.toJson()) }
        FileIo.writeAtomic(file(context), arr.toString())
    }

    @Synchronized
    fun getAll(context: Context): List<Skill> =
        load(context).sortedBy { it.name.lowercase() }

    @Synchronized
    fun getEnabled(context: Context): List<Skill> =
        getAll(context).filter { it.enabled }

    @Synchronized
    fun get(context: Context, id: String): Skill? =
        load(context).firstOrNull { it.id == id }

    @Synchronized
    fun add(context: Context, skill: Skill) {
        load(context).add(skill)
        save(context)
    }

    @Synchronized
    fun delete(context: Context, skill: Skill) {
        load(context).removeAll { it.id == skill.id }
        save(context)
    }
}
