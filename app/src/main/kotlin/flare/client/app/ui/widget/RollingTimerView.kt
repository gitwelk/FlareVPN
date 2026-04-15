package flare.client.app.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import flare.client.app.R

class RollingTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val digitViews = mutableListOf<AnimatedDigitView>()
    private var currentText: String = ""
    
    private var textColor: Int = ContextCompat.getColor(context, R.color.text_primary)
    private var textSizeSp: Float = 18f
    private var typeface: Typeface? = null
    private var fontVariationSettings: String? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }

    fun setTime(time: String, animate: Boolean = true) {
        if (currentText == time) return
        
        
        while (digitViews.size < time.length) {
            val dv = AnimatedDigitView(context).apply {
                setDigitTextSize(textSizeSp)
                setTextColorInt(textColor)
                setDigitTypeface(typeface)
                setFontVariationSettings(fontVariationSettings)
            }
            digitViews.add(dv)
            addView(dv)
        }
        
        
        while (digitViews.size > time.length) {
            val dv = digitViews.removeAt(digitViews.size - 1)
            removeView(dv)
        }

        for (i in time.indices) {
            val char = time[i]
            val oldChar = if (i < currentText.length) currentText[i] else null
            
            
            val shouldAnimate = animate && char != oldChar
            digitViews[i].setText(char.toString(), shouldAnimate)
        }
        
        currentText = time
    }

    fun setTextColor(color: Int) {
        this.textColor = color
        digitViews.forEach { it.setTextColorInt(color) }
    }

    fun setTextSize(sizeSp: Float) {
        this.textSizeSp = sizeSp
        digitViews.forEach { it.setDigitTextSize(sizeSp) }
    }

    fun setTypeface(tf: Typeface?) {
        this.typeface = tf
        digitViews.forEach { it.setDigitTypeface(tf) }
    }

    fun setFontVariationSettings(settings: String?) {
        this.fontVariationSettings = settings
        digitViews.forEach { it.setFontVariationSettings(settings) }
    }
}
