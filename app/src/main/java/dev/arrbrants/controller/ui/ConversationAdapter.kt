package dev.arrbrants.controller.ui

import dev.arrbrants.controller.R
import dev.arrbrants.controller.data.ConversationStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/** Conversation list in the drawer; highlights the open conversation. */
class ConversationAdapter(
    private val conversations: () -> List<ConversationStore.Conversation>,
    private val currentId: () -> Long,
    private val onOpen: (ConversationStore.Conversation) -> Unit,
    private val onDelete: (ConversationStore.Conversation) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder>() {

    override fun getItemCount(): Int = conversations().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ConversationViewHolder(v)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        val conv = conversations()[position]
        val selected = conv.id == currentId()
        holder.title.text = conv.title
        holder.count.text =
            holder.itemView.context.getString(R.string.msg_count, conv.messages.size)
        holder.card.setCardBackgroundColor(
            MaterialColors.getColor(
                holder.itemView,
                if (selected) com.google.android.material.R.attr.colorPrimaryContainer
                else com.google.android.material.R.attr.colorSurfaceVariant
            )
        )
        holder.title.setTextColor(
            MaterialColors.getColor(
                holder.itemView,
                if (selected) com.google.android.material.R.attr.colorOnPrimaryContainer
                else com.google.android.material.R.attr.colorOnSurface
            )
        )
        holder.itemView.setOnClickListener { onOpen(conv) }
        holder.delete.setOnClickListener { onDelete(conv) }
    }

    class ConversationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.convCard)
        val title: TextView = view.findViewById(R.id.convTitle)
        val count: TextView = view.findViewById(R.id.convCount)
        val delete: View = view.findViewById(R.id.convDelete)
    }
}
