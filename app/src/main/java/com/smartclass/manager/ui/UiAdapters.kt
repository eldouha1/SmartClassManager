package com.smartclass.manager.ui

import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.smartclass.manager.data.EvaluationRecord
import com.smartclass.manager.data.EvaluationStatus
import com.smartclass.manager.data.Student

class StudentAdapter(
    private var items: List<Student>,
    private var safeDisplayMode: Boolean,
    private val onItemClick: (Student) -> Unit,
    private val onItemLongClick: (Student) -> Unit
) : RecyclerView.Adapter<StudentAdapter.VH>() {

    class VH(val root: LinearLayout, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 22, 28, 22)
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
        }
        val title = TextView(ctx).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#F8FAFC"))
        }
        val sub = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        root.addView(title)
        root.addView(sub)
        return VH(root, title, sub)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.title.text = "${position + 1}. ${s.fullName}"
        holder.sub.text = if (safeDisplayMode) {
            "اضغط لعرض بطاقة التلميذ | اضغط مطولاً للتعديل/الحذف"
        } else {
            "النقاط: ${s.totalScore} | أجاب: ${s.answeredCount} | غياب: ${s.absentCount} | سُحب: ${s.drawCount}"
        }
        holder.root.setOnClickListener { onItemClick(s) }
        holder.root.setOnLongClickListener {
            onItemLongClick(s)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<Student>, safeMode: Boolean) {
        items = newItems
        safeDisplayMode = safeMode
        notifyDataSetChanged()
    }
}

class HistoryAdapter(
    private var items: List<EvaluationRecord>,
    private var safeDisplayMode: Boolean
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    class VH(val root: LinearLayout, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
        }
        val title = TextView(ctx).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#F8FAFC"))
        }
        val sub = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        root.addView(title)
        root.addView(sub)
        return VH(root, title, sub)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val statusColor = when (r.status) {
            EvaluationStatus.ANSWERED -> "#10B981"
            EvaluationStatus.MISSED -> "#F59E0B"
            EvaluationStatus.ABSENT -> "#EF4444"
        }
        holder.title.setTextColor(Color.parseColor(statusColor))
        val scorePart = if (safeDisplayMode || r.status != EvaluationStatus.ANSWERED) ""
        else " | العلامة: ${r.score}" + if (r.bonusAdded > 0) " (+${r.bonusAdded})" else ""

        holder.title.text = "${r.studentName} — ${r.status.arabicLabel}$scorePart"
        val extra = listOfNotNull(
            r.dateStr,
            r.className,
            r.subject.takeIf { it.isNotBlank() },
            r.note.takeIf { it.isNotBlank() }
        ).joinToString(" | ")
        holder.sub.text = extra
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<EvaluationRecord>, safeMode: Boolean) {
        items = newItems
        safeDisplayMode = safeMode
        notifyDataSetChanged()
    }
}
