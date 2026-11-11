package app.nida

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

/**
 * City, the six times, and the time left until the next one.
 * It redraws at the start of every minute with a non-waking alarm, so it is idle while the
 * screen is off. Times are calculated on the phone: no GPS, no network.
 */
class NidaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refreshAll(context)

    override fun onDisabled(context: Context) = cancelTick(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_EXACT_CHANGED -> {
                refreshAll(context)
                if (intent.action != ACTION_TICK) Reminders.schedule(context)
            }
        }
    }

    companion object {
        private const val ACTION_TICK = "app.nida.TICK"
        private const val ACTION_EXACT_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
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
            val highlight = style.highlight(context)
            v.setTextColor(R.id.left, highlight)

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
            if (place == null || m == null) {
                v.setTextViewText(R.id.city, place?.name?.ifEmpty { null } ?: c.getString(R.string.app_name))
                v.setTextViewText(R.id.left, c.getString(R.string.widget_no_place))
                for (i in 0..5) {
                    v.setTextViewText(timeIds[i], "--:--")
                    v.setTextColor(nameIds[i], style.dim)
                    v.setTextColor(timeIds[i], style.dim)
                }
                return v
            }

            v.setTextViewText(R.id.city, place.name.ifEmpty { c.getString(R.string.current_location) })
            v.setTextViewText(R.id.left, c.getString(R.string.widget_left, Fmt.prayer(c, m.next), Fmt.left(c, m.nextAt - m.now)))
            val day = m.day!!
            for (i in 0..5) {
                v.setTextViewText(timeIds[i], Fmt.time(c, day.t[i], schedule.zone, short = true))
                // The time we are in stands out; the countdown above already names the next one.
                val now = i == m.previous
                v.setTextColor(nameIds[i], if (now) highlight else style.dim)
                v.setTextColor(timeIds[i], if (now) highlight else style.textColor)
            }
            return v
        }

        private fun tickIntent(context: Context) = PendingIntent.getBroadcast(
            context, 0, Intent(context, NidaWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Whether the countdown may tick exactly on the minute (Android 12+ asks the user once). */
        fun exact(context: Context) =
            Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        /**
         * At the start of the next minute. RTC (not RTC_WAKEUP): while the phone sleeps nothing runs,
         * and the widget catches up the moment the screen comes on.
         */
        private fun scheduleTick(context: Context) {
            val now = System.currentTimeMillis()
            val next = now - now % 60_000 + 60_000 + 200
            val am = context.getSystemService(AlarmManager::class.java)
            if (exact(context)) am.setExact(AlarmManager.RTC, next, tickIntent(context))
            else am.set(AlarmManager.RTC, next, tickIntent(context))
        }

        private fun cancelTick(context: Context) =
            context.getSystemService(AlarmManager::class.java).cancel(tickIntent(context))
    }
}
