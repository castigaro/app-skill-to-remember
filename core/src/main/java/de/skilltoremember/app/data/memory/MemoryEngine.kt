package de.skilltoremember.app.data.memory

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class RememberResult(
    val stored: Boolean,
    val outcome: String, // "stored" | "reinforced" | "skipped"
    val entry: MemoryEntry?,
    val reason: String? = null,
)

data class RecallResult(val query: String, val matched: Int, val hits: List<MemoryEntry>)

data class ForgetResult(val forgottenIds: List<String>)

data class ConsolidateResult(
    val merged: List<String>,
    val promoted: List<String>,
    val archived: List<String>,
    val kept: Int,
)

data class BootResult(val meta: JSONObject, val digest: String, val consolidationDue: Boolean)

/**
 * Portierung der Kernlogik aus `memory.py` (`humanoid-behavior`-Skill,
 * `castigaro/my-ai-skills`) — Salience-Filter, Strength/Decay, Merge- und
 * Digest-Regeln funktional identisch zum Original, damit ein Gedächtnis
 * zwischen der Python-Engine (Desktop) und dieser App (Handy) über dasselbe
 * Git-Repo wandern kann. Reine Datei-/Logikebene, kein Netzwerk — Sync läuft
 * über [de.skilltoremember.app.data.memory.GitHubMemorySync].
 */
object MemoryEngine {

    const val SALIENCE_THRESHOLD = 0.35
    const val PRUNE_STRENGTH = 0.15

    /** Ab dieser Länge zählt ein Wort auch als Bestandteil eines längeren (siehe `teilwort`). */
    const val TEILWORT_MINDESTLAENGE = 4
    const val PROMOTE_AFTER = 3
    const val DEFAULT_BUDGET_TOKENS = 1200
    const val CHARS_PER_TOKEN = 4

    private val LAYER_ALIASES = mapOf(
        "identity" to "idt", "self" to "idt",
        "semantic" to "sem", "fact" to "sem", "facts" to "sem", "preference" to "sem",
        "episodic" to "epi", "episode" to "epi", "event" to "epi",
        "procedural" to "prc", "procedure" to "prc", "how" to "prc",
    )

