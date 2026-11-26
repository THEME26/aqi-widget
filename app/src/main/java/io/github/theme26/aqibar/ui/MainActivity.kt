package io.github.theme26.aqibar.ui

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
import androidx.core.view.isVisible
import io.github.theme26.aqibar.BuildConfig
import io.github.theme26.aqibar.R
import io.github.theme26.aqibar.core.AqiLevel
import io.github.theme26.aqibar.core.AqiSource
import io.github.theme26.aqibar.core.AqiStandard
import io.github.theme26.aqibar.core.AqiState
import io.github.theme26.aqibar.core.MaskTier
import io.github.theme26.aqibar.core.NotifPolicy
import io.github.theme26.aqibar.data.AqiCache
import io.github.theme26.aqibar.data.AqiPrefs
import io.github.theme26.aqibar.live.LiveUpdate
import io.github.theme26.aqibar.render.AqiNotifier
import io.github.theme26.aqibar.widget.AqiWidgetCompact
import io.github.theme26.aqibar.widget.AqiWidgetMain
import io.github.theme26.aqibar.widget.AqiWidgetMiniSquare
import io.github.theme26.aqibar.widget.AqiWidgetMiniTall
import io.github.theme26.aqibar.worker.RefreshScheduler

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

    private var todayState: AqiState? = null

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
        breath?.cancel()
        heroGauge.alpha = 1f
        prefs.unregisterSyncListener(syncListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::tvLiveState.isInitialized) applyLiveState()
        renderFromCache()
        renderPermissionRows()
        renderWidgetDiag()
        val age = System.currentTimeMillis() - prefs.lastSyncAtMillis
        if (prefs.lastSyncAtMillis == 0L || age > prefs.intervalMinutes * 60_000L) {
            RefreshScheduler.refreshNow(this)
        }
    }

    private fun bindViews() {
        pageToday = findViewById(R.id.page_today)
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

    private fun wireControls() {
        pageToday.findViewById<View>(R.id.foot_link).setOnClickListener { selectTab(1) }

        pageToday.findViewById<View>(R.id.btn_refresh).setOnClickListener {
            markFetching()
            RefreshScheduler.refreshNow(this)
        }

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
                if (next == AqiSource.WAQI_RECALC && prefs.token.isBlank()) {
                    toast(R.string.toast_area_need_token)
                    segAreaSource.select(areaSourceValues.indexOf(prefs.areaSource).coerceAtLeast(0))
                } else {
                    prefs.areaSource = next
                    applyRadiusUi()
                    renderFromCache()
                    RefreshScheduler.refreshNow(this)
                }
            }
        }

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

        swAreaWorst.setChecked(prefs.areaWorst)
        swAreaWorst.onCheckedChange = { checked ->
            if (checked != prefs.areaWorst) {
                prefs.areaWorst = checked
                renderFromCache()
                RefreshScheduler.refreshNow(this)
            }
        }

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

        swDual.setChecked(prefs.dualSourceEnabled)
        swDual.onCheckedChange = { checked ->
            if (checked != prefs.dualSourceEnabled) {
                prefs.dualSourceEnabled = checked
                renderFromCache()
                if (checked) RefreshScheduler.refreshNow(this)
            }
        }

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
        pageSettings.findViewById<View>(R.id.row_live).isVisible = LiveUpdate.supported
        swLive.setChecked(prefs.liveUpdate)
        swLive.onCheckedChange = { checked ->
            prefs.liveUpdate = checked
            repostNotification()
            RefreshScheduler.refreshNow(this)
            applyLiveState()
        }
        tvLiveState.setOnClickListener {
            if (!LiveUpdate.supported) return@setOnClickListener
            if (!LiveUpdate.canPost(this)) {
                openPromotedSettings()
            } else if (LiveUpdate.promotedState(this, AqiNotifier.NOTIF_ID) == true) {
                openChannelSettings()
            }
        }
        applyLiveState()

        explainToggle.setOnClickListener {
            explainOpen = !explainOpen
            explainBlock.visibility = if (explainOpen) View.VISIBLE else View.GONE
            explainChevron.rotation = if (explainOpen) 90f else 0f
            explainToggleLabel(explainOpen)
        }

        pageSettings.findViewById<View>(R.id.btn_location_perm).setOnClickListener { requestLocationPermission() }
        pageSettings.findViewById<View>(R.id.btn_notif_perm).setOnClickListener { requestNotificationPermission() }
        pageSettings.findViewById<View>(R.id.btn_battery).setOnClickListener { openBatterySettings() }

        wirePinButtons()
    }

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

    private fun renderStandardDesc(std: AqiStandard) {
        standardDesc.text = getString(
            if (std == AqiStandard.CN) R.string.standard_short_cn else R.string.standard_short_us,
        )
        pageSettings.findViewById<TextView>(R.id.about_standard).text = getString(
            if (std == AqiStandard.CN) R.string.standard_desc_cn else R.string.standard_desc_us,
        )
        val isUs = std == AqiStandard.US
        segSource.visibility = if (isUs) View.VISIBLE else View.GONE
        swDual.visibility = if (isUs) View.VISIBLE else View.GONE
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

    private fun markFetching() {
        prefs.recordSync(false, getString(R.string.sync_getting))
        renderFromCache()
    }

    private fun repostNotification() {
        val s = AqiCache(this).loadForDisplay(prefs.intervalMinutes, prefs.standard, prefs.token.isBlank())
        if (!s.hasValue) return
        val notifier = AqiNotifier(this)
        notifier.cancel()
        notifier.show(s)
    }

    private fun renderFromCache() {
        val std = prefs.standard
        val cache = AqiCache(this)
        val tokenBlank = prefs.token.isBlank()
        val s: AqiState = cache.loadForDisplay(prefs.intervalMinutes, std, tokenBlank)

        renderStandardDesc(std)
        todayState = s
        binder.bindToday(pageToday, s, prefs.areaEnabled)
        binder.bindDetail(pageDetail, s)

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

        val msg = prefs.lastSyncMessage
        syncNote.text = msg
        syncNote.setTextColor(
            ContextCompat.getColor(this, if (prefs.lastSyncOk) R.color.text_secondary else R.color.aqi_usg_ink),
        )
        syncNote.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE

        applyMotion(s)
    }

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
        markFetching()
        RefreshScheduler.refreshNow(this)
    }

    private companion object {
        const val REQ_NOTIFICATIONS = 100
        const val REQ_LOCATION = 101
        const val KEY_TAB = "tab"
    }
}
