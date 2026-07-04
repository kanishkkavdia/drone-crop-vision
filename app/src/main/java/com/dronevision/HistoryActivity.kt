package com.dronevision

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dronevision.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        load()
    }

    private fun load() {
        val records = DetectionStore.loadAll(this)
        binding.emptyText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.recycler.adapter = HistoryAdapter(records) { record ->
            AlertDialog.Builder(this)
                .setTitle("Delete this capture?")
                .setPositiveButton("Delete") { _, _ ->
                    DetectionStore.delete(record)
                    load()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private class HistoryAdapter(
        private val items: List<SavedRecord>,
        private val onLongPress: (SavedRecord) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.itemImage)
            val title: TextView = v.findViewById(R.id.itemTitle)
            val subtitle: TextView = v.findViewById(R.id.itemSubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_capture, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.summary
            holder.subtitle.text = DetectionStore.formatTime(item.timestampMillis)
            try {
                holder.image.setImageBitmap(BitmapFactory.decodeFile(item.imagePath))
            } catch (_: Exception) { }
            holder.itemView.setOnLongClickListener { onLongPress(item); true }
        }

        override fun getItemCount() = items.size
    }
}
