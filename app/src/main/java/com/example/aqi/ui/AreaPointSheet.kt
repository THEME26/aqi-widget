package com.example.aqi.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.TextView
import com.example.aqi.R
import com.example.aqi.core.AqiLevel
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.AreaKind
import com.example.aqi.core.AreaPoint
import com.example.aqi.core.AreaStat
import java.util.Locale

/**
 * 点位详情（v0.4.4 起，v0.4.5 分两种数据源）。
 *
 * 用户点九宫格里的一格，最想知道的是「这一格在哪、是什么」。能诚实回答的：
 * 方位、距离、AQI 与档位、坐标、数据时间。两种来源的差别要说清楚：
 *
 *  - [AreaKind.GRID]（Open-Meteo）：**不是监测站**。只有 CAMS 模式格点，
 *    没有站点名，也没有站点编号。所以这里不编假站名，而是明确写「不是监测站」，
 *    并告诉用户想要站点实测该往哪走（切 WAQI + Token）。
 *  - [AreaKind.STATION]（WAQI）：真实监测站，有站名。数值是本 App 用站点
 *    已发布的 2012 分指数逆推浓度、再按 2024 断点重算的结果，不是站点自报值。
 *    这个区别必须写出来，否则用户拿去和 aqicn 页面一对就发现差几点。
 *
 * 另一个常被误会成 bug 的现象也写在这里：OM 的 9 个点常常只有 2 个不同的值。
 * 因为国境内 CAMS 全球模式是 0.4°≈45 公里，半径 25 公里的 9 个点在
 * 大多数城市里只落在 1~2 个格子里 —— 不是数据没刷新。
 */
object AreaPointSheet {

    fun show(activity: Activity, area: AreaStat, index: Int) {
        val p = area.point(index) ?: return
        val ctx = activity
        val standard = AqiStandard.US

        val dlg = Dialog(activity)
        dlg.setContentView(R.layout.dialog_area_point)
        // 圆角卡片自带背景，Dialog 默认的白色 window 背景会把四个角补成方块
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

        // 同值时追加一段解释：OM 下是「同格」，WAQI 下是「同一站点或相邻点位」。
        dlg.findViewById<TextView>(R.id.dlg_note).text = note(ctx, area, p)

        dlg.findViewById<View>(R.id.dlg_close).setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    /** 方位数组按索引取；中心返回「中心」文案。 */
    fun bearingOf(ctx: android.content.Context, area: AreaStat, index: Int): String =
        if (index == 0) ctx.getString(R.string.dlg_center)
        else ctx.resources.getStringArray(R.array.bearings).getOrElse(index - 1) { "" }

    /**
     * 标题：
     *  - 站点模式·中心：`站名`（主链路那个站）；外围站自算时要带距离
     *  - 站点模式·外围：`站名 · 1.8 公里 · 第 3 近` —— 位置是排名，所以排名要写出来
     *  - 格点模式：`西南 · 25 公里`
     *
     * @param index 九宫格索引；排名 = index + 1（中心是最近的，第 1 近）
     */
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

    /** 说明文字：始终说清数据到底是什么，同值时再解释「为什么两格一样」。 */
    fun note(ctx: android.content.Context, area: AreaStat, p: AreaPoint): String = buildString {
        if (area.kind == AreaKind.STATION) {
            // 先说位置的含义，再说数值的口径。反过来的话，用户会先读到「2024 重算」
            // 然后带着「这格是东北方向」的成见去看，越看越对不上。
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
