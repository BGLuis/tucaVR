package com.vrplayer

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import java.io.File

class VRPresentation(outerContext: Context, display: Display, private val onVideoSelected: (String) -> Unit) : Presentation(outerContext, display) {

    private var currentDir = android.os.Environment.getExternalStorageDirectory()
    private lateinit var adapter: FileAdapter

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
        
        val pathText = TextView(context).apply {
            text = currentDir.absolutePath
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
        
        adapter = FileAdapter { file ->
            if (file.name == "..") {
                currentDir.parentFile?.let {
                    currentDir = it
                    pathText.text = currentDir.absolutePath
                    loadFiles()
                }
            } else if (file.isDirectory) {
                currentDir = file
                pathText.text = currentDir.absolutePath
                loadFiles()
            } else {
                val name = file.name.lowercase()
                if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".m4p") || name.endsWith(".m4v") || name.endsWith(".webm") || name.endsWith(".avi") || name.endsWith(".mov")) {
                    onVideoSelected(file.absolutePath)
                }
            }
        }
        recycler.adapter = adapter
        layout.addView(recycler)
        
        setContentView(layout)
        loadFiles()
    }

    fun loadFiles() {
        val files = currentDir.listFiles()?.toList() ?: emptyList()
        val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val list = mutableListOf<File>()
        
        // Always add a dummy ".." file if we have a parent
        if (currentDir.parentFile != null) {
            list.add(File("..")) // Special marker
        }
        
        list.addAll(sorted)
        adapter.files = list
        adapter.notifyDataSetChanged()
    }

    inner class FileAdapter(private val onClick: (File) -> Unit) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {
        var files: List<File> = emptyList()
        
        inner class ViewHolder(val view: TextView) : RecyclerView.ViewHolder(view)
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = TextView(parent.context).apply {
                textSize = 28f
                setTextColor(Color.WHITE)
                setPadding(32, 24, 32, 24)
            }
            return ViewHolder(tv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            holder.view.text = if (file.name == "..") "📁 Go Up" else (if (file.isDirectory) "📁 " else "🎬 ") + file.name
            holder.view.setOnClickListener { onClick(file) }
        }

        override fun getItemCount() = files.size
    }
}
