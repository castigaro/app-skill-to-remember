package de.skilltoremember.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import de.skilltoremember.app.data.Chat
import de.skilltoremember.app.data.ChatStore
import de.skilltoremember.app.data.Message
import de.skilltoremember.app.databinding.ActivityChatBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter
    private lateinit var chat: Chat

    /** Trägt die laufende Generierung über Rotationen hinweg. */
    private val viewModel: ChatViewModel by viewModels()

    /** Zuletzt als Snackbar gezeigter Fehler, damit derselbe nicht mehrfach aufpoppt. */
    private var shownError: String? = null

    /** Sprachausgabe für den Dialogmodus — erst beim Einschalten initialisiert. */
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Bis zu welcher Revision schon vorgelesen wurde — verhindert Doppel-Vorlesen nach Rotation. */
    private var lastSpokenRevision = 0L

    private val speechInput = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
        } else {
            ""
        }
        when {
            // Dialogmodus: Gesprochenes geht direkt raus, Abbrechen beendet den Modus.
            viewModel.state.value.dialogMode -> {
                if (spoken.isNotBlank()) {
                    viewModel.send(chat, spoken)
                    updateTitle()
                } else {
                    endDialogMode()
                }
            }
            spoken.isNotBlank() -> {
                val existing = binding.inputMessage.text?.toString().orEmpty()
                val combined = if (existing.isBlank()) spoken else "$existing $spoken"
                binding.inputMessage.setText(combined)
                binding.inputMessage.setSelection(combined.length)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val loaded = ChatStore.get(this, intent.getStringExtra(EXTRA_CHAT_ID).orEmpty())
        if (loaded == null) {
            finish()
            return
        }
        chat = loaded
        updateTitle()

        adapter = MessageAdapter()
        binding.messageList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messageList.adapter = adapter
        renderMessages()

        binding.buttonSend.setOnClickListener { sendMessage() }
        binding.buttonMic.setOnClickListener { startSpeechInput() }

        lastSpokenRevision = viewModel.state.value.revision
        if (viewModel.state.value.dialogMode) ensureTts { }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> renderState(state) }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        // Ein nie benutzter Chat (angelegt, aber ohne Nachricht wieder verlassen)
        // soll die Chat-Liste nicht zumüllen.
        if (isFinishing && ::chat.isInitialized && chat.messages.isEmpty()) {
            ChatStore.delete(this, chat)
        }
        super.onDestroy()
    }

    private fun sendMessage() {
        if (viewModel.state.value.busy) return
        val text = binding.inputMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.inputMessage.setText("")
        viewModel.send(chat, text)
        updateTitle()
    }

    private fun renderState(state: ChatViewModel.UiState) {
        setBusy(state.busy)
        renderMessages()
        if (state.revision != lastSpokenRevision) {
            lastSpokenRevision = state.revision
            val last = chat.messages.lastOrNull()
            if (state.dialogMode && last != null && last.role == Message.ROLE_ASSISTANT) {
                speak(last.text)
            }
        }
        if (state.error != null && state.error != shownError) {
            shownError = state.error
            Snackbar.make(
                binding.root,
                getString(R.string.chat_error, state.error),
                Snackbar.LENGTH_INDEFINITE,
            ).setAction(R.string.retry) { viewModel.retry(chat) }.show()
        }
        if (state.error == null) shownError = null
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.statusText.visibility = binding.progress.visibility
        binding.inputMessage.isEnabled = !busy
        binding.buttonSend.isEnabled = !busy
        binding.buttonMic.isEnabled = !busy
    }

    private fun renderMessages() {
        adapter.submit(chat.messages.toList())
        binding.messageList.scrollToPosition(adapter.itemCount - 1)
    }

    private fun updateTitle() {
        supportActionBar?.title = chat.title.ifBlank { getString(R.string.untitled_chat) }
    }

    // ---- Dialogmodus: sprechen -> senden -> Antwort vorlesen -> wieder sprechen ----

    private fun toggleDialogMode() {
        if (viewModel.state.value.dialogMode) {
            endDialogMode()
            return
        }
        viewModel.setDialogMode(true)
        invalidateOptionsMenu()
        ensureTts {
            Snackbar.make(binding.root, R.string.dialog_mode_on, Snackbar.LENGTH_SHORT).show()
            startSpeechInput()
        }
    }

    private fun endDialogMode() {
        viewModel.setDialogMode(false)
        invalidateOptionsMenu()
        tts?.stop()
        Snackbar.make(binding.root, R.string.dialog_mode_off, Snackbar.LENGTH_SHORT).show()
    }

    private fun ensureTts(onReady: () -> Unit) {
        if (ttsReady) {
            onReady()
            return
        }
        if (tts != null) return // Initialisierung läuft bereits
        tts = TextToSpeech(this) { status ->
            runOnUiThread {
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    ttsReady = true
                    onReady()
                } else {
                    tts = null
                    Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_LONG).show()
                    viewModel.setDialogMode(false)
                    invalidateOptionsMenu()
                }
            }
        }
    }

    private fun speak(text: String) {
        val engine = tts ?: return
        VoiceSettings.apply(this, engine)
        // Fürs Vorlesen reicht purer Text — Markdown-Reste stören nur.
        val cleaned = text.replace(Regex("[*_#`>|]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (cleaned.isBlank()) return
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread { continueDialog() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { continueDialog() }
            }
        })
        engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, "reply-${chat.messages.size}")
    }

    /** Nach dem Vorlesen wieder zuhören — solange der Dialogmodus an ist. */
    private fun continueDialog() {
        val state = viewModel.state.value
        if (state.dialogMode && !state.busy && !isFinishing) startSpeechInput()
    }

    private fun startSpeechInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt))
        }
        runCatching { speechInput.launch(intent) }
            .onFailure { Toast.makeText(this, R.string.speech_unavailable, Toast.LENGTH_LONG).show() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chat, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_dialog_mode)?.isChecked = viewModel.state.value.dialogMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_dialog_mode -> {
            toggleDialogMode()
            true
        }
        R.id.action_delete_chat -> {
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_chat)
                .setMessage(getString(R.string.delete_chat_message, chat.title.ifBlank { getString(R.string.untitled_chat) }))
                .setPositiveButton(R.string.delete) { _, _ ->
                    ChatStore.delete(this, chat)
                    finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_CHAT_ID = "chatId"
    }
}
