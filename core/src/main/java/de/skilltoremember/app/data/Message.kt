package de.skilltoremember.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Ein einzelner Beitrag im Chat-Verlauf. */
class Message(
    val role: String, // ROLE_USER oder ROLE_ASSISTANT
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("text", text)
        put("createdAt", createdAt)
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        fun fromJson(json: JSONObject) = Message(
            role = json.optString("role", ROLE_USER),
            text = json.optString("text"),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}

/** Ein Chat: Titel (aus der ersten Nachricht abgeleitet) und Verlauf. */
class Chat(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val messages: MutableList<Message> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("messages", JSONArray().also { arr -> messages.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): Chat {
            val chat = Chat(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            )
            val arr = json.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until arr.length()) {
                chat.messages.add(Message.fromJson(arr.getJSONObject(i)))
            }
            return chat
        }
    }
}
