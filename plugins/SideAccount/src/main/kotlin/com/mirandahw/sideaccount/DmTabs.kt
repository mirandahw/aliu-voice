package com.mirandahw.sideaccount

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.utils.DimenUtils.dp
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R

/**
 * Row of account chips shown at the top of the DM panel. The selected chip decides whose DMs the
 * list below shows. 0 means "the account the client is logged into".
 */
class DmTabs(ctx: Context, private val onSelect: (accountId: Long) -> Unit) : HorizontalScrollView(ctx) {
    private val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(12.dp, 6.dp, 12.dp, 6.dp)
    }

    var selected: Long = 0L
        private set

    init {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }

    fun rebuild(current: Account?, others: List<Account>) {
        row.removeAllViews()
        if (others.none { it.id == selected }) selected = 0L
        row.addView(chip(current?.tag ?: "This account", 0L))
        for (acc in others) row.addView(chip(acc.tag, acc.id))
    }

    fun select(accountId: Long) {
        if (selected == accountId) return
        selected = accountId
        for (i in 0 until row.childCount) style(row.getChildAt(i) as TextView)
        onSelect(accountId)
    }

    private fun chip(label: String, accountId: Long) = TextView(context).apply {
        text = label
        tag = accountId
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(14.dp, 6.dp, 14.dp, 6.dp)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = 8.dp }
        style(this)
        setOnClickListener { select(accountId) }
    }

    private fun style(view: TextView) {
        val active = (view.tag as Long) == selected
        view.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        view.setTextColor(
            ColorCompat.getThemedColor(context, if (active) R.b.colorHeaderPrimary else R.b.colorHeaderSecondary),
        )
        view.background = GradientDrawable().apply {
            cornerRadius = 16.dp.toFloat()
            setColor(
                ColorCompat.getThemedColor(
                    context,
                    if (active) R.b.colorBackgroundModifierSelected else R.b.colorBackgroundSecondary,
                ),
            )
        }
        view.alpha = if (active) 1f else 0.75f
    }
}
