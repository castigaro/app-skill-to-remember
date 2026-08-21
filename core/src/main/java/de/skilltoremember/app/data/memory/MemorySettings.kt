package de.skilltoremember.app.data.memory

import android.content.Context
import java.io.File

/** Verbindung zum privaten Gedächtnis-Repo auf GitHub — lokal gespeichert, wie der KI-API-Key. */
object MemorySettings {

    private fun prefs(context: Context) =
        context.getSharedPreferences("memory_settings", Context.MODE_PRIVATE)

    fun getOwner(context: Context): String = prefs(context).getString("owner", "")!!
    fun getRepo(context: Context): String = prefs(context).getString("repo", "")!!
    fun getBranch(context: Context): String = prefs(context).getString("branch", "main")!!.ifBlank { "main" }
    fun getToken(context: Context): String = prefs(context).getString("token", "")!!
    fun getLastSync(context: Context): String? = prefs(context).getString("lastSync", null)

    fun isConfigured(context: Context): Boolean =
        getOwner(context).isNotBlank() && getRepo(context).isNotBlank() && getToken(context).isNotBlank()

    fun save(context: Context, owner: String, repo: String, branch: String, token: String) {
        prefs(context).edit()
            .putString("owner", owner.trim())
            .putString("repo", repo.trim())
            .putString("branch", branch.trim().ifBlank { "main" })
            .putString("token", token.trim())
            .apply()
    }

    fun setLastSync(context: Context, iso: String) {
        prefs(context).edit().putString("lastSync", iso).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun config(context: Context): GitHubMemoryConfig =
        GitHubMemoryConfig(getOwner(context), getRepo(context), getBranch(context), getToken(context))

    /** Wurzelverzeichnis des lokalen Gedächtnis-Speichers — app-privat, außerhalb jedes Chat-/Skill-Datenordners. */
    fun storeRoot(context: Context): File = File(context.filesDir, "humanoid-memory")

    fun store(context: Context): MemoryStore = MemoryStore(storeRoot(context))
}
