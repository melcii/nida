package app.nida

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Brings location and times up to date, off the main thread. */
object Refresh {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val MOVED_KM = 3.0

    /**
     * Finds where we are once, names the city, and caches its times.
     * [located] runs as soon as the fix is in; [done] after the times are ready (true) or unavailable.
     */
    fun here(context: Context, located: () -> Unit = {}, done: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        Locator.current(context) { loc ->
            located()
            io.execute {
                if (loc != null) {
                    val old = Places.here(app)
                    // A few metres of drift shouldn't refetch anything.
                    val moved = old == null || old.name.isEmpty() ||
                        Qibla.distanceKm(old.lat, old.lng, loc.latitude, loc.longitude) > MOVED_KM
                    if (moved) {
                        val name = Locator.cityName(app, loc.latitude, loc.longitude) ?: old?.name.orEmpty()
                        Places.setHere(app, Place(Places.HERE, name, "", loc.latitude, loc.longitude))
                    }
                }
                val ok = Places.here(app)?.let { Times.ensure(app, it) } ?: false
                main.post {
                    NidaWidget.refreshAll(app)
                    done(ok)
                }
            }
        }
    }

    /** Caches times for a place without touching location. */
    fun place(context: Context, place: Place, done: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        io.execute {
            val ok = Times.ensure(app, place)
            main.post {
                if (place.isHere) NidaWidget.refreshAll(app)
                done(ok)
            }
        }
    }

    fun run(task: () -> Unit) = io.execute(task)
    fun post(task: () -> Unit) = main.post(task)
}
