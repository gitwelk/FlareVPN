package flare.client.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.AttributeSet
import com.google.android.material.materialswitch.MaterialSwitch
import java.lang.reflect.Field

class GlassyMaterialSwitch @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = com.google.android.material.R.attr.materialSwitchStyle
) : MaterialSwitch(context, attrs, defStyleAttr) {

    private var glassShader: Any? = null
    private var thumbPositionField: Field? = null
    private val thumbBasePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var lastCenterX = 0f
    private var lastCenterY = 0f
    private var lastRadius = 0f

    init {
        val transparent = ColorStateList.valueOf(Color.TRANSPARENT)
        thumbTintList = transparent
        thumbIconTintList = transparent
        
        setThumbIconResource(android.R.color.transparent)
        
        background = null
        
        try {
            var clazz: Class<*>? = javaClass
            while (clazz != null && thumbPositionField == null) {
                try {
                    thumbPositionField = clazz.getDeclaredField("thumbPosition")
                } catch (e: Exception) {
                    try {
                        thumbPositionField = clazz.getDeclaredField("mThumbPosition")
                    } catch (e2: Exception) {}
                }
                clazz = clazz.superclass
            }
            thumbPositionField?.isAccessible = true
        } catch (e: Exception) {}
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            glassShader = LiquidGlassShader(this)
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        updateShader()
        super.onDraw(canvas)

        
        
        if (lastRadius > 0) {
            val isNightMode = (resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            
            
            
            val colorStart = Color.argb(if (isNightMode) 70 else 100, 255, 255, 255)
            val colorEnd = Color.argb(if (isNightMode) 15 else 20, 255, 255, 255)
            
            thumbBasePaint.shader = android.graphics.RadialGradient(
                lastCenterX, lastCenterY, lastRadius,
                intArrayOf(colorStart, colorStart, colorEnd), 
                floatArrayOf(0f, 0.6f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawCircle(lastCenterX, lastCenterY, lastRadius, thumbBasePaint)
        }
    }

    private fun updateShader() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && width > 0 && height > 0) {
            val dp = resources.displayMetrics.density
            
            
            val trackWidth = (52f * dp)
            val trackHeight = (32f * dp)
            
            
            val trackLeft = (width - trackWidth) / 2f
            val trackTop = (height - trackHeight) / 2f

            
            
            val pos = try {
                thumbPositionField?.get(this) as? Float ?: if (isChecked) 1f else 0f
            } catch (e: Exception) {
                if (isChecked) 1f else 0f
            }

            
            val currentThumbSize = (24f + 6f * pos) * dp 
            val radius = currentThumbSize / 2f

            
            
            val margin = 2f * dp
            val travelRange = trackWidth - currentThumbSize - 2 * margin
            
            val centerX = trackLeft + margin + radius + (travelRange * pos)
            val centerY = trackTop + (trackHeight / 2f)
            
            lastCenterX = centerX
            lastCenterY = centerY
            lastRadius = radius

            val isNightMode = (resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            
            val fgColor = if (isNightMode)
                Color.argb(45, 255, 255, 255) 
            else
                Color.argb(55, 255, 255, 255) 

            (glassShader as? LiquidGlassShader)?.update(
                left = centerX - radius,
                top = centerY - radius,
                right = centerX + radius,
                bottom = centerY + radius,
                radiusLeftTop = radius, radiusRightTop = radius,
                radiusRightBottom = radius, radiusLeftBottom = radius,
                thickness = 2.2f * dp, 
                intensity = 1.3f, 
                index = 1.52f, 
                glassHeight = 1.0f, 
                foregroundColor = fgColor,
                isNightMode = isNightMode,
                hasInnerShadow = true, 
                clipToGlass = true
            )



        }
    }
}
