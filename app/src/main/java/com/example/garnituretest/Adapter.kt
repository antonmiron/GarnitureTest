package com.example.garnituretest

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.garnituretest.databinding.ItemRvLogBinding
import kotlinx.android.synthetic.main.item_rv_log.view.*

class Adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val items = mutableListOf<String>()

    fun addLog(log: String){
        items.add("${items.size}) $log")
        notifyItemInserted(items.size)
    }

    fun getLogs() = items.toList()

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val binding = ItemRvLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as LogViewHolder).bind(items[position])
    }

    inner class LogViewHolder(viewBinding: ItemRvLogBinding): RecyclerView.ViewHolder(viewBinding.root) {
        fun bind(text: String){
            itemView.tvLog.text = text
        }
    }
}