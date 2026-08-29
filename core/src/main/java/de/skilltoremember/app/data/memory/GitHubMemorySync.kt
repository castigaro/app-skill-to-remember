package de.skilltoremember.app.data.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

data class GitHubMemoryConfig(val owner: String, val repo: String, val branch: String, val token: String) {
    val url: String get() = "https://github.com/$owner/$repo"
}

data class SyncReport(var pulled: Boolean = false, var pushed: Boolean = false, var conflicted: Boolean = false)

/**
 * Ersetzt `git fetch`/`git show <rev>:<pfad>`/`git commit`/`git push` aus
 * `op_sync` in `memory.py` durch GitHubs Git-Data-API (reine HTTP/JSON-
 * Aufrufe, kein eingebetteter Git-Client): Pull liest die bekannten Dateien
 * über die Contents-API; Push baut Blob → Tree → Commit → Ref-Update und
 * committet atomar in einem Rutsch — genau wie `git add -A && git commit &&
 * git push` im Original, nur über die REST-API statt über das
 * Git-Wire-Protokoll.
 *
 * Gepullt und mit dem lokalen Stand vereinigt werden nur die Dateien, aus
 * denen sich Einträge zusammensetzen ([PULL_FILES]) — `meta.json` und
 * `index.json` sind lokale Buchführung/gerenderter Cache und werden nur
 * mitgepusht, nie zurückgelesen (genau wie im Original: `git add -A`
 * versioniert alle neun Dateien, aber `_absorb_remote` liest nur sieben
 * davon zurück).
 */
object GitHubMemorySync {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Nur für Tests überschreibbar, um gegen einen MockWebServer statt die echte GitHub-API zu laufen. */
    internal var apiBaseUrl: String = "https://api.github.com"

    class GitHubSyncException(message: String) : IOException(message)

    private fun request(config: GitHubMemoryConfig, path: String): Request.Builder =
        Request.Builder()
            .url("$apiBaseUrl/repos/${config.owner}/${config.repo}$path")
            .header("Authorization", "Bearer ${config.token}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")

