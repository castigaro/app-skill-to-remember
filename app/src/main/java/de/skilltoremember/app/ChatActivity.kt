package de.skilltoremember.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
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

    private val speechInput = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) {
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> renderState(state) }
            }
        }
    }

    override fun onDestroy() {
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
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
