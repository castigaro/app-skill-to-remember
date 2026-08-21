package de.skilltoremember.app.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Einfacher JSON-Datei-Speicher für die Chats. Die öffentlichen Methoden sind
 * synchronisiert, weil UI-Thread und [kotlinx.coroutines.Dispatchers.IO]
 * (siehe [de.skilltoremember.app.api.ChatApi]) denselben Cache teilen.
 */
object ChatStore {

    private var chats: MutableList<Chat>? = null

    /** Nur für Tests: verwirft den In-Memory-Cache, erzwingt Neuladen. */
    internal fun resetForTest() {
        chats = null
    }

    private fun file(context: Context): File = File(context.filesDir, "chats.json")

    private fun load(context: Context): MutableList<Chat> {
        chats?.let { return it }
        val loaded = mutableListOf<Chat>()
        val f = file(context)
        if (f.exists()) {
            runCatching {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    loaded.add(Chat.fromJson(arr.getJSONObject(i)))
                }
            }.onFailure {
                // Nicht mehr parsebar: wegsichern, sonst würde der nächste save()
                // die noch reparierbare Datei stillschweigend überschreiben.
                FileIo.quarantine(f)
            }
        }
        chats = loaded
        return loaded
    }

    @Synchronized
    fun save(context: Context) {
        val arr = JSONArray()
        load(context).forEach { arr.put(it.toJson()) }
        FileIo.writeAtomic(file(context), arr.toString())
    }

    @Synchronized
    fun getAll(context: Context): List<Chat> =
        load(context).sortedByDescending { it.createdAt }

    @Synchronized
    fun get(context: Context, id: String): Chat? =
        load(context).firstOrNull { it.id == id }

    @Synchronized
    fun add(context: Context, chat: Chat) {
        load(context).add(chat)
        save(context)
    }

    @Synchronized
    fun delete(context: Context, chat: Chat) {
        load(context).removeAll { it.id == chat.id }
        save(context)
    }
}
