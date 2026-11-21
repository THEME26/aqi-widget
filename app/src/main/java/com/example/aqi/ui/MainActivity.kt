package com.example.aqi.ui

import android.Manifest
import android.animation.ValueAnimator
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.aqi.BuildConfig
import com.example.aqi.R
import com.example.aqi.core.AqiLevel
import com.example.aqi.core.AqiSource
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.AqiState
import com.example.aqi.core.MaskTier
import com.example.aqi.core.NotifPolicy
import com.example.aqi.data.AqiCache
import com.example.aqi.data.AqiPrefs
import com.example.aqi.live.LiveUpdate
import com.example.aqi.render.AqiNotifier
import com.example.aqi.widget.AqiWidgetCompact
import com.example.aqi.widget.AqiWidgetMain
import com.example.aqi.widget.AqiWidgetMiniSquare
import com.example.aqi.widget.AqiWidgetMiniTall
import com.example.aqi.worker.RefreshScheduler

/**
 * v0.4.0：一屏配置页 → 三屏（今日 / 详情 / 设置）+ 底栏。
 *
 * 分工是这一版的核心，不是排版偏好：
 *  - **今日**只回答「现在能不能出门」。位置、环、结论、区域对比、9 格分布。
 *  - **详情**回答「为什么是这个数」。大环、六格尺、六档表、计算口径、诊断行。
 *  - **设置**才是配置。分组卡，数据 / 提醒 / 位置 / 小部件 / 权限 / 关于。
 *
 * 三页都常驻（不销毁重建）：设置页有 EditText 和滚动位置，来回切 tab 重建会丢焦点。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AqiPrefs
    private lateinit var binder: UiBinder

    private lateinit var pageToday: View
    private lateinit var pageDetail: View
    private lateinit var pageSettings: View
    private lateinit var tabIcons: List<android.widget.ImageView>
    private lateinit var tabLabels: List<TextView>

    private lateinit var heroGauge: android.widget.ImageView
    private lateinit var hero: View
    private lateinit var syncNote: TextView

    private lateinit var segSource: SegmentedControl
    private lateinit var segStandard: SegmentedControl
    private lateinit var segRadius: SegmentedControl
    private lateinit var segAreaSource: SegmentedControl
    private lateinit var segInterval: SegmentedControl
    private lateinit var segPolicy: SegmentedControl
    private lateinit var swArea: ToggleSwitch
    private lateinit var swAreaWorst: ToggleSwitch
    private lateinit var swDual: ToggleSwitch
    private lateinit var swLockFull: ToggleSwitch
    private lateinit var swLive: ToggleSwitch
    private lateinit var tvLiveState: TextView
    private lateinit var areaRadiusBlock: View
    private lateinit var radiusBlock: View
    private lateinit var radiusRankNote: View
    private lateinit var tokenBlock: View
    private lateinit var tokenInput: EditText
    private lateinit var coordInput: EditText
    private lateinit var standardDesc: TextView
    private lateinit var hintNotifDenied: TextView
    private lateinit var widgetDiag: TextView
    private lateinit var valLocationPerm: TextView
    private lateinit var valNotifPerm: TextView
    private lateinit var explainBlock: View
    private lateinit var explainChevron: android.widget.ImageView
    private lateinit var explainToggle: View

    private var explainOpen = false
    private var breath: ValueAnimator? = null
    private var lastAnimatedValue = Int.MIN_VALUE

    /** 当前渲染用的状态：点环上圆点时要从这里取区域坐标。 */
    private var todayState: AqiState? = null

    /** Worker 是异步的：刷新一结束就靠这个监听把结果刷到页面，不必等用户切后台再回来 */
    private val syncListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AqiPrefs.KEY_SYNC_AT || key == AqiPrefs.KEY_SYNC_MSG) {
            runOnUiThread { renderFromCache() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = AqiPrefs(this)
        binder = UiBinder(this)
        bindViews()
        applyInsets()
        wireTabs()
        wireControls()
        selectTab(if (savedInstanceState == null) 0 else savedInstanceState.getInt(KEY_TAB, 0))
        RefreshScheduler.ensurePeriodic(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    override fun onStart() {
        super.onStart()
        prefs.registerSyncListener(syncListener)
        renderFromCache()
    }

    override fun onStop() {
        // 常驻类应用在后台跑动画是红线：一退到后台立刻停
        breath?.cancel()
        heroGauge.alpha = 1f
        prefs.unregisterSyncListener(syncListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置回来时权限可能已变，重新读一次真实状态
        if (::tvLiveState.isInitialized) applyLiveState()
        renderFromCache()
        renderPermissionRows()
        renderWidgetDiag()
        // 打开 App 时如果上一轮已经超过刷新间隔，立刻补一次。
        // 国产 ROM 经常把 WorkManager 的周期任务压到几小时才跑一次，
        // 用户主动打开 App 是唯一可靠的「该刷新了」信号（失败也会更新时间，不会连环重试）。
        val age = System.currentTimeMillis() - prefs.lastSyncAtMillis
        if (prefs.lastSyncAtMillis == 0L || age > prefs.intervalMinutes * 60_000L) {
            RefreshScheduler.refreshNow(this)
        }
    }

    // ─────────────────────────── 视图绑定 ───────────────────────────

    private fun bindViews() {
        pageToday = findViewById(R.id.page_today)
        // 点九宫格看详情：坐标 / 方位 / 站名 / 数据时间。取不到坐标（旧缓存）时
        // AreaGridView 自己会返回 -1，这里再判一次 area.point() 为 null 就什么都不做。
        pageToday.findViewById<AreaGridView>(R.id.area_grid).onPointTap = { index ->
            todayState?.area?.let { AreaPointSheet.show(this, it, index) }
        }
        pageDetail = findViewById(R.id.page_detail)
        pageSettings = findViewById(R.id.page_settings)
        tabIcons = listOf(
            findViewById(R.id.tab_today_icon),
            findViewById(R.id.tab_detail_icon),
            findViewById(R.id.tab_settings_icon),
        )
        tabLabels = listOf(
            findViewById(R.id.tab_today_label),
            findViewById(R.id.tab_detail_label),
            findViewById(R.id.tab_settings_label),
        )

        heroGauge = pageToday.findViewById(R.id.hero_gauge)
        hero = pageToday.findViewById(R.id.hero)
        syncNote = pageToday.findViewById(R.id.aqi_sync_note)

        segSource = pageSettings.findViewById(R.id.seg_source)
        segStandard = pageSettings.findViewById(R.id.seg_standard)
        segRadius = pageSettings.findViewById(R.id.seg_radius)
        segAreaSource = pageSettings.findViewById(R.id.seg_area_source)
        segInterval = pageSettings.findViewById(R.id.seg_interval)
        segPolicy = pageSettings.findViewById(R.id.seg_policy)
        swArea = pageSettings.findViewById(R.id.sw_area)
        swAreaWorst = pageSettings.findViewById(R.id.sw_area_worst)
        swDual = pageSettings.findViewById(R.id.sw_dual_source)
        swLockFull = pageSettings.findViewById(R.id.sw_lock_full)
        areaRadiusBlock = pageSettings.findViewById(R.id.area_radius_block)
        radiusBlock = pageSettings.findViewById(R.id.radius_block)
        radiusRankNote = pageSettings.findViewById(R.id.radius_rank_note)
        tokenBlock = pageSettings.findViewById(R.id.token_block)
        tokenInput = pageSettings.findViewById(R.id.token_input)
        coordInput = pageSettings.findViewById(R.id.coord_input)
        standardDesc = pageSettings.findViewById(R.id.standard_desc)
        hintNotifDenied = pageSettings.findViewById(R.id.hint_notif_denied)
        widgetDiag = pageSettings.findViewById(R.id.widget_diag)
        valLocationPerm = pageSettings.findViewById(R.id.val_location_perm)
        valNotifPerm = pageSettings.findViewById(R.id.val_notif_perm)
        explainBlock = pageSettings.findViewById(R.id.explain_block)
        explainChevron = pageSettings.findViewById(R.id.explain_chevron)
        explainToggle = pageSettings.findViewById(R.id.explain_toggle)

        pageSettings.findViewById<TextView>(R.id.settings_sub).text = getString(
            R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
        )
        pageSettings.findViewById<TextView>(R.id.about_version).text = getString(
            R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
        )
    }

    /**
     * Android 15（targetSdk 35）强制 edge-to-edge：状态栏与导航栏不再由系统留白，
     * 不处理 inset 的话「今日」页的站点名会被状态栏压住、底栏会压在手势条上。
     * 三页都在同一个 host 里，所以只需给 host 加上边、给底栏加下边。
     */
    private fun applyInsets() {
        val host = findViewById<View>(R.id.page_host)
        val tabbar = findViewById<View>(R.id.tabbar)
        val baseTabBottom = (7 * resources.displayMetrics.density).toInt()
        val baseTabTop = (9 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.app_root)) { _, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            host.setPadding(0, bars.top, 0, 0)
            tabbar.setPadding(tabbar.paddingLeft, baseTabTop, tabbar.paddingRight, baseTabBottom + bars.bottom)
            insets
        }
    }

    // ─────────────────────────── 底栏 ───────────────────────────

    private var currentTab = 0

    private fun wireTabs() {
        val ids = listOf(R.id.tab_today, R.id.tab_detail, R.id.tab_settings)
        ids.forEachIndexed { i, id -> findViewById<View>(id).setOnClickListener { selectTab(i) } }
    }

    private fun selectTab(index: Int) {
        currentTab = index
        val pages = listOf(pageToday, pageDetail, pageSettings)
        pages.forEachIndexed { i, p ->
            p.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        tabIcons.forEachIndexed { i, iv ->
            iv.setColorFilter(ContextCompat.getColor(this, if (i == index) R.color.text_primary else R.color.text_dim))
        }
        tabLabels.forEachIndexed { i, tv ->
            tv.setTextColor(ContextCompat.getColor(this, if (i == index) R.color.text_primary else R.color.text_dim))
            tv.setTypeface(null, if (i == index) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    // ─────────────────────────── 设置控件 ───────────────────────────

    private fun wireControls() {
        pageToday.findViewById<View>(R.id.foot_link).setOnClickListener { selectTab(1) }

        pageToday.findViewById<View>(R.id.btn_refresh).setOnClickListener {
            markFetching()
            RefreshScheduler.refreshNow(this)
        }

        // ── 数据源 ──
        segSource.setItems(listOf(getString(R.string.source_seg_om), getString(R.string.source_seg_waqi)))
        segSource.select(if (prefs.primarySource == AqiSource.WAQI_RECALC) 1 else 0)
        segSource.onSelect = { i ->
            val next = if (i == 1) AqiSource.WAQI_RECALC else AqiSource.OM
            if (next != prefs.primarySource) {
                prefs.primarySource = next
                prefs.recordSync(false, getString(R.string.sync_switched_source, next.shortLabel(this)))
                renderFromCache()
                RefreshScheduler.refreshNow(this)
            }
        }

        // ── 显示标准 ──
        segStandard.setItems(listOf(getString(R.string.standard_seg_us), getString(R.string.standard_seg_cn)))
        segStandard.select(if (prefs.standard == AqiStandard.CN) 1 else 0)
        segStandard.onSelect = { i ->
            val next = if (i == 1) AqiStandard.CN else AqiStandard.US
            if (next != prefs.standard) {
                prefs.standard = next
                prefs.recordSync(false, getString(R.string.sync_switched, next.fullLabel(this)))
                renderFromCache()
                RefreshScheduler.refreshNow(this)
            }
        }

        // ── 周边数据源（v0.4.5）──
        // 三项：跟随主源 / Open-Meteo / WAQI 站点。默认跟随主源 —— 用户选了 WAQI 当主源，
        // 环上就该是真实站点；选了 OM，环上就该是模式格点。单独指定是为了两种情况：
        // ① 主源用 OM（省流量），但想看看周边真实站点叫什么；
        // ② 主源用 WAQI，但不想每次刷新多发 8 个请求。
        val areaSourceValues = listOf<AqiSource?>(null, AqiSource.OM, AqiSource.WAQI_RECALC)
        segAreaSource.setItems(
            listOf(
                getString(R.string.area_src_follow),
                getString(R.string.source_seg_om),
                getString(R.string.source_seg_waqi),
            ),
        )
        segAreaSource.select(areaSourceValues.indexOf(prefs.areaSource).coerceAtLeast(0))
        segAreaSource.onSelect = { i ->
            val next = areaSourceValues[i]
            if (next != prefs.areaSource) {
                // 选了站点环却还没填 Token：不静默退化成格点（那会让用户以为
                // 「这几个站就是这个值」），也不留一个永远空着的设置，直接撤销这次点击。
                if (next == AqiSource.WAQI_RECALC && prefs.token.isBlank()) {
                    toast(R.string.toast_area_need_token)
                    segAreaSource.select(areaSourceValues.indexOf(prefs.areaSource).coerceAtLeast(0))
                } else {
                    prefs.areaSource = next
                    applyRadiusUi()
                    // 缓存里那份是另一种口径的统计：Kind 校验会把它抹掉，确认要重拉
                    renderFromCache()
                    RefreshScheduler.refreshNow(this)
                }
            }
        }

        // ── 周边半径 ──
        // 3 km 档在 v0.4.2 移除：国境内模式分辨率约 45 公里，3 公里圆周上的
        // 8 个点全在同一个格子里，实测 9 个数值完全相同 —— 那个档位等于没开。
        val radiusValues = AqiPrefs.RADIUS_CHOICES
        segRadius.setItems(
            listOf(
                getString(R.string.radius_seg_10),
                getString(R.string.radius_seg_25),
                getString(R.string.radius_seg_50),
            ),
        )
        segRadius.select(radiusValues.indexOf(prefs.areaRadiusKm).coerceAtLeast(0))
        segRadius.onSelect = { i ->
            val km = radiusValues[i]
            if (km != prefs.areaRadiusKm) {
                prefs.areaRadiusKm = km
                // 半径变了但缓存里还是旧半径的统计：直接重拉，绝不拿 25 km 的中位数冒充 10 km
                RefreshScheduler.refreshNow(this)
            }
        }

        swArea.setChecked(prefs.areaEnabled)
        areaRadiusBlock.visibility = if (prefs.areaEnabled) View.VISIBLE else View.GONE
        swArea.onCheckedChange = { checked ->
            if (checked != prefs.areaEnabled) {
                prefs.areaEnabled = checked
                areaRadiusBlock.visibility = if (checked) View.VISIBLE else View.GONE
                renderFromCache()
                if (checked) RefreshScheduler.refreshNow(this)
            }
        }

        // 取值口径：中位数（默认）/ 最差格点。切换后同样要重拉 —— 缓存里那份
        // 统计是中位数算出来的，直接拿它冒充「最差」比不显示更糟。
        swAreaWorst.setChecked(prefs.areaWorst)
        swAreaWorst.onCheckedChange = { checked ->
            if (checked != prefs.areaWorst) {
                prefs.areaWorst = checked
                renderFromCache()
                RefreshScheduler.refreshNow(this)
            }
        }

        // ── 刷新间隔 ──
        val intervalValues = listOf(30, 60, 120)
        segInterval.setItems(
            listOf(
                getString(R.string.interval_seg_30),
                getString(R.string.interval_seg_60),
                getString(R.string.interval_seg_120),
            ),
        )
        segInterval.select(intervalValues.indexOf(prefs.intervalMinutes).coerceAtLeast(0))
        segInterval.onSelect = { i ->
            val minutes = intervalValues[i]
            if (minutes != prefs.intervalMinutes) {
                prefs.intervalMinutes = minutes
                RefreshScheduler.reschedule(this)
            }
        }

        // ── 两源对照 ──
        swDual.setChecked(prefs.dualSourceEnabled)
        swDual.onCheckedChange = { checked ->
            if (checked != prefs.dualSourceEnabled) {
                prefs.dualSourceEnabled = checked
                renderFromCache()
                if (checked) RefreshScheduler.refreshNow(this)
            }
        }

        // ── Token / 坐标 ──
        tokenInput.setText(prefs.token)
        coordInput.setText(prefs.manualCoord)

        pageSettings.findViewById<View>(R.id.btn_save).setOnClickListener {
            val token = tokenInput.text.toString()
            if (token.isBlank()) {
                toast(R.string.toast_token_empty)
                return@setOnClickListener
            }
            prefs.token = token
            markFetching()
            toast(R.string.toast_saved)
            RefreshScheduler.refreshNow(this)
        }

        pageSettings.findViewById<View>(R.id.btn_save_coord).setOnClickListener {
            prefs.manualCoord = coordInput.text.toString()
            markFetching()
            toast(R.string.toast_coord_saved)
            RefreshScheduler.refreshNow(this)
        }

        // ── 通知时机 ──
        segPolicy.setItems(
            listOf(
                getString(R.string.policy_seg_alert),
                getString(R.string.policy_seg_always),
                getString(R.string.policy_seg_never),
            ),
        )
        segPolicy.select(
            when (prefs.notifPolicy) {
                NotifPolicy.ALERT_ONLY -> 0
                NotifPolicy.ALWAYS -> 1
                NotifPolicy.NEVER -> 2
            },
        )
        segPolicy.onSelect = { i ->
            val next = when (i) {
                1 -> NotifPolicy.ALWAYS
                2 -> NotifPolicy.NEVER
                else -> NotifPolicy.ALERT_ONLY
            }
            prefs.notifPolicy = next
            if (next == NotifPolicy.NEVER) {
                AqiNotifier(this).cancel()
            } else {
                // 立刻按新策略重算一次可见性：优/良时会被 cancel 掉，不必等下一轮轮询
                RefreshScheduler.refreshNow(this)
                renderFromCache()
            }
        }

        swLockFull.setChecked(prefs.lockScreenFull)
        swLockFull.onCheckedChange = { checked ->
            prefs.lockScreenFull = checked
            RefreshScheduler.refreshNow(this)
        }

        swLive = pageSettings.findViewById(R.id.sw_live)
        tvLiveState = pageSettings.findViewById(R.id.tv_live_state)
        swLive.setChecked(prefs.liveUpdate)
        swLive.onCheckedChange = { checked ->
            prefs.liveUpdate = checked
            // 当场重发一次，开关的效果立刻可见，不用等下一个刷新周期
            RefreshScheduler.refreshNow(this)
            applyLiveState()
        }
        // 未授权时点这行直接跳系统设置；其余状态点了没反应，不假装可点
        // 两种「没生效」要把用户送到两个不同的页面：应用级没授权 → 应用的通知页；
        // 已授权但锁屏没东西 → 渠道页，那个 Live Alerts 开关就在里面。
        tvLiveState.setOnClickListener {
            if (!LiveUpdate.supported) return@setOnClickListener
            if (!LiveUpdate.canPost(this)) {
                openPromotedSettings()
            } else if (LiveUpdate.promotedState(this, AqiNotifier.NOTIF_ID) == true) {
                openChannelSettings()
            }
        }
        applyLiveState()

        // ── 说明与口径（默认收起）──
        explainToggle.setOnClickListener {
            explainOpen = !explainOpen
            explainBlock.visibility = if (explainOpen) View.VISIBLE else View.GONE
            explainChevron.rotation = if (explainOpen) 90f else 0f
            explainToggleLabel(explainOpen)
        }

        // ── 权限 ──
        pageSettings.findViewById<View>(R.id.btn_location_perm).setOnClickListener { requestLocationPermission() }
        pageSettings.findViewById<View>(R.id.btn_notif_perm).setOnClickListener { requestNotificationPermission() }
        pageSettings.findViewById<View>(R.id.btn_battery).setOnClickListener { openBatterySettings() }

        wirePinButtons()
    }

    /**
     * 半径那一组是否适用。
     *
     * 站点模式取的是「最近的 9 个」，压根没有半径 —— 留着 10/25/50 三个选项，
     * 用户调了没反应，会以为是 bug（真机上就是这么被发现的：半径 10 km 时
     * 探测点全在 10 km 外，回来 2 个站还被半径滤掉，整块退回格点）。
     * 所以整组藏掉，换成一句说明。
     */
    private fun applyRadiusUi(std: AqiStandard = prefs.standard) {
        val source = if (std == AqiStandard.CN) {
            AqiSource.OM
        } else {
            prefs.areaSource ?: prefs.primarySource
        }
        val rankMode = source == AqiSource.WAQI_RECALC
        radiusBlock.visibility = if (rankMode) View.GONE else View.VISIBLE
        radiusRankNote.visibility = if (rankMode) View.VISIBLE else View.GONE
    }

    private fun explainToggleLabel(open: Boolean) {
        val row = explainToggle as LinearLayout
        val title = row.getChildAt(0) as? TextView ?: return
        title.setText(if (open) R.string.explain_toggle_less else R.string.explain_toggle_more)
    }

    /** 标准说明与区块可见性：CN 固定单源，隐藏数据源与两源对照。 */
    private fun renderStandardDesc(std: AqiStandard) {
        // 行内只放一句结论；断点数字与口径全文收进「关于 → 说明与口径」，
        // 否则设置页第一屏就被 5 行技术文档占满（v0.4.0 第一版截图就是这样）。
        standardDesc.text = getString(
            if (std == AqiStandard.CN) R.string.standard_short_cn else R.string.standard_short_us,
        )
        pageSettings.findViewById<TextView>(R.id.about_standard).text = getString(
            if (std == AqiStandard.CN) R.string.standard_desc_cn else R.string.standard_desc_us,
        )
        val isUs = std == AqiStandard.US
        segSource.visibility = if (isUs) View.VISIBLE else View.GONE
        swDual.visibility = if (isUs) View.VISIBLE else View.GONE
        // 国标模式下周边固定 OM（WAQI 分指数是美标口径），选择项没有意义，直接收起
        segAreaSource.visibility = if (isUs) View.VISIBLE else View.GONE
        applyRadiusUi(std)
        tokenBlock.visibility = if (isUs &&
            (prefs.dualSourceEnabled || prefs.primarySource == AqiSource.WAQI_RECALC)
        ) View.VISIBLE else View.GONE
    }

    private fun wirePinButtons() {
        val targets = mapOf(
            R.id.btn_pin_main to AqiWidgetMain::class.java,
            R.id.btn_pin_compact to AqiWidgetCompact::class.java,
            R.id.btn_pin_tall to AqiWidgetMiniTall::class.java,
            R.id.btn_pin_square to AqiWidgetMiniSquare::class.java,
        )
        targets.forEach { (id, cls) ->
            pageSettings.findViewById<View>(id).setOnClickListener { pinWidget(cls) }
        }
    }

    private fun pinWidget(provider: Class<*>) {
        val mgr = AppWidgetManager.getInstance(this)
        if (!mgr.isRequestPinAppWidgetSupported) {
            toast(R.string.toast_pin_unsupported)
            return
        }
        val ok = try {
            mgr.requestPinAppWidget(ComponentName(this, provider), null, null)
        } catch (e: Exception) {
            false
        }
        toast(if (ok) R.string.toast_pin_ok else R.string.toast_pin_failed)
    }

    /** 用系统真实枚举到的 provider 数量做诊断，区分「没注册」和「选择器找不到」。 */
    private fun renderWidgetDiag() {
        val count = try {
            AppWidgetManager.getInstance(this)
                .installedProviders.count { it.provider.packageName == packageName }
        } catch (e: Exception) {
            0
        }
        widgetDiag.text = if (count > 0) getString(R.string.widget_diag, count)
        else getString(R.string.widget_diag_zero)
    }

    private fun renderPermissionRows() {
        val locOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }
        val green = ContextCompat.getColor(this, R.color.aqi_good_ink)
        val alarm = ContextCompat.getColor(this, R.color.aqi_usg_ink)

        valLocationPerm.text = getString(if (locOk) R.string.perm_granted else R.string.perm_denied)
        valLocationPerm.setTextColor(if (locOk) green else alarm)
        valNotifPerm.text = getString(if (notifOk) R.string.perm_granted else R.string.perm_denied)
        valNotifPerm.setTextColor(if (notifOk) green else alarm)
        hintNotifDenied.visibility = if (notifOk) View.GONE else View.VISIBLE
    }

    /** 立刻把「正在获取」写进同步状态，避免按钮点了看起来没反应 */
    private fun markFetching() {
        prefs.recordSync(false, getString(R.string.sync_getting))
        renderFromCache()
    }

    // ─────────────────────────── 渲染 ───────────────────────────

    private fun renderFromCache() {
        val std = prefs.standard
        val cache = AqiCache(this)
        val tokenBlank = prefs.token.isBlank()
        val s: AqiState = cache.loadForDisplay(prefs.intervalMinutes, std, tokenBlank)

        renderStandardDesc(std)
        todayState = s
        binder.bindToday(pageToday, s, prefs.areaEnabled)
        binder.bindDetail(pageDetail, s)

        // 详情页的对照行：主源之外的另一个源。没有值就整行隐藏，不显示占位假数
        val dual = pageDetail.findViewById<TextView>(R.id.detail_dual)
        val other = AqiSource.other(prefs.primarySource)
        val sec = if (std == AqiStandard.US && prefs.dualSourceEnabled) {
            cache.loadSecondaryForDisplay(prefs.intervalMinutes, std, tokenBlank)
        } else null
        if (sec != null && sec.hasValue && sec.value >= 0) {
            val t = MaskTier.of(sec.value)
            dual.text = getString(
                R.string.dual_secondary, other.shortLabel(this), sec.value,
                t?.symbol ?: "–", t?.let { getString(it.labelRes) } ?: "",
            )
            dual.visibility = View.VISIBLE
        } else {
            dual.visibility = View.GONE
        }

        // 状态条：完整原因（成功 / 没权限 / 没位置 / Token 无效 / 网络失败）始终可见
        val msg = prefs.lastSyncMessage
        syncNote.text = msg
        syncNote.setTextColor(
            ContextCompat.getColor(this, if (prefs.lastSyncOk) R.color.text_secondary else R.color.aqi_usg_ink),
        )
        syncNote.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE

        applyMotion(s)
    }

    /**
     * 动效只有两件事，都是「信号」不是「装饰」：
     *
     * 1. 报警呼吸：AQI ≥ 101 且数据新鲜时，环在 0.62↔1.00 之间 2.2 s 循环 ——
     *    这一屏大部分时间是静态图，呼吸是唯一能说明「还在更新」的线索，且只在该警惕时出现。
     * 2. 数值变化：环从 0.94 缩放回 1.00，320 ms —— 刷新完成时给一个落定感。
     *
     * 为什么删掉 v0.3.0 的 HazeView（20fps 自绘雾霾）：它铺满整张主卡，
     * 在需要看清「152 还是 120」的场景里反而降低可读性；这一版换成渐变底 + 干净文字。
     */
    private fun applyMotion(s: AqiState) {
        val alert = s.hasValue && !s.isStale && s.value >= 101
        if (alert) {
            if (breath == null) {
                breath = ValueAnimator.ofFloat(0.62f, 1f).apply {
                    duration = 2200
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { heroGauge.alpha = it.animatedValue as Float }
                }
            }
            if (breath?.isRunning != true) breath?.start()
        } else {
            breath?.cancel()
            heroGauge.alpha = 1f
        }

        if (s.value != lastAnimatedValue) {
            lastAnimatedValue = s.value
            heroGauge.animate().cancel()
            heroGauge.scaleX = 0.94f
            heroGauge.scaleY = 0.94f
            heroGauge.animate().scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    // ─────────────────────────── 权限与系统跳转 ───────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS,
            )
        } else {
            openNotificationSettings()
        }
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            REQ_LOCATION,
        )
    }

    private fun openNotificationSettings() = startActivityOrToast(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    )

    /**
     * 系统到底认不认这条通知 —— 三件事必须分开看：
     * 系统有没有这套机制、用户允许不允许、发出去之后有没有**真的**被提升。
     * 只写一句「已开启」没有意义：请求发了、权限也有，系统仍可能因为类别不符
     * 或 ROM 白名单而拒绝，那条通知压根没上过息屏。
     */
    private fun applyLiveState() {
        if (!prefs.liveUpdate) {
            tvLiveState.text = getString(R.string.live_state_off)
            return
        }
        tvLiveState.text = when {
            !LiveUpdate.supported -> getString(R.string.live_state_unsupported)
            !LiveUpdate.canPost(this) -> getString(R.string.live_state_denied)
            else -> when (LiveUpdate.promotedState(this, AqiNotifier.NOTIF_ID)) {
                true -> getString(R.string.live_state_ok)
                false -> getString(R.string.live_state_pending)
                null -> getString(R.string.live_state_no_notif)
            }
        }
    }

    /**
     * 跳系统的「实时通知」授权页。action 是 API 36 才有的常量，
     * 用反射取，避免为了一个字符串把整个工程升到 compileSdk 36。
     */
    /**
     * 渠道级开关（`Show Live Updates on Live Alerts`）没有公开 API 可读，
     * 唯一能做的是把用户送到它所在的那一页。
     */
    private fun openChannelSettings() = startActivityOrToast(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, AqiNotifier.CHANNEL_ID),
    )

    private fun openPromotedSettings() {
        val action = runCatching {
            Settings::class.java
                .getField("ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS")
                .get(null) as String
        }.getOrNull()
        if (action != null) {
            startActivityOrToast(Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else {
            openNotificationSettings()
        }
    }

    // 只打开系统列表让用户自行设置，不申请 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 这个 Play 敏感权限
    private fun openBatterySettings() = startActivityOrToast(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    private fun startActivityOrToast(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast(R.string.toast_settings_unavailable)
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderPermissionRows()
        // 授权后立刻拉一次，让通知/小部件尽快出现，而不是等下一个周期
        markFetching()
        RefreshScheduler.refreshNow(this)
    }

    private companion object {
        const val REQ_NOTIFICATIONS = 100
        const val REQ_LOCATION = 101
        const val KEY_TAB = "tab"
    }
}
