package com.l7.dealscanner

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ListingsAdapter(
    private val onOpen: (String) -> Unit,
    private val onHide: (String) -> Unit
) : RecyclerView.Adapter<ListingsAdapter.VH>() {

    private var items: List<Listing> = emptyList()

    fun submit(newItems: List<Listing>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val badge: TextView = v.findViewById(R.id.badge)
        val price: TextView = v.findViewById(R.id.price)
        val title: TextView = v.findViewById(R.id.title)
        val location: TextView = v.findViewById(R.id.location)
        val drop: TextView = v.findViewById(R.id.drop)
        val openBtn: Button = v.findViewById(R.id.openBtn)
        val hideBtn: Button = v.findViewById(R.id.hideBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_listing, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.badge.text = DealScorer.badge(item.score)
        h.badge.setTextColor(
            when (item.score) {
                DealScorer.STEAL -> Color.parseColor("#1DB954")
                DealScorer.FAIR -> Color.parseColor("#4A9EFF")
                DealScorer.HIGH -> Color.parseColor("#FF5555")
                else -> Color.parseColor("#999999")
            }
        )
        h.price.text = "$${"%,d".format(item.price)}"
        h.title.text = item.title
        h.location.text = item.location.ifBlank { "—" }
        if (item.prevPrice != null && item.prevPrice > item.price) {
            h.drop.visibility = View.VISIBLE
            h.drop.text = "📉 was $${"%,d".format(item.prevPrice)}"
        } else {
            h.drop.visibility = View.GONE
        }
        h.openBtn.setOnClickListener { onOpen(item.url) }
        h.hideBtn.setOnClickListener { onHide(item.id) }
    }
}
