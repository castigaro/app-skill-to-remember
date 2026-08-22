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

    /**
     * Installiert die eingebauten Skills beim allerersten Start und zieht bei
     * App-Updates nur ihren Anleitungstext nach ([BuiltInSkills.VERSION]).
     * Name, Beschreibung und Aktiv-Schalter des Nutzers bleiben erhalten;
     * ein bewusst gelöschter eingebauter Skill kommt nicht zurück.
     */
    private fun seedBuiltInsOnce(context: Context, loaded: MutableList<Skill>) {
        val p = prefs(context)
        val seededVersion = when {
            p.contains("builtInsVersion") -> p.getInt("builtInsVersion", 0)
            p.getBoolean("builtInsSeeded", false) -> 1 // alte Installation ohne Versions-Marke
            else -> 0
        }
        if (seededVersion >= BuiltInSkills.VERSION) return
        val existing = loaded.firstOrNull { it.id == BuiltInSkills.HUMANOID_BEHAVIOR.id }
        when {
            existing != null -> existing.body = BuiltInSkills.HUMANOID_BEHAVIOR.body
            // Kopie statt Singleton: Spätere Änderungen am Store-Eintrag (Schalter,
            // Name) dürfen das geteilte BuiltInSkills-Objekt nicht mitverändern.
            seededVersion == 0 -> loaded.add(Skill.fromJson(BuiltInSkills.HUMANOID_BEHAVIOR.toJson()))
            // sonst: vom Nutzer gelöscht — nicht wieder aufdrängen
        }
        p.edit().putBoolean("builtInsSeeded", true).putInt("builtInsVersion", BuiltInSkills.VERSION).apply()
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

    /**
     * Ersetzt alle importierten (nicht eingebauten) Skills durch [imported] —
     * für den Abgleich Handy -> Uhr: Die Uhr spiegelt die Skills des Handys.
     * Eingebaute Skills verwaltet jedes Gerät selbst (Versions-Migration),
     * die bleiben unangetastet.
     */
    @Synchronized
    fun replaceImported(context: Context, imported: List<Skill>) {
        val loaded = load(context)
        loaded.removeAll { !it.builtIn }
        imported.filterNot { it.builtIn }.forEach { loaded.add(it) }
        save(context)
    }
}
