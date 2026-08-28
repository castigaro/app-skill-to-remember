package de.skilltoremember.app.api

import android.content.Context
import de.skilltoremember.app.data.BuiltInSkills
import de.skilltoremember.app.data.Chat
import de.skilltoremember.app.data.Message
import de.skilltoremember.app.data.Skill
import de.skilltoremember.app.data.SkillMarkdown
import de.skilltoremember.app.data.SkillStore
import de.skilltoremember.app.data.memory.GitHubMemorySync
import de.skilltoremember.app.data.memory.MemoryClock
import de.skilltoremember.app.data.memory.MemoryEngine
import de.skilltoremember.app.data.memory.MemorySettings
import de.skilltoremember.app.data.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Anbindung an Anthropic (Messages API) und OpenAI (Chat Completions API).
 *
 * Skills werden dem Modell nicht komplett in den Kontext gepackt, sondern
 * nur als Name + Beschreibung gelistet ("progressive disclosure", wie bei
 * Claude Code Skills). Ein Tool `load_skill(name)` lässt das Modell selbst
 * entscheiden, wann ein Skill zur Anfrage passt, und lädt dann dessen
 * vollständige Anleitung als Tool-Ergebnis nach.
 *
 * Ist der eingebaute `humanoid-behavior`-Skill aktiv und ein Gedächtnis-Repo
 * verbunden, kommen drei weitere Tools dazu — `remember`/`recall`/`forget` —
 * über die das Modell das persistente Gedächtnis bedient (siehe
 * [de.skilltoremember.app.data.memory.MemoryEngine]). Der Boot-Digest (das, was
 * das Gedächtnis "weiß") wird automatisch vor jeder Antwort geladen und dem
 * System-Prompt vorangestellt — kein Tool-Aufruf nötig, entspricht dem
 * SessionStart-Hook aus der Original-Skill-Doku.
 */
object ChatApi {

    private const val TOOL_LOAD_SKILL = "load_skill"
    private const val TOOL_REMEMBER = "remember"
    private const val TOOL_RECALL = "recall"
    private const val TOOL_FORGET = "forget"
    private const val TOOL_GET_LOCATION = "get_location"
    private const val TOOL_ADD_CALENDAR_EVENT = "add_calendar_event"
    private const val TOOL_DRAFT_EMAIL = "draft_email"
    private const val MAX_TOOL_ROUNDS = 6
    private const val MEMORY_PULL_INTERVAL_SECONDS = 5 * 60L

    // Basis-Variante des serverseitigen Suche-Tools: läuft auf allen aktuellen
    // Claude-Modellen (auch Haiku) — die neueren Varianten nicht.
    private const val WEB_SEARCH_TOOL_TYPE = "web_search_20250305"
    private const val WEB_SEARCH_MAX_USES = 5

    /** Überschrift des angehängten Quellen-Blocks — die Sprachausgabe schneidet ab hier ab. */
    const val SOURCES_HEADING = "Quellen:"

    private val JSON = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    class ApiException(message: String) : IOException(message)

    private val BASE_SYSTEM_PROMPT = """
        Du bist ein hilfreicher KI-Assistent in einer Chat-App. Antworte klar,
        knapp und in der Sprache des Nutzers (im Zweifel Deutsch).
    """.trimIndent()

    /**
     * Erzeugt die nächste Assistenten-Antwort für den Chat-Verlauf.
     * [concise] steht für den Sprachdialog-Modus: Die Antwort wird vorgelesen,
     * das Modell soll sich deshalb kurz fassen.
     */
    suspend fun reply(context: Context, chat: Chat, concise: Boolean = false): String = withContext(Dispatchers.IO) {
        val config = ProviderSettings.activeConfig(context)
            ?: throw ApiException("Kein aktiver API-Key — in den Einstellungen hinterlegen oder aktivieren.")
        val skills = SkillStore.getEnabled(context)
        val memoryStore = memoryStoreIfActive(context, skills)
        memoryStore?.let { pullMemoryIfStale(context, it) }
        val digest = memoryStore?.let { runCatching { MemoryEngine.boot(it).digest }.getOrNull() }
        val systemPrompt = buildSystemPrompt(skills, memoryStore != null, digest, concise)
        val turn = Turn()

        try {
            if (config.provider == ProviderSettings.PROVIDER_OPENAI) {
                requestOpenAi(context, config.apiKey, config.model, systemPrompt, chat.messages, skills, memoryStore, turn = turn)
            } else {
                requestAnthropic(context, config.apiKey, config.model, systemPrompt, chat.messages, skills, memoryStore, turn = turn)
            }
        } finally {
            // EIN Sync je Antwort statt einer je Schreib-Tool: "Milch, Butter,
            // Brot" ist damit ein Commit, nicht drei volle Sync-Runden. Im
            // finally, damit auch eine abgebrochene Antwort ihre schon
            // geschriebenen Einträge noch verteilt. `dirty` fängt zusätzlich
            // Altlasten früherer fehlgeschlagener Syncs auf.
            if (memoryStore != null && MemorySettings.isConfigured(context)
                && (turn.geschrieben || memoryStore.meta().optBoolean("dirty", false))
            ) {
                syncAfterWrite(context, memoryStore)
            }
        }
    }