    private fun call(builder: Request.Builder): Pair<Int, String> {
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            return response.code to body
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).optString("message") }.getOrNull()
        return "GitHub-API-Fehler ($code)" + if (!detail.isNullOrBlank()) ": $detail" else ""
    }

    /**
     * Liest `meta.json` vom Remote-Repo — nur für den Verbinden-Flow
     * gebraucht, um zu entscheiden, ob ein bestehendes Gedächtnis
     * wiederhergestellt wird oder ein neues entsteht (analog
     * `bootstrap_from_remote`/`op_init`). Der reguläre [sync] rührt
     * `meta.json` beim Pull nicht an, siehe Klassenkommentar.
     */
    fun readRemoteMeta(config: GitHubMemoryConfig): JSONObject? =
        readRemoteFile(config, "meta.json")?.let { JSONObject(it) }

    // ---- Pull: bekannte Dateien vom aktuellen Branch-Stand lesen ----

    /** Liefert den Dateiinhalt bei `path` auf `branch`, oder null wenn er (noch) nicht existiert. */
    private fun readRemoteFile(config: GitHubMemoryConfig, path: String): String? {
        val (code, body) = call(
            request(config, "/contents/$path?ref=${config.branch}"),
        )
        if (code == 404) return null
        if (code !in 200..299) throw GitHubSyncException(errorMessage(code, body))
        val json = JSONObject(body)
        if (json.optString("encoding") == "base64") {
            return String(Base64.getMimeDecoder().decode(json.optString("content", "")), Charsets.UTF_8)
        }
        // Ab 1 MB liefert die Contents-API keinen Inhalt mehr ("encoding": "none") —
        // betrifft realistisch das append-only journal.jsonl. Denselben Pfad als
        // Raw-Media-Type nachladen, das trägt bis 100 MB.
        val (rawCode, rawBody) = call(
            request(config, "/contents/$path?ref=${config.branch}")
                .header("Accept", "application/vnd.github.raw+json"),
        )
        if (rawCode !in 200..299) throw GitHubSyncException(errorMessage(rawCode, rawBody))
        return rawBody
    }

    /**
     * Liest die Remote-Version der bekannten Dateien und vereinigt sie mit
     * dem lokalen Speicher — inhaltsadressierte IDs machen den Merge
     * deterministisch (kein Text-Diff nötig), analog `_absorb_remote`.
     */
    fun pull(store: MemoryStore, config: GitHubMemoryConfig): Boolean {
        var sawAnyRemoteFile = false

        val remoteCharacter = readRemoteFile(config, "character.json")
        val remoteSemantic = readRemoteFile(config, "layers/semantic.jsonl")
        val remoteEpisodic = readRemoteFile(config, "layers/episodic.jsonl")
        val remoteProcedural = readRemoteFile(config, "layers/procedural.jsonl")
        val remoteEdges = readRemoteFile(config, "edges.jsonl")
        val remoteArchive = readRemoteFile(config, "archive.jsonl")
        val remoteJournal = readRemoteFile(config, "journal.jsonl")

        val entries = store.loadEntries()
        val incoming = mutableListOf<MemoryEntry>()
        remoteCharacter?.let {
            sawAnyRemoteFile = true
            val arr = JSONObject(it).optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) incoming.add(MemoryEntry.fromJson(arr.getJSONObject(i)))
        }
        for (text in listOf(remoteSemantic, remoteEpisodic, remoteProcedural)) {
            if (text == null) continue
            sawAnyRemoteFile = true
            parseJsonl(text).forEach { incoming.add(MemoryEntry.fromJson(it)) }
        }

        val remoteArchiveRows = remoteArchive?.let { sawAnyRemoteFile = true; parseJsonl(it) } ?: emptyList()

        // Tombstones aus lokalem UND remote Archiv vereinigen (id -> jüngster Zeitpunkt).
        // Nur remote zu schauen würde eine lokale Löschung beim Pull rückgängig machen,
        // weil der Eintrag remote noch in seinem Layer-File steht.
        val tombstones = mutableMapOf<String, String>()
        for (row in store.archive() + remoteArchiveRows) {
            val tomb = row.optString("tomb", "")
            if (tomb.isBlank()) continue
            val id = row.optString("id")
            if (tomb > (tombstones[id] ?: "")) tombstones[id] = tomb
        }

        for (entry in incoming) {
            val existing = entries[entry.id]
            entries[entry.id] = if (existing != null) MemoryEngine.mergeEntry(existing, entry) else entry
        }

        // Tombstone gegen Eintrag: Zugriff nach der Löschung (la > tomb) heißt bewusst
        // neu gelernt — der Eintrag überlebt und der Tombstone fällt ("a tombstone is
        // not a life sentence", siehe remember()); sonst gewinnt die Löschung.
        val obsoleteTombs = mutableSetOf<String>()
        for ((id, tomb) in tombstones) {
            val entry = entries[id] ?: continue
            if (entry.la > tomb) obsoleteTombs.add(id) else entries.remove(id)
        }

        store.saveEntries(entries)

        remoteEdges?.let {
            sawAnyRemoteFile = true
            store.saveEdges(store.edges() + parseJsonl(it))
        }
        if (remoteArchiveRows.isNotEmpty()) {
            store.appendArchive(remoteArchiveRows.map { MemoryEntry.fromJson(it) })
        }
        if (obsoleteTombs.isNotEmpty()) {
            store.writeJsonl(
                store.archivePath,
                store.archive().filterNot { it.optString("id") in obsoleteTombs && it.has("tomb") },
            )
        }
        remoteJournal?.let {
            sawAnyRemoteFile = true
            unionLines(store.journalPath, it)
        }

        return sawAnyRemoteFile
    }

    private fun parseJsonl(text: String): List<JSONObject> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { JSONObject(it) }.toList()

    /** Vereinigt eine Append-only-Datei zeilenweise, Reihenfolge erhalten, keine Duplikate. */
    private fun unionLines(path: File, remoteText: String) {
        val local = if (path.isFile) path.readLines().map { it.trimEnd('\n') }.filter { it.isNotBlank() } else emptyList()
        val remote = remoteText.lineSequence().filter { it.isNotBlank() }.toList()
        val seen = LinkedHashSet<String>()
        (remote + local).forEach { seen.add(it) }
        path.parentFile?.mkdirs()
        path.writeText(seen.joinToString("") { "$it\n" })
    }

    // ---- Push: alle neun Dateien in einem atomaren Commit ----

    private fun pushFiles(store: MemoryStore): List<Pair<String, File>> = buildList {
        add("meta.json" to store.metaPath)
        add("index.json" to store.indexPath)
        add("character.json" to store.characterPath)
        add("layers/semantic.jsonl" to store.layerPath("sem"))
        add("layers/episodic.jsonl" to store.layerPath("epi"))
        add("layers/procedural.jsonl" to store.layerPath("prc"))
        add("edges.jsonl" to store.edgesPath)
        add("archive.jsonl" to store.archivePath)
        add("journal.jsonl" to store.journalPath)
    }

    private fun createBlob(config: GitHubMemoryConfig, content: String): String {
        val body = JSONObject().put("content", content).put("encoding", "utf-8")
        val (code, resp) = call(
            request(config, "/git/blobs")
                .post(body.toString().toRequestBody(JSON)),
        )
        if (code !in 200..299) throw GitHubSyncException(errorMessage(code, resp))
        return JSONObject(resp).getString("sha")
    }

    private fun getRef(config: GitHubMemoryConfig): String? {
        val (code, body) = call(request(config, "/git/ref/heads/${config.branch}"))
        if (code == 404) return null
        if (code !in 200..299) throw GitHubSyncException(errorMessage(code, body))
        return JSONObject(body).getJSONObject("object").getString("sha")
    }

    private fun getTreeSha(config: GitHubMemoryConfig, commitSha: String): String {
        val (code, body) = call(request(config, "/git/commits/$commitSha"))
        if (code !in 200..299) throw GitHubSyncException(errorMessage(code, body))
        return JSONObject(body).getJSONObject("tree").getString("sha")
    }

    /**
     * Push als ein atomarer Commit. Gibt `true` zurück, wenn wirklich etwas
     * gepusht wurde.
     *
     * Ist der neue Baum identisch mit dem des Elterncommits, entsteht KEIN
     * Commit: Vorher schrieb jeder Sync einen Eintrag in die Historie, auch
     * wenn sich keine einzige Datei geändert hatte (im Repo als Commit mit
     * "0 files changed" sichtbar).
     */
    fun push(store: MemoryStore, config: GitHubMemoryConfig, message: String, retries: Int = 4): Boolean {
        var attempt = 0
        var delayMs = 2000L
        while (true) {
            attempt++
            val parentSha = getRef(config)
            val treeEntries = JSONArray()
            for ((path, file) in pushFiles(store)) {
                val content = if (file.isFile) file.readText() else ""
                val blobSha = createBlob(config, content)
                treeEntries.put(
                    JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", blobSha),
                )
            }
            val treeBody = JSONObject().put("tree", treeEntries)
            val parentTreeSha = if (parentSha != null) getTreeSha(config, parentSha) else null
            if (parentTreeSha != null) treeBody.put("base_tree", parentTreeSha)
            val (treeCode, treeResp) = call(
                request(config, "/git/trees").post(treeBody.toString().toRequestBody(JSON)),
            )
            if (treeCode !in 200..299) throw GitHubSyncException(errorMessage(treeCode, treeResp))
            val newTreeSha = JSONObject(treeResp).getString("sha")

            // Nichts geändert: GitHub liefert bei gleichem Inhalt dieselbe
            // Tree-Kennung zurück. Ein Commit darauf wäre ein leerer Eintrag
            // in der Historie.
            if (newTreeSha == parentTreeSha) return false

            val commitBody = JSONObject().put("message", message).put("tree", newTreeSha)
            commitBody.put("parents", if (parentSha != null) JSONArray().put(parentSha) else JSONArray())
            val (commitCode, commitResp) = call(
                request(config, "/git/commits").post(commitBody.toString().toRequestBody(JSON)),
            )
            if (commitCode !in 200..299) throw GitHubSyncException(errorMessage(commitCode, commitResp))
            val newCommitSha = JSONObject(commitResp).getString("sha")

            val (refCode, refResp) = if (parentSha != null) {
                call(
                    request(config, "/git/refs/heads/${config.branch}")
                        .patch(JSONObject().put("sha", newCommitSha).put("force", false).toString().toRequestBody(JSON)),
                )
            } else {
                call(
                    request(config, "/git/refs")
                        .post(
                            JSONObject().put("ref", "refs/heads/${config.branch}").put("sha", newCommitSha)
                                .toString().toRequestBody(JSON),
                        ),
                )
            }
            if (refCode in 200..299) return true

            // Ref hat sich zwischenzeitlich bewegt (Konflikt) oder existiert schon: erneut pullen und retryen.
            if (attempt > retries) throw GitHubSyncException(errorMessage(refCode, refResp))
            pull(store, config)
            Thread.sleep(delayMs)
            delayMs *= 2
        }
    }

    /** Pull + Push in einem Rutsch, wie `op_sync` im Original. */
    suspend fun sync(store: MemoryStore, config: GitHubMemoryConfig, message: String): SyncReport =
        withContext(Dispatchers.IO) {
            val report = SyncReport()
            report.pulled = pull(store, config)
            report.pushed = push(store, config, message)
            report
        }
}
