package com.example.optik.view

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.optik.R

class LUTAdapter(
    private val items: List<String>,
    private val onLutSelected: (String) -> Unit
) : RecyclerView.Adapter<LUTAdapter.LUTViewHolder>() {

    var selectedPosition = -1

    class LUTViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.text_lut_name)
        val container: View = view.findViewById(R.id.lut_container)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LUTViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lut, parent, false)
        return LUTViewHolder(view)
    }

    override fun onBindViewHolder(holder: LUTViewHolder, position: Int) {
        val name = items[position]
        holder.textView.text = name

        if (name == "Gốc") {
            holder.container.setBackgroundColor(android.graphics.Color.WHITE)
        } else {
            holder.container.setBackgroundColor(android.graphics.Color.parseColor("#A0A0A0"))
        }

        if (position == selectedPosition) {
            holder.textView.setTextColor(android.graphics.Color.parseColor("#FF9800")) // Orange text
        } else {
            holder.textView.setTextColor(android.graphics.Color.BLACK)
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
