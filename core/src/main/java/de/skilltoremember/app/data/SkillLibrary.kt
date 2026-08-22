package de.skilltoremember.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Der In-App-Katalog der Skill-Bibliothek: Die CI des Skills-Repos
 * veröffentlicht neben den Einzel-ZIPs eine `skills-index.json`
 * (Name, Beschreibung, Download-Link je Skill) im Release "latest" —
 * die App lädt daraus die Auswahl und installiert Skills direkt,
 * ohne Umweg über Browser und Download-Ordner.
 */
object SkillLibrary {

    const val INDEX_URL =
        "https://github.com/castigaro/skilltoremember-skills/releases/download/latest/skills-index.json"

    data class Entry(val name: String, val description: String, val zipUrl: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Nur für Tests überschreibbar. */
    internal var indexUrl: String = INDEX_URL

    internal fun parseIndex(json: String): List<Entry> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name")
            val zipUrl = obj.optString("zipUrl")
            if (name.isBlank() || zipUrl.isBlank()) null
            else Entry(name, obj.optString("description"), zipUrl)
        }
    }

    /** Lädt den Katalog; wirft bei Netzwerk-/Formatfehlern eine [IOException]. */
    suspend fun fetchIndex(): List<Entry> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(indexUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Katalog nicht erreichbar (${response.code}).")
            runCatching { parseIndex(response.body?.string().orEmpty()) }
                .getOrElse { throw IOException("Katalog nicht lesbar.") }
        }
    }

    /** Lädt die ZIP eines Skills und parst die enthaltenen SKILL.md-Dateien. */
    suspend fun fetchSkills(zipUrl: String, fallbackUntitled: String): List<Skill> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(zipUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download fehlgeschlagen (${response.code}).")
            val body = response.body ?: throw IOException("Leere Antwort.")
            body.byteStream().use { SkillZip.parse(it, fallbackUntitled) }
        }
    }
}
