// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * The launcher chrome has no XML layout: the panel is assembled in code so it
 * can follow live session state. This is the one place that owns how it looks —
 * colours come from res/values/launcher_theme.xml, never from literals here.
 */
object LauncherStyle {
    /** Visual weight of a panel action. */
    enum class ButtonKind { PRIMARY, TONAL, SECONDARY, DANGER }

    const val SHEET_RADIUS = 22f
    const val CARD_RADIUS = 16f
    const val BUTTON_RADIUS = 12f

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun color(context: Context, res: Int): Int = context.getColor(res)

    /** Solid rounded rectangle, optionally outlined. */
    fun rounded(context: Context, fill: Int, radius: Float, stroke: Int? = null, strokeWidth: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(context, radius).toFloat()
            if (stroke != null) setStroke(dp(context, strokeWidth).coerceAtLeast(1), stroke)
        }

    /** Bottom-sheet surface: rounded at the top only, hairline outline above. */
    fun sheetBackground(context: Context): Drawable {
        val radius = dp(context, SHEET_RADIUS).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color(context, R.color.launcher_surface))
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            setStroke(dp(context, 1f).coerceAtLeast(1), color(context, R.color.launcher_outline))
        }
    }

    /** Wraps [content] in the platform ripple so touches stay legible on dark fills. */
    fun ripple(context: Context, content: Drawable, rippleColor: Int = R.color.launcher_ripple): Drawable =
        RippleDrawable(ColorStateList.valueOf(color(context, rippleColor)), content, null)

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(context, color(context, R.color.launcher_surface_raised), CARD_RADIUS,
            color(context, R.color.launcher_outline))
        val padding = dp(context, 12f)
        setPadding(padding, padding, padding, padding)
    }

    fun title(context: Context, textRes: Int): TextView = TextView(context).apply {
        setText(textRes)
        setTextColor(color(context, R.color.launcher_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT_BOLD
    }

    fun sectionTitle(context: Context, textRes: Int): TextView = TextView(context).apply {
        setText(textRes)
        setTextColor(color(context, R.color.launcher_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        letterSpacing = 0.08f
        setPadding(dp(context, 4f), dp(context, 14f), dp(context, 4f), dp(context, 6f))
    }

    fun body(context: Context, size: Float = 13f, colorRes: Int = R.color.launcher_text_secondary): TextView =
        TextView(context).apply {
            setTextColor(color(context, colorRes))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setLineSpacing(dp(context, 2f).toFloat(), 1f)
        }

    /** Phase headline in the status card: a coloured dot plus the phase name. */
    fun phaseLabel(context: Context): TextView = TextView(context).apply {
        setTextColor(color(context, R.color.launcher_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        compoundDrawablePadding = dp(context, 8f)
    }

    /**
     * Applies the status dot in front of a [phaseLabel] or [chip]. Status ticks
     * arrive twice a second, so an unchanged colour keeps its drawable; the tag
     * of these launcher-owned views holds nothing else.
     */
    fun applyDot(view: TextView, colorValue: Int) {
        if (view.tag as? Int == colorValue && view.compoundDrawablesRelative[0] != null) return
        view.tag = colorValue
        val context = view.context
        val size = dp(context, 9f)
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorValue)
            setSize(size, size)
        }
        dot.setBounds(0, 0, size, size)
        view.setCompoundDrawablesRelative(dot, null, null, null)
    }

    fun chip(context: Context, text: CharSequence, dot: Int): TextView = TextView(context).apply {
        setText(text)
        setTextColor(color(context, R.color.launcher_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        background = rounded(context, color(context, R.color.launcher_surface_sunken), 999f,
            color(context, R.color.launcher_outline))
        val horizontal = dp(context, 10f)
        val vertical = dp(context, 5f)
        setPadding(horizontal, vertical, horizontal, vertical)
        compoundDrawablePadding = dp(context, 6f)
        applyDot(this, dot)
    }

    /** Slim rounded progress bar; the indeterminate sweep is tinted to match. */
    fun progressBar(context: Context): ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            val radius = dp(context, 5f).toFloat()
            val track = GradientDrawable().apply {
                setColor(color(context, R.color.launcher_track)); cornerRadius = radius
            }
            val fill = GradientDrawable().apply {
                setColor(color(context, R.color.launcher_accent)); cornerRadius = radius
            }
            progressDrawable = LayerDrawable(arrayOf<Drawable>(track,
                ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            indeterminateTintList = ColorStateList.valueOf(color(context, R.color.launcher_accent))
            minimumHeight = dp(context, 8f)
            max = 100
        }

    fun button(context: Context, textRes: Int, kind: ButtonKind = ButtonKind.SECONDARY, onClick: () -> Unit): Button =
        Button(context).apply {
            setText(textRes)
            isAllCaps = false
            stateListAnimator = null
            elevation = 0f
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(context, 46f)
            minimumHeight = dp(context, 46f)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            val horizontal = dp(context, 10f)
            setPadding(horizontal, 0, horizontal, 0)
            val fill = when (kind) {
                ButtonKind.PRIMARY -> rounded(context, color(context, R.color.launcher_accent), BUTTON_RADIUS)
                ButtonKind.TONAL -> rounded(context, color(context, R.color.launcher_accent_soft), BUTTON_RADIUS,
                    color(context, R.color.launcher_accent))
                ButtonKind.DANGER -> rounded(context, color(context, R.color.launcher_danger_soft), BUTTON_RADIUS,
                    color(context, R.color.launcher_danger))
                ButtonKind.SECONDARY -> rounded(context, color(context, R.color.launcher_surface_sunken), BUTTON_RADIUS,
                    color(context, R.color.launcher_outline))
            }
            background = ripple(context, fill,
                if (kind == ButtonKind.PRIMARY) R.color.launcher_accent_pressed else R.color.launcher_ripple)
            setTextColor(color(context, when (kind) {
                ButtonKind.PRIMARY -> R.color.launcher_on_accent
                ButtonKind.TONAL -> R.color.launcher_accent
                ButtonKind.DANGER -> R.color.launcher_danger
                ButtonKind.SECONDARY -> R.color.launcher_text_primary
            }))
            setOnClickListener { onClick() }
        }

    /** A borderless text action, used for the panel's close control. */
    fun textAction(context: Context, textRes: Int, onClick: () -> Unit): TextView = TextView(context).apply {
        setText(textRes)
        setTextColor(color(context, R.color.launcher_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        minHeight = dp(context, 40f)
        minWidth = dp(context, 64f)
        background = ripple(context, rounded(context, Color.TRANSPARENT, BUTTON_RADIUS,
            color(context, R.color.launcher_outline)))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })
    }

    /** Drag affordance at the top of the sheet. */
    fun grabber(context: Context): View = View(context).apply {
        background = rounded(context, color(context, R.color.launcher_grabber), 999f)
    }

    /** Row of up to two equal-width actions; a single action spans the row. */
    fun actionRow(context: Context, vararg actions: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val gap = dp(context, 8f)
        for ((index, action) in actions.withIndex()) {
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) params.marginStart = gap
            addView(action, params)
        }
    }

    fun verticalGap(context: Context, size: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, size))
    }
}
