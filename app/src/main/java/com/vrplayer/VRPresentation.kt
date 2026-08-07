package com.vrplayer

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vrplayer.filebrowser.DirectoryLister
import com.vrplayer.filebrowser.DirectoryNavigator
import com.vrplayer.filebrowser.MediaEntry
import com.vrplayer.filebrowser.MediaType
import com.vrplayer.filebrowser.SortBy
import com.vrplayer.filebrowser.ThumbnailGenerator
import com.vrplayer.filebrowser.sortMediaEntries
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VRPresentation(outerContext: Context, display: Display, private val onVideoSelected: (String) -> Unit) : Presentation(outerContext, display) {

    private val navigator = DirectoryNavigator(android.os.Environment.getExternalStorageDirectory())
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var adapter: FileAdapter
    private lateinit var pathText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(context).apply {
            text = "VR File Browser"
            textSize = 36f
            setTextColor(Color.WHITE)
            setPadding(16, 16, 16, 32)
        }
        layout.addView(title)

        pathText = TextView(context).apply {
            text = navigator.currentPath.absolutePath
            textSize = 24f
            setTextColor(Color.LTGRAY)
            setPadding(16, 0, 16, 16)
        }
        layout.addView(pathText)

        val recycler = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(context)
        }

        adapter = FileAdapter(
            context = context,
            scope = scope,
            onUpClick = {
                if (navigator.goBack()) {
                    pathText.text = navigator.currentPath.absolutePath
                    loadFiles()
                }
            },
            onDirectoryClick = { dir ->
                navigator.enter(dir)
                pathText.text = navigator.currentPath.absolutePath
                loadFiles()
            },
            onVideoClick = { entry -> onVideoSelected(entry.path) }
        )
        recycler.adapter = adapter
        layout.addView(recycler)

        setContentView(layout)
        loadFiles()
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    fun loadFiles() {
        val dir = navigator.currentPath
        val showUp = navigator.canGoBack()
        scope.launch {
            val entries = DirectoryLister.listMedia(dir)
                .filter { it.type == MediaType.DIRECTORY || it.type == MediaType.VIDEO }
            val sorted = sortMediaEntries(entries, SortBy.NAME)
            adapter.submit(sorted, showUp)
        }
    }

    // Adapter roda em cima de MediaEntry (T5.3) em vez de java.io.File cru, e usa o
    // ThumbnailGenerator (T5.4) de forma preguicosa/assincrona por item visivel — nunca
    // gera thumbnails para a pasta inteira de uma vez (travaria a UI, ver cuidados do T5).
    private class FileAdapter(
        private val context: Context,
        private val scope: CoroutineScope,
        private val onUpClick: () -> Unit,
        private val onDirectoryClick: (File) -> Unit,
        private val onVideoClick: (MediaEntry) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

        private sealed class Row {
            object Up : Row()
            data class Item(val entry: MediaEntry) : Row()
        }

        private var rows: List<Row> = emptyList()

        fun submit(entries: List<MediaEntry>, showUp: Boolean) {
            rows = buildList {
                if (showUp) add(Row.Up)
                entries.forEach { add(Row.Item(it)) }
            }
            notifyDataSetChanged()
        }

        inner class ViewHolder(root: LinearLayout, val thumbnail: ImageView, val label: TextView) :
            RecyclerView.ViewHolder(root) {
            var thumbnailJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val thumbHeightPx = (parent.resources.displayMetrics.density * 64).toInt()
            val thumbnail = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(thumbHeightPx * 16 / 9, thumbHeightPx).also {
                    it.marginEnd = 24
                }
                visibility = View.GONE
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val label = TextView(parent.context).apply {
                textSize = 28f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
            }
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(32, 24, 32, 24)
                addView(thumbnail)
                addView(label)
            }
            return ViewHolder(root, thumbnail, label)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.thumbnailJob?.cancel()
            holder.thumbnail.visibility = View.GONE
            holder.thumbnail.setImageBitmap(null)

            when (val row = rows[position]) {
                is Row.Up -> {
                    holder.label.text = "📁 Go Up"
                    holder.itemView.setOnClickListener { onUpClick() }
                }
                is Row.Item -> {
                    val entry = row.entry
                    holder.label.text = if (entry.type == MediaType.DIRECTORY) {
                        "📁 ${entry.name}"
                    } else {
                        "🎬 ${entry.name}"
                    }
                    holder.itemView.setOnClickListener {
                        if (entry.type == MediaType.DIRECTORY) {
                            onDirectoryClick(File(entry.path))
                        } else {
                            onVideoClick(entry)
                        }
                    }
                    if (entry.type == MediaType.VIDEO) {
                        holder.thumbnailJob = scope.launch {
                            val bitmap = ThumbnailGenerator.getThumbnail(context, entry)
                            if (bitmap != null && holder.adapterPosition == position) {
                                holder.thumbnail.setImageBitmap(bitmap)
                                holder.thumbnail.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }

        override fun getItemCount() = rows.size
    }
}
