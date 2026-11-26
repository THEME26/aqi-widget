package io.github.theme26.aqibar.ui

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
import io.github.theme26.aqibar.R
import io.github.theme26.aqibar.core.AqiLevel
import io.github.theme26.aqibar.core.AqiState
import io.github.theme26.aqibar.core.AqiStandard
import io.github.theme26.aqibar.core.AreaStat
import io.github.theme26.aqibar.core.DiagInfo
import io.github.theme26.aqibar.core.Freshness

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AppPageRenderTest {

    private val out = File("build/render-check").apply { mkdirs() }

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
            area = if (area == null) null else AreaStat.of(
                grid, radius, useWorst,
                grid.mapIndexed { i, v ->
                    io.github.theme26.aqibar.core.AreaPoint(
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

    private fun inflateRoot(): View {
        val themed = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_AqiWidget)
        val root = android.view.LayoutInflater.from(themed).inflate(R.layout.activity_main, null, false)
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

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", sdk = [34], application = Application::class)
    fun todayScreenStationGrid() {
        val root = inflateRoot()
        val vals = listOf(168, 152, 175, 189, 203, 168, 158, 174, 181)
        val names = listOf("东四", "奥体中心", "农展馆", "天坛", "万寿西宫", "官园", "北部新区", "定陵", "顺义新城")
        val area = AreaStat.of(
            vals, 0, false,
            vals.mapIndexed { i, v ->
                io.github.theme26.aqibar.core.AreaPoint(
                    index = i, value = v,
                    lat = 39.9042 + (if (i == 0) 0.0 else 0.2 * kotlin.math.cos((i - 1) * Math.PI / 4)),
                    lon = 116.4074 + (if (i == 0) 0.0 else 0.26 * kotlin.math.sin((i - 1) * Math.PI / 4)),
                    name = names[i],
                    distanceKm = if (i == 0) 0.0 else 2.4 + i * 1.7,
                )
            },
            "2026-11-16 07:00:00",
            io.github.theme26.aqibar.core.AreaKind.STATION,
        )!!
        val s = state(168, 168, station = "东四").copy(area = area)
        UiBinder(root.context).bindToday(root.findViewById(R.id.page_today), s, true)
        showTab(root, 0)
        shot(root, "app_today_station_grid")
        assertTrue(File(out, "app_today_station_grid.png").length() > 10_000)

        val title = root.findViewById<android.widget.TextView>(R.id.grid_title).text.toString()
        assertEquals(root.context.getString(R.string.grid_title_station, 9), title)
        assertTrue("站点模式标题里不该再出现公里数，实际：$title", !title.contains("公里"))
        val hint = root.findViewById<android.widget.TextView>(R.id.grid_hint).text.toString()
        assertEquals(root.context.getString(R.string.grid_hint_rank), hint)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", sdk = [34], application = Application::class)
    fun todayScreenStationFallback() {
        val root = inflateRoot()
        val s0 = state(87, 152, radius = 25)
        val area = s0.area!!.copy(
            noteCode = io.github.theme26.aqibar.core.AreaStat.NOTE_STATION_FAILED,
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

    @Test
    fun todayNoData() {
        val root = inflateRoot()
        val s = AqiState.placeholder(Freshness.NO_PERMISSION, AqiStandard.US)
        UiBinder(root.context).bindToday(root.findViewById(R.id.page_today), s, true)
        root.findViewById<android.widget.TextView>(R.id.aqi_sync_note).apply {
            text = io.github.theme26.aqibar.core.AqiFormat.freshnessLabel(Freshness.NO_PERMISSION, root.context)
            visibility = View.VISIBLE
        }
        showTab(root, 0)
        shot(root, "app_today_nodata")
    }

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
        val gauge = root.findViewById<android.view.View>(R.id.detail_gauge)
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val top = loc[1]
        gauge.getLocationInWindow(loc)
        val gTop = loc[1]
        assertTrue("数字必须落在环的上沿之内", top >= gTop)
        assertTrue("数字必须在环的下沿之上", top + v.height <= gTop + gauge.height)
    }

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

    private fun settingsSegments(page: View, radius: Int = 0) {
        val c = page.context
        fun pick(id: Int, vararg res: Int) = seg(page, id, res.map { c.getString(it) }, 0)
        pick(R.id.seg_source, R.string.source_seg_om, R.string.source_seg_waqi)
        pick(R.id.seg_standard, R.string.standard_seg_us, R.string.standard_seg_cn)
        pick(R.id.seg_interval, R.string.interval_seg_30, R.string.interval_seg_60, R.string.interval_seg_120)
        pick(R.id.seg_policy, R.string.policy_seg_alert, R.string.policy_seg_always, R.string.policy_seg_never)
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
        page.findViewById<ToggleSwitch>(R.id.sw_live).setChecked(true)
        page.findViewById<android.widget.TextView>(R.id.tv_live_state).text =
            root.context.getString(R.string.live_state_pending)
        page.findViewById<android.widget.TextView>(R.id.widget_diag).text =
            root.context.getString(R.string.widget_diag, 4)
        showTab(root, 2)
        shot(root, "app_settings_light")
        root.measure(
            View.MeasureSpec.makeMeasureSpec(411 * 3, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1900 * 3, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 411 * 3, 1900 * 3)
        shot(root, "app_settings_full", 411 * 3, 1900 * 3)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
    fun chineseScreens() {
        val root = inflateRoot()
        UiBinder(root.context).bindToday(root.findViewById(R.id.page_today), state(68, 152), true)
        showTab(root, 0)
        shot(root, "app_today_zh")

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

        page.findViewById<ToggleSwitch>(R.id.sw_live).setChecked(true)
        page.findViewById<android.widget.TextView>(R.id.tv_live_state).text =
            root3.context.getString(R.string.live_state_pending)
        shot(root3, "app_settings_zh_full", 411 * 3, 1900 * 3)
    }

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

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", sdk = [34], application = Application::class)
    fun pointSheet() {
        val activity = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val vals = listOf(174, 174, 174, 203, 174, 174, 174, 174, 174)
        val area = AreaStat.of(
            vals, 25, false,
            vals.mapIndexed { i, v ->
                io.github.theme26.aqibar.core.AreaPoint(
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
        c.drawColor(0xFF10141C.toInt())
        decor.draw(c)
        File(out, "app_point_sheet_zh.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(File(out, "app_point_sheet_zh.png").length() > 10_000)
    }
}
