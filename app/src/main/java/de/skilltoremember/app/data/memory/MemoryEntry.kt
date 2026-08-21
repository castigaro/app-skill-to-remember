package de.skilltoremember.app.data.memory

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ein Gedächtnis-Eintrag — Feldnamen und Bedeutung exakt wie in
 * `references/memory-model.md` des `humanoid-behavior`-Skills: kurze Keys,
 * weil jedes gespeicherte Byte um Kontext konkurriert.
 */
class MemoryEntry(
    val id: String,
    val l: String, // Layer-Code: idt, sem, epi, prc
    val t: String, // dotted topic path
    val v: String, // Wert (der eigentliche Fakt)
    var s: Double, // Salience 0..1
    var c: Double, // Confidence 0..1
    var f: Int = 0, // Abrufzähler
    val ts: String, // erstellt (ISO-8601 UTC)
    var la: String, // zuletzt zugegriffen (ISO-8601 UTC)
    var src: String = "",
    var k: MutableList<String> = mutableListOf(), // Keywords
    var lk: MutableList<String> = mutableListOf(), // verknüpfte Entry-IDs
    var tomb: String? = null, // nur in archive.jsonl: Zeitpunkt der Löschung
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("l", l)
        put("t", t)
        put("v", v)
        put("s", s)
        put("c", c)
        put("f", f)
        put("ts", ts)
        put("la", la)
        put("src", src)
        put("k", JSONArray(k.sorted()))
        put("lk", JSONArray(lk.sorted()))
        tomb?.let { put("tomb", it) }
    }

    fun copy(): MemoryEntry = MemoryEntry(
        id, l, t, v, s, c, f, ts, la, src, k.toMutableList(), lk.toMutableList(), tomb,
    )

    companion object {
        fun fromJson(json: JSONObject): MemoryEntry {
            val k = json.optJSONArray("k")
            val lk = json.optJSONArray("lk")
            return MemoryEntry(
                id = json.getString("id"),
                l = json.getString("l"),
                t = json.getString("t"),
                v = json.getString("v"),
                s = json.optDouble("s", 0.5),
                c = json.optDouble("c", 0.8),
                f = json.optInt("f", 0),
                ts = json.getString("ts"),
                la = json.optString("la", json.getString("ts")),
                src = json.optString("src", ""),
                k = (0 until (k?.length() ?: 0)).map { k!!.getString(it) }.toMutableList(),
                lk = (0 until (lk?.length() ?: 0)).map { lk!!.getString(it) }.toMutableList(),
                tomb = if (json.has("tomb")) json.optString("tomb") else null,
            )
        }
    }
}
