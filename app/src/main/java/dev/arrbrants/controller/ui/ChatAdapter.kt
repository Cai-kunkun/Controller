package dev.arrbrants.controller.ui

import dev.arrbrants.controller.R
import dev.arrbrants.controller.agent.ShotThumbs
import dev.arrbrants.controller.data.ChatMessage
import android.graphics.Bitmap
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon

/** Renders the conversation: user/assistant bubbles, screenshots, attachments. */
class ChatAdapter(
    private val messages: () -> List<ChatMessage>
) : RecyclerView.Adapter<ChatAdapter.BubbleViewHolder>() {

    private var markwon: Markwon? = null

    override fun getItemCount(): Int = messages().size

    override fun getItemViewType(position: Int): Int =
        if (messages()[position].role == "user") 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BubbleViewHolder {
        val layout =
            if (viewType == 0) R.layout.item_message_user else R.layout.item_message_assistant
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return BubbleViewHolder(v)
    }

    override fun onBindViewHolder(holder: BubbleViewHolder, position: Int) {
        val msg = messages()[position]
        val renderer = markwon
            ?: Markwon.create(holder.itemView.context).also { markwon = it }
        holder.text.movementMethod = LinkMovementMethod.getInstance()
        renderer.setMarkdown(holder.text, msg.content)
        val bitmap: Bitmap? = msg.imagePath?.let { ShotThumbs.get(holder.itemView.context, it) }
        if (bitmap != null) {
            holder.thumb.visibility = View.VISIBLE
            holder.thumb.setImageBitmap(bitmap)
        } else {
            holder.thumb.visibility = View.GONE
            holder.thumb.setImageDrawable(null)
        }
        holder.attachments?.let { bindAttachments(it, msg) }
    }

    private fun bindAttachments(container: LinearLayout, msg: ChatMessage) {
        container.removeAllViews()
        if (msg.attachments.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val context = container.context
        var imagesShown = 0
        for (a in msg.attachments) {
            if (a.isImage && imagesShown < MAX_IMAGES_SHOWN) {
                val image = ShotThumbs.get(context, a.path)
                if (image != null) {
                    val view = ImageView(context)
                    view.setImageBitmap(image)
                    view.scaleType = ImageView.ScaleType.CENTER_CROP
                    view.layoutParams = LinearLayout.LayoutParams(dp(context, 160), dp(context, 120))
                        .apply { topMargin = dp(context, 4) }
                    container.addView(view)
                    imagesShown++
                    continue
                }
            }
            val chip = TextView(context)
            chip.text = (if (a.isImage) "🖼 " else "📎 ") + a.name
            chip.textSize = 12f
            chip.maxLines = 1
            chip.ellipsize = TextUtils.TruncateAt.END
            chip.setBackgroundResource(R.drawable.bg_chip)
            chip.setPadding(
                dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6)
            )
            chip.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 4) }
            container.addView(chip)
        }
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    class BubbleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.content)
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val attachments: LinearLayout? = view.findViewById(R.id.attachments)
    }

    private companion object {
        const val MAX_IMAGES_SHOWN = 3
    }
}
