package com.example.optik.view

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.optik.R
import com.example.optik.camera.LutHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LUTAdapter(
    private val items: List<String>,
    private val lutFiles: List<String?>,
    private val onLutSelected: (String) -> Unit
) : RecyclerView.Adapter<LUTAdapter.LUTViewHolder>() {

    var selectedPosition = -1

    class LUTViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.text_lut_name)
        val container: View = view.findViewById(R.id.lut_container)
        val imgThumb: ImageView = view.findViewById(R.id.img_lut_thumb)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LUTViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lut, parent, false)
        return LUTViewHolder(view)
    }

    override fun onBindViewHolder(holder: LUTViewHolder, position: Int) {
        val name = items[position]
        val lutFile = lutFiles[position]
        holder.textView.text = name

        // Load thumbnail with LUT
        holder.imgThumb.setImageDrawable(null) // Clear previous
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val bitmap = LutHelper.getThumbnail(holder.itemView.context, lutFile)
                holder.imgThumb.setImageBitmap(bitmap)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (position == selectedPosition) {
            holder.textView.setTextColor(android.graphics.Color.parseColor("#FF9800")) // Orange text
            holder.container.setBackgroundResource(R.drawable.circle_white) // Add border
        } else {
            holder.textView.setTextColor(android.graphics.Color.WHITE)
            holder.container.setBackgroundResource(R.drawable.circle_transparent)
        }

        holder.itemView.setOnClickListener {
            val oldPos = selectedPosition
            selectedPosition = position
            notifyItemChanged(oldPos)
            notifyItemChanged(selectedPosition)
            onLutSelected(name)
        }
    }

    override fun getItemCount() = items.size
}