    private val TOPIC_INVALID = Regex("[^a-z0-9._-]+")
    private val TOPIC_DOTS = Regex("\\.{2,}")
    private val WHITESPACE = Regex("\\s+")
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}_]+")

    fun normalizeLayer(name: String): String {
        val key = LAYER_ALIASES[name.trim().lowercase()] ?: name.trim().lowercase()
        require(MEMORY_LAYERS.containsKey(key)) { "unknown layer \"$name\" — use one of: idt, sem, epi, prc" }
        return key
    }

    fun normalizeTopic(topic: String): String {
        var t = topic.trim().lowercase()
        t = TOPIC_INVALID.replace(t, "-")
        t = t.trim('-', '.')
        t = TOPIC_DOTS.replace(t, ".")
        require(t.isNotBlank()) { "topic must not be empty" }
        return t
    }

    fun normalizeValue(value: String): String = WHITESPACE.replace(value.trim(), " ")

    fun makeId(layer: String, topic: String, value: String): String {
        val seed = "$layer|$topic|${normalizeValue(value).lowercase()}"
        return "${layer}_${Blake2s.hashHex(seed, 5)}"
    }

    private fun clamp(value: Double, low: Double = 0.0, high: Double = 1.0): Double =
        max(low, min(high, value))

    fun tokensOf(text: String): Int = max(1, text.length / CHARS_PER_TOKEN)

    private fun wordSet(vararg parts: String?): Set<String> {
        val blob = parts.filterNotNull().joinToString(" ")
        return blob.lowercase().split(WORD_SPLIT).filter { it.length > 1 }.toSet()
    }

    /**
     * Teiltreffer zwischen einem gespeicherten Wort und einem Suchwort — in
     * BEIDE Richtungen.
     *
     * Vorher zählte nur „gespeichertes Wort enthält das Suchwort": Die Frage
     * nach der „Einkaufsliste" fand `list.einkauf.eier` deshalb nie
     * (`"einkauf".contains("einkaufsliste")` ist falsch), und das Modell
     * antwortete wahrheitsgemäß „nichts gefunden". Deutsche Komposita sind
     * aber genau der Normalfall, in dem gefragt wird.
     *
     * Die Mindestlänge hält Zufallstreffer draußen: Ohne sie fände „das"
     * jedes Wort mit „da" darin.
     */
    private fun teilwort(gespeichert: String, gesucht: String): Boolean {
        if (gespeichert.contains(gesucht)) return true
        return gesucht.length >= TEILWORT_MINDESTLAENGE
            && gespeichert.length >= TEILWORT_MINDESTLAENGE
            && gesucht.contains(gespeichert)
    }

    /** strength = salience * 0.5^(age_days/half_life) * (1 + 0.15 * ln(1 + recalls)) */
    fun strength(entry: MemoryEntry, at: Instant): Double {
        val half = MEMORY_LAYERS.getValue(entry.l).second
        val base = clamp(entry.s)
        val decay = if (half == null) {
            1.0
        } else {
            val age = MemoryClock.daysBetween(at, MemoryClock.parseIso(entry.la))
            0.5.pow(age / half)
        }
        val rehearsal = 1.0 + 0.15 * ln(1.0 + max(0, entry.f))
        return base * decay * rehearsal
    }

    /** Gewinner-Regel für zwei Versionen derselben Entry-ID: besser abgerufen, dann jünger, dann salienter. */
    private fun pickWinner(a: MemoryEntry, b: MemoryEntry): MemoryEntry {
        if (a.f != b.f) return if (a.f > b.f) a else b
        if (a.la != b.la) return if (a.la > b.la) a else b
        if (a.s != b.s) return if (a.s > b.s) a else b
        return a
    }

    fun mergeEntry(a: MemoryEntry, b: MemoryEntry): MemoryEntry {
        val winner = pickWinner(a, b).copy()
        winner.f = max(a.f, b.f)
        winner.s = max(a.s, b.s)
        winner.c = max(a.c, b.c)
        winner.la = maxOf(a.la, b.la)
        winner.k = (a.k + b.k).distinct().sorted().toMutableList()
        winner.lk = (a.lk + b.lk).distinct().sorted().toMutableList()
        return winner
    }

    private fun markDirty(store: MemoryStore, meta: JSONObject) {
        if (!meta.optBoolean("dirty", false)) {
            meta.put("dirty", true)
            store.saveMeta(meta)
        }
    }

    // ---- remember ----

    fun remember(
        store: MemoryStore,
        layer: String,
        topic: String,
        value: String,
        salience: Double,
        confidence: Double = 0.8,
        keywords: List<String> = emptyList(),
        source: String = "",
        links: List<String> = emptyList(),
        force: Boolean = false,
    ): RememberResult {
        val at = MemoryClock.now()
        val meta = store.meta()
        val l = normalizeLayer(layer)
        val t = normalizeTopic(topic)
        val v = normalizeValue(value)
        require(v.isNotBlank()) { "value must not be empty" }
        val s = clamp(salience)

        if (s < SALIENCE_THRESHOLD && !force) {
            val counters = meta.optJSONObject("counters") ?: JSONObject().also { meta.put("counters", it) }
            counters.put("skipped", counters.optInt("skipped", 0) + 1)
            store.saveMeta(meta)
            store.journal("skip", JSONObject().put("t", t).put("s", s))
            return RememberResult(false, "skipped", null, "below salience threshold %.2f".format(Locale.ROOT, SALIENCE_THRESHOLD))
        }

        val entries = store.loadEntries()
        val entryId = makeId(l, t, v)

        val archive = store.archive().toMutableList()
        val tombstoned = archive.filter { it.optString("id") == entryId && it.has("tomb") }
        if (tombstoned.isNotEmpty()) {
            // deliberately re-learned: a tombstone is not a life sentence
            store.writeJsonl(store.archivePath, archive.filterNot { it.optString("id") == entryId })
        }

        val fresh = MemoryEntry(
            id = entryId, l = l, t = t, v = v, s = s, c = clamp(confidence), f = 0,
            ts = MemoryClock.iso(at), la = MemoryClock.iso(at),
            src = source, k = keywords.toMutableSet().toMutableList(),
            lk = links.toMutableSet().toMutableList(),
        )

        val outcome: String
        val stored: MemoryEntry
        val existing = entries[entryId]
        if (existing != null) {
            val merged = mergeEntry(existing, fresh)
            merged.la = MemoryClock.iso(at)
            merged.f = existing.f + 1 // re-learning rehearses
            entries[entryId] = merged
            stored = merged
            outcome = "reinforced"
        } else {
            entries[entryId] = fresh
            stored = fresh
            outcome = "stored"
        }

        if (fresh.lk.isNotEmpty()) {
            val edges = store.edges().toMutableList()
            fresh.lk.forEach { target ->
                edges.add(JSONObject().put("a", entryId).put("b", target).put("r", "rel").put("at", MemoryClock.iso(at)))
            }
            store.saveEdges(edges)
        }
        store.saveEntries(entries)

        val counters = meta.optJSONObject("counters") ?: JSONObject().also { meta.put("counters", it) }
        counters.put("remembered", counters.optInt("remembered", 0) + 1)
        store.saveMeta(meta)
        store.journal(
            "remember",
            JSONObject().put("id", entryId).put("l", l).put("t", t).put("s", s).put("outcome", outcome),
        )
        reindex(store, entries, meta, at)
        markDirty(store, meta)
        return RememberResult(true, outcome, stored)
    }

    // ---- recall ----

    fun recall(
        store: MemoryStore,
        query: String = "",
        layer: String? = null,
        topic: String? = null,
        limit: Int = 8,
        bump: Boolean = true,
    ): RecallResult {
        val at = MemoryClock.now()
        val meta = store.meta()
        val entries = store.loadEntries()
        val wanted = wordSet(query)
        val normLayer = layer?.let { normalizeLayer(it) }
        val topicPrefix = topic?.let { normalizeTopic(it) }

        data class Scored(val score: Double, val match: Double, val entry: MemoryEntry)

        val scored = mutableListOf<Scored>()
        for (entry in entries.values) {
            if (normLayer != null && entry.l != normLayer) continue
            if (topicPrefix != null && !entry.t.startsWith(topicPrefix)) continue
            val haystack = wordSet(entry.t.replace(".", " "), entry.v, entry.k.joinToString(" "))
            var match: Double
            if (wanted.isNotEmpty()) {
                val hits = (wanted intersect haystack).size
                val partial = wanted.count { w -> haystack.any { teilwort(it, w) } }
                match = (hits + 0.5 * (partial - hits)) / wanted.size
                if (match <= 0) continue
            } else {
                match = 1.0
            }
            if (topicPrefix != null && entry.t == topicPrefix) match += 0.25
            scored.add(Scored(match * (0.25 + strength(entry, at)), match, entry))
        }
        scored.sortByDescending { it.score }
        val hits = scored.take(max(1, limit)).map { it.entry }

        if (bump && hits.isNotEmpty()) {
            hits.forEach { hit ->
                entries[hit.id]?.let { e ->
                    e.f += 1
                    e.la = MemoryClock.iso(at)
                }
            }
            store.saveEntries(entries)
            val counters = meta.optJSONObject("counters") ?: JSONObject().also { meta.put("counters", it) }
            counters.put("recalled", counters.optInt("recalled", 0) + hits.size)
            store.saveMeta(meta)
            store.journal("recall", JSONObject().put("q", query).put("n", hits.size))
            reindex(store, entries, meta, at)
            markDirty(store, store.meta()) // Rehearsal fährt beim nächsten echten Schreiben mit
        }
        return RecallResult(query, scored.size, hits)
    }

    // ---- forget ----

    fun forget(store: MemoryStore, ids: List<String> = emptyList(), topic: String? = null, hard: Boolean = false): ForgetResult {
        val at = MemoryClock.now()
        val meta = store.meta()
        val entries = store.loadEntries()

        val targets = LinkedHashMap<String, MemoryEntry>()
        ids.forEach { id -> entries[id]?.let { targets[id] = it } }
        if (topic != null) {
            val prefix = normalizeTopic(topic)
            entries.values.filter { it.t.startsWith(prefix) }.forEach { targets[it.id] = it }
        }
        if (targets.isEmpty()) return ForgetResult(emptyList())

        if (!hard) {
            // Tombstone statt reiner Archiv-Kopie: sonst würde ein Sync den Eintrag
            // von einer Maschine zurückholen, die ihn noch hat.
            val tombstoned = targets.values.map { it.copy().apply { tomb = MemoryClock.iso(at) } }
            store.appendArchive(tombstoned)
        }
        targets.keys.forEach { entries.remove(it) }
        store.saveEntries(entries)
        store.saveEdges(store.edges().filter {
            entries.containsKey(it.optString("a")) && entries.containsKey(it.optString("b"))
        })
        store.saveMeta(meta)
        store.journal(
            "forget",
            JSONObject().put("ids", JSONArray(targets.keys.toList())).put("hard", hard),
        )
        reindex(store, entries, meta, at)
        markDirty(store, meta)
        return ForgetResult(targets.keys.toList())
    }

    // ---- consolidate ----

    fun consolidate(store: MemoryStore): ConsolidateResult {
        val at = MemoryClock.now()
        val meta = store.meta()
        val entries = store.loadEntries()

        val merged = mutableListOf<String>()
        val byKey = LinkedHashMap<Triple<String, String, String>, MemoryEntry>()
        for (entry in entries.values.toList()) {
            val key = Triple(entry.l, entry.t, entry.v.lowercase())
            val other = byKey[key]
            if (other != null) {
                val winner = mergeEntry(other, entry)
                winner.f = other.f + entry.f
                entries.remove(other.id)
                entries.remove(entry.id)
                entries[winner.id] = winner
                byKey[key] = winner
                merged.add(winner.id)
            } else {
                byKey[key] = entry
            }
        }

        val promoted = mutableListOf<String>()
        val episodesByTopic = entries.values.filter { it.l == "epi" }.groupBy { it.t }
        for ((topic, bucket) in episodesByTopic) {
            if (bucket.size < PROMOTE_AFTER) continue
            val sorted = bucket.sortedByDescending { it.la }
            val seen = mutableSetOf<String>()
            val parts = mutableListOf<String>()
            for (entry in sorted) {
                val lower = entry.v.lowercase()
                if (!seen.add(lower)) continue
                parts.add(entry.v)
                if (parts.size == 3) break
            }
            val gist = normalizeValue("recurring: " + parts.joinToString("; ")).take(280)
            val layer = if (topic.substringBefore('.') in setOf("how", "workflow", "process")) "prc" else "sem"
            val gistId = makeId(layer, topic, gist)
            val salience = min(0.95, bucket.maxOf { it.s } + 0.1)
            val existingGist = entries[gistId]
            if (existingGist != null) {
                existingGist.f += 1
                existingGist.la = MemoryClock.iso(at)
            } else {
                entries[gistId] = MemoryEntry(
                    id = gistId, l = layer, t = topic, v = gist, s = salience, c = 0.7, f = 0,
                    ts = MemoryClock.iso(at), la = MemoryClock.iso(at), src = "consolidate",
                    k = bucket.flatMap { it.k }.distinct().sorted().toMutableList(),
                    lk = bucket.map { it.id }.sorted().toMutableList(),
                )
                promoted.add(gistId)
            }
        }

        val archived = mutableListOf<MemoryEntry>()
        for (entry in entries.values.toList()) {
            if (entry.l == "idt") continue
            if (entry.f == 0 && strength(entry, at) < PRUNE_STRENGTH) {
                entries.remove(entry.id)
                archived.add(entry)
            }
        }
        if (archived.isNotEmpty()) store.appendArchive(archived)

        store.saveEntries(entries)
        store.saveEdges(store.edges().filter {
            entries.containsKey(it.optString("a")) && entries.containsKey(it.optString("b"))
        })
        val counters = meta.optJSONObject("counters") ?: JSONObject().also { meta.put("counters", it) }
        counters.put("pruned", counters.optInt("pruned", 0) + archived.size)
        counters.put("promoted", counters.optInt("promoted", 0) + promoted.size)
        meta.put("last_consolidated", MemoryClock.iso(at))
        store.saveMeta(meta)
        store.journal(
            "consolidate",
            JSONObject().put("merged", merged.size).put("promoted", promoted.size).put("archived", archived.size),
        )
        reindex(store, entries, meta, at)
        markDirty(store, meta)
        return ConsolidateResult(merged, promoted, archived.map { it.id }, entries.size)
    }

    // ---- digest / boot ----

    private fun renderLine(entry: MemoryEntry, at: Instant): String {
        // Locale.ROOT: das Python-Original schreibt immer "0.95" — auf einem
        // deutschen Gerät würde die Default-Locale sonst "0,95" daraus machen.
        val tail = "(%.2f/%d)".format(Locale.ROOT, strength(entry, at), entry.f)
        return "- [${entry.id}] ${entry.t} :: ${entry.v} $tail"
    }

    private fun topicMap(entries: Collection<MemoryEntry>): String {
        val groups = LinkedHashMap<String, Int>()
        entries.forEach { e ->
            val head = e.t.substringBefore('.')
            groups[head] = (groups[head] ?: 0) + 1
        }
        return groups.entries.sortedByDescending { it.value }.take(24)
            .joinToString(" · ") { "${it.key}(${it.value})" }
    }

    private fun ageLabel(created: String?, at: Instant): String {
        if (created.isNullOrBlank()) return "unknown"
        val days = MemoryClock.daysBetween(at, MemoryClock.parseIso(created)).toInt()
        return "$days day(s)"
    }

    fun buildIndex(entries: Map<String, MemoryEntry>, meta: JSONObject, at: Instant): JSONObject {
        val budget = if (meta.has("budget_tokens")) meta.getInt("budget_tokens") else DEFAULT_BUDGET_TOKENS
        val ranked = entries.values.sortedByDescending { strength(it, at) }
        val identity = ranked.filter { it.l == "idt" }
        val rest = ranked.filter { it.l != "idt" }

        fun render(shown: List<MemoryEntry>): String {
            val lines = mutableListOf<String>()
            lines.add(
                "# MEMORY DIGEST — ${MemoryClock.iso(at).take(10)} · ${entries.size} entries · " +
                    "character age ${ageLabel(meta.optString("created", "").ifBlank { null }, at)}",
            )
            lines.add("")
            for (layer in listOf("idt", "sem", "prc", "epi")) {
                val bucket = shown.filter { it.l == layer }
                if (bucket.isEmpty()) continue
                lines.add("## ${MEMORY_LAYER_TITLES.getValue(layer)}")
                bucket.sortedByDescending { strength(it, at) }.forEach { lines.add(renderLine(it, at)) }
                lines.add("")
            }
            if (entries.isNotEmpty()) {
                lines.add("## TOPIC MAP")
                lines.add(topicMap(entries.values))
            }
            val hidden = entries.size - shown.size
            if (hidden > 0) {
                lines.add("")
                lines.add("> $hidden weaker entries are held below the digest — reach them with recall.")
            }
            if (entries.isEmpty()) {
                lines.add("## EMPTY CHARACTER")
                lines.add("No memories yet. Start blank: observe, do not invent a past.")
            }
            return lines.joinToString("\n").trimEnd() + "\n"
        }

        val shown = identity.toMutableList()
        var used = tokensOf(render(identity))
        for (entry in rest) {
            val cost = tokensOf(renderLine(entry, at))
            if (used + cost > budget) continue
            used += cost
            shown.add(entry)
        }

        var digest = render(shown)
        while (tokensOf(digest) > budget) {
            val droppable = shown.filter { it.l != "idt" }
            if (droppable.isEmpty()) break
            shown.remove(droppable.minByOrNull { strength(it, at) })
            digest = render(shown)
        }

        val counts = JSONObject()
        MEMORY_LAYERS.keys.forEach { code -> counts.put(code, entries.values.count { it.l == code }) }

        return JSONObject().apply {
            put("schema", MEMORY_SCHEMA_VERSION)
            put("generated", MemoryClock.iso(at))
            put("budget_tokens", budget)
            put("digest_tokens", tokensOf(digest))
            put("counts", counts)
            put("total", entries.size)
            put("hidden", max(0, entries.size - shown.size))
            put("topics", JSONArray(entries.values.map { it.t }.distinct().sorted()))
            put("digest", digest)
        }
    }

    fun reindex(store: MemoryStore, entries: Map<String, MemoryEntry>, meta: JSONObject, at: Instant): JSONObject {
        val index = buildIndex(entries, meta, at)
        store.writeJson(store.indexPath, index)
        return index
    }

    private fun consolidationDue(meta: JSONObject, at: Instant): Boolean {
        val last = meta.optString("last_consolidated", "")
        if (last.isBlank()) return true
        return MemoryClock.daysBetween(at, MemoryClock.parseIso(last)) >= 1.0
    }

    /**
     * Läuft automatisch vor jeder Chat-Antwort (kein Tool): liefert den
     * Digest-Text, stößt bei Bedarf [consolidate] an. Netzwerk-Sync ist
     * Sache des Aufrufers.
     */
    fun boot(store: MemoryStore): BootResult {
        val at = MemoryClock.now()
        val meta = store.meta()
        val due = consolidationDue(meta, at)
        if (due) consolidate(store)

        val finalMeta = if (due) store.meta() else meta
        val entries = store.loadEntries()
        val diskIndex = store.readJson(store.indexPath)
        val index: JSONObject = if (due || diskIndex == null || diskIndex.optInt("schema", -1) != MEMORY_SCHEMA_VERSION) {
            reindex(store, entries, finalMeta, at)
        } else {
            diskIndex
        }
        return BootResult(finalMeta, index.getString("digest"), due)
    }

    /** Erstellt einen frischen, leeren Speicher (neuer Charakter). */
    fun createFresh(store: MemoryStore, gitUrl: String?, branch: String?): JSONObject {
        val at = MemoryClock.now()
        val meta = JSONObject().apply {
            put("schema", MEMORY_SCHEMA_VERSION)
            put("root_id", java.util.UUID.randomUUID().toString())
            put("created", MemoryClock.iso(at))
            put("git", gitUrl ?: JSONObject.NULL)
            put("branch", branch ?: "main")
            put("budget_tokens", DEFAULT_BUDGET_TOKENS)
            put("dirty", false)
            put("counters", JSONObject().apply {
                put("remembered", 0); put("skipped", 0); put("recalled", 0)
                put("pruned", 0); put("promoted", 0)
            })
        }
        store.saveMeta(meta)
        store.saveCharacter(JSONObject().put("schema", MEMORY_SCHEMA_VERSION).put("entries", JSONArray()))
        for (layer in listOf("sem", "epi", "prc")) store.writeJsonl(store.layerPath(layer), emptyList())
        store.writeJsonl(store.edgesPath, emptyList())
        store.writeJsonl(store.archivePath, emptyList())
        store.journal("init", JSONObject().put("root_id", meta.getString("root_id")).put("restored", false))
        reindex(store, emptyMap(), meta, at)
        return meta
    }
}
