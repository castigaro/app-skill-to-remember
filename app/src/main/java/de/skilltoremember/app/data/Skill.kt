package de.skilltoremember.app.data

import org.json.JSONObject
import java.util.UUID

/**
 * Ein Skill: kurzer Name + Beschreibung (das Modell sieht diese beiden immer,
 * um zu entscheiden, ob der Skill relevant ist) und der eigentliche
 * Anleitungstext (body), der erst bei Bedarf per Tool-Aufruf nachgeladen
 * wird — siehe [de.skilltoremember.app.api.ChatApi]. Entspricht in Aufbau dem
 * SKILL.md-Format des Agent-Skills-Standards (agentskills.io).
 */
class Skill(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var description: String,
    var body: String,
    var enabled: Boolean = true,
    val builtIn: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("body", body)
        put("enabled", enabled)
        put("builtIn", builtIn)
    }

    companion object {
        fun fromJson(json: JSONObject): Skill = Skill(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name"),
            description = json.optString("description"),
            body = json.optString("body"),
            enabled = json.optBoolean("enabled", true),
            builtIn = json.optBoolean("builtIn", false),
        )
    }
}