    /**
     * Zustand einer einzelnen Antwort-Runde: gab es Schreibzugriffe (dann am
     * Ende EIN Sync), und wurde das Gedächtnis für diese Runde schon frisch
     * gepullt (dann nicht noch einmal je geladenem Skill).
     */
    internal class Turn(
        var geschrieben: Boolean = false,
        var frischGepullt: Boolean = false,
    )

    private fun memoryStoreIfActive(context: Context, skills: List<Skill>): MemoryStore? {
        val skillActive = skills.any { it.id == BuiltInSkills.HUMANOID_BEHAVIOR.id }
        if (!skillActive) return null
        // Aktiv, sobald der lokale Speicher existiert — mit verbundenem Repo
        // (synchronisiert) oder ohne (Nur-lokal-Modus, siehe Einstellungen).
        val store = MemorySettings.store(context)
        if (!store.exists()) return null
        return store
    }

    private val CONCISE_PROMPT = """
        Sprachdialog-Modus: Der Nutzer spricht mit dir und hört deine Antwort
        über eine Sprachausgabe. Fasse dich deshalb besonders kurz — zwei,
        drei gesprochene Sätze, keine Aufzählungen, kein Markdown, keine
        Codeblöcke. Nenne erst dann mehr Details, wenn der Nutzer nachfragt.
        Die Kürze gilt NUR für den gesprochenen Text: Werkzeuge (Skills laden,
        Gedächtnis) nutzt du weiterhin vollständig und gewissenhaft.
    """.trimIndent()

    // ---- KI-Skill-Autor: aus einer Idee eine saubere SKILL.md machen ----

    private val SKILL_AUTHOR_PROMPT = """
        Du bist Autor von SKILL.md-Dateien für die App SkillToRemember (Format des
        Agent-Skills-Standards). Der Nutzer beschreibt eine Idee; du antwortest
        AUSSCHLIESSLICH mit dem Inhalt der fertigen SKILL.md — kein Text davor oder
        danach, keine Code-Zäune.

        Aufbau: YAML-Frontmatter mit genau zwei einzeiligen Feldern — `name`
        (kurz, kleingeschrieben, Bindestriche statt Leerzeichen) und `description`
        (ein Satz: wann der Skill greifen soll, mit zwei, drei typischen
        Beispiel-Formulierungen des Nutzers in Anführungszeichen). Danach die
        Anleitung in klarem, knappem Markdown mit wenigen Abschnitten.

        Soll sich der Skill Dinge dauerhaft merken, nutze die vorhandenen
        Gedächtnis-Werkzeuge remember/recall/forget: Lege eine feste Topic-Struktur
        fest (Punktpfade wie `list.<thema>` oder `date.<anlass>.<person>`),
        beschreibe Speicher- und Abfrage-Verhalten konkret, und erlaube `forget`
        nur auf ausdrücklichen Nutzerwunsch. Schreibe die Anleitung auf Deutsch.
    """.trimIndent()

    /**
     * Erzeugt aus einer Nutzer-Idee einen fertigen Skill. Läuft ohne Werkzeuge und
     * ohne Gedächtnis-Kontext — eine reine Schreibaufgabe. Das Modell wird dafür
     * bei Bedarf angehoben (siehe [skillAuthorModel]): Ein Skill wird einmal
     * geschrieben und hundertfach benutzt, hier zählt Qualität vor Preis.
     */
    suspend fun generateSkill(context: Context, idea: String): Skill = withContext(Dispatchers.IO) {
        val config = ProviderSettings.activeConfig(context)
            ?: throw ApiException("Kein aktiver API-Key — in den Einstellungen hinterlegen oder aktivieren.")
        val model = skillAuthorModel(config.provider, config.model)
        val history = listOf(Message(Message.ROLE_USER, idea))
        val text = if (config.provider == ProviderSettings.PROVIDER_OPENAI) {
            requestOpenAi(context, config.apiKey, model, SKILL_AUTHOR_PROMPT, history, emptyList(), null, withTools = false)
        } else {
            requestAnthropic(context, config.apiKey, model, SKILL_AUTHOR_PROMPT, history, emptyList(), null, withTools = false)
        }
        SkillMarkdown.parse(stripCodeFence(text), fallbackName = "neuer-skill")
    }

