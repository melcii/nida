package app.nida

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener {

    private lateinit var createdLang: String
    private var tab = SIMPLE
    private val clock = Handler(Looper.getMainLooper())
    private var locating = false
    private var lastLocate = 0L
    private var askedOnce = false
    private var drawerOpen = false
    private var resumed = false

    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val rotationSensor by lazy { sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    private var compassOn = false
    private var heading = Float.NaN
    private var declination = 0f
    private var wasFacing = false

    private val tabViews by lazy {
        listOf<View>(find(R.id.viewSimple), find(R.id.viewDetailed), find(R.id.viewQibla), find(R.id.viewCalendar), find(R.id.viewCompact))
    }
    private val tabButtons by lazy {
        listOf<TextView>(find(R.id.tabSimple), find(R.id.tabDetailed), find(R.id.tabQibla), find(R.id.tabCalendar), find(R.id.tabCompact))
    }

    // Location and compass health, for the Qibla tab.
    private var gpsWeak = false
    private var compassAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var calibShown = false

    // Calendar tab.
    private val month by lazy { MonthView(this, find(R.id.monthGrid)) { pickDay(it) } }
    private var shownMonth: YearMonth? = null
    private var pickedDay: LocalDate? = null

    // Compact tab: the picked block, kept across the minute redraws.
    private var compactPick: Pair<Long, Int>? = null

    private fun <T : View> find(id: Int): T = findViewById(id)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createdLang = Lang.current(this)
        tab = Store.tab(this)

        val drawer = find<View>(R.id.drawer)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            find<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                find<View>(R.id.scroll).setPadding(bars.left, bars.top, bars.right, bars.bottom)
                drawer.setPadding(dp(16) + bars.left, bars.top, dp(16), bars.bottom)
                insets
            }
        }

        find<View>(R.id.menu).setOnClickListener { openDrawer() }
        find<View>(R.id.scrim).setOnClickListener { closeDrawer() }
        find<View>(R.id.openSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        find<View>(R.id.addCity).setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, CitySearchActivity::class.java))
        }
        find<View>(R.id.allow).setOnClickListener { askForLocation() }
        find<View>(R.id.chooseCity).setOnClickListener { startActivity(Intent(this, CitySearchActivity::class.java)) }
        find<View>(R.id.calibLink).setOnClickListener {
            calibShown = !calibShown
            renderCalibration()
        }
        find<View>(R.id.prevMonth).setOnClickListener { shownMonth = shownMonth?.minusMonths(1); renderCalendar() }
        find<View>(R.id.nextMonth).setOnClickListener { shownMonth = shownMonth?.plusMonths(1); renderCalendar() }
        tabButtons.forEachIndexed { i, b -> b.setOnClickListener { showTab(i, animate = true) } }

        find<DayChart>(R.id.dayChart).onPick = { p ->
            compactPick = p.from to p.row
            showPick(p)
        }
        find<NightBar>(R.id.nightBar).marks = floatArrayOf(1 / 3f, 0.5f, 2 / 3f)
        showTab(tab, animate = false)
        render()
        if (savedInstanceState == null) {
            Motion.stagger(listOf(find(R.id.heading), find(R.id.tabs), find(R.id.stage), find(R.id.footer)), delay = 60, step = 90)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (Lang.current(this) != createdLang) return recreate()
        render()
        refreshData()
        tick()
        startCompass()
        Reminders.schedule(this)
        askForNotifications()
    }

    /** Once, after there are times to notify about: Android 13+ needs the user's OK to show notifications. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33 || !Reminders.on(this) || Reminders.allowed(this)) return
        if (Places.selected(this) == null || Store.prefs(this).getBoolean("asked_notify", false)) return
        Store.prefs(this).edit().putBoolean("asked_notify", true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), ASK_NOTIFY)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        clock.removeCallbacksAndMessages(null)
        stopCompass()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    /** Redraws at the start of every minute while the app is open. */
    private fun tick() {
        clock.removeCallbacksAndMessages(null)
        val now = System.currentTimeMillis()
        clock.postDelayed({
            render()
            tick()
        }, 60_000 - now % 60_000 + 200)
    }

    // ---- data -------------------------------------------------------------------------------

    /** Takes one location fix when showing where you are. Cities need nothing: their times are calculated. */
    private fun refreshData(force: Boolean = false) {
        if (Places.selectedId(this) != Places.HERE && Places.selected(this) != null) return
        if (!Locator.granted(this) || locating) return
        if (!force && System.currentTimeMillis() - lastLocate < 60_000) return
        locating = true
        lastLocate = System.currentTimeMillis()
        render()
        Refresh.here(this, done = { fresh ->
            locating = false
            gpsWeak = !fresh && Locator.enabled(this)
            if (!isDestroyed) render()
        })
    }

    private fun askForLocation() {
        val blocked = askedOnce && !shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (blocked) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            askedOnce = true
            requestPermissions(Locator.permissions, 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == ASK_NOTIFY) return Reminders.schedule(this)
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            Places.select(this, Places.HERE)
            render()
            refreshData(force = true)
        }
    }

    private fun setStatus(text: String?) {
        find<TextView>(R.id.status).apply {
            visibility = if (text == null) View.GONE else View.VISIBLE
            this.text = text
        }
    }

    // ---- drawing ----------------------------------------------------------------------------

    private fun render() {
        val place = Places.selected(this)
        val empty = place == null
        // While the first fix is on its way, just say so; the card comes back only if it fails.
        find<View>(R.id.viewEmpty).visibility = if (empty && !(locating && Locator.granted(this))) View.VISIBLE else View.GONE
        find<View>(R.id.tabs).visibility = if (empty) View.GONE else View.VISIBLE
        find<View>(R.id.heading).visibility = if (empty) View.GONE else View.VISIBLE
        find<TextView>(R.id.footer).text = getString(
            R.string.footer, Store.methodName(this),
            getString(if (Store.school(this) == 1) R.string.asr_hanafi else R.string.asr_standard),
        )
        if (place == null) {
            tabViews.forEach { it.visibility = View.GONE }
            setStatus(if (locating) getString(R.string.locating) else null)
            return
        }

        find<TextView>(R.id.placeName).text = place.name.ifEmpty { getString(R.string.current_location) }
        val schedule = Times.get(this, place)
        val zone = schedule.zone
        find<TextView>(R.id.placeLabel).text = when {
            place.isHere -> getString(R.string.current_location)
            zone.rules.getOffset(java.time.Instant.now()) != ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()) ->
                listOf(place.detail, getString(R.string.local_time, Fmt.time(this, System.currentTimeMillis(), zone)))
                    .filter { it.isNotEmpty() }.joinToString(" · ")
            else -> place.detail
        }

        renderQibla(place)
        val m = Moment(schedule)
        setStatus(null)
        tabViews.forEachIndexed { i, v -> if (i == tab) v.visibility = View.VISIBLE }

        val hijriDay = m.hijri!!
        find<TextView>(R.id.hijri).text = Fmt.hijri(this, hijriDay).let {
            if (hijriDay !== m.day) getString(R.string.hijri_from_maghrib, it) else it
        }
        find<TextView>(R.id.gregorian).text = Fmt.date(this, m.today)
        if (m.day!!.online) find<TextView>(R.id.footer).append(" · " + getString(R.string.source_online))
        renderSimple(m)
        renderDetailed(m)
        renderCompact(schedule, m.now)
        renderCalendar()
    }

    private fun renderSimple(m: Moment) {
        val zone = m.schedule.zone
        find<TextView>(R.id.nextLabel).text = getString(R.string.next_in, Fmt.prayer(this, m.next))
        find<TextView>(R.id.nextLeft).text = Fmt.left(this, m.nextAt - m.now)
        find<TextView>(R.id.nextAt).text = getString(R.string.at_time, Fmt.time(this, m.nextAt, zone))
        find<NightBar>(R.id.simpleBar).progress =
            ((m.now - m.previousAt).toFloat() / (m.nextAt - m.previousAt)).coerceIn(0f, 1f)
        find<TextView>(R.id.since).text = getString(R.string.since, Fmt.prayer(this, m.previous), Fmt.time(this, m.previousAt, zone))
    }

    private fun renderDetailed(m: Moment) {
        val zone = m.schedule.zone
        val day = m.day!!

        fun row(group: ViewGroup, name: String, time: String, note: String?, highlight: Boolean, past: Boolean) =
            timeRow(group, name, time, note, highlight, past)

        val times = find<LinearLayout>(R.id.timesGroup)
        times.removeAllViews()
        // The time we are in is marked; the next one says how long until it starts.
        for (i in 0..5) {
            val note = when {
                i == m.current -> getString(R.string.now)
                m.nextIndex == i -> getString(R.string.compact_in, Fmt.left(this, m.nextAt - m.now))
                else -> null
            }
            row(times, Fmt.prayer(this, i), Fmt.time(this, day.t[i], zone), note, i == m.current, day.t[i] <= m.now && i != m.current)
        }

        // The night: Maghrib to Fajr, in thirds.
        val p = m.nightProgress
        val title = find<TextView>(R.id.nightTitle)
        val sub = find<TextView>(R.id.nightSub)
        val bar = find<NightBar>(R.id.nightBar)
        val range = getString(R.string.night_range, Fmt.time(this, m.nightStart, zone), Fmt.time(this, m.nightEnd, zone))
        if (m.inNight) {
            title.text = getString(when {
                p < 1 / 3f -> R.string.night_first
                p < 0.5f -> R.string.night_second_a
                p < 2 / 3f -> R.string.night_second_b
                else -> R.string.night_last
            })
            sub.text = getString(R.string.night_passed, (p * 100).toInt()) + " · " + range
            bar.progress = p
        } else {
            title.text = getString(R.string.night_day)
            sub.text = range
            bar.progress = -1f
        }
        val night = find<LinearLayout>(R.id.nightGroup)
        night.removeAllViews()
        listOf(1 / 3.0 to R.string.first_third_ends, 0.5 to R.string.midnight, 2 / 3.0 to R.string.last_third)
            .forEach { (f, label) ->
                val at = m.nightMark(f)
                row(night, getString(label), Fmt.time(this, at, zone), null, false, m.inNight && at <= m.now)
            }

        val forbidden = find<LinearLayout>(R.id.forbiddenGroup)
        forbidden.removeAllViews()
        val names = listOf(R.string.forbidden_sunrise, R.string.forbidden_zenith, R.string.forbidden_sunset)
        m.forbidden.forEachIndexed { i, (from, to) ->
            val now = m.now in from until to
            row(forbidden, getString(names[i]),
                Fmt.time(this, from, zone) + " – " + Fmt.time(this, to, zone),
                if (now) getString(R.string.now) else null, now, to <= m.now)
        }
    }

    // ---- compact ----------------------------------------------------------------------------

    private var compactZone: ZoneId = ZoneId.systemDefault()
    private var compactNow = 0L

    /**
     * The whole day on one timeline, from just before Fajr to just before the next Fajr.
     * Until half an hour before Fajr, the night still belongs to yesterday's chart.
     */
    private fun renderCompact(s: Schedule, now: Long) {
        val zone = s.zone
        val today = Moment(s, now)
        val date = if (now < today.day!!.imsak - LEAD) today.today.minusDays(1) else today.today
        val d = s.day(date)
        val prev = s.day(date.minusDays(1))
        val next = s.day(date.plusDays(1))
        val dm = Moment(s, d.imsak)
        val start = d.imsak - LEAD
        val end = next.imsak + LEAD
        val (rise, zenith, set) = dm.forbidden
        val ishraq = rise.second
        val duha = ishraq + 20 * Moment.MIN
        val names = resources.getStringArray(R.array.prayers)
        val fajr = getString(R.string.fajr)
        val avoid = getString(R.string.forbidden_short)

        // The day line: each part runs until the next one starts.
        class Mark(val label: String, val name: String, val kind: Int, val at: Long, val low: Boolean = false)
        val marks = listOf(
            Mark(names[Day.ISHA], names[Day.ISHA], DayChart.PRAYER, prev.t[Day.ISHA], low = true),
            Mark(fajr, fajr, DayChart.PRAYER, d.imsak),
            Mark(avoid, getString(R.string.forbidden_sunrise), DayChart.FORBIDDEN, rise.first),
            Mark(getString(R.string.ishraq), getString(R.string.ishraq), DayChart.PRAYER, ishraq),
            Mark(getString(R.string.duha), getString(R.string.duha), DayChart.PRAYER, duha),
            Mark(avoid, getString(R.string.forbidden_zenith), DayChart.FORBIDDEN, zenith.first),
            Mark(names[Day.DHUHR], names[Day.DHUHR], DayChart.PRAYER, d.dhuhr),
            Mark(names[Day.ASR], names[Day.ASR], DayChart.PRAYER, d.t[Day.ASR]),
            Mark(avoid, getString(R.string.forbidden_sunset), DayChart.FORBIDDEN, set.first),
            Mark(names[Day.MAGHRIB], names[Day.MAGHRIB], DayChart.PRAYER, d.maghrib, low = true),
            Mark(names[Day.ISHA], names[Day.ISHA], DayChart.PRAYER, d.t[Day.ISHA], low = true),
            Mark(fajr, fajr, DayChart.PRAYER, next.imsak),
        )
        val parts = mutableListOf<DayChart.Part>()
        var at = Long.MIN_VALUE
        marks.forEachIndexed { i, mk ->
            val from = maxOf(mk.at, at)
            val to = if (i + 1 < marks.size) marks[i + 1].at else next.sunrise
            if (to > from) parts += DayChart.Part(mk.label, mk.name, from, to, mk.kind, low = mk.low)
            at = maxOf(at, to)
        }

        // The night rows, for last night's end and tonight: whole, in thirds, in halves with Seher.
        val nightLabel = getString(R.string.night_short)
        for ((ns, ne) in listOf(prev.maghrib to d.imsak, d.maghrib to next.imsak)) {
            fun f(x: Double) = ns + ((ne - ns) * x).toLong()
            parts += DayChart.Part(nightLabel, getString(R.string.night), ns, ne, DayChart.NIGHT, row = 1)
            parts += DayChart.Part("1/3", getString(R.string.night_first), ns, f(1 / 3.0), DayChart.NIGHT, row = 2)
            parts += DayChart.Part("2/3", getString(R.string.night_second), f(1 / 3.0), f(2 / 3.0), DayChart.NIGHT, row = 2, alt = true)
            parts += DayChart.Part("3/3", getString(R.string.night_last), f(2 / 3.0), ne, DayChart.NIGHT, row = 2)
            parts += DayChart.Part("1/2", getString(R.string.night_half_first), ns, f(0.5), DayChart.NIGHT, row = 3)
            parts += DayChart.Part("2/2", getString(R.string.night_half_second), f(0.5), f(5 / 6.0), DayChart.NIGHT, row = 3, alt = true)
            parts += DayChart.Part(getString(R.string.seher), getString(R.string.seher_name), f(5 / 6.0), ne, DayChart.NIGHT, row = 3)
        }
        val shown = parts.filter { it.to > start && it.from < end }
        val current = shown.firstOrNull { it.row == 0 && now >= it.from && now < it.to }

        val chart = find<DayChart>(R.id.dayChart)
        chart.show(shown, start, end, now, zone, current?.let { if (it.kind == DayChart.FORBIDDEN) avoid else it.label }.orEmpty(),
            android.text.format.DateFormat.is24HourFormat(this))
        compactZone = zone
        compactNow = now
        val pick = compactPick?.let { (from, row) -> shown.firstOrNull { it.from == from && it.row == row } } ?: current
        chart.picked = pick
        pick?.let { showPick(it) }

        // The times themselves: the six, then the extra ones of the day, then the night.
        fun f(x: Double) = d.maghrib + ((next.imsak - d.maghrib) * x).toLong()
        val rows = listOf(
            (0..5).map { names[it] to d.t[it] },
            listOf(getString(R.string.ishraq) to ishraq, getString(R.string.duha) to duha, getString(R.string.evvabin) to d.maghrib),
            listOf(getString(R.string.night_third_1) to f(1 / 3.0), getString(R.string.midnight_short) to f(0.5),
                getString(R.string.night_third_2) to f(2 / 3.0), getString(R.string.seher) to f(5 / 6.0)),
        )
        // Like the widget: the prayer time we are in is the one in green.
        val inNow = d.t[if (date == today.today) today.current else Day.ISHA]
        val group = find<LinearLayout>(R.id.compactTimes)
        group.removeAllViews()
        rows.forEachIndexed { r, cells -> cellRow(group, cells, zone, now, if (r == 0) inNow else null) }

        find<TextView>(R.id.compactDate).text = Fmt.date(this, date)
        find<TextView>(R.id.compactHijri).text = Fmt.hijri(this, d)
        find<TextView>(R.id.compactLegend).text = android.text.SpannableStringBuilder().apply {
            fun dot(color: Int) {
                val at = length
                append("●  ")
                setSpan(android.text.style.ForegroundColorSpan(getColor(color)), at, at + 1, 0)
            }
            dot(R.color.forbidden_ink); append(getString(R.string.forbidden)); append("     ")
            dot(R.color.night); append(getString(R.string.night)); append("\n")
            append(getString(R.string.compact_hint))
        }
    }

    private fun showPick(p: DayChart.Part) {
        val now = compactNow
        find<TextView>(R.id.compactPick).text = p.name
        val range = Fmt.time(this, p.from, compactZone) + " – " + Fmt.time(this, p.to, compactZone)
        val state = when {
            now < p.from -> getString(R.string.compact_in, Fmt.left(this, p.from - now))
            now < p.to -> getString(R.string.compact_left, Fmt.left(this, p.to - now))
            else -> getString(R.string.compact_past)
        }
        find<TextView>(R.id.compactPickSub).text = "$range · $state"
        find<View>(R.id.dayChart).contentDescription = "${p.name}, $range, $state"
    }

    /** A row of equal cells, each a name over a time; [current] in the accent color. */
    private fun cellRow(group: LinearLayout, cells: List<Pair<String, Long>>, zone: ZoneId, now: Long, current: Long?) {
        if (group.childCount > 0) group.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.hairline))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(16); marginEnd = dp(16)
            }
        })
        val medium = resources.getFont(R.font.inter_medium)
        val row = LinearLayout(this).apply { setPadding(dp(4), dp(10), dp(4), dp(10)) }
        cells.forEach { (label, at) ->
            val lit = at == current
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            cell.addView(TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(if (lit) R.color.accent else R.color.muted))
            })
            cell.addView(TextView(this).apply {
                text = Fmt.time(this@MainActivity, at, zone, short = true)
                gravity = android.view.Gravity.CENTER
                textSize = 16f
                typeface = medium
                setPadding(0, dp(2), 0, 0)
                setTextColor(getColor(when { lit -> R.color.accent; at <= now -> R.color.muted; else -> R.color.ink }))
            })
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        group.addView(row)
    }

    /** One line of a times list: a dot for the next one, muted once it has passed. */
    private fun timeRow(group: ViewGroup, name: String, time: String, note: String?, highlight: Boolean, past: Boolean) {
        val ink = getColor(R.color.ink)
        val muted = getColor(R.color.muted)
        if (group.childCount > 0) group.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.hairline))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(38) }
        })
        val r = LayoutInflater.from(this).inflate(R.layout.row_time, group, false)
        r.findViewById<View>(R.id.dot).visibility = if (highlight) View.VISIBLE else View.INVISIBLE
        r.findViewById<TextView>(R.id.name).apply { text = name; setTextColor(if (past) muted else ink) }
        r.findViewById<TextView>(R.id.time).apply { text = time; setTextColor(if (past) muted else ink) }
        r.findViewById<TextView>(R.id.note).text = note.orEmpty()
        group.addView(r)
    }

    private fun renderQibla(place: Place) {
        val bearing = Qibla.bearing(place.lat, place.lng)
        val km = NumberFormat.getIntegerInstance(java.util.Locale(Lang.current(this))).format(Qibla.distanceToKaaba(place.lat, place.lng).roundToInt())
        find<QiblaDial>(R.id.dial).qibla = bearing.toFloat()
        find<TextView>(R.id.qiblaDeg).text = "${bearing.roundToInt()}°"
        find<TextView>(R.id.qiblaSub).text = getString(R.string.qibla_from_north, km)
        find<TextView>(R.id.qiblaFor).apply {
            visibility = if (place.isHere) View.GONE else View.VISIBLE
            text = getString(R.string.qibla_other_place, place.name)
        }
        declination = GeomagneticField(place.lat.toFloat(), place.lng.toFloat(), 0f, System.currentTimeMillis()).declination
        updateQiblaHint()
        renderLocationStatus(place)
        renderCalibration()
    }

    /** Says where the location came from, and what to do when GPS is weak, old or off. */
    private fun renderLocationStatus(place: Place) {
        val box = find<View>(R.id.locStatus)
        val text = find<TextView>(R.id.locText)
        val action = find<TextView>(R.id.locAction)
        if (!place.isHere) return run { box.visibility = View.GONE }
        box.visibility = View.VISIBLE
        val age = System.currentTimeMillis() - place.fixedAt
        val ago = Fmt.ago(this, place.fixedAt)
        var warn = true
        var act: Pair<Int, () -> Unit>? = null
        text.text = when {
            !Locator.granted(this) -> {
                act = R.string.allow_location to { askForLocation() }
                getString(R.string.loc_denied)
            }
            !Locator.enabled(this) -> {
                act = R.string.loc_turn_on to { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                getString(R.string.loc_off, ago)
            }
            locating -> {
                warn = false
                getString(R.string.loc_finding)
            }
            gpsWeak -> {
                act = R.string.try_again to { refreshData(force = true) }
                getString(R.string.loc_weak, ago)
            }
            age > OLD_FIX -> {
                act = R.string.loc_update to { refreshData(force = true) }
                getString(R.string.loc_old, ago)
            }
            place.accuracy > ROUGH_FIX -> {
                act = R.string.loc_update to { refreshData(force = true) }
                getString(R.string.loc_rough, Fmt.distance(this, place.accuracy))
            }
            else -> {
                warn = false
                getString(R.string.loc_good, Fmt.distance(this, place.accuracy), ago)
            }
        }
        box.setBackgroundResource(if (warn) R.drawable.note_bg else 0)
        text.setTextColor(getColor(if (warn) R.color.ink else R.color.muted))
        action.visibility = if (act == null) View.GONE else View.VISIBLE
        act?.let { (label, run) ->
            action.setText(label)
            action.setOnClickListener { run() }
        }
    }

    /** The figure-8 card: shown when the compass reports low accuracy, or when asked for. */
    private fun renderCalibration() {
        val needed = rotationSensor != null && compassAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        val card = find<View>(R.id.calibCard)
        val show = rotationSensor != null && (needed || calibShown)
        if (show && card.visibility != View.VISIBLE) Motion.settle(card)
        card.visibility = if (show) View.VISIBLE else View.GONE
        find<TextView>(R.id.calibTitle).setText(if (needed) R.string.calib_needed else R.string.calib_title)
        find<View>(R.id.calibLink).visibility = if (rotationSensor == null || needed) View.GONE else View.VISIBLE
        find<TextView>(R.id.calibLink).setText(if (calibShown) R.string.calib_hide else R.string.calib_link)
    }

    // ---- calendar ---------------------------------------------------------------------------

    private fun renderCalendar() {
        val place = Places.selected(this) ?: return
        val schedule = Times.get(this, place)
        val today = LocalDate.now(schedule.zone)
        val picked = pickedDay ?: today.also { pickedDay = it }
        val shown = shownMonth ?: YearMonth.from(picked).also { shownMonth = it }
        val locale = java.util.Locale(Lang.current(this))
        find<TextView>(R.id.monthTitle).text =
            java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(shown).replaceFirstChar { it.titlecase(locale) }
        val first = schedule.day(shown.atDay(1))
        val last = schedule.day(shown.atEndOfMonth())
        val months = resources.getStringArray(R.array.hijri_months)
        find<TextView>(R.id.monthHijri).text = if (first.hijriMonth == last.hijriMonth) "${months[first.hijriMonth - 1]} ${first.hijriYear}"
        else "${months[first.hijriMonth - 1]} – ${months[last.hijriMonth - 1]} ${last.hijriYear}"
        month.show(shown, picked, today)

        val day = schedule.day(picked)
        find<TextView>(R.id.dayTitle).text = Fmt.date(this, picked)
        find<TextView>(R.id.dayHijri).text = Fmt.hijri(this, day)
        val group = find<LinearLayout>(R.id.dayGroup)
        group.removeAllViews()
        val now = System.currentTimeMillis()
        val m = Moment(schedule, now)
        val current = if (picked == today) m.current else -1
        val next = if (picked == today && m.nextIndex < 6) m.nextIndex else -1
        for (i in 0..5) {
            val note = when (i) {
                current -> getString(R.string.now)
                next -> getString(R.string.compact_in, Fmt.left(this, day.t[i] - now))
                else -> null
            }
            timeRow(group, Fmt.prayer(this, i), Fmt.time(this, day.t[i], schedule.zone),
                note, i == current, picked == today && day.t[i] <= now && i != current)
        }
    }

    private fun pickDay(date: LocalDate) {
        pickedDay = date
        renderCalendar()
        Motion.stagger(listOf(find(R.id.dayTitle), find(R.id.dayHijri), find(R.id.dayGroup)), step = 40, rise = 6f)
    }

    private fun updateQiblaHint() {
        val dial = find<QiblaDial>(R.id.dial)
        find<TextView>(R.id.qiblaHint).apply {
            text = getString(when {
                rotationSensor == null -> R.string.qibla_no_compass
                dial.facing && !heading.isNaN() -> R.string.qibla_facing
                else -> R.string.qibla_hint
            })
            setTextColor(getColor(if (dial.facing && !heading.isNaN()) R.color.accent else R.color.muted))
        }
    }

    // ---- tabs -------------------------------------------------------------------------------

    private fun showTab(index: Int, animate: Boolean) {
        val old = tab
        tab = index
        Store.setTab(this, index)
        tabButtons.forEachIndexed { i, b ->
            b.isSelected = i == index
            b.setTextColor(getColor(if (i == index) R.color.ink else R.color.muted))
        }
        tabViews.forEachIndexed { i, v -> if (i != index) v.visibility = View.GONE }
        render()
        val shown = tabViews[index]
        if (animate && old != index && shown.visibility == View.VISIBLE) {
            if (index == DETAILED) Motion.stagger(Motion.children(shown as ViewGroup), step = 50, rise = 10f)
            else Motion.settle(shown)
        }
        if (index == QIBLA) startCompass() else stopCompass()
    }

    // ---- compass (only while the Qibla tab is on screen) ------------------------------------

    private fun startCompass() {
        if (compassOn || tab != QIBLA || rotationSensor == null || !resumed) return
        compassOn = sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopCompass() {
        if (!compassOn) return
        sensors.unregisterListener(this)
        compassOn = false
    }

    private val matrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.accuracy != compassAccuracy) onAccuracyChanged(event.sensor, event.accuracy)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        @Suppress("DEPRECATION")
        val rotation = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0 else windowManager.defaultDisplay.rotation
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(matrix, x, y, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val target = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + declination) + 360) % 360
        // Ease toward the new reading, the short way round, so the dial doesn't jitter.
        heading = if (heading.isNaN()) target else {
            val diff = ((target - heading + 540) % 360) - 180
            (heading + diff * 0.15f + 360) % 360
        }
        val dial = find<QiblaDial>(R.id.dial)
        dial.heading = heading
        if (dial.facing && !wasFacing) dial.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (dial.facing != wasFacing) {
            wasFacing = dial.facing
            updateQiblaHint()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val wasNeeded = compassAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        compassAccuracy = accuracy
        if (wasNeeded && accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            // Calibrated: say so briefly, then tuck the card away.
            calibShown = true
            find<TextView>(R.id.calibTitle).setText(R.string.calib_done)
            clock.postDelayed({ calibShown = false; renderCalibration() }, 2500)
            find<View>(R.id.calibLink).visibility = View.GONE
            return
        }
        renderCalibration()
    }

    // ---- places drawer ----------------------------------------------------------------------

    private val closeOnBack by lazy { OnBackInvokedCallback { closeDrawer() } }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        buildPlaces()
        val drawer = find<View>(R.id.drawer)
        val scrim = find<View>(R.id.scrim)
        drawer.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        drawer.translationX = -dp(300).toFloat()
        drawer.animate().translationX(0f).setDuration(380).setInterpolator(Motion.ease).start()
        scrim.animate().alpha(1f).setDuration(300).start()
        Motion.stagger(Motion.children(find(R.id.placeList)), delay = 120, step = 50, rise = 8f)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeOnBack)
        }
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        val drawer = find<View>(R.id.drawer)
        val scrim = find<View>(R.id.scrim)
        drawer.animate().translationX(-drawer.width.toFloat()).setDuration(280).setInterpolator(Motion.ease)
            .withEndAction { drawer.visibility = View.GONE }.start()
        scrim.animate().alpha(0f).setDuration(250).withEndAction { scrim.visibility = View.GONE }.start()
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(closeOnBack)
    }

    @Deprecated("Only reached below Android 13")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (drawerOpen) closeDrawer() else super.onBackPressed()
    }

    private fun buildPlaces() {
        val list = find<LinearLayout>(R.id.placeList)
        list.removeAllViews()
        val selected = Places.selectedId(this)
        val here = Places.here(this)
        val inflater = LayoutInflater.from(this)

        fun add(place: Place?, id: String) {
            val r = inflater.inflate(R.layout.row_place, list, false)
            r.isSelected = id == selected
            val icon = r.findViewById<ImageView>(R.id.icon)
            icon.setImageResource(if (id == Places.HERE) R.drawable.ic_pin else R.drawable.ic_globe)
            r.findViewById<TextView>(R.id.name).text =
                place?.name?.ifEmpty { null } ?: getString(R.string.current_location)
            r.findViewById<TextView>(R.id.detail).text = place?.let { nextLine(it) }
                ?: getString(R.string.current_location)
            r.findViewById<View>(R.id.remove).apply {
                visibility = if (id == Places.HERE) View.INVISIBLE else View.VISIBLE
                setOnClickListener {
                    Places.remove(this@MainActivity, id)
                    buildPlaces()
                    render()
                }
            }
            r.setOnClickListener {
                Places.select(this, id)
                closeDrawer()
                render()
                refreshData(force = id == Places.HERE)
                Motion.stagger(listOf(find(R.id.heading), find(R.id.stage)), step = 80, rise = 10f)
            }
            list.addView(r)
        }

        add(here, Places.HERE)
        Places.cities(this).forEach { add(it, it.id) }
    }

    /** "Asr in 1h 2m" for a place whose times are cached, or where it is. */
    private fun nextLine(place: Place): String {
        val m = Moment(Times.get(this, place))
        return getString(R.string.widget_left, Fmt.prayer(this, m.next), Fmt.left(this, m.nextAt - m.now))
    }

    companion object {
        const val SIMPLE = 0
        const val DETAILED = 1
        const val QIBLA = 2
        const val CALENDAR = 3
        const val COMPACT = 4
        private const val ASK_NOTIFY = 2
        private const val LEAD = 30 * Moment.MIN
        private const val OLD_FIX = 6 * 3_600_000L
        private const val ROUGH_FIX = 5_000f
    }
}
