package app.nida

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Background colour, opacity and text colour for the widget, with a live preview over a
 * stand-in wallpaper. Also opens when the widget is first placed or reconfigured.
 */
class WidgetStyleActivity : Activity() {
    private lateinit var style: WidgetStyle

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_style)
        // Placing the widget succeeds even if this screen is just closed.
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            findViewById<View>(R.id.scroll).setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        style = WidgetStyle.load(this)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.done).setOnClickListener { finish() }

        val swatches = findViewById<LinearLayout>(R.id.swatches)
        WidgetStyle.swatches.forEach { color ->
            swatches.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
                tag = color
                setOnClickListener { update(style.copy(color = color)) }
            })
        }

        findViewById<SeekBar>(R.id.opacity).apply {
            max = 100
            progress = style.opacity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) update(style.copy(opacity = value))
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }

        listOf(R.id.textAuto to WidgetStyle.TEXT_AUTO, R.id.textLight to WidgetStyle.TEXT_LIGHT, R.id.textDark to WidgetStyle.TEXT_DARK)
            .forEach { (id, v) -> findViewById<View>(id).setOnClickListener { update(style.copy(text = v)) } }

        render()
        if (savedInstanceState == null) Motion.stagger(Motion.children(findViewById<ViewGroup>(R.id.page)), step = 60)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun update(next: WidgetStyle) {
        style = next
        style.save(this)
        NidaWidget.refreshAll(applicationContext)
        render()
    }

    private fun render() {
        // The real widget, drawn by the same code the home screen uses.
        val frame = findViewById<FrameLayout>(R.id.preview)
        frame.removeAllViews()
        val view = NidaWidget.build(this, style).apply(applicationContext, frame)
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(116)))

        val swatches = findViewById<LinearLayout>(R.id.swatches)
        for (i in 0 until swatches.childCount) {
            val v = swatches.getChildAt(i)
            val color = v.tag as Int
            val selected = color == style.color
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(if (selected) 3 else 1), getColor(if (selected) R.color.accent else R.color.hairline))
            }
            v.scaleX = if (selected) 1f else 0.86f
            v.scaleY = v.scaleX
        }
        findViewById<TextView>(R.id.opacityValue).text = getString(R.string.opacity_value, style.opacity)
        listOf(R.id.textAuto to WidgetStyle.TEXT_AUTO, R.id.textLight to WidgetStyle.TEXT_LIGHT, R.id.textDark to WidgetStyle.TEXT_DARK)
            .forEach { (id, v) ->
                findViewById<TextView>(id).apply {
                    isSelected = style.text == v
                    setTextColor(getColor(if (style.text == v) R.color.ink else R.color.muted))
                }
            }
    }
}
