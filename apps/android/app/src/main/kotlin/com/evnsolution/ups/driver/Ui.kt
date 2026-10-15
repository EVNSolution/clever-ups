package com.evnsolution.ups.driver

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Small view helpers, so each screen reads as a list of what it shows. Colors match the web's tokens. */
object Ui {
    const val INK = 0xFF141414.toInt()
    const val MUTED = 0xFF6B6B6B.toInt()
    const val ACCENT = 0xFF0057E0.toInt()
    const val OK = 0xFF17692F.toInt()
    const val WARN = 0xFF7A4A00.toInt()

    // Text sizes in sp, from the page title down to the small grey lines.
    const val TITLE = 22f
    const val HEAD = 19f
    const val HEADER = 17f
    const val SECTION = 16f
    const val BODY = 15f
    const val NOTE = 14f
    const val SMALL = 13f
    const val TINY = 12f

    // Spacing in dp.
    const val PADDING = 16
    const val PAGE = 24
    const val ROW = 10
    const val DOT = 28
    const val DOT_GAP = 12

    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private val korea = ZoneId.of("Asia/Seoul")

    /** Hours and minutes in Korea, such as 09:35, from an ISO date-time. */
    fun clock(iso: String?): String =
        iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(korea).format(clock) }.getOrNull() } ?: ""

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun column(context: Context, padding: Int = PADDING): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, padding)
        setPadding(pad, pad, pad, pad)
    }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(context: Context, value: String, size: Float = BODY, color: Int = INK, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun button(context: Context, label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    /** The round visit number of the timeline. */
    fun number(context: Context, value: String, color: Int = ACCENT): TextView = TextView(context).apply {
        text = value
        textSize = TINY
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val size = dp(context, DOT)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(context, DOT_GAP) }
    }

    /** The view, out of the screen it was on: a note line is kept across redraws and joins each new screen. */
    fun <V : View> detached(view: V): V = view.also { (it.parent as? ViewGroup)?.removeView(it) }

    fun gap(context: Context, height: Int = DOT_GAP): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, height))
    }
}
