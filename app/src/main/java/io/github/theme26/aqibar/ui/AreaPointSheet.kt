package io.github.theme26.aqibar.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.TextView
import io.github.theme26.aqibar.R
import io.github.theme26.aqibar.core.AqiLevel
import io.github.theme26.aqibar.core.AqiStandard
import io.github.theme26.aqibar.core.AreaKind
import io.github.theme26.aqibar.core.AreaPoint
import io.github.theme26.aqibar.core.AreaStat
import java.util.Locale

object AreaPointSheet {

    fun show(activity: Activity, area: AreaStat, index: Int) {
        val p = area.point(index) ?: return
        val ctx = activity
        val standard = AqiStandard.US

        val dlg = Dialog(activity)
        dlg.setContentView(R.layout.dialog_area_point)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        dlg.findViewById<TextView>(R.id.dlg_title).text =
            title(ctx, area, p, bearingOf(ctx, area, index), index)

        val valueTv = dlg.findViewById<TextView>(R.id.dlg_value)
        val levelTv = dlg.findViewById<TextView>(R.id.dlg_level)
        if (p.value >= 0) {
            val lv = AqiLevel.of(p.value)
            valueTv.text = p.value.toString()
            valueTv.setTextColor(lv.textColorInt(ctx))
            levelTv.text = lv.label(ctx, standard)
            levelTv.setTextColor(lv.textColorInt(ctx))
        } else {
            valueTv.text = ctx.getString(R.string.dash)
            valueTv.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_dim))
            levelTv.text = ctx.getString(R.string.dlg_missing)
            levelTv.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_dim))
        }

        dlg.findViewById<TextView>(R.id.dlg_coord).text = ctx.getString(
            R.string.dlg_coord,
            String.format(Locale.US, "%.4f", p.lat),
            String.format(Locale.US, "%.4f", p.lon),
        )
        dlg.findViewById<TextView>(R.id.dlg_source).text =
            if (area.kind == AreaKind.STATION) {
                ctx.getString(R.string.dlg_source_station)
            } else {
                ctx.getString(R.string.dlg_source)
            }

        val timeTv = dlg.findViewById<TextView>(R.id.dlg_time)
        if (area.observedText.isNotBlank()) {
            timeTv.visibility = View.VISIBLE
            timeTv.text = ctx.getString(R.string.dlg_time, area.observedHhmm)
        } else {
            timeTv.visibility = View.GONE
        }

        dlg.findViewById<TextView>(R.id.dlg_note).text = note(ctx, area, p)

        dlg.findViewById<View>(R.id.dlg_close).setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    fun bearingOf(ctx: android.content.Context, area: AreaStat, index: Int): String =
        if (index == 0) ctx.getString(R.string.dlg_center)
        else ctx.resources.getStringArray(R.array.bearings).getOrElse(index - 1) { "" }

    fun title(
        ctx: android.content.Context,
        area: AreaStat,
        p: AreaPoint,
        bearing: String,
        index: Int = p.index,
    ): String {
        val stationMode = area.kind == AreaKind.STATION
        return when {
            p.isCenter && stationMode && p.name.isNotBlank() ->
                if (p.distanceKm > 0.05) {
                    ctx.getString(R.string.dlg_title_station, p.name, p.distanceKm)
                } else {
                    p.name
                }
            p.isCenter -> bearing
            stationMode && p.name.isNotBlank() ->
                ctx.getString(R.string.dlg_title_rank, p.name, p.distanceKm, index + 1)
            else -> ctx.getString(R.string.dlg_title, bearing, area.radiusKm)
        }
    }

    fun note(ctx: android.content.Context, area: AreaStat, p: AreaPoint): String = buildString {
        if (area.kind == AreaKind.STATION) {
            append(ctx.getString(R.string.dlg_note_rank))
            append("\n\n")
            append(ctx.getString(R.string.dlg_note_station))
            if (!p.isCenter && p.value >= 0 && p.value == area.grid.firstOrNull()) {
                append("\n\n")
                append(ctx.getString(R.string.dlg_same_station))
            }
        } else {
            append(ctx.getString(R.string.dlg_note))
            if (!p.isCenter && p.value >= 0 && p.value == area.grid.firstOrNull()) {
                append("\n\n")
                append(ctx.getString(R.string.dlg_same_cell))
            }
        }
    }
}
