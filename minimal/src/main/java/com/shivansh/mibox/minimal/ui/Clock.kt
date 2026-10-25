package com.shivansh.mibox.minimal.ui

import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import com.shivansh.mibox.minimal.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home's clock: a greeting, the date, the time very large with an orange colon, and a ruler the
 * second hand steps along. It ticks once a second; words change only when the minute does, so
 * the one thing redrawn each second is the small hand. [corner] is the small time on the other tabs.
 */
class Clock(root: View, private val corner: TextView) {
    private val greeting: TextView = root.findViewById(R.id.greeting)
    private val date: TextView = root.findViewById(R.id.date)
    private val time: TextView = root.findViewById(R.id.time)
    private val ampm: TextView = root.findViewById(R.id.ampm)
    private val ruler: Ruler = root.findViewById(R.id.ruler)
    private val hand: View = root.findViewById(R.id.second)
    private val main = Handler(Looper.getMainLooper())
    private val tick = Runnable { update() }
    private var minute = ""

    /** True on Home; elsewhere only [corner] is kept current. */
    var big = true

    fun start() {
        minute = ""
        update()
    }

    fun stop() = main.removeCallbacks(tick)

    private fun update() {
        val now = LocalDateTime.now()
        val h24 = DateFormat.is24HourFormat(time.context)
        val hours = if (h24) "%02d".format(now.hour) else ((now.hour + 11) % 12 + 1).toString()
        val clock = "$hours:%02d".format(now.minute)
        val stamp = "$clock ${now.toLocalDate()} $h24"
        if (stamp != minute) {
            minute = stamp
            time.text = SpannableString(clock).apply {
                val colon = clock.indexOf(':')
                setSpan(ForegroundColorSpan(Palette.ORANGE), colon, colon + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            ampm.text = if (h24) "" else if (now.hour < 12) "AM" else "PM"
            date.text = now.format(DATE)
            greeting.setText(
                when (now.hour) {
                    in 5..11 -> R.string.good_morning
                    in 12..16 -> R.string.good_afternoon
                    in 17..21 -> R.string.good_evening
                    else -> R.string.good_night
                },
            )
            corner.text = if (h24) clock else "$clock ${ampm.text}"
        }
        if (big && ruler.width > 0) hand.translationX = ruler.offsetFor(now.second, hand.width)
        // To the next whole second, so the hand steps in time with the minute turning over.
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1_000L - now.nano / 1_000_000 + 4)
    }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE  d MMMM", Locale.getDefault())
    }
}
