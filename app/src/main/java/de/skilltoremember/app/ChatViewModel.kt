package de.skilltoremember.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.skilltoremember.app.api.ChatApi
import de.skilltoremember.app.data.Chat
import de.skilltoremember.app.data.ChatStore
import de.skilltoremember.app.data.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hält die laufende Antwort-Generierung außerhalb des Activity-Lebenszyklus.
 * Eine Bildschirmrotation zerstört die Activity, aber nicht dieses ViewModel —
 * die (bezahlte) API-Antwort geht dadurch nicht mehr verloren, sondern landet
 * nach dem Eintreffen im [ChatStore], egal welche Activity gerade dranhängt.
 */
class ChatViewModel(private val app: Application) : AndroidViewModel(app) {

    /**
     * [revision] zählt Änderungen am Chat-Verlauf hoch, damit die Activity nach
     * Rotation oder eingetroffener Antwort neu rendert. [error] bleibt gesetzt,
     * bis erneut gesendet oder der Retry ausgelöst wird.
     */
    data class UiState(
        val busy: Boolean = false,
        val error: String? = null,
        val revision: Long = 0L,
        val dialogMode: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Hängt die Nutzernachricht an den Chat und stößt die Antwort an. */
    fun send(chat: Chat, text: String) {
        if (_state.value.busy) return
        chat.messages.add(Message(Message.ROLE_USER, text))
        if (chat.title.isBlank()) chat.title = text.take(40)
        ChatStore.save(app)
        _state.value = _state.value.copy(revision = _state.value.revision + 1)
        generate(chat)
    }

    /** Fordert die Antwort erneut an (Retry nach Fehler). */
    fun retry(chat: Chat) = generate(chat)

    /** Sprachdialog an/aus — lebt hier, damit der Zustand Rotationen überlebt. */
    fun setDialogMode(enabled: Boolean) {
        _state.value = _state.value.copy(dialogMode = enabled)
    }

    private fun generate(chat: Chat) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val reply = ChatApi.reply(app, chat, concise = _state.value.dialogMode)
                chat.messages.add(Message(Message.ROLE_ASSISTANT, reply))
                withContext(Dispatchers.IO) { ChatStore.save(app) }
                _state.value = _state.value.copy(busy = false, revision = _state.value.revision + 1)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = e.message ?: "?")
            }
        }
    }
}
