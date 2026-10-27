package com.appporter

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.format.Formatter
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows the list of apps with their icons. Icons load in the background. */
class AppAdapter(
    private val scope: CoroutineScope,
    private val pm: PackageManager,
    private val onClick: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()
    private val iconCache = LruCache<String, Drawable>(200)

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val details: TextView = view.findViewById(R.id.details)
        val badge: TextView = view.findViewById(R.id.badge)
        var iconJob: Job? = null
    }

    fun submit(list: List<AppEntry>) {
        items = list
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun cachedIcon(packageName: String): Drawable? = iconCache.get(packageName)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = items[position]
        val ctx = holder.itemView.context
        holder.name.text = app.label
        val size = Formatter.formatShortFileSize(ctx, app.totalBytes)
        holder.details.text = "${app.packageName}\nv${app.versionName}  •  $size"
        holder.badge.visibility = if (app.isSplit) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(app) }

        holder.iconJob?.cancel()
        val cached = iconCache.get(app.packageName)
        if (cached != null) {
            holder.icon.setImageDrawable(cached)
        } else {
            holder.icon.setImageDrawable(null)
            holder.iconJob = scope.launch {
                val drawable = withContext(Dispatchers.IO) {
                    try {
                        pm.getApplicationIcon(app.packageName)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (drawable != null) {
                    iconCache.put(app.packageName, drawable)
                    holder.icon.setImageDrawable(drawable)
                }
            }
        }
    }
}
