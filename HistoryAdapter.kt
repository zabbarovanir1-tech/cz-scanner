package ru.czcheck.scanner

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LevelColors {
    val OK = Color.parseColor("#2E7D32")
    val BAD = Color.parseColor("#C62828")
    val WARN = Color.parseColor("#F9A825")
    val ERROR = Color.parseColor("#546E7A")
    val PENDING = Color.parseColor("#1565C0")
    val IDLE = Color.parseColor("#455A64")

    fun of(level: Level): Int = when (level) {
        Level.OK -> OK
        Level.BAD -> BAD
        Level.WARN -> WARN
        Level.ERROR -> ERROR
        Level.PENDING -> PENDING
    }
}

class HistoryAdapter(context: Context, private val items: List<ScanItem>) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)
    private val timeFmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    private class Holder(v: View) {
        val stripe: View = v.findViewById(R.id.stripe)
        val title: TextView = v.findViewById(R.id.itemTitle)
        val time: TextView = v.findViewById(R.id.itemTime)
        val product: TextView = v.findViewById(R.id.itemProduct)
        val code: TextView = v.findViewById(R.id.itemCode)
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = items[position].id

    override fun hasStableIds(): Boolean = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_scan, parent, false)
        val h = (view.tag as? Holder) ?: Holder(view).also { view.tag = it }
        val item = items[position]

        h.stripe.setBackgroundColor(LevelColors.of(item.level))
        h.title.text = if (item.repeat) item.title + "  · повтор" else item.title
        h.time.text = timeFmt.format(Date(item.time))
        val product = item.product
        if (product.isNullOrBlank()) {
            h.product.visibility = View.GONE
        } else {
            h.product.visibility = View.VISIBLE
            h.product.text = product
        }
        h.code.text = item.cis ?: CodeNormalizer.display(item.code)
        return view
    }
}
