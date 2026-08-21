package de.skilltoremember.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.skilltoremember.app.data.Message
import de.skilltoremember.app.databinding.ItemMsgAssistantBinding
import de.skilltoremember.app.databinding.ItemMsgUserBinding

/** Chat-Verlauf: Assistent links, Nutzer rechts. */
class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<Message>()

    fun submit(newMessages: List<Message>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].role == Message.ROLE_ASSISTANT) TYPE_ASSISTANT else TYPE_USER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ASSISTANT) {
            AssistantHolder(ItemMsgAssistantBinding.inflate(inflater, parent, false))
        } else {
            UserHolder(ItemMsgUserBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = messages.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is AssistantHolder -> holder.binding.messageText.text = message.text
            is UserHolder -> holder.binding.messageText.text = message.text
        }
    }

    class AssistantHolder(val binding: ItemMsgAssistantBinding) : RecyclerView.ViewHolder(binding.root)
    class UserHolder(val binding: ItemMsgUserBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val TYPE_ASSISTANT = 0
        private const val TYPE_USER = 1
    }
}
