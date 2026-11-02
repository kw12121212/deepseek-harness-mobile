package app.deepseek.harness.mobile

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

internal class GoalBarView(context: Context, palette: AppPalette) : LinearLayout(context) {
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private val objectiveView = TextView(context)
    private val phaseView = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        val label = TextView(context).apply {
            text = "目标"
            textSize = 12f
            setTextColor(palette.muted)
        }
        addView(label)
        objectiveView.apply {
            textSize = 14f
            setTextColor(palette.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(objectiveView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        phaseView.apply {
            textSize = 12f
            setTextColor(palette.muted)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(palette.control)
                cornerRadius = dp(12).toFloat()
            }
        }
        addView(phaseView)
    }

    fun bind(objective: String, phase: String) {
        objectiveView.text = objective
        phaseView.text = phase
    }
}