    /** Kleine Modelle liefern gültiges Format, aber flachere Anleitungen — fürs einmalige Schreiben lohnt die nächste Stufe. */
    internal fun skillAuthorModel(provider: String, model: String): String = when {
        provider == ProviderSettings.PROVIDER_OPENAI && model.contains("mini", ignoreCase = true) -> "gpt-4o"
        provider != ProviderSettings.PROVIDER_OPENAI && model.contains("haiku", ignoreCase = true) -> "claude-sonnet-5"
        else -> model
    }

    /** Modelle packen Markdown gern in ```-Zäune, obwohl man sie darum bittet, es zu lassen. */
    internal fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutFirstLine = trimmed.substringAfter('\n', missingDelimiterValue = "")
        return withoutFirstLine.substringBeforeLast("```").trim()
    }

    /** Abgleich fällig, wenn der letzte Sync fehlt, unlesbar oder älter als das Intervall ist. */
    internal fun isMemoryPullDue(lastSyncIso: String?, now: Instant): Boolean {
        val last = lastSyncIso?.let { runCatching { MemoryClock.parseIso(it) }.getOrNull() } ?: return true
        return ChronoUnit.SECONDS.between(last, now) >= MEMORY_PULL_INTERVAL_SECONDS
    }

    /**
     * Holt vor der Antwort die Erinnerungen der anderen Geräte — gedrosselt, damit
     * nicht jede Nachricht die GitHub-API anfragt. Nach dem Pull wird der Digest neu
     * gebaut (der Pull selbst schreibt index.json nicht). Scheitert der Abgleich
     * (offline), antwortet die App mit dem lokalen Stand.
     */
    private fun pullMemoryIfStale(context: Context, store: MemoryStore) {
        if (!MemorySettings.isConfigured(context)) return // Nur-lokal-Modus: nichts zu holen
        if (!isMemoryPullDue(MemorySettings.getLastSync(context), MemoryClock.now())) return
        runCatching {
            GitHubMemorySync.pull(store, MemorySettings.config(context))
            MemoryEngine.reindex(store, store.loadEntries(), store.meta(), MemoryClock.now())
            MemorySettings.setLastSync(context, MemoryClock.isoNow())
        }
    }

    /**
     * Datum und Uhrzeit gehören in jeden System-Prompt: Ohne sie kann das Modell
     * "heute", "morgen" oder fällige Erinnerungen nicht auflösen — sein
     * Trainingswissen endet irgendwann, die Geräteuhr nicht.
     */
    internal fun dateTimeLine(now: ZonedDateTime): String {
        val formatter = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN)
        return "Aktuelles Datum und Uhrzeit beim Nutzer: ${now.format(formatter)} Uhr (Zeitzone ${now.zone.id})."
    }

    internal fun buildSystemPrompt(skills: List<Skill>, memoryActive: Boolean, digest: String?, concise: Boolean): String {
        val parts = mutableListOf(BASE_SYSTEM_PROMPT, dateTimeLine(ZonedDateTime.now()))
        if (concise) {
            parts.add(CONCISE_PROMPT)
        }
        if (memoryActive && digest != null) {
            parts.add(digest.trim())
        }
        if (memoryActive) {
            // Die harte Regel gegen das "Schein-Speichern": Das Modell hatte
            // Einträge bestätigt, ohne das Werkzeug zu rufen — jedes Gerät
            // führte seine Liste nur im eigenen Chatverlauf.
            parts.add(
                """
                    Gedächtnis-Regel: Sage NIEMALS, dass du dir etwas gemerkt, gespeichert,
                    auf eine Liste gesetzt oder vergessen hast, ohne dass der zugehörige
                    Werkzeug-Aufruf ($TOOL_REMEMBER bzw. $TOOL_FORGET) in dieser Antwort
                    erfolgreich war. Eine Bestätigung ohne Werkzeug-Aufruf ist eine falsche
                    Auskunft. Antworten aus dem Gesprächsverlauf ersetzen keinen Abruf per
                    $TOOL_RECALL.
                """.trimIndent(),
            )
        }
        if (skills.isNotEmpty()) {
            val list = skills.joinToString("\n") { "- ${it.name}: ${it.description}" }
            parts.add(
                """
                    Verfügbare Skills (per "$TOOL_LOAD_SKILL"-Tool mit dem exakten Namen ladbar):
                    $list

                    Ruf "$TOOL_LOAD_SKILL" auf, sobald ein Skill zur Anfrage passt, und befolge
                    danach dessen Anleitung — insbesondere IMMER, bevor du etwas merkst,
                    auf eine Liste setzt oder eine Liste vorliest, wenn ein Skill dafür
                    existiert. Ohne passenden Skill antworte normal.
                """.trimIndent(),
            )
        }
        return parts.joinToString("\n\n")
    }

    private fun findSkill(skills: List<Skill>, name: String): Skill? =
        skills.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun apiRole(message: Message): String =
        if (message.role == Message.ROLE_ASSISTANT) "assistant" else "user"

    private fun trackCost(context: Context, provider: String, model: String, inputTokens: Long, outputTokens: Long) {
        val micros = ModelPricing.estimateCostMicros(provider, model, inputTokens, outputTokens)
        ProviderSettings.addCostMicros(context, provider, micros)
    }

    // ---- Tool-Ausführung (gemeinsam für Anthropic und OpenAI) ----

    private fun executeTool(context: Context, name: String, input: JSONObject, skills: List<Skill>, memoryStore: MemoryStore?, turn: Turn): String =
        when (name) {
            TOOL_LOAD_SKILL -> {
                val skillName = input.optString("name")
                val skill = findSkill(skills, skillName)
                // Wer einen Skill lädt, will gleich mit dem Gedächtnis arbeiten —
                // die Lese-Drossel wird hier einmal je Runde übersprungen, damit
                // z. B. die Einkaufsliste den Stand des anderen Geräts von JETZT
                // sieht, nicht den von vor fünf Minuten.
                if (skill != null && memoryStore != null && !turn.frischGepullt
                    && MemorySettings.isConfigured(context)
                ) {
                    turn.frischGepullt = true
                    runCatching {
                        GitHubMemorySync.pull(memoryStore, MemorySettings.config(context))
                        MemoryEngine.reindex(memoryStore, memoryStore.loadEntries(), memoryStore.meta(), MemoryClock.now())
                        MemorySettings.setLastSync(context, MemoryClock.isoNow())
                    }
                }
                skill?.body ?: unknownSkillMessage(skillName, skills)
            }
            TOOL_REMEMBER -> executeRemember(input, memoryStore, turn)
            TOOL_RECALL -> executeRecall(input, memoryStore)
            TOOL_FORGET -> executeForget(input, memoryStore, turn)
            TOOL_GET_LOCATION ->
                if (ProviderSettings.isLocationEnabled(context)) LocationProvider.describe(context)
                else "Standortabfrage ist in den Einstellungen deaktiviert."
            TOOL_ADD_CALENDAR_EVENT -> executeAddCalendarEvent(context, input)
            TOOL_DRAFT_EMAIL -> executeDraftEmail(context, input)
            else -> "Unbekanntes Tool \"$name\"."
        }

    private fun executeAddCalendarEvent(context: Context, input: JSONObject): String {
        val title = input.optString("title")
        if (title.isBlank()) return "Titel fehlt."
        val start = parseLocalDateTimeMillis(input.optString("start"))
            ?: return "Startzeit fehlt oder ist kein ISO-Datum (erwartet z. B. 2026-09-20T10:00)."
        val end = input.optString("end").ifBlank { null }?.let { parseLocalDateTimeMillis(it) }
        return DeviceActions.openCalendarInsert(
            context, title, start, end,
            location = input.optString("location").ifBlank { null },
            notes = input.optString("notes").ifBlank { null },
        )
    }

    private fun executeDraftEmail(context: Context, input: JSONObject): String {
        val subject = input.optString("subject")
        val body = input.optString("body")
        if (subject.isBlank() && body.isBlank()) return "Betreff oder Text fehlt."
        return DeviceActions.openEmailDraft(context, input.optString("to").ifBlank { null }, subject, body)
    }

    /** Akzeptiert lokales ISO-Datum mit oder ohne Uhrzeit ("2026-09-20T10:00", "2026-09-20"). */
    internal fun parseLocalDateTimeMillis(text: String): Long? = runCatching {
        val trimmed = text.trim()
        val local = runCatching { java.time.LocalDateTime.parse(trimmed) }
            .getOrElse { java.time.LocalDate.parse(trimmed).atStartOfDay() }
        local.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    /** Kalender-Termin und E-Mail-Entwurf — nur am Handy; die Uhr speichert solche Wünsche als Erinnerung (siehe Skill). */
    private fun deviceActionTools(forOpenAi: Boolean): List<JSONObject> {
        fun schema(properties: JSONObject, required: List<String>): JSONObject = JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            put("required", JSONArray(required))
        }

        fun tool(name: String, description: String, params: JSONObject): JSONObject =
            if (forOpenAi) {
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", name).put("description", description).put("parameters", params),
                )
            } else {
                JSONObject().put("name", name).put("description", description).put("input_schema", params)
            }

        val calendarSchema = schema(
            JSONObject().apply {
                put("title", JSONObject().put("type", "string").put("description", "Titel des Termins"))
                put("start", JSONObject().put("type", "string").put("description", "Beginn, lokales ISO-Format: JJJJ-MM-TTThh:mm (oder nur JJJJ-MM-TT für ganztägig)"))
                put("end", JSONObject().put("type", "string").put("description", "Ende, gleiches Format, optional"))
                put("location", JSONObject().put("type", "string").put("description", "Ort, optional"))
                put("notes", JSONObject().put("type", "string").put("description", "Notiz/Beschreibung, optional"))
            },
            listOf("title", "start"),
        )
        val emailSchema = schema(
            JSONObject().apply {
                put("to", JSONObject().put("type", "string").put("description", "Empfänger-Adresse; weglassen, wenn unbekannt"))
                put("subject", JSONObject().put("type", "string").put("description", "Betreff"))
                put("body", JSONObject().put("type", "string").put("description", "Der ausformulierte Text der E-Mail"))
            },
            listOf("subject", "body"),
        )

        return listOf(
            tool(
                TOOL_ADD_CALENDAR_EVENT,
                "Öffnet die Kalender-App mit einem vorausgefüllten Termin — der Nutzer prüft und speichert ihn selbst. Nichts wird automatisch eingetragen.",
                calendarSchema,
            ),
            tool(
                TOOL_DRAFT_EMAIL,
                "Öffnet die Standard-Mail-App mit einem fertigen Entwurf — der Nutzer prüft und sendet ihn selbst. Es wird nie automatisch gesendet.",
                emailSchema,
            ),
        )
    }

    /** Standort-Werkzeug — parameterlos; das Modell soll es nur bei Ortsbezug aufrufen. */
    private fun locationTool(forOpenAi: Boolean): JSONObject {
        val description = "Ermittelt den ungefähren aktuellen Standort des Nutzers (Ortsname und " +
            "Koordinaten). Nur aufrufen, wenn die Frage einen Bezug zum Aufenthaltsort hat — " +
            "z. B. \"Wo bin ich?\", lokales Wetter oder Fragen zur Umgebung."
        val schema = JSONObject().put("type", "object").put("properties", JSONObject())
        return if (forOpenAi) {
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", TOOL_GET_LOCATION).put("description", description).put("parameters", schema),
            )
        } else {
            JSONObject().put("name", TOOL_GET_LOCATION).put("description", description).put("input_schema", schema)
        }
    }

    private fun executeRemember(input: JSONObject, memoryStore: MemoryStore?, turn: Turn): String {
        if (memoryStore == null) return "Kein Gedächtnis-Repo verbunden."
        val keywords = input.optJSONArray("keywords")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
        val result = runCatching {
            MemoryEngine.remember(
                store = memoryStore,
                layer = input.optString("layer"),
                topic = input.optString("topic"),
                value = input.optString("value"),
                salience = input.optDouble("salience", 0.5),
                confidence = input.optDouble("confidence", 0.8),
                keywords = keywords,
            )
        }
        return result.fold(
            onSuccess = { r ->
                if (!r.stored) return "Nicht gespeichert: ${r.reason}"
                turn.geschrieben = true // der Sync läuft EINMAL am Ende der Runde
                "Gespeichert (${r.outcome})."
            },
            onFailure = { e -> "Fehler beim Speichern: ${e.message}" },
        )
    }

    internal fun executeRecall(input: JSONObject, memoryStore: MemoryStore?): String {
        if (memoryStore == null) return "Kein Gedächtnis-Repo verbunden."
        val result = runCatching {
            MemoryEngine.recall(
                store = memoryStore,
                query = input.optString("query"),
                layer = input.optString("layer").ifBlank { null },
                topic = input.optString("topic").ifBlank { null },
                limit = input.optInt("limit", 8).coerceIn(1, 100),
                bump = input.optBoolean("bump", true),
            )
        }
        return result.fold(
            onSuccess = { r ->
                if (r.hits.isEmpty()) "Keine Treffer."
                else r.hits.joinToString("\n") { "[${it.id}] ${it.t} :: ${it.v}" }
            },
            onFailure = { e -> "Fehler beim Abrufen: ${e.message}" },
        )
    }

    private fun executeForget(input: JSONObject, memoryStore: MemoryStore?, turn: Turn): String {
        if (memoryStore == null) return "Kein Gedächtnis-Repo verbunden."
        val id = input.optString("id").ifBlank { null }
        val topic = input.optString("topic").ifBlank { null }
        val result = runCatching { MemoryEngine.forget(memoryStore, listOfNotNull(id), topic) }
        return result.fold(
            onSuccess = { r ->
                if (r.forgottenIds.isEmpty()) return "Nichts gefunden."
                turn.geschrieben = true // der Sync läuft EINMAL am Ende der Runde
                "Vergessen: ${r.forgottenIds.joinToString(", ")}."
            },
            onFailure = { e -> "Fehler beim Vergessen: ${e.message}" },
        )
    }

    private fun syncAfterWrite(context: Context, store: MemoryStore): Boolean = runCatching {
        val config = MemorySettings.config(context)
        GitHubMemorySync.pull(store, config)
        MemoryEngine.reindex(store, store.loadEntries(), store.meta(), MemoryClock.now())
        GitHubMemorySync.push(store, config, "memory: ${MemoryClock.isoNow()}")
        val meta = store.meta()
        meta.put("dirty", false)
        meta.put("last_sync", MemoryClock.isoNow())
        store.saveMeta(meta)
        MemorySettings.setLastSync(context, MemoryClock.isoNow())
        true
    }.getOrDefault(false)

    private fun memoryTools(memoryStore: MemoryStore?, forOpenAi: Boolean): List<JSONObject> {
        if (memoryStore == null) return emptyList()

        fun schema(properties: JSONObject, required: List<String>): JSONObject = JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            put("required", JSONArray(required))
        }

        val rememberSchema = schema(
            JSONObject().apply {
                put("layer", JSONObject().put("type", "string").put("enum", JSONArray(listOf("idt", "sem", "epi", "prc")))
                    .put("description", "idt=Identität, sem=stabile Fakten, epi=Ereignis, prc=Arbeitsweise"))
                put("topic", JSONObject().put("type", "string").put("description", "Punktpfad, z. B. pref.shell"))
                put("value", JSONObject().put("type", "string").put("description", "Der destillierte Fakt, ein Satz"))
                put("salience", JSONObject().put("type", "number").put("description", "0..1, siehe Skill-Anleitung"))
                put("confidence", JSONObject().put("type", "number").put("description", "0..1, optional, Default 0.8"))
                put("keywords", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            },
            listOf("layer", "topic", "value", "salience"),
        )
        val recallSchema = schema(
            JSONObject().apply {
                put("query", JSONObject().put("type", "string"))
                put("layer", JSONObject().put("type", "string").put("enum", JSONArray(listOf("idt", "sem", "epi", "prc"))))
                put("topic", JSONObject().put("type", "string"))
                // Für Listen-Abfragen: hohes limit statt der 8er-Vorgabe, und
                // bump=false, damit bloßes Vorlesen nicht als "Wiederlernen"
                // zählt (das hielte gelöschte Einträge am Leben).
                put("limit", JSONObject().put("type", "integer").put("description", "Höchstzahl Treffer (1–100, Vorgabe 8). Für vollständige Listen hoch wählen."))
                put("bump", JSONObject().put("type", "boolean").put("description", "false = reiner Lesezugriff ohne Gedächtnis-Auffrischung (für Listen-Abfragen)."))
            },
            listOf("query"),
        )
        val forgetSchema = schema(
            JSONObject().apply {
                put("id", JSONObject().put("type", "string"))
                put("topic", JSONObject().put("type", "string"))
            },
            emptyList(),
        )

        fun tool(name: String, description: String, params: JSONObject): JSONObject =
            if (forOpenAi) {
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", name).put("description", description).put("parameters", params),
                )
            } else {
                JSONObject().put("name", name).put("description", description).put("input_schema", params)
            }

        return listOf(
            tool(TOOL_REMEMBER, "Speichert oder verstärkt einen Gedächtnis-Eintrag.", rememberSchema),
            tool(TOOL_RECALL, "Sucht im Gedächtnis nach passenden Einträgen.", recallSchema),
            tool(TOOL_FORGET, "Archiviert einen falschen oder veralteten Eintrag — nur auf ausdrücklichen Wunsch des Nutzers verwenden, nie aus eigener Initiative.", forgetSchema),
        )
    }

    // ---- Anthropic ----

    internal fun anthropicTools(
        skills: List<Skill>,
        memoryStore: MemoryStore?,
        webSearch: Boolean,
        location: Boolean,
        deviceActions: Boolean = false,
    ): JSONArray {
        val tools = JSONArray()
        if (location) {
            tools.put(locationTool(forOpenAi = false))
        }
        if (deviceActions) {
            deviceActionTools(forOpenAi = false).forEach { tools.put(it) }
        }
        if (webSearch) {
            // Serverseitig: Anthropic führt die Suche selbst aus, die App muss nichts tun.
            tools.put(JSONObject().apply {
                put("type", WEB_SEARCH_TOOL_TYPE)
                put("name", "web_search")
                put("max_uses", WEB_SEARCH_MAX_USES)
                put("user_location", JSONObject().apply {
                    put("type", "approximate")
                    put("timezone", java.util.TimeZone.getDefault().id)
                })
            })
        }
        if (skills.isNotEmpty()) {
            tools.put(JSONObject().apply {
                put("name", TOOL_LOAD_SKILL)
                put("description", "Lädt die vollständige Anleitung eines Skills anhand seines exakten Namens.")
                put("input_schema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("name", JSONObject().apply {
                            put("type", "string")
                            put("description", "Exakter Name des Skills")
                        })
                    })
                    put("required", JSONArray().put("name"))
                })
            })
        }
        memoryTools(memoryStore, forOpenAi = false).forEach { tools.put(it) }
        return tools
    }

    private fun requestAnthropic(
        context: Context,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<Message>,
        skills: List<Skill>,
        memoryStore: MemoryStore?,
        withTools: Boolean = true,
        turn: Turn = Turn(),
    ): String {
        val messages = JSONArray()
        history.forEach { msg ->
            messages.put(JSONObject().apply {
                put("role", apiRole(msg))
                put("content", msg.text)
            })
        }
        val tools = if (withTools) {
            anthropicTools(
                skills, memoryStore,
                webSearch = ProviderSettings.isWebSearchEnabled(context),
                location = ProviderSettings.isLocationEnabled(context),
                deviceActions = DeviceActions.available(context),
            )
        } else {
            JSONArray()
        }

        for (round in 0..MAX_TOOL_ROUNDS) {
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 2048)
                put("system", systemPrompt)
                put("messages", messages)
                if (tools.length() > 0) put("tools", tools)
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw ApiException(apiErrorMessage(responseText, response.code))
                }
                val json = JSONObject(responseText)
                json.optJSONObject("usage")?.let { usage ->
                    trackCost(
                        context, ProviderSettings.PROVIDER_ANTHROPIC, model,
                        inputTokens = usage.optLong("input_tokens", 0L),
                        outputTokens = usage.optLong("output_tokens", 0L),
                    )
                    val searches = usage.optJSONObject("server_tool_use")?.optLong("web_search_requests", 0L) ?: 0L
                    if (searches > 0) {
                        ProviderSettings.addCostMicros(
                            context, ProviderSettings.PROVIDER_ANTHROPIC,
                            searches * ModelPricing.WEB_SEARCH_COST_MICROS_PER_SEARCH,
                        )
                    }
                }

                val content = json.getJSONArray("content")

                // Lange Such-Runden pausiert die API — die angefangene Antwort
                // unverändert zurückschicken, dann macht sie an der Stelle weiter.
                if (json.optString("stop_reason") == "pause_turn") {
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", content)
                    })
                    return@use
                }

                if (json.optString("stop_reason") == "tool_use") {
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", content)
                    })
                    val toolResults = JSONArray()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") != "tool_use") continue
                        val toolName = block.optString("name")
                        val toolInput = block.optJSONObject("input") ?: JSONObject()
                        val resultText = executeTool(context, toolName, toolInput, skills, memoryStore, turn)
                        toolResults.put(JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", block.getString("id"))
                            put("content", resultText)
                        })
                    }
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", toolResults)
                    })
                    return@use
                }

                val text = extractAnthropicText(content)
                if (text.isNotBlank()) return text
                throw ApiException("Leere Antwort vom Modell.")
            }
        }
        throw ApiException("Zu viele Tool-Aufrufe in Folge — abgebrochen.")
    }

    /**
     * Fügt alle Text-Blöcke der Antwort zusammen — mit aktiver Websuche liefert
     * die API den Text in mehreren Blöcken mit Quellen-Zitaten; Such-Blöcke
     * (server_tool_use, web_search_tool_result) werden übersprungen. Zitierte
     * Quellen werden dedupliziert als "[SOURCES_HEADING]"-Block angehängt
     * (Pflicht bei der Anzeige von Suchergebnissen).
     */
    internal fun extractAnthropicText(content: JSONArray): String {
        val text = StringBuilder()
        val sources = LinkedHashMap<String, String>() // URL -> Titel, in Zitier-Reihenfolge
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") != "text") continue
            text.append(block.optString("text"))
            val citations = block.optJSONArray("citations") ?: continue
            for (c in 0 until citations.length()) {
                val citation = citations.getJSONObject(c)
                val url = citation.optString("url")
                if (url.isNotBlank() && !sources.containsKey(url)) {
                    sources[url] = citation.optString("title")
                }
            }
        }
        if (sources.isNotEmpty()) {
            text.append("\n\n").append(SOURCES_HEADING)
            sources.forEach { (url, title) ->
                text.append("\n• ").append(title.ifBlank { url }).append(" — ").append(url)
            }
        }
        return text.toString().trim()
    }

    // ---- OpenAI ----

    private fun openAiTools(skills: List<Skill>, memoryStore: MemoryStore?, location: Boolean, deviceActions: Boolean): JSONArray {
        val tools = JSONArray()
        if (location) {
            tools.put(locationTool(forOpenAi = true))
        }
        if (deviceActions) {
            deviceActionTools(forOpenAi = true).forEach { tools.put(it) }
        }
        if (skills.isNotEmpty()) {
            tools.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", TOOL_LOAD_SKILL)
                    put("description", "Lädt die vollständige Anleitung eines Skills anhand seines exakten Namens.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("name", JSONObject().apply {
                                put("type", "string")
                                put("description", "Exakter Name des Skills")
                            })
                        })
                        put("required", JSONArray().put("name"))
                    })
                })
            })
        }
        memoryTools(memoryStore, forOpenAi = true).forEach { tools.put(it) }
        return tools
    }

    private fun requestOpenAi(
        context: Context,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<Message>,
        skills: List<Skill>,
        memoryStore: MemoryStore?,
        withTools: Boolean = true,
        turn: Turn = Turn(),
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        history.forEach { msg ->
            messages.put(JSONObject().apply {
                put("role", apiRole(msg))
                put("content", msg.text)
            })
        }
        val tools = if (withTools) {
            openAiTools(
                skills, memoryStore,
                location = ProviderSettings.isLocationEnabled(context),
                deviceActions = DeviceActions.available(context),
            )
        } else {
            JSONArray()
        }

        for (round in 0..MAX_TOOL_ROUNDS) {
            val body = JSONObject().apply {
                put("model", model)
                // Nicht "max_tokens": das ist bei OpenAI deprecated, und neuere
                // Modelle (o-Serie u. a.) lehnen es ab — das Modellfeld ist frei wählbar.
                put("max_completion_tokens", 2048)
                put("messages", messages)
                if (tools.length() > 0) put("tools", tools)
            }

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw ApiException(apiErrorMessage(responseText, response.code))
                }
                val json = JSONObject(responseText)
                json.optJSONObject("usage")?.let { usage ->
                    trackCost(
                        context, ProviderSettings.PROVIDER_OPENAI, model,
                        inputTokens = usage.optLong("prompt_tokens", 0L),
                        outputTokens = usage.optLong("completion_tokens", 0L),
                    )
                }

                val choice = json.getJSONArray("choices").getJSONObject(0)
                val message = choice.getJSONObject("message")
                val toolCalls = message.optJSONArray("tool_calls")

                if (toolCalls != null && toolCalls.length() > 0) {
                    messages.put(message)
                    for (i in 0 until toolCalls.length()) {
                        val call = toolCalls.getJSONObject(i)
                        val function = call.getJSONObject("function")
                        val toolName = function.optString("name")
                        val toolInput = runCatching { JSONObject(function.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                        val resultText = executeTool(context, toolName, toolInput, skills, memoryStore, turn)
                        messages.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", call.getString("id"))
                            put("content", resultText)
                        })
                    }
                    return@use
                }

                val text = message.optString("content").trim()
                if (text.isBlank()) throw ApiException("Leere Antwort vom Modell.")
                return text
            }
        }
        throw ApiException("Zu viele Tool-Aufrufe in Folge — abgebrochen.")
    }

    private fun unknownSkillMessage(name: String, skills: List<Skill>): String =
        "Kein Skill mit dem Namen \"$name\" gefunden. Verfügbar: ${skills.joinToString(", ") { it.name }}"

    private fun apiErrorMessage(responseText: String, code: Int): String {
        val detail = runCatching {
            JSONObject(responseText).getJSONObject("error").getString("message")
        }.getOrNull()
        return when {
            detail != null -> "API-Fehler ($code): $detail"
            code == 401 -> "API-Key ungültig (401)."
            code == 429 -> "Rate-Limit erreicht (429) — bitte kurz warten."
            else -> "API-Fehler ($code)."
        }
    }
}
