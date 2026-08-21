package de.skilltoremember.app.data.memory

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kanonische JSON-Serialisierung, kompatibel zu Pythons `json.dumps(obj,
 * sort_keys=True, separators=(",", ":"), ensure_ascii=False)` aus
 * `memory.py` (`canonical()`): Objekt-Keys alphabetisch sortiert, keine
 * Leerzeichen, Unicode unverändert (nicht als `\\uXXXX` escaped). Wird für
 * jede Zeile in den `.jsonl`-Dateien gebraucht — nur so bleiben lokal und
 * remote geschriebene Zeilen byte-identisch und damit zeilenweise
 * deduplizierbar (siehe `_union_lines` im Original).
 */
object CanonicalJson {

    fun of(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null, JSONObject.NULL -> out.append("null")
            is JSONObject -> writeObject(value, out)
            is JSONArray -> writeArray(value, out)
            is String -> writeString(value, out)
            is Boolean -> out.append(value.toString())
            is Int, is Long -> out.append(value.toString())
            is Double, is Float -> out.append(formatNumber(value.toString().toDouble()))
            else -> writeString(value.toString(), out)
        }
    }

    private fun writeObject(obj: JSONObject, out: StringBuilder) {
        out.append('{')
        val keys = obj.keys().asSequence().sorted().toList()
        keys.forEachIndexed { i, key ->
            if (i > 0) out.append(',')
            writeString(key, out)
            out.append(':')
            write(obj.get(key), out)
        }
        out.append('}')
    }

    private fun writeArray(arr: JSONArray, out: StringBuilder) {
        out.append('[')
        for (i in 0 until arr.length()) {
            if (i > 0) out.append(',')
            write(arr.get(i), out)
        }
        out.append(']')
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u%04x".format(ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    /** Ganzzahlige Doubles ohne ".0", wie Pythons json-Modul es für Floats mit Ganzzahlwert tut. */
    private fun formatNumber(d: Double): String {
        if (d == d.toLong().toDouble()) return d.toLong().toString() + ".0"
        return d.toString()
    }
}
