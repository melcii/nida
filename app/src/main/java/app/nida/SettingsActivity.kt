package app.nida

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView

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

        render()
        if (savedInstanceState == null) Motion.stagger(Motion.children(findViewById<ViewGroup>(R.id.page)), step = 60)
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
        segment(listOf(R.id.langEn to Lang.EN, R.id.langTr to Lang.TR), Lang.current(this))
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

    /** The cached times no longer match; fetch new ones for where we last were. */
    private fun changed() {
        render()
        NidaWidget.refreshAll(applicationContext)
        Places.here(this)?.let { Refresh.place(this, it) }
    }

    private fun switchLanguage(lang: String) {
        if (lang == Lang.current(this)) return
        Lang.set(this, lang)
        NidaWidget.refreshAll(applicationContext)
        recreate()
    }
}
