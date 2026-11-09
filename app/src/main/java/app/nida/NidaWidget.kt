package app.nida

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * City, the six times, and the time left until the next one.
 * It redraws once a minute with a non-waking alarm, so it is idle while the screen is off,
 * and reads only cached times: no GPS, no network.
 */
class NidaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refreshAll(context)

    override fun onDisabled(context: Context) = cancelTick(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED -> refreshAll(context)
        }
    }

    companion object {
        private const val ACTION_TICK = "app.nida.TICK"
        private val nameIds = intArrayOf(R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4, R.id.n5)
        private val timeIds = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4, R.id.t5)

        private fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, NidaWidget::class.java))

        fun hasWidgets(context: Context) = ids(context).isNotEmpty()

        fun refreshAll(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return cancelTick(context)
            AppWidgetManager.getInstance(context).updateAppWidget(ids, build(context))
            scheduleTick(context)
        }

        fun showLocating(context: Context) {
            val c = Lang.wrap(context)
            val views = RemoteViews(context.packageName, R.layout.widget_nida)
            views.setTextViewText(R.id.city, c.getString(R.string.locating))
            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids(context), views)
        }

        fun build(context: Context, style: WidgetStyle = WidgetStyle.load(context)): RemoteViews {
            val c = Lang.wrap(context)
            val v = RemoteViews(context.packageName, R.layout.widget_nida)

            v.setInt(R.id.bg, "setColorFilter", style.color or 0xFF000000.toInt())
            v.setInt(R.id.bg, "setImageAlpha", style.alpha)
            v.setInt(R.id.pin, "setColorFilter", style.dim)
            v.setTextColor(R.id.city, style.textColor)
            v.setTextColor(R.id.left, style.highlight)

            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            v.setOnClickPendingIntent(R.id.header, PendingIntent.getActivity(
                context, 1, Intent(context, RefreshActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))

            val names = c.resources.getStringArray(R.array.prayers_short)
            names.forEachIndexed { i, n -> v.setTextViewText(nameIds[i], n) }

            val place = Places.here(context)
            val schedule = place?.let { Times.get(context, it) }
            val m = schedule?.let { Moment(it) }
            if (place == null || m == null || !m.ready) {
                v.setTextViewText(R.id.city, place?.name?.ifEmpty { null } ?: c.getString(R.string.app_name))
                v.setTextViewText(R.id.left, c.getString(if (place == null) R.string.widget_no_place else R.string.widget_loading))
                for (i in 0..5) {
                    v.setTextViewText(timeIds[i], "--:--")
                    v.setTextColor(nameIds[i], style.dim)
                    v.setTextColor(timeIds[i], style.dim)
                }
                if (place != null) FetchJob.schedule(context)
                return v
            }
            if (Times.needsFetch(context, place)) FetchJob.schedule(context)

            v.setTextViewText(R.id.city, place.name.ifEmpty { c.getString(R.string.current_location) })
            v.setTextViewText(R.id.left, c.getString(R.string.widget_left, Fmt.prayer(c, m.next), Fmt.left(c, m.nextAt - m.now)))
            val day = m.day!!
            for (i in 0..5) {
                v.setTextViewText(timeIds[i], Fmt.time(c, day.t[i], schedule.zone, short = true))
                val color = if (i == m.next) style.highlight else style.dim
                v.setTextColor(nameIds[i], color)
                v.setTextColor(timeIds[i], if (i == m.next) style.highlight else style.textColor)
            }
            return v
        }

        private fun tickIntent(context: Context) = PendingIntent.getBroadcast(
            context, 0, Intent(context, NidaWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** At the start of the next minute. RTC (not RTC_WAKEUP): while the phone sleeps, nothing runs. */
        private fun scheduleTick(context: Context) {
            val now = System.currentTimeMillis()
            val next = now - now % 60_000 + 60_000 + 500
            context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, next, tickIntent(context))
        }

        private fun cancelTick(context: Context) =
            context.getSystemService(AlarmManager::class.java).cancel(tickIntent(context))
    }
}
