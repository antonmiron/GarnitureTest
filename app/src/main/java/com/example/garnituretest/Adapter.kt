package com.example.garnituretest

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.viewbinding.ViewBindings
import com.example.garnituretest.databinding.ItemRvBinding
import kotlinx.android.synthetic.main.item_rv.view.*

class Adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val list = mutableListOf<String>()

    fun addItem(text: String){
        list.add(text)
        notifyItemInserted(list.size-1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val binding = ItemRvBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as MyViewHolder).bind(list[position])
    }

    override fun getItemCount() = list.size

    class MyViewHolder(val binding: ViewBinding): RecyclerView.ViewHolder(binding.root){
        fun bind(text: String){
            with(binding){
                itemView.tvItemStatus.text = text
            }
        }
    }
}