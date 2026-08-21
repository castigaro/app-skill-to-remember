package de.skilltoremember.app.data.memory

import de.skilltoremember.app.data.FileIo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Layer-Codes mit Halbwertszeit in Tagen (null = verfällt nie). */
val MEMORY_LAYERS: Map<String, Pair<String, Double?>> = mapOf(
    "idt" to ("identity" to null),
    "sem" to ("semantic" to 180.0),
    "epi" to ("episodic" to 30.0),
    "prc" to ("procedural" to 365.0),
)

val MEMORY_LAYER_TITLES = mapOf(
    "idt" to "WHO (identity)",
    "sem" to "KNOWN (semantic)",
    "epi" to "LIVED (episodic)",
    "prc" to "HOW (procedural)",
)

/**
 * Datei-I/O für einen Gedächtnis-Speicher — Layout identisch zu `Store` in
 * `memory.py`, damit derselbe Ordner (per Git-Repo gespiegelt) zwischen
 * Desktop (Python) und Handy (diese App) austauschbar bleibt:
 *
 * ```
 * meta.json          Schema, root_id, Git-Bindung, Zähler       (JSON, hübsch)
 * character.json     Identitäts-Layer (idt)                     (JSON, hübsch)
 * index.json         vorgerenderter Boot-Digest                 (JSON, hübsch)
 * layers/semantic.jsonl / episodic.jsonl / procedural.jsonl     (kanonisch, 1 Zeile/Eintrag)
 * edges.jsonl        Verknüpfungen                               (kanonisch)
 * archive.jsonl      verblasste/gelöschte Einträge (Tombstones)  (kanonisch)
 * journal.jsonl      Audit-Log, append-only                      (kanonisch)
 * ```
 *
 * `meta.json`/`character.json`/`index.json` sind mit Einrückung geschrieben
 * (menschenlesbar, so wie `write_json` im Original); die `.jsonl`-Dateien
 * kanonisch-kompakt (`write_jsonl`/`canonical`), damit lokal und remote
 * geschriebene Zeilen byte-identisch sind — Voraussetzung für den
 * zeilenweisen Union-Merge beim Sync.
 */
class MemoryStore(root: File) {

    val metaPath = File(root, "meta.json")
    val characterPath = File(root, "character.json")
    val indexPath = File(root, "index.json")
    val edgesPath = File(root, "edges.jsonl")
    val archivePath = File(root, "archive.jsonl")
    val journalPath = File(root, "journal.jsonl")
    private val layersDir = File(root, "layers")

    fun layerPath(layer: String): File =
        File(layersDir, "${MEMORY_LAYERS.getValue(layer).first}.jsonl")

    fun exists(): Boolean = metaPath.isFile

    // ---- low-level io ----

    private fun writeAtomic(file: File, text: String) = FileIo.writeAtomic(file, text)

    fun readJson(file: File): JSONObject? =
        if (file.isFile) JSONObject(file.readText()) else null

    fun writeJson(file: File, obj: JSONObject) {
        writeAtomic(file, obj.toString(2) + "\n")
    }

    fun readJsonl(file: File): List<JSONObject> {
        if (!file.isFile) return emptyList()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { JSONObject(it) }
    }

    fun writeJsonl(file: File, rows: List<JSONObject>) {
        val body = buildString {
            for (row in rows) {
                append(CanonicalJson.of(row))
                append('\n')
            }
        }
        writeAtomic(file, body)
    }

    fun journal(op: String, fields: JSONObject = JSONObject()) {
        val record = JSONObject()
        record.put("op", op)
        record.put("at", MemoryClock.isoNow())
        fields.keys().forEach { record.put(it, fields.get(it)) }
        journalPath.parentFile?.mkdirs()
        journalPath.appendText(CanonicalJson.of(record) + "\n")
    }

    // ---- documents ----

    fun meta(): JSONObject =
        readJson(metaPath) ?: throw MemoryException("no memory at this root — call init first")

    fun saveMeta(meta: JSONObject) = writeJson(metaPath, meta)

    fun character(): JSONObject =
        readJson(characterPath) ?: JSONObject().apply {
            put("schema", MEMORY_SCHEMA_VERSION)
            put("entries", JSONArray())
        }

    fun saveCharacter(character: JSONObject) = writeJson(characterPath, character)

    /** Alle Einträge über alle Layer, nach ID. */
    fun loadEntries(): MutableMap<String, MemoryEntry> {
        val entries = LinkedHashMap<String, MemoryEntry>()
        val idtArr = character().optJSONArray("entries") ?: JSONArray()
        for (i in 0 until idtArr.length()) {
            val e = MemoryEntry.fromJson(idtArr.getJSONObject(i))
            entries[e.id] = e
        }
        for (layer in listOf("sem", "epi", "prc")) {
            for (row in readJsonl(layerPath(layer))) {
                val e = MemoryEntry.fromJson(row)
                entries[e.id] = e
            }
        }
        return entries
    }

    fun saveEntries(entries: Map<String, MemoryEntry>) {
        val buckets = MEMORY_LAYERS.keys.associateWith { mutableListOf<MemoryEntry>() }
        entries.values.forEach { buckets.getValue(it.l).add(it) }

        val character = character()
        character.put("schema", MEMORY_SCHEMA_VERSION)
        character.put(
            "entries",
            JSONArray(buckets.getValue("idt").sortedBy { it.t }.map { it.toJson() }),
        )
        saveCharacter(character)

        for (layer in listOf("sem", "epi", "prc")) {
            val rows = buckets.getValue(layer).sortedWith(compareBy({ it.t }, { it.id }))
            writeJsonl(layerPath(layer), rows.map { it.toJson() })
        }
    }

    fun edges(): List<JSONObject> = readJsonl(edgesPath)

    fun saveEdges(edges: List<JSONObject>) {
        val seen = mutableSetOf<Triple<String, String, String>>()
        val rows = mutableListOf<JSONObject>()
        for (edge in edges) {
            val a = edge.optString("a", "")
            val b = edge.optString("b", "")
            val r = edge.optString("r", "rel")
            if (a.isBlank() || b.isBlank()) continue
            val key = Triple(a, b, r)
            if (!seen.add(key)) continue
            rows.add(edge)
        }
        writeJsonl(edgesPath, rows.sortedWith(compareBy({ it.getString("a") }, { it.getString("b") })))
    }

    fun archive(): List<JSONObject> = readJsonl(archivePath)

    fun appendArchive(entries: List<MemoryEntry>) {
        val byId = LinkedHashMap<String, JSONObject>()
        archive().forEach { byId[it.getString("id")] = it }
        entries.forEach { byId[it.id] = it.toJson() }
        writeJsonl(archivePath, byId.values.sortedBy { it.getString("id") })
    }
}

const val MEMORY_SCHEMA_VERSION = 1

class MemoryException(message: String) : Exception(message)
