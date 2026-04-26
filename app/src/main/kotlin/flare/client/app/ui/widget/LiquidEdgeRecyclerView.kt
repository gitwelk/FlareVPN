package flare.client.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView
import flare.client.app.R
import java.io.InputStreamReader

class LiquidEdgeRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private var runtimeShader: RuntimeShader? = null
    
    private var fadeHeight: Float = 32f * context.resources.displayMetrics.density
    private var topOffset: Float = 0f
    
    
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var linearGradient: LinearGradient? = null

    init {
        
        isVerticalFadingEdgeEnabled = false
        overScrollMode = OVER_SCROLL_NEVER

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val inputStream = context.resources.openRawResource(R.raw.liquid_edge_shader)
                val shaderCode = InputStreamReader(inputStream).readText()
                runtimeShader = RuntimeShader(shaderCode)
                
                runtimeShader?.setFloatUniform("fadeHeight", fadeHeight)
                runtimeShader?.setFloatUniform("topOffset", topOffset)
                runtimeShader?.setFloatUniform("time", 0f)
                runtimeShader?.setFloatUniform("topIntensity", 0f)
                runtimeShader?.setFloatUniform("bottomIntensity", 0f)
                runtimeShader?.setFloatUniform("viewHeight", 0f)
                
                setRenderEffect(RenderEffect.createRuntimeShaderEffect(runtimeShader!!, "img"))
            } catch (e: Exception) {
                e.printStackTrace()
                runtimeShader = null
            }
        }

        
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            var scrollOffset = 0f
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                scrollOffset += dy
                updateShaderParameters(scrollOffset * 0.05f)
            }
        })
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateShaderParameters(0f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            linearGradient = LinearGradient(
                0f, topOffset,
                0f, topOffset + fadeHeight,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.BLACK,
                Shader.TileMode.CLAMP
            )
            fadePaint.shader = linearGradient
        }
    }

    private var lastIntensity = -1f

    override fun dispatchDraw(canvas: Canvas) {
        val scrollY = computeVerticalScrollOffset().toFloat()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val scrollRange = computeVerticalScrollRange().toFloat()
            val scrollExtent = computeVerticalScrollExtent().toFloat()
            val maxScroll = scrollRange - scrollExtent
            
            val topIntensity = if (fadeHeight > 0) (scrollY / fadeHeight).coerceIn(0f, 1f) else 0f
            val bottomIntensity = if (fadeHeight > 0) ((maxScroll - scrollY) / fadeHeight).coerceIn(0f, 1f) else 0f

            if (topIntensity > 0.01f || bottomIntensity > 0.01f) {
                val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                super.dispatchDraw(canvas)
                
                if (topIntensity > 0.01f) {
                    val startAlpha = ((1f - topIntensity) * 255).toInt()
                    linearGradient = LinearGradient(
                        0f, topOffset,
                        0f, topOffset + fadeHeight,
                        android.graphics.Color.argb(startAlpha, 0, 0, 0),
                        android.graphics.Color.BLACK,
                        Shader.TileMode.CLAMP
                    )
                    fadePaint.shader = linearGradient
                    canvas.drawRect(0f, 0f, width.toFloat(), topOffset + fadeHeight, fadePaint)
                }
                
                if (bottomIntensity > 0.01f) {
                    val startAlpha = ((1f - bottomIntensity) * 255).toInt()
                    val bottomFadeStart = height - fadeHeight
                    linearGradient = LinearGradient(
                        0f, height.toFloat(),
                        0f, bottomFadeStart,
                        android.graphics.Color.argb(startAlpha, 0, 0, 0),
                        android.graphics.Color.BLACK,
                        Shader.TileMode.CLAMP
                    )
                    fadePaint.shader = linearGradient
                    canvas.drawRect(0f, bottomFadeStart, width.toFloat(), height.toFloat(), fadePaint)
                }
                
                canvas.restoreToCount(saveCount)
            } else {
                super.dispatchDraw(canvas)
            }
        } else {
            super.dispatchDraw(canvas)
        }
    }

    fun updateShaderParameters(time: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val scrollY = computeVerticalScrollOffset().toFloat()
            val scrollRange = computeVerticalScrollRange().toFloat()
            val scrollExtent = computeVerticalScrollExtent().toFloat()
            val maxScroll = scrollRange - scrollExtent
            
            val topIntensity = if (fadeHeight > 0) (scrollY / fadeHeight).coerceIn(0f, 1f) else 0f
            val bottomIntensity = if (fadeHeight > 0) ((maxScroll - scrollY) / fadeHeight).coerceIn(0f, 1f) else 0f
            
            runtimeShader?.setFloatUniform("time", time)
            runtimeShader?.setFloatUniform("topIntensity", topIntensity)
            runtimeShader?.setFloatUniform("bottomIntensity", bottomIntensity)
            runtimeShader?.setFloatUniform("viewHeight", height.toFloat())
            setRenderEffect(RenderEffect.createRuntimeShaderEffect(runtimeShader!!, "img"))
        }
    }
}
