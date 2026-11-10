package app.nida

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Opens the system page where the user allows exact alarms for this app. */
fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= 31) {
        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)))
    }
}

/** Calculation method, Asr, widget style, and language. */
class SettingsActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            findViewById<View>(R.id.scroll).setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.asrStandard).setOnClickListener { setSchool(0) }
        findViewById<View>(R.id.asrHanafi).setOnClickListener { setSchool(1) }
        findViewById<View>(R.id.langEn).setOnClickListener { switchLanguage(Lang.EN) }
        findViewById<View>(R.id.langTr).setOnClickListener { switchLanguage(Lang.TR) }
        findViewById<View>(R.id.styleRow).setOnClickListener { startActivity(Intent(this, WidgetStyleActivity::class.java)) }
        val widgets = getSystemService(AppWidgetManager::class.java)
        findViewById<View>(R.id.addWidgetRow).apply {
            visibility = if (widgets.isRequestPinAppWidgetSupported) View.VISIBLE else View.GONE
            setOnClickListener { widgets.requestPinAppWidget(ComponentName(context, NidaWidget::class.java), null, null) }
        }
        findViewById<View>(R.id.addWidgetDivider).visibility = findViewById<View>(R.id.addWidgetRow).visibility
        // Android 12+ asks the user before an app may set exact alarms; the widget uses them to tick on the minute.
        findViewById<View>(R.id.exactRow).apply {
            visibility = if (Build.VERSION.SDK_INT >= 31) View.VISIBLE else View.GONE
            setOnClickListener { openExactAlarmSettings(this@SettingsActivity) }
        }
        findViewById<View>(R.id.exactDivider).visibility = findViewById<View>(R.id.exactRow).visibility
        findViewById<View>(R.id.updateRow).setOnClickListener { update() }
        findViewById<View>(R.id.notifyRow).setOnClickListener {
            when {
                Reminders.on(this) && !Reminders.allowed(this) -> askForNotifications()
                else -> {
                    Reminders.setOn(this, !Reminders.on(this))
                    if (Reminders.on(this)) askForNotifications()
                    notifyChanged()
                }
            }
        }
        findViewById<View>(R.id.atRow).setOnClickListener { Reminders.setAtTime(this, !Reminders.atTime(this)); notifyChanged() }
        findViewById<View>(R.id.beforeRow).setOnClickListener { pickBefore() }
        findViewById<View>(R.id.whichRow).setOnClickListener { pickWhich() }

        render()
        if (savedInstanceState == null) Motion.stagger(Motion.children(findViewById<ViewGroup>(R.id.page)), step = 60)
    }

    override fun onResume() {
        super.onResume()
        val exact = NidaWidget.exact(this)
        findViewById<TextView>(R.id.exactState).apply {
            setText(if (exact) R.string.exact_on else R.string.exact_off)
            setTextColor(getColor(if (exact) R.color.muted else R.color.accent))
        }
        NidaWidget.refreshAll(applicationContext)
        renderNotify()
    }

    private fun render() {
        val group = findViewById<LinearLayout>(R.id.methods)
        group.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val current = Store.method(this)
        Store.methods.forEachIndexed { i, method ->
            if (i > 0) group.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.hairline))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp).apply { marginStart = 20.dp }
            })
            val row = inflater.inflate(R.layout.row_choice, group, false)
            row.findViewById<TextView>(R.id.name).text = getString(method.name)
            row.findViewById<View>(R.id.check).visibility = if (method.id == current) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                if (method.id == current) return@setOnClickListener
                Store.setMethod(this, method.id)
                changed()
            }
            group.addView(row)
        }
        segment(listOf(R.id.asrStandard to 0, R.id.asrHanafi to 1), Store.school(this))
        renderUpdate()
        renderNotify()
        segment(listOf(R.id.langEn to Lang.EN, R.id.langTr to Lang.TR), Lang.current(this))
    }

    // ---- notifications ----

    private fun renderNotify() {
        val on = Reminders.on(this)
        val blocked = on && !Reminders.allowed(this)
        findViewById<Switch>(R.id.notifySwitch).isChecked = on && !blocked
        findViewById<TextView>(R.id.notifySub).apply {
            setText(if (blocked) R.string.notify_blocked else R.string.notify_sub)
            setTextColor(getColor(if (blocked) R.color.accent else R.color.muted))
        }
        val before = Reminders.before(this)
        findViewById<TextView>(R.id.beforeValue).text =
            if (before == 0) getString(R.string.notify_off) else getString(R.string.notify_minutes, before)
        findViewById<Switch>(R.id.atSwitch).isChecked = Reminders.atTime(this)
        val names = resources.getStringArray(R.array.prayers)
        val which = Reminders.which(this)
        findViewById<TextView>(R.id.whichSub).text =
            names.filterIndexed { i, _ -> which and (1 shl i) != 0 }.joinToString(", ").ifEmpty { getString(R.string.notify_none) }
        listOf(R.id.beforeRow, R.id.atRow, R.id.whichRow).forEach {
            findViewById<View>(it).apply { isEnabled = on; alpha = if (on) 1f else 0.4f }
        }
    }

    private fun notifyChanged() {
        renderNotify()
        Reminders.schedule(this)
    }

    private fun askForNotifications() {
        if (Reminders.allowed(this)) return
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) || !Store.prefs(this).getBoolean("asked_notify", false)) {
            Store.prefs(this).edit().putBoolean("asked_notify", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            // Android won't ask again: send them to the app's notification settings.
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = notifyChanged()

    private fun pickBefore() {
        val choices = Reminders.beforeChoices
        val labels = choices.map { if (it == 0) getString(R.string.notify_off) else getString(R.string.notify_minutes, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.notify_before)
            .setSingleChoiceItems(labels, choices.indexOf(Reminders.before(this))) { d, i ->
                Reminders.setBefore(this, choices[i])
                notifyChanged()
                d.dismiss()
            }
            .show()
    }

    private fun pickWhich() {
        val names = resources.getStringArray(R.array.prayers)
        var bits = Reminders.which(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.notify_which)
            .setMultiChoiceItems(names, BooleanArray(6) { bits and (1 shl it) != 0 }) { _, i, on ->
                bits = if (on) bits or (1 shl i) else bits and (1 shl i).inv()
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                Reminders.setWhich(this, bits)
                notifyChanged()
            }
            .show()
    }

    private var updating = false
    private var updateFailed = false

    /** The update row: when the shown place was last downloaded, or why it couldn't be. */
    private fun renderUpdate() {
        val sub = findViewById<TextView>(R.id.updateSub)
        val place = Places.selected(this)
        val coverage = place?.let { Online.coverage(this, it) }
        sub.text = when {
            updateFailed -> getString(R.string.update_failed)
            place == null -> getString(R.string.update_none)
            coverage != null -> getString(R.string.update_done, Fmt.ago(this, coverage.at),
                java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale(Lang.current(this))).format(coverage.until))
            else -> getString(R.string.update_never)
        }
        sub.setTextColor(getColor(if (updateFailed) R.color.ink else R.color.muted))
        findViewById<TextView>(R.id.updateState).setText(if (updating) R.string.update_running else R.string.update_action)
        findViewById<View>(R.id.updateRow).isEnabled = !updating && place != null
    }

    private fun update() {
        if (updating) return
        updating = true
        updateFailed = false
        renderUpdate()
        Refresh.run {
            val ok = runCatching { Online.update(applicationContext) }.isSuccess
            Refresh.post {
                updating = false
                updateFailed = !ok
                NidaWidget.refreshAll(applicationContext)
                Reminders.schedule(applicationContext)
                if (!isDestroyed) renderUpdate()
            }
        }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private fun <T> segment(items: List<Pair<Int, T>>, value: T) = items.forEach { (id, v) ->
        findViewById<TextView>(id).apply {
            isSelected = v == value
            setTextColor(getColor(if (v == value) R.color.ink else R.color.muted))
        }
    }

    private fun setSchool(school: Int) {
        if (school == Store.school(this)) return
        Store.setSchool(this, school)
        changed()
    }

    private fun changed() {
        render()
        NidaWidget.refreshAll(applicationContext)
        Reminders.schedule(this)
    }

    private fun switchLanguage(lang: String) {
        if (lang == Lang.current(this)) return
        Lang.set(this, lang)
        NidaWidget.refreshAll(applicationContext)
        recreate()
    }
}
