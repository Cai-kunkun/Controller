package dev.arrbrants.controller.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import dev.arrbrants.controller.R
import dev.arrbrants.controller.data.Plugin

class PluginAdapter(
    private val plugins: () -> List<Plugin>,
    private val onToggle: (Plugin, Boolean) -> Unit,
    private val onDelete: (Plugin) -> Unit
) : RecyclerView.Adapter<PluginAdapter.Holder>() {

    override fun getItemCount(): Int = plugins().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_plugin, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val plugin = plugins()[position]
        val context = holder.itemView.context
        holder.name.text = plugin.name
        holder.description.text = plugin.description.ifBlank {
            context.getString(R.string.plugins_no_description)
        }
        holder.meta.text = context.getString(
            R.string.plugins_meta, plugin.id, plugin.steps.size
        )
        holder.switch.setOnCheckedChangeListener(null)
        holder.switch.isChecked = plugin.enabled
        holder.switch.setOnCheckedChangeListener { _, checked -> onToggle(plugin, checked) }
        holder.delete.setOnClickListener { onDelete(plugin) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.pluginName)
        val description: TextView = view.findViewById(R.id.pluginDescription)
        val meta: TextView = view.findViewById(R.id.pluginMeta)
        val switch: MaterialSwitch = view.findViewById(R.id.pluginSwitch)
        val delete: ImageView = view.findViewById(R.id.pluginDelete)
    }
}
