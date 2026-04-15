package flare.client.app.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextSwitcher
import android.widget.TextView
import android.widget.ViewSwitcher
import androidx.core.content.res.ResourcesCompat
import flare.client.app.R

class AnimatedDigitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextSwitcher(context, attrs), ViewSwitcher.ViewFactory {

    private var currentText: String = ""

    init {
        if (childCount == 0) {
            setFactory(this)
        }
        setInAnimation(context, R.anim.slide_in_top)
        setOutAnimation(context, R.anim.slide_out_bottom)
    }

    override fun makeView(): View {
        return TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            typeface = try {
                ResourcesCompat.getFont(context, R.font.geologica_medium)
            } catch (e: Exception) {
                android.graphics.Typeface.DEFAULT_BOLD
            }
        }
    }

    fun setText(text: String, animate: Boolean = true) {
        if (currentText == text) return
        currentText = text
        if (animate) {
            super.setText(text)
        } else {
            val outAnim = outAnimation
            val inAnim = inAnimation
            setInAnimation(null)
            setOutAnimation(null)
            setCurrentText(text)
            setInAnimation(inAnim)
            setOutAnimation(outAnim)
        }
    }

    fun setDigit(digit: Int, animate: Boolean = true) {
        setText(digit.toString(), animate)
    }

    fun setTextColorRes(colorRes: Int) {
        val color = ResourcesCompat.getColor(resources, colorRes, null)
        setTextColorInt(color)
    }

    fun setTextColorInt(color: Int) {
        (getChildAt(0) as? TextView)?.setTextColor(color)
        (getChildAt(1) as? TextView)?.setTextColor(color)
    }

    fun setDigitTextSize(sizeSp: Float) {
        (getChildAt(0) as? TextView)?.textSize = sizeSp
        (getChildAt(1) as? TextView)?.textSize = sizeSp
    }

    fun setDigitTypeface(tf: Typeface?) {
        (getChildAt(0) as? TextView)?.typeface = tf
        (getChildAt(1) as? TextView)?.typeface = tf
    }
}
