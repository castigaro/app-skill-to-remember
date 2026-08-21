package de.skilltoremember.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageTest {

    @Test
    fun `Message-Roundtrip erhaelt alle Felder`() {
        val message = Message(role = Message.ROLE_ASSISTANT, text = "Klar, gerne …", createdAt = 42L)
        val loaded = Message.fromJson(message.toJson())
        assertEquals(Message.ROLE_ASSISTANT, loaded.role)
        assertEquals("Klar, gerne …", loaded.text)
        assertEquals(42L, loaded.createdAt)
    }

    @Test
    fun `Chat-Roundtrip erhaelt Verlauf`() {
        val chat = Chat(title = "Frage zu Kotlin", createdAt = 7L)
        chat.messages.add(Message(Message.ROLE_USER, "Was ist ein data class?"))
        chat.messages.add(Message(Message.ROLE_ASSISTANT, "Eine Klasse, die …"))

        val loaded = Chat.fromJson(chat.toJson())
        assertEquals(chat.id, loaded.id)
        assertEquals("Frage zu Kotlin", loaded.title)
        assertEquals(2, loaded.messages.size)
        assertEquals(Message.ROLE_USER, loaded.messages[0].role)
        assertEquals("Eine Klasse, die …", loaded.messages[1].text)
    }

    @Test
    fun `spaerliche JSON-Daten bekommen Defaults`() {
        val loaded = Chat.fromJson(JSONObject("""{"title":"Nur Titel"}"""))
        assertEquals("Nur Titel", loaded.title)
        assertTrue(loaded.messages.isEmpty())
        assertTrue("Id muss generiert werden", loaded.id.isNotBlank())
    }
}
