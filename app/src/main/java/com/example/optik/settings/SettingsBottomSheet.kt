package com.example.optik.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.optik.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsBottomSheet : BottomSheetDialogFragment() {

    var title: String = ""
    var description: String = ""
    var options: List<String> = emptyList()
    var selectedOption: String = ""
    var onOptionSelected: ((String) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_settings_options, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        view.findViewById<ImageView>(R.id.btn_close).setOnClickListener { dismiss() }
        view.findViewById<TextView>(R.id.tv_title).text = title
        
        val tvDesc = view.findViewById<TextView>(R.id.tv_description)
        if (description.isNotEmpty()) {
            tvDesc.text = description
            tvDesc.visibility = View.VISIBLE
        } else {
            tvDesc.visibility = View.GONE
        }

        val rv = view.findViewById<RecyclerView>(R.id.rv_options)
        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = OptionsAdapter()
    }

    inner class OptionsAdapter : RecyclerView.Adapter<OptionsAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_option_name)
            val ivRadio: ImageView = view.findViewById(R.id.iv_radio)
            
            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onOptionSelected?.invoke(options[pos])
                        dismiss()
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_setting_option, parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val option = options[position]
            holder.tvName.text = option
            
            if (option == selectedOption) {
                holder.tvName.setTextColor(Color.parseColor("#FF8C00")) // Orange
                holder.ivRadio.imageTintList = ColorStateList.valueOf(Color.parseColor("#FF8C00"))
                holder.ivRadio.alpha = 1f
            } else {
                holder.tvName.setTextColor(Color.WHITE)
                holder.ivRadio.imageTintList = ColorStateList.valueOf(Color.parseColor("#88FFFFFF"))
                holder.ivRadio.alpha = 0.5f
            }
        }

        override fun getItemCount() = options.size
    }
}
