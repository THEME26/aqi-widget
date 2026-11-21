package com.example.aqi.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.example.aqi.R
import com.example.aqi.core.AqiLevel
import com.example.aqi.core.AqiState
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.AreaStat
import com.example.aqi.core.DiagInfo
import com.example.aqi.core.Freshness

/**
 * 把 v0.4.0 的三屏真的画一遍。
 *
 * 这一版 UI 的主要风险不是崩溃，而是「字被裁掉一格」「环和数字对不上」这类
 * 只有看到像素才知道的问题。Robolectric 的 NATIVE 图形模式跑真实 Skia，
 * inflate → bind → measure → draw 得到的就是真机上会显示的那张图。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AppPageRenderTest {

    private val out = File("build/render-check").apply { mkdirs() }

    /**
     * 只有 [pointSheet] 需要真的起 Activity，而 MainActivity.onCreate 会
     * `RefreshScheduler.ensurePeriodic` → WorkManager。测试里用裸 Application，
     * 所以在这里显式初始化一次；其余渲染用例不受影响。
     */
    @org.junit.Before
    fun initWorkManager() {
        val ctx = RuntimeEnvironment.getApplication()
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(
                ctx,
                androidx.work.Configuration.Builder().build(),
            )
        }
    }

    private fun state(
        value: Int,
        area: Int?,
        radius: Int = 10,
        station: String = "奥体中心站",
        modelGrid: Boolean = false,
        useWorst: Boolean = false,
    ): AqiState {
        // 顺序 = [中心, 北, 东北, 东, 东南, 南, 西南, 西, 西北]，与 v0.4.2 的圆周采样一致。
        // 用固定偏移生成，最差固定落在「西」(+18)、最低落在「南」(-12)，
        // 截图里图例的最高/最低、环上的描边点必须和这两个方位对得上。
        val off = listOf(-4, 8, -1, 3, -12, -5, 18, 0)
        val grid = if (area == null) emptyList() else
            listOf(value) + off.map { area + it }
        return AqiState(
            value = value,
            level = AqiLevel.of(value),
            dominantPollutant = "PM2.5",
            subIndices = linkedMapOf("PM2.5" to value, "PM10" to 82, "O3" to 41, "NO2" to 23),
            stationName = if (modelGrid) "" else station,
            modelGrid = modelGrid,
            distanceKm = 1.2,
            observedText = "14:00",
            fetchedAtMillis = 1_700_000_000_000L,
            freshness = Freshness.FRESH,
            attribution = "",
            standard = AqiStandard.US,
            diag = DiagInfo(
                pollutant = "PM2.5",
                windowHours = 24,
                concentration = 58.0,
                localAqi = value,
                officialAqi = value,
            ),
            // 走真实的中位数/最大值计算，而不是手填 value —— 否则测试会「验证」
            // 一个跟数据层口径无关的数字。
            //
            // v0.4.4：带上坐标与模式场时间，否则环上圆点不可点、详情弹层没有内容可显示。
            area = if (area == null) null else AreaStat.of(
                grid, radius, useWorst,
                grid.mapIndexed { i, v ->
                    com.example.aqi.core.AreaPoint(
                        index = i,
                        value = v,
                        lat = 39.9042 + (if (i == 0) 0.0 else 0.22 * kotlin.math.cos((i - 1) * Math.PI / 4)),
                        lon = 116.4074 + (if (i == 0) 0.0 else 0.28 * kotlin.math.sin((i - 1) * Math.PI / 4)),
                    )
                },
                "2026-11-16T07:00",
            ),
        )
    }

    /** 用真正的 Theme.AqiWidget 包一层，否则 ?attr/selectableItemBackgroundBorderless 找不到 */
    private fun inflateRoot(): View {
        val themed = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_AqiWidget)
        val root = android.view.LayoutInflater.from(themed).inflate(R.layout.activity_main, null, false)
        // 手动触发一次 inset 布局：测试里没有 WindowInsets 分发，直接给状态栏高度的上边距
        root.findViewById<View>(R.id.page_host).setPadding(0, (24 * 3), 0, 0)
        return root
    }

    private fun shot(root: View, name: String, w: Int = 411 * 3, h: Int = 891 * 3) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        root.draw(c)
        File(out, "$name.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun showTab(root: View, tab: Int) {
        val pages = listOf(R.id.page_today, R.id.page_detail, R.id.page_settings)
        pages.forEachIndexed { i, id ->
            root.findViewById<View>(id).visibility = if (i == tab) View.VISIBLE else View.GONE
        }
    }

    /** v0.4.5：WAQI 站点九宫格。中心是最近站（有站名），外围按真实方位摆。 */
    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", sdk = [34], application = Application::class)
    fun todayScreenStationGrid() {
        val root = inflateRoot()
        val vals = listOf(168, 152, 175, 189, 203, 168, 158, 174, 181)
        val names = listOf("东四", "奥体中心", "农展馆", "天坛", "万寿西宫", "官园", "北部新区", "定陵", "顺义新城")
        val area = AreaStat.of(
            vals, 0, false,          // 0 = 不限半径，站点模式没有半径这个概念
            vals.mapIndexed { i, v ->
                com.example.aqi.core.AreaPoint(
                    index = i, value = v,
                    lat = 39.9042 + (if (i == 0) 0.0 else 0.2 * kotlin.math.cos((i - 1) * Math.PI / 4)),
                    lon = 116.4074 + (if (i == 0) 0.0 else 0.26 * kotlin.math.sin((i - 1) * Math.PI / 4)),
                    name = names[i],
                    distanceKm = if (i == 0) 0.0 else 2.4 + i * 1.7,
                )
            },
            "2026-11-16 07:00:00",
            com.example.aqi.core.AreaKind.STATION,
        )!!
        val s = state(168, 168, station = "东四").copy(area = area)
        UiBinder(root.context).bindToday(root.findViewById(R.id.page_today), s, true)
        showTab(root, 0)
        shot(root, "app_today_station_grid")
        assertTrue(File(out, "app_today_station_grid.png").length() > 10_000)

        // 标题不带半径；说明行必须交代「位置 = 距离排名」，否则用户会当方位看
        val title = root.findViewById<android.widget.TextView>(R.id.grid_title).text.toString()
        assertEquals(root.context.getString(R.string.grid_title_station, 9), title)
        assertTrue("站点模式标题里不该再出现公里数，实际：$title", !title.contains("公里"))
        val hint = root.findViewById<android.widget.TextView>(R.id.grid_hint).text.toString()
        assertEquals(root.context.getString(R.string.grid_hint_rank), hint)
    }

    /**
     * 站点环取不到 → 退回模式格点（v0.4.6 真机故障的回归图）。
     *
     * 这一屏的意义：用户选的是 WAQI 站点，但 `map/bounds` 对免费 Token 直接报错，
     * 于是退回格点。**必须有一行字说明这件事**，否则卡片写着「9 个格点」，
     * 用户会以为站点环生效了 —— v0.4.5 的表现是整块周边直接消失。
     */
    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", sdk = [34], application = Application::class)
    fun todayScreenStationFallback() {
        val root = inflateRoot()
        val s0 = state(87, 152, radius = 25)
        val area = s0.area!!.copy(
            noteCode = com.example.aqi.core.AreaStat.NOTE_STATION_FAILED,
            noteDetail = "Invalid key",
        )
        UiBinder(root.context).bindToday(
            root.findViewById(R.id.page_today),
            s0.copy(area = area),
            true,
        )
        showTab(root, 0)
        shot(root, "app_today_station_fallback")

        val note = root.findViewById<android.widget.TextView>(R.id.grid_note)
        assertEquals(View.VISIBLE, note.visibility)
        assertTrue("说明里要写明原因，实际：${note.text}", note.text.contains("Invalid key"))
        assertTrue(
            "说明里必须讲清「已退回格点」，实际：${note.text}",
            note.text.contains("退回"),
        )
    }

    @Test
    fun todayScreen() {
        val root = inflateRoot()
        val s = state(68, 152, station = "Olympic Sports Center")
        UiBinder(root.context).bindToday(root.findViewById(R.id.page_today), s, true)
        showTab(root, 0)
        shot(root, "app_today_light")
        assertTrue(File(out, "app_today_light.png").length() > 10_000)
    }

    @Test
    fun todayScreenAlert() {
        val root = inflateRoot()
        UiBinder(root.context).bindToday(
            root.findViewById(R.id.page_today),
            state(268, 152, station = "Olympic Sports Center"),
            true,
        )
        showTab(root, 0)
        shot(root, "app_today_alert")
    }

    @Test
    fun todayScreenNoArea() {
        val root = inflateRoot()
        UiBinder(root.context).bindToday(root.findViewById(R.id.page_today), state(68, null), true)
        showTab(root, 0)
        shot(root, "app_today_no_area")
    }

    /** 无数据 / 无权限时也必须是一张完整的卡，而不是一堆空 TextView。 */
    @Test
    fun todayNoData() {
        val root = inflateRoot()
        val s = AqiState.placeholder(Freshness.NO_PERMISSION, AqiStandard.US)
        UiBinder(root.context).bindToday(root.findViewById(R.id.page_today), s, true)
        root.findViewById<android.widget.TextView>(R.id.aqi_sync_note).apply {
            text = com.example.aqi.core.AqiFormat.freshnessLabel(Freshness.NO_PERMISSION, root.context)
            visibility = View.VISIBLE
        }
        showTab(root, 0)
        shot(root, "app_today_nodata")
    }

    /**
     * 「取最差格点」开启：主数字 = 9 点最大值，右上角口径标签变「最差格点」，
     * 图例三个数不变。这张图是判断「换了口径会不会让用户以为是别的东西」的依据。
     */
    @Test
    fun todayScreenWorstPoint() {
        val root = inflateRoot()
        UiBinder(root.context).bindToday(
            root.findViewById(R.id.page_today),
            state(152, 170, useWorst = true, station = "Olympic Sports Center"),
            true,
        )
        showTab(root, 0)
        shot(root, "app_today_worst")
    }

    /**
     * 详情页：环里必须有读数。
     *
     * v0.4.6 的真机截图里那个 176dp 的大圈是空的 —— `GaugePainter.ring` 只画弧不画字，
     * 详情页布局里又没有 `hero_value` 那样的叠层，于是「环是量程」这件事完全读不出来。
     * 断言写在文本上，而不是「截图不为空」，否则空圈照样能过。
     */
    @Test
    fun detailScreen() {
        val root = inflateRoot()
        UiBinder(root.context).bindDetail(
            root.findViewById(R.id.page_detail),
            state(152, 198, station = "Olympic Sports Center"),
        )
        showTab(root, 1)
        shot(root, "app_detail_light")

        val v = root.findViewById<android.widget.TextView>(R.id.detail_value)
        assertEquals("152", v.text.toString())
        assertEquals(View.VISIBLE, v.visibility)
        // 读数必须在环里：上下沿夹住它，否则等于挂在环外
        val gauge = root.findViewById<android.view.View>(R.id.detail_gauge)
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val top = loc[1]
        gauge.getLocationInWindow(loc)
        val gTop = loc[1]
        assertTrue("数字必须落在环的上沿之内", top >= gTop)
        assertTrue("数字必须在环的下沿之上", top + v.height <= gTop + gauge.height)
    }

    /** 详情页整页：诊断行在六档表下方，只有拉高才看得到。 */
    @Test
    fun detailFull() {
        val root = inflateRoot()
        UiBinder(root.context).bindDetail(
            root.findViewById(R.id.page_detail),
            state(152, 198, station = "Olympic Sports Center"),
        )
        showTab(root, 1)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(411 * 3, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1500 * 3, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 411 * 3, 1500 * 3)
        shot(root, "app_detail_full", 411 * 3, 1500 * 3)
    }

    private fun seg(root: View, id: Int, items: List<String>, on: Int) {
        val c = root.findViewById<SegmentedControl>(id)
        c.setItems(items)
        c.select(on)
    }

    /**
     * 设置页上所有分段控件的文案，抽成一处。
     *
     * 原先的两个设置页用例各手抄一份 `seg(...)`，v0.4.5 新加的周边数据源分段
     * 就只加进了 MainActivity、没加进测试 —— 于是截图里那一行静静地是空的，
     * 而断言（文件 > 10 KB）照样通过。渲染测试的失效方式就是这样：
     * 它证明「画出来了」，不证明「画全了」。所以这里改成一处定义，漏一个就一起漏。
     */
    private fun settingsSegments(page: View, radius: Int = 0) {
        val c = page.context
        fun pick(id: Int, vararg res: Int) = seg(page, id, res.map { c.getString(it) }, 0)
        pick(R.id.seg_source, R.string.source_seg_om, R.string.source_seg_waqi)
        pick(R.id.seg_standard, R.string.standard_seg_us, R.string.standard_seg_cn)
        pick(R.id.seg_interval, R.string.interval_seg_30, R.string.interval_seg_60, R.string.interval_seg_120)
        pick(R.id.seg_policy, R.string.policy_seg_alert, R.string.policy_seg_always, R.string.policy_seg_never)
        // v0.4.5：跟随主源 / Open-Meteo / WAQI。三项里第一项最长（德语 Hauptquelle folgen），
        // 分段控件按内容撑宽、不换行也不省略，所以这一行是各语言里最容易被挤出屏幕的。
        pick(R.id.seg_area_source, R.string.area_src_follow, R.string.source_seg_om, R.string.source_seg_waqi)
        seg(
            page, R.id.seg_radius,
            listOf(R.string.radius_seg_10, R.string.radius_seg_25, R.string.radius_seg_50).map { c.getString(it) },
            radius,
        )
    }

    @Test
    fun settingsScreen() {
        val root = inflateRoot()
        val page = root.findViewById<View>(R.id.page_settings)
        settingsSegments(page, radius = 0)
        page.findViewById<ToggleSwitch>(R.id.sw_area).setChecked(true)
        page.findViewById<ToggleSwitch>(R.id.sw_lock_full).setChecked(true)
        // 这两行只有 MainActivity 会填：截图里手动给一个「最坏情况」——
        // 最长的状态文案（系统未采纳）+ 开关打开，用来检查会不会把行撑破
        page.findViewById<ToggleSwitch>(R.id.sw_live).setChecked(true)
        page.findViewById<android.widget.TextView>(R.id.tv_live_state).text =
            root.context.getString(R.string.live_state_pending)
        page.findViewById<android.widget.TextView>(R.id.widget_diag).text =
            root.context.getString(R.string.widget_diag, 4)
        showTab(root, 2)
        shot(root, "app_settings_light")
        // 整页高度：用来肉眼检查设置页下半部分（小部件 / 权限 / 关于）有没有错位
        root.measure(
            View.MeasureSpec.makeMeasureSpec(411 * 3, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1900 * 3, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 411 * 3, 1900 * 3)
        shot(root, "app_settings_full", 411 * 3, 1900 * 3)
    }

    /**
     * 中文界面单独出一版：这个 App 文案量比控件多，
     * 「中文里夹一句英文」这类问题只有把整屏画出来才看得见。
     */
    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
    fun chineseScreens() {
        val root = inflateRoot()
        UiBinder(root.context).bindToday(root.findViewById(R.id.page_today), state(68, 152), true)
        showTab(root, 0)
        shot(root, "app_today_zh")

        // 同一屏、只切口径：主数字 151 → 170，右上角「中位数」→「最差格点」。
        val rootW = inflateRoot()
        UiBinder(rootW.context).bindToday(
            rootW.findViewById(R.id.page_today),
            state(68, 170, useWorst = true),
            true,
        )
        showTab(rootW, 0)
        shot(rootW, "app_today_zh_worst")

        val root2 = inflateRoot()
        UiBinder(root2.context).bindDetail(root2.findViewById(R.id.page_detail), state(152, 198))
        showTab(root2, 1)
        shot(root2, "app_detail_zh")
        root2.measure(
            View.MeasureSpec.makeMeasureSpec(411 * 3, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1500 * 3, View.MeasureSpec.EXACTLY),
        )
        root2.layout(0, 0, 411 * 3, 1500 * 3)
        shot(root2, "app_detail_zh_full", 411 * 3, 1500 * 3)

        val root3 = inflateRoot()
        val page = root3.findViewById<View>(R.id.page_settings)
        settingsSegments(page, radius = 1)
        page.findViewById<ToggleSwitch>(R.id.sw_area).setChecked(true)
        page.findViewById<ToggleSwitch>(R.id.sw_lock_full).setChecked(true)
        showTab(root3, 2)
        shot(root3, "app_settings_zh")

        // v0.5.0：系统息屏那一段的措辞只有中文才看得出是否得体，
        // 顺便用最长的「未采纳」文案压一下，确认四行说明不会把行撑破。
        page.findViewById<ToggleSwitch>(R.id.sw_live).setChecked(true)
        page.findViewById<android.widget.TextView>(R.id.tv_live_state).text =
            root3.context.getString(R.string.live_state_pending)
        shot(root3, "app_settings_zh_full", 411 * 3, 1900 * 3)
    }

    /**
     * 德语设置页（v0.4.5）：新增的「周边数据源」三项分段是本页最难过的一行。
     *
     * `item_seg` 是 wrap_content + maxLines=1，既省略号也不换行 —— 一行超宽只会
     * 被父容器裁掉右边，也就是 WAQI 那一项凭空消失，且不产生任何布局报错。
     * 德语 Hauptquelle folgen 是 7 种语言里最长的一项，它放得下其余就都放得下。
     *
     * 断言用 `paint.measureText` 量文本原宽，而不是比 `measuredWidth`：
     * TextView 在 AT_MOST 约束下 measuredWidth 会被截断到父宽，那时它已经不告诉你
     * 「本来需要多宽」了。
     */
    @Test
    @Config(qualifiers = "de-rDE-w411dp-h891dp-xxhdpi")
    fun settingsGerman() {
        val root = inflateRoot()
        val page = root.findViewById<View>(R.id.page_settings)
        settingsSegments(page, radius = 1)
        page.findViewById<ToggleSwitch>(R.id.sw_area).setChecked(true)
        showTab(root, 2)
        shot(root, "app_settings_de")

        val segc = page.findViewById<SegmentedControl>(R.id.seg_area_source)
        var need = 0
        for (i in 0 until segc.childCount) {
            val tv = segc.getChildAt(i) as android.widget.TextView
            need += Math.ceil(tv.paint.measureText(tv.text.toString()).toDouble()).toInt() +
                tv.paddingLeft + tv.paddingRight
        }
        val avail = segc.measuredWidth - segc.paddingLeft - segc.paddingRight
        assertTrue(
            "德语「周边数据源」分段放不下：需要 ${need}px，可用 ${avail}px（会被裁掉右边）",
            need <= avail,
        )
    }

    @Test
    fun darkVariants() {
        RuntimeEnvironment.setQualifiers("+night")
        val root = inflateRoot()
        UiBinder(root.context).bindToday(root.findViewById(R.id.page_today), state(152, 198), true)
        showTab(root, 0)
        shot(root, "app_today_dark")

        val root2 = inflateRoot()
        UiBinder(root2.context).bindDetail(root2.findViewById(R.id.page_detail), state(152, 198))
        showTab(root2, 1)
        shot(root2, "app_detail_dark")
    }

    /**
     * 点位详情弹层（v0.4.4）：走真实 `Dialog`，不是把一个布局摆拍一遍。
     *
     * 之所以要经过 Activity：`AreaPointSheet.show()` 里有 window 背景、宽度、
     * 点击关闭三件事，单独 inflate 布局验证不到。
     */
    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", sdk = [34], application = Application::class)
    fun pointSheet() {
        val activity = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // 9 点里 8 个同值、1 个偏高 —— 正是用户看到的「只有 203 和 174 两个数」。
        // 取索引 1（北，174，与中心相同）才会触发「大概率同格」那段解释。
        val vals = listOf(174, 174, 174, 203, 174, 174, 174, 174, 174)
        val area = AreaStat.of(
            vals, 25, false,
            vals.mapIndexed { i, v ->
                com.example.aqi.core.AreaPoint(
                    index = i, value = v,
                    lat = 39.9042 + (if (i == 0) 0.0 else 0.22 * kotlin.math.cos((i - 1) * Math.PI / 4)),
                    lon = 116.4074 + (if (i == 0) 0.0 else 0.28 * kotlin.math.sin((i - 1) * Math.PI / 4)),
                )
            },
            "2026-11-16T07:00",
        )!!
        AreaPointSheet.show(activity, area, 1)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue("弹层没有出现", dialog != null && dialog.isShowing)
        val decor = dialog.window!!.decorView
        val w = 411 * 3
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(891 * 3, View.MeasureSpec.AT_MOST),
        )
        decor.layout(0, 0, w, decor.measuredHeight)

        val h = decor.measuredHeight
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // 模拟 Dialog 的遮罩，否则透明 window 画出来是黑底
        c.drawColor(0xFF10141C.toInt())
        decor.draw(c)
        File(out, "app_point_sheet_zh.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(File(out, "app_point_sheet_zh.png").length() > 10_000)
    }
}
