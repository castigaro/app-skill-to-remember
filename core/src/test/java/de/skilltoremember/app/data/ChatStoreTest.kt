package de.skilltoremember.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ChatStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        ChatStore.resetForTest()
        File(context.filesDir, "chats.json").delete()
    }

    @Test
    fun `Chats ueberleben einen Neustart`() {
        val chat = Chat(title = "Roundtrip", createdAt = 1L)
        chat.messages.add(Message(Message.ROLE_USER, "Hallo"))
        ChatStore.add(context, chat)

        ChatStore.resetForTest()

        val loaded = ChatStore.get(context, chat.id)!!
        assertEquals("Roundtrip", loaded.title)
        assertEquals("Hallo", loaded.messages.single().text)
    }

    @Test
    fun `getAll sortiert neueste zuerst`() {
        ChatStore.add(context, Chat(title = "alt", createdAt = 1L))
        ChatStore.add(context, Chat(title = "neu", createdAt = 2L))
        assertEquals(listOf("neu", "alt"), ChatStore.getAll(context).map { it.title })
    }

    @Test
    fun `korrupte Datei wird weggesichert statt spaeter ueberschrieben`() {
        val f = File(context.filesDir, "chats.json")
        f.writeText("{ kaputt")

        assertTrue(ChatStore.getAll(context).isEmpty())
        val backup = File(context.filesDir, "chats.json.corrupt")
        assertTrue(backup.exists())
        assertEquals("{ kaputt", backup.readText())

        // Ein anschließendes Speichern zerstört das Backup nicht.
        ChatStore.add(context, Chat(title = "neu", createdAt = 1L))
        assertTrue(backup.exists())
        assertEquals(1, ChatStore.getAll(context).size)
    }

    @Test
    fun `delete entfernt den Chat dauerhaft`() {
        val chat = Chat(title = "weg damit", createdAt = 1L)
        ChatStore.add(context, chat)
        ChatStore.delete(context, chat)

        ChatStore.resetForTest()
        assertNull(ChatStore.get(context, chat.id))
        assertTrue(ChatStore.getAll(context).isEmpty())
    }
}
