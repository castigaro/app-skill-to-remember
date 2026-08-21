package de.skilltoremember.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import de.skilltoremember.app.api.ProviderSettings
import de.skilltoremember.app.data.Chat
import de.skilltoremember.app.data.ChatStore
import de.skilltoremember.app.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = ChatAdapter(
            onClick = { chat -> openChat(chat) },
            onLongClick = { chat -> confirmDelete(chat) },
        )
        binding.chatList.layoutManager = LinearLayoutManager(this)
        binding.chatList.adapter = adapter

        binding.fabNewChat.setOnClickListener { startNewChat() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val chats = ChatStore.getAll(this)
        adapter.submit(chats)
        binding.emptyState.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun startNewChat() {
        if (!ProviderSettings.isConfigured(this)) {
            Snackbar.make(binding.root, R.string.needs_key, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_settings) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .show()
            return
        }
        val chat = Chat()
        ChatStore.add(this, chat)
        openChat(chat)
    }

    private fun openChat(chat: Chat) {
        startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_CHAT_ID, chat.id))
    }

    private fun confirmDelete(chat: Chat) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_chat)
            .setMessage(getString(R.string.delete_chat_message, chat.title.ifBlank { getString(R.string.untitled_chat) }))
            .setPositiveButton(R.string.delete) { _, _ ->
                ChatStore.delete(this, chat)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_skills -> {
            startActivity(Intent(this, SkillsActivity::class.java))
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
