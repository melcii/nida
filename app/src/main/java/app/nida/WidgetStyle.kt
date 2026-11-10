package app.nida

import android.content.Context
import android.graphics.Color

/** How the widget looks: background colour, how see-through it is, and the text colour. */
data class WidgetStyle(val color: Int, val opacity: Int, val text: Int) {

    val alpha get() = (opacity * 255 / 100).coerceIn(0, 255)

    /** Light text on dark or see-through backgrounds, unless the user chose. */
    val lightText: Boolean
        get() = when (text) {
            TEXT_LIGHT -> true
            TEXT_DARK -> false
            else -> opacity < 40 || luminance(color) < 0.5
        }

    val textColor get() = if (lightText) 0xFFF4F5F2.toInt() else 0xFF1B1F1D.toInt()

    /** The current time stands out in the accent green, or in full white/ink where green would clash. */
    val highlight: Int
        get() = when {
            color == GREEN && opacity >= 40 -> textColor
            lightText -> 0xFFA8DDC2.toInt()
            else -> 0xFF2F6B55.toInt()
        }

    val dim get() = (textColor and 0x00FFFFFF) or ((if (lightText) 0xB3 else 0x99) shl 24)

    fun save(context: Context) = Store.prefs(context).edit()
        .putInt(KEY_COLOR, color).putInt(KEY_OPACITY, opacity).putInt(KEY_TEXT, text).apply()

    companion object {
        const val TEXT_AUTO = 0
        const val TEXT_LIGHT = 1
        const val TEXT_DARK = 2
        const val GREEN = 0xFF2F6B55.toInt()

        val swatches = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFF6F5F1.toInt(), 0xFFE6EFEA.toInt(),
            GREEN, 0xFF1E2A44.toInt(), 0xFF000000.toInt(),
        )

        private const val KEY_COLOR = "w_color"
        private const val KEY_OPACITY = "w_opacity"
        private const val KEY_TEXT = "w_text"

        fun load(context: Context) = Store.prefs(context).let {
            WidgetStyle(it.getInt(KEY_COLOR, 0xFFF6F5F1.toInt()), it.getInt(KEY_OPACITY, 90), it.getInt(KEY_TEXT, TEXT_AUTO))
        }

        private fun luminance(c: Int) =
            (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255
    }
}
