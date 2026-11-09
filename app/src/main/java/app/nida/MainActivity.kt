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
import java.time.ZoneId
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener {

    private lateinit var createdLang: String
    private var tab = SIMPLE
    private val clock = Handler(Looper.getMainLooper())
    private var locating = false
    private var lastLocate = 0L
    private var failed = false
    private var askedOnce = false
    private var drawerOpen = false
    private var resumed = false

    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val rotationSensor by lazy { sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    private var compassOn = false
    private var heading = Float.NaN
    private var declination = 0f
    private var wasFacing = false

    private val tabViews by lazy { listOf<View>(find(R.id.viewSimple), find(R.id.viewDetailed), find(R.id.viewQibla)) }
    private val tabButtons by lazy { listOf<TextView>(find(R.id.tabSimple), find(R.id.tabDetailed), find(R.id.tabQibla)) }

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
        find<View>(R.id.status).setOnClickListener { if (failed) refreshData(force = true) }
        tabButtons.forEachIndexed { i, b -> b.setOnClickListener { showTab(i, animate = true) } }

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

    private fun refreshData(force: Boolean = false) {
        val place = Places.selected(this)
        if (Places.selectedId(this) == Places.HERE || place == null) {
            if (!Locator.granted(this) || locating) return
            if (!force && System.currentTimeMillis() - lastLocate < 60_000 && !failed) return
            locating = true
            lastLocate = System.currentTimeMillis()
            if (Places.here(this) == null) render()
            Refresh.here(this, done = { ok ->
                locating = false
                failed = !ok
                if (!isDestroyed) render()
            })
        } else {
            Refresh.place(this, place) { ok ->
                failed = !ok
                if (!isDestroyed) render()
            }
        }
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
        val zone = schedule?.zone ?: ZoneId.systemDefault()
        find<TextView>(R.id.placeLabel).text = when {
            place.isHere -> getString(R.string.current_location)
            zone.rules.getOffset(java.time.Instant.now()) != ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()) ->
                listOf(place.detail, getString(R.string.local_time, Fmt.time(this, System.currentTimeMillis(), zone)))
                    .filter { it.isNotEmpty() }.joinToString(" · ")
            else -> place.detail
        }

        renderQibla(place)
        val m = schedule?.let { Moment(it) }
        if (m == null || !m.ready) {
            find<TextView>(R.id.hijri).text = ""
            find<TextView>(R.id.gregorian).text = ""
            tabViews.forEachIndexed { i, v -> v.visibility = if (i == QIBLA && tab == QIBLA) View.VISIBLE else View.GONE }
            setStatus(getString(if (failed) R.string.offline else R.string.loading))
            return
        }
        setStatus(null)
        tabViews.forEachIndexed { i, v -> if (i == tab) v.visibility = View.VISIBLE }

        val hijriDay = m.hijri!!
        find<TextView>(R.id.hijri).text = Fmt.hijri(this, hijriDay).let {
            if (hijriDay !== m.day) getString(R.string.hijri_from_maghrib, it) else it
        }
        find<TextView>(R.id.gregorian).text = Fmt.date(this, m.today)
        renderSimple(m)
        renderDetailed(m)
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
        val inflater = LayoutInflater.from(this)
        val ink = getColor(R.color.ink)
        val muted = getColor(R.color.muted)

        fun row(group: ViewGroup, name: String, time: String, note: String?, highlight: Boolean, past: Boolean) {
            if (group.childCount > 0) group.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.hairline))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(38) }
            })
            val r = inflater.inflate(R.layout.row_time, group, false)
            r.findViewById<View>(R.id.dot).visibility = if (highlight) View.VISIBLE else View.INVISIBLE
            r.findViewById<TextView>(R.id.name).apply { text = name; setTextColor(if (past) muted else ink) }
            r.findViewById<TextView>(R.id.time).apply { text = time; setTextColor(if (past) muted else ink) }
            r.findViewById<TextView>(R.id.note).text = note.orEmpty()
            group.addView(r)
        }

        val times = find<LinearLayout>(R.id.timesGroup)
        times.removeAllViews()
        for (i in 0..5) {
            val isNext = m.nextIndex == i
            row(times, Fmt.prayer(this, i), Fmt.time(this, day.t[i], zone),
                if (isNext) Fmt.left(this, m.nextAt - m.now) else null, isNext, day.t[i] <= m.now)
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
                failed = false
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
        val s = Times.get(this, place) ?: return if (place.isHere) getString(R.string.current_location) else place.detail
        val m = Moment(s)
        if (!m.ready) return place.detail
        return getString(R.string.widget_left, Fmt.prayer(this, m.next), Fmt.left(this, m.nextAt - m.now))
    }

    companion object {
        const val SIMPLE = 0
        const val DETAILED = 1
        const val QIBLA = 2
    }
}
