package com.kazuhira.hcsync

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZoneId

sealed class LogRow {
    data class Header(val date: LocalDate, val totalKcal: Double) : LogRow()
    data class Meal(val record: LocalMealRecord) : LogRow()
}

/** Ration log grouped by day, newest first. */
class RationLogAdapter(private val context: Context) : BaseAdapter() {

    private var rows: List<LogRow> = emptyList()

    fun submit(meals: List<LocalMealRecord>) {
        val zone = ZoneId.systemDefault()
        rows = meals
            .groupBy { it.instant.atZone(zone).toLocalDate() }
            .flatMap { (date, dayMeals) ->
                listOf<LogRow>(LogRow.Header(date, dayMeals.sumOf { it.calories })) + dayMeals.map { LogRow.Meal(it) }
            }
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): LogRow = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = if (rows[position] is LogRow.Header) 0 else 1
    override fun areAllItemsEnabled(): Boolean = false
    override fun isEnabled(position: Int): Boolean = rows[position] is LogRow.Meal

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val row = rows[position]) {
            is LogRow.Header -> bindHeader(row, convertView ?: inflate(R.layout.item_log_header, parent))
            is LogRow.Meal -> bindMeal(row.record, convertView ?: inflate(R.layout.item_meal, parent))
        }

    private fun inflate(layout: Int, parent: ViewGroup): View =
        LayoutInflater.from(context).inflate(layout, parent, false)

    private fun bindHeader(row: LogRow.Header, view: View): View {
        view.findViewById<TextView>(R.id.tvDayLabel).text = RationFormat.dayLabel(row.date)
        view.findViewById<TextView>(R.id.tvDayTotal).text = "${RationFormat.kcal(row.totalKcal)} KCAL"
        return view
    }

    private fun bindMeal(item: LocalMealRecord, view: View): View {
        view.findViewById<TextView>(R.id.tvMealTitle).text = item.mealName
        view.findViewById<TextView>(R.id.tvMealCalories).text = "${RationFormat.kcal(item.calories)} kcal"
        view.findViewById<TextView>(R.id.tvProteinBadge).text = "P ${RationFormat.grams(item.proteinG)}"
        view.findViewById<TextView>(R.id.tvCarbBadge).text = "C ${RationFormat.grams(item.carbG)}"
        view.findViewById<TextView>(R.id.tvFatBadge).text = "F ${RationFormat.grams(item.fatG)}"

        val time = item.instant.atZone(ZoneId.systemDefault())
        val mealType = HealthConnectSync.mealTypeLabel(HealthConnectSync.resolveMealType(item))
        view.findViewById<TextView>(R.id.tvMealTime).text = "$mealType // ${RationFormat.clock(context, time)}"

        val pending = !item.syncedToHealthConnect
        view.findViewById<TextView>(R.id.tvSyncState).visibility = if (pending) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.tvSyncDiamond).apply {
            text = if (pending) "◇" else "◆"
            setTextColor(ContextCompat.getColor(context, if (pending) R.color.idroid_warn else R.color.idroid_cyan))
        }
        return view
    }
}
