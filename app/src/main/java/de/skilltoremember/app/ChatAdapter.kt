package de.skilltoremember.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.skilltoremember.app.data.Chat
import de.skilltoremember.app.databinding.RowChatBinding
import java.text.DateFormat
import java.util.Date

class ChatAdapter(
    private val onClick: (Chat) -> Unit,
    private val onLongClick: (Chat) -> Unit,
) : RecyclerView.Adapter<ChatAdapter.Holder>() {

    private val chats = mutableListOf<Chat>()

    fun submit(newChats: List<Chat>) {
        chats.clear()
        chats.addAll(newChats)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = RowChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = chats.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(chats[position])
    }

    inner class Holder(private val binding: RowChatBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chat: Chat) {
            val context = binding.root.context
            binding.chatTitle.text = chat.title.ifBlank { context.getString(R.string.untitled_chat) }

            val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(chat.createdAt))
            val preview = chat.messages.lastOrNull()?.text?.take(60).orEmpty()
            binding.chatSubtitle.text = if (preview.isBlank()) date else "$date · $preview"

            binding.root.setOnClickListener { onClick(chat) }
            binding.root.setOnLongClickListener { onLongClick(chat); true }
        }
    }
}
