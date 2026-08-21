package de.skilltoremember.app.api

import android.content.Context
import de.skilltoremember.app.data.BuiltInSkills
import de.skilltoremember.app.data.Chat
import de.skilltoremember.app.data.Message
import de.skilltoremember.app.data.Skill
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
    private const val MAX_TOOL_ROUNDS = 6

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
        val digest = memoryStore?.let { runCatching { MemoryEngine.boot(it).digest }.getOrNull() }
        val systemPrompt = buildSystemPrompt(skills, memoryStore != null, digest, concise)

        if (config.provider == ProviderSettings.PROVIDER_OPENAI) {
            requestOpenAi(context, config.apiKey, config.model, systemPrompt, chat.messages, skills, memoryStore)
        } else {
            requestAnthropic(context, config.apiKey, config.model, systemPrompt, chat.messages, skills, memoryStore)
        }
    }

    private fun memoryStoreIfActive(context: Context, skills: List<Skill>): MemoryStore? {
        val skillActive = skills.any { it.id == BuiltInSkills.HUMANOID_BEHAVIOR.id }
        if (!skillActive || !MemorySettings.isConfigured(context)) return null
        val store = MemorySettings.store(context)
        if (!store.exists()) return null
        return store
    }

    private val CONCISE_PROMPT = """
        Sprachdialog-Modus: Der Nutzer spricht mit dir und hört deine Antwort
        über eine Sprachausgabe. Fasse dich deshalb besonders kurz — zwei,
        drei gesprochene Sätze, keine Aufzählungen, kein Markdown, keine
        Codeblöcke. Nenne erst dann mehr Details, wenn der Nutzer nachfragt.
    """.trimIndent()

    private fun buildSystemPrompt(skills: List<Skill>, memoryActive: Boolean, digest: String?, concise: Boolean): String {
        val parts = mutableListOf(BASE_SYSTEM_PROMPT)
        if (concise) {
            parts.add(CONCISE_PROMPT)
        }
        if (memoryActive && digest != null) {
            parts.add(digest.trim())
        }
        if (skills.isNotEmpty()) {
            val list = skills.joinToString("\n") { "- ${it.name}: ${it.description}" }
            parts.add(
                """
                    Verfügbare Skills (per "$TOOL_LOAD_SKILL"-Tool mit dem exakten Namen ladbar):
                    $list

                    Ruf "$TOOL_LOAD_SKILL" auf, sobald ein Skill zur Anfrage passt, und befolge
                    danach dessen Anleitung. Ohne passenden Skill antworte normal.
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

    private fun executeTool(context: Context, name: String, input: JSONObject, skills: List<Skill>, memoryStore: MemoryStore?): String =
        when (name) {
            TOOL_LOAD_SKILL -> {
                val skillName = input.optString("name")
                val skill = findSkill(skills, skillName)
                skill?.body ?: unknownSkillMessage(skillName, skills)
            }
            TOOL_REMEMBER -> executeRemember(context, input, memoryStore)
            TOOL_RECALL -> executeRecall(input, memoryStore)
            TOOL_FORGET -> executeForget(context, input, memoryStore)
            else -> "Unbekanntes Tool \"$name\"."
        }

    private fun executeRemember(context: Context, input: JSONObject, memoryStore: MemoryStore?): String {
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
                val synced = trySyncAfterWrite(context, memoryStore)
                "Gespeichert (${r.outcome})." + if (synced) "" else " Nicht synchronisiert — bleibt vorerst nur auf diesem Gerät."
            },
            onFailure = { e -> "Fehler beim Speichern: ${e.message}" },
        )
    }

    private fun executeRecall(input: JSONObject, memoryStore: MemoryStore?): String {
        if (memoryStore == null) return "Kein Gedächtnis-Repo verbunden."
        val result = runCatching {
            MemoryEngine.recall(
                store = memoryStore,
                query = input.optString("query"),
                layer = input.optString("layer").ifBlank { null },
                topic = input.optString("topic").ifBlank { null },
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

    private fun executeForget(context: Context, input: JSONObject, memoryStore: MemoryStore?): String {
        if (memoryStore == null) return "Kein Gedächtnis-Repo verbunden."
        val id = input.optString("id").ifBlank { null }
        val topic = input.optString("topic").ifBlank { null }
        val result = runCatching { MemoryEngine.forget(memoryStore, listOfNotNull(id), topic) }
        return result.fold(
            onSuccess = { r ->
                if (r.forgottenIds.isEmpty()) return "Nichts gefunden."
                val synced = trySyncAfterWrite(context, memoryStore)
                "Vergessen: ${r.forgottenIds.joinToString(", ")}." +
                    if (synced) "" else " Nicht synchronisiert — bleibt vorerst nur auf diesem Gerät."
            },
            onFailure = { e -> "Fehler beim Vergessen: ${e.message}" },
        )
    }

    /** Pusht sofort nach einem Schreibzugriff ("on the fly", wie gefordert). Netzwerkfehler brechen die Antwort nicht ab. */
    private fun trySyncAfterWrite(context: Context, store: MemoryStore): Boolean = runCatching {
        val config = MemorySettings.config(context)
        GitHubMemorySync.pull(store, config)
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
            tool(TOOL_FORGET, "Archiviert einen falschen oder veralteten Eintrag.", forgetSchema),
        )
    }

    // ---- Anthropic ----

    private fun anthropicTools(skills: List<Skill>, memoryStore: MemoryStore?): JSONArray {
        val tools = JSONArray()
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
    ): String {
        val messages = JSONArray()
        history.forEach { msg ->
            messages.put(JSONObject().apply {
                put("role", apiRole(msg))
                put("content", msg.text)
            })
        }
        val tools = anthropicTools(skills, memoryStore)

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
                }

                val content = json.getJSONArray("content")

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
                        val resultText = executeTool(context, toolName, toolInput, skills, memoryStore)
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

                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.optString("type") == "text") {
                        return block.getString("text").trim()
                    }
                }
                throw ApiException("Leere Antwort vom Modell.")
            }
        }
        throw ApiException("Zu viele Tool-Aufrufe in Folge — abgebrochen.")
    }

    // ---- OpenAI ----

    private fun openAiTools(skills: List<Skill>, memoryStore: MemoryStore?): JSONArray {
        val tools = JSONArray()
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
        val tools = openAiTools(skills, memoryStore)

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
                        val resultText = executeTool(context, toolName, toolInput, skills, memoryStore)
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
