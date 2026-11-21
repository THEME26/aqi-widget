package com.example.aqi.core

import java.util.Locale

/**
 * 周边九宫格的选站规则（v0.4.7）：**按距离排名，不看方位，不限半径**。
 *
 * ```
 *   ⑧  ①  ②          序号 = 由近到远的排名
 *   ⑦  ·   ③          · = 最近的站（中心格，描边）
 *   ⑥  ⑤  ④          从正上方开始顺时针，第 2 近 … 第 9 近
 * ```
 *
 * ## 为什么把「方位」丢掉了
 * v0.4.5 按 45° 扇区挑最近的站，v0.4.6 改成 8 个方位各探一次并过滤半径。
 * 真机结果是「only 2 usable cells」：探测点本身落在半径上，它返回的站自然也在
 * 半径边缘，再拿 `d > radiusKm` 一滤就没了。**半径和「最近 N 个」本来就是互斥的**：
 * 先画一个圈再往里挑，圈外的站永远进不来，而用户要的就是「最近的」。
 *
 * 现在只做一件事：把候选按距离排序，取前 9。于是
 *  - 中心格 = 最近的站（不是「你」，也不是某个方向）；
 *  - 外圈 8 格 = 第 2–9 近，顺时针铺开；
 *  - 格子位置**只表示远近，不表示方位** —— 卡片上那行说明会写明，详情弹层也不写方位。
 *
 * 这个取舍有代价，必须写在代码里：九宫格不再是一张地图。以前看东北角就知道东北方向
 * 空气如何；现在只能知道「第三近的那个站是多少」。换来的是它说的每句话都成立。
 */
object StationPick {

    const val CELLS = 9

    /**
     * 一个候选站。`value` 已经是 2024 重算后的值；<0 表示这个站取不到可用读数。
     *
     * `uid` 是 WAQI 的站点编号，`search` 与 `map/bounds` 都会回传。用它判重，
     * 而不是靠站名 —— 同一个站的中文名与拼音名可能同时出现在结果里。
     */
    data class Raw(
        val uid: Int,
        val name: String,
        val lat: Double,
        val lon: Double,
        val value: Int,
    )

    /**
     * 候选站 → 由近到远的 [limit] 个。
     *
     * @param excludeName 中心站的名字（主链路已经用它了）。命中的候选会被剔除，
     *   否则它会以「最近的站」的身份在外圈再出现一次 —— 看起来像附近有两个站。
     */
    fun nearest(
        stations: List<Raw>,
        lat: Double,
        lon: Double,
        excludeName: String,
        limit: Int = CELLS,
    ): List<Raw> {
        if (limit <= 0) return emptyList()
        val seen = HashSet<String>()
        val scored = ArrayList<Pair<Raw, Double>>(stations.size)
        for (s in stations) {
            if (s.lat.isNaN() || s.lon.isNaN()) continue
            if (excludeName.isNotBlank() && s.name.equals(excludeName, ignoreCase = true)) continue
            val key = if (s.uid > 0) "u${s.uid}" else "n" + s.name.lowercase(Locale.US)
            if (!seen.add(key)) continue
            val d = Geo.distanceKm(lat, lon, s.lat, s.lon)
            if (d.isNaN()) continue
            scored += s to d
        }
        // 距离相同按 uid 排：没有这条，两次刷新的顺序可能不同，格子会「自己跳」
        scored.sortWith(compareBy({ it.second }, { it.first.uid }))
        return scored.take(limit).map { it.first }
    }

    /**
     * 排序后的站 → 9 个格子（索引 0 是中心，1..8 从正上方顺时针）。
     *
     * 中心格有两条来源：
     *  - [centerValue] >= 0：主链路就是这个源，中心格直接复用主数字（同口径、省一次请求）；
     *  - [centerValue] < 0：周边源 ≠ 主源，中心格由**最近的站**充当，值来自它自己。
     *    绝不把 OM 的 144 塞进一圈 WAQI 站中间 —— 那不是同一个口径。
     *
     * 缺的格子一律 `-1`，由界面画「–」。缺 6 个以上时 [AreaStat.of] 会整块返回 null，
     * 那时整行「周边」消失，而不是给一张缺一半的九宫格。
     */
    fun assemble(
        centerName: String,
        centerValue: Int,
        lat: Double,
        lon: Double,
        ranked: List<Raw>,
        centerDistanceKm: Double = 0.0,
    ): List<AreaPoint> {
        // 固定槽位：第 i 个候选永远落在第 i 格，取不到值的也占着位置画「–」。
        // 如果改成「跳过失败的那格、后面的往前挤」，格子上的排名就和实际排名对不上了 ——
        // 而排名是这张图唯一的语义，错一位等于说谎。
        val out = arrayOfNulls<AreaPoint>(CELLS)
        val offset: Int
        if (centerValue >= 0) {
            out[0] = AreaPoint(
                index = 0,
                value = centerValue,
                lat = lat,
                lon = lon,
                name = centerName,
                distanceKm = centerDistanceKm,
            )
            offset = 1
        } else {
            offset = 0
        }
        for ((i, c) in ranked.withIndex()) {
            val slot = i + offset
            if (slot >= CELLS) break
            out[slot] = AreaPoint(
                index = slot,
                value = c.value,
                lat = c.lat,
                lon = c.lon,
                name = c.name,
                distanceKm = Geo.distanceKm(lat, lon, c.lat, c.lon),
            )
        }
        return (0 until CELLS).map { out[it] ?: AreaPoint(index = it, value = -1, lat = 0.0, lon = 0.0) }
    }
}
