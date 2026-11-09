package app.nida

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * One day's times, in epoch millis. [t] holds the six the app shows:
 * imsak (the start of Fajr), sunrise, dhuhr, asr, maghrib, isha.
 */
class Day(
    val date: LocalDate,
    val t: LongArray,
    val sunset: Long,
    val hijriDay: Int,
    val hijriMonth: Int,
    val hijriYear: Int,
) {
    val imsak get() = t[IMSAK]
    val sunrise get() = t[SUNRISE]
    val dhuhr get() = t[DHUHR]
    val maghrib get() = t[MAGHRIB]

    companion object {
        const val IMSAK = 0
        const val SUNRISE = 1
        const val DHUHR = 2
        const val ASR = 3
        const val MAGHRIB = 4
        const val ISHA = 5
    }
}

/** A cached run of days for one place, in that place's time zone. */
class Schedule(val key: String, val zone: ZoneId, days: List<Day>) {
    private val byDate = days.associateBy { it.date }
    fun day(date: LocalDate): Day? = byDate[date]
}

/**
 * Prayer times come from the AlAdhan API a month or two at a time and are kept on disk,
 * so the widget can count down for weeks without touching the network or GPS.
 */
object Times {
    private val memo = HashMap<String, Schedule>()

    private fun file(context: Context, id: String) = File(context.filesDir, "times-${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    /** Changes whenever the place moves or the method changes, which makes the cache stale. */
    private fun key(context: Context, place: Place) = String.format(
        Locale.US, "%.3f,%.3f|%d|%d", place.lat, place.lng, Store.method(context), Store.school(context),
    )

    @Synchronized
    fun get(context: Context, place: Place): Schedule? {
        val key = key(context, place)
        memo[place.id]?.let { if (it.key == key) return it }
        val f = file(context, place.id)
        if (!f.exists()) return null
        val s = runCatching { read(JSONObject(f.readText())) }.getOrNull() ?: return null
        if (s.key != key) return null
        memo[place.id] = s
        return s
    }

    @Synchronized
    fun forget(context: Context, id: String) {
        memo.remove(id)
        file(context, id).delete()
    }

    /** True when the next few days aren't cached yet. */
    fun needsFetch(context: Context, place: Place): Boolean {
        val s = get(context, place) ?: return true
        val today = LocalDate.now(s.zone)
        return (0L..3L).any { s.day(today.plusDays(it)) == null }
    }

    /** Fetches only if needed. Blocking network: call it off the main thread. Returns whether times are available. */
    fun ensure(context: Context, place: Place): Boolean {
        if (!needsFetch(context, place)) return true
        return try {
            fetch(context, place)
            true
        } catch (e: Exception) {
            get(context, place) != null
        }
    }

    private fun fetch(context: Context, place: Place) {
        val key = key(context, place)
        val method = Store.method(context)
        val school = Store.school(context)
        val now = LocalDate.now()
        val first = if (now.dayOfMonth <= 2) YearMonth.from(now).minusMonths(1) else YearMonth.from(now)
        val months = generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(now).plusMonths(1) }

        var zone = "UTC"
        val days = JSONArray()
        for (ym in months) {
            val url = "https://api.aladhan.com/v1/calendar/${ym.year}/${ym.monthValue}" +
                "?latitude=${place.lat}&longitude=${place.lng}&method=$method&school=$school&iso8601=true"
            val data = Net.json(url).getJSONArray("data")
            for (i in 0 until data.length()) {
                val d = data.getJSONObject(i)
                val tm = d.getJSONObject("timings")
                fun at(name: String) = OffsetDateTime.parse(tm.getString(name).substringBefore(" ")).toInstant().toEpochMilli()
                val g = d.getJSONObject("date").getJSONObject("gregorian").getString("date").split("-")
                val h = d.getJSONObject("date").getJSONObject("hijri")
                zone = d.getJSONObject("meta").optString("timezone", zone)
                days.put(
                    JSONObject()
                        .put("d", LocalDate.of(g[2].toInt(), g[1].toInt(), g[0].toInt()).toString())
                        .put("t", JSONArray(listOf(at("Fajr"), at("Sunrise"), at("Dhuhr"), at("Asr"), at("Maghrib"), at("Isha"))))
                        .put("ss", at("Sunset"))
                        .put("h", JSONArray(listOf(h.getString("day").toInt(), h.getJSONObject("month").getInt("number"), h.getString("year").toInt()))),
                )
            }
        }
        val json = JSONObject().put("key", key).put("tz", zone).put("days", days)
        synchronized(this) {
            file(context, place.id).writeText(json.toString())
            memo.remove(place.id)
        }
    }

    private fun read(o: JSONObject): Schedule {
        val arr = o.getJSONArray("days")
        val days = (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val t = d.getJSONArray("t")
            val h = d.getJSONArray("h")
            Day(
                LocalDate.parse(d.getString("d")),
                LongArray(6) { t.getLong(it) },
                d.getLong("ss"),
                h.getInt(0), h.getInt(1), h.getInt(2),
            )
        }
        val zone = runCatching { ZoneId.of(o.getString("tz")) }.getOrDefault(ZoneId.systemDefault())
        return Schedule(o.getString("key"), zone, days)
    }
}
