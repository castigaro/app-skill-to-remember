package de.skilltoremember.app.wear

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.WearableLinearLayoutManager
import de.skilltoremember.app.data.Chat
import de.skilltoremember.app.data.ChatStore
import de.skilltoremember.app.wear.databinding.ActivityChatsBinding
import java.text.DateFormat
import java.util.Date

/**
 * Die Gespräche der Uhr: antippen wechselt, langer Druck löscht.
 *
 * Rein lokal — der ChatStore wird nirgendwo synchronisiert (weder mit dem
 * Handy noch mit dem Gedächtnis-Repo). Ein gelöschtes Gespräch nimmt also
 * nichts aus dem Gedächtnis mit; dort stehen die gemerkten Fakten, nicht
 * der Gesprächsverlauf.
 */
class ChatsActivity : ComponentActivity() {

    private lateinit var binding: ActivityChatsBinding
    private val adapter = ChatAdapter(::oeffne, ::frageLoeschen)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.chatList.layoutManager = WearableLinearLayoutManager(this)
        binding.chatList.isEdgeItemsCenteringEnabled = true
        binding.chatList.adapter = adapter
        zeige()
    }

    override fun onResume() {
        super.onResume()
        zeige() // ein gelöschter oder neu bestückter Chat soll sofort stimmen
    }

    private fun zeige() {
        val chats = ChatStore.getAll(this)
        adapter.setze(chats, aktiverChat(this))
        binding.emptyText.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Wechselt den aktiven Chat und kehrt zum Sprech-Screen zurück. */
    private fun oeffne(chat: Chat) {
        setzeAktivenChat(this, chat.id)
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun frageLoeschen(chat: Chat) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.wear_chat_delete_title))
            .setMessage(getString(R.string.wear_chat_delete_message, chat.title))
            .setPositiveButton(android.R.string.ok) { _, _ -> loesche(chat) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loesche(chat: Chat) {
        ChatStore.delete(this, chat)
        // War es der aktive Chat, darf die Kennung nicht stehen bleiben —
        // sonst spräche der Sprech-Screen in ein gelöschtes Gespräch.
        if (aktiverChat(this) == chat.id) setzeAktivenChat(this, null)
        zeige()
    }

    companion object {
        private const val PREFS = "wear"
        private const val KEY_CHAT = "chatId"

        fun starten(context: Context): Intent = Intent(context, ChatsActivity::class.java)

        fun aktiverChat(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHAT, null)

        fun setzeAktivenChat(context: Context, id: String?) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (id == null) prefs.remove(KEY_CHAT) else prefs.putString(KEY_CHAT, id)
            prefs.apply()
        }
    }
}

/** Titel plus Datum und Anriss der letzten Nachricht — wie in der Handy-Liste. */
private class ChatAdapter(
    private val aufKlick: (Chat) -> Unit,
    private val aufLangdruck: (Chat) -> Unit,
) : RecyclerView.Adapter<ChatAdapter.Halter>() {

    private var chats: List<Chat> = emptyList()
    private var aktiv: String? = null

    fun setze(neue: List<Chat>, aktiverChat: String?) {
        chats = neue
        aktiv = aktiverChat
        notifyDataSetChanged()
    }

    class Halter(val wurzel: View) : RecyclerView.ViewHolder(wurzel) {
        val titel: TextView = wurzel.findViewById(R.id.chatTitle)
        val unter: TextView = wurzel.findViewById(R.id.chatSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Halter =
        Halter(LayoutInflater.from(parent.context).inflate(R.layout.row_wear_chat, parent, false))

    override fun getItemCount(): Int = chats.size

    override fun onBindViewHolder(holder: Halter, position: Int) {
        val chat = chats[position]
        val laufend = chat.id == aktiv
        holder.titel.text = if (laufend) "▸ ${chat.title}" else chat.title

        val datum = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(chat.createdAt))
        val letzte = chat.messages.lastOrNull()?.text?.replace("\n", " ")?.take(40).orEmpty()
        holder.unter.text = if (letzte.isBlank()) datum else "$datum · $letzte"

        holder.wurzel.setOnClickListener { aufKlick(chat) }
        holder.wurzel.setOnLongClickListener { aufLangdruck(chat); true }
    }
}
