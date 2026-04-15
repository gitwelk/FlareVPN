package flare.client.app.util

import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot


object AnimUtils {

    private const val DURATION_OUT = 180L
    private const val DURATION_IN  = 280L
    private const val SLIDE_OFFSET = 40f
    private const val MAX_BLUR = 25f

    
    fun navigateForward(fromView: View, toView: View) {
        
        fromView.animate()
            .alpha(0f)
            .translationY(-SLIDE_OFFSET)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(DURATION_OUT)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction {
                fromView.visibility = View.GONE
                fromView.translationY = 0f
                fromView.scaleX = 1f
                fromView.scaleY = 1f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    fromView.setRenderEffect(null)
                }
            }
            .start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ValueAnimator.ofFloat(0.1f, MAX_BLUR).apply {
                duration = DURATION_OUT
                addUpdateListener { animator ->
                    val radius = animator.animatedValue as Float
                    fromView.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                }
                start()
            }
        }

        
        toView.alpha = 0f
        toView.translationY = SLIDE_OFFSET * 1.5f
        toView.scaleX = 0.95f
        toView.scaleY = 0.95f
        toView.visibility = View.VISIBLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            toView.setRenderEffect(RenderEffect.createBlurEffect(MAX_BLUR, MAX_BLUR, Shader.TileMode.CLAMP))
            ValueAnimator.ofFloat(MAX_BLUR, 0.1f).apply {
                duration = DURATION_IN
                startDelay = 20L
                addUpdateListener { animator ->
                    val radius = animator.animatedValue as Float
                    if (radius <= 0.5f) {
                        toView.setRenderEffect(null)
                    } else {
                        toView.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                    }
                }
                start()
            }
        }

        toView.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(DURATION_IN)
            .setStartDelay(20L)
            .setInterpolator(DecelerateInterpolator(2.2f))
            .start()
    }

    
    fun morphNavigate(clickedView: View, fromView: View, targetView: View, rootContainer: View) {
        val location = IntArray(2)
        clickedView.getLocationInWindow(location)
        val rootLocation = IntArray(2)
        rootContainer.getLocationInWindow(rootLocation)

        val cx = location[0] - rootLocation[0] + clickedView.width / 2
        val cy = location[1] - rootLocation[1] + clickedView.height / 2

        val finalRadius = hypot(rootContainer.width.toDouble(), rootContainer.height.toDouble()).toFloat()

        
        targetView.visibility = View.VISIBLE
        targetView.alpha = 1f
        
        val reveal = ViewAnimationUtils.createCircularReveal(targetView, cx, cy, 0f, finalRadius)
        reveal.duration = DURATION_IN + 100L
        reveal.interpolator = DecelerateInterpolator(1.5f)
        reveal.start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            targetView.setRenderEffect(RenderEffect.createBlurEffect(MAX_BLUR / 2, MAX_BLUR / 2, Shader.TileMode.CLAMP))
            ValueAnimator.ofFloat(MAX_BLUR / 2, 0.1f).apply {
                duration = DURATION_IN
                addUpdateListener { animator ->
                    val radius = animator.animatedValue as Float
                    if (radius <= 0.5f) targetView.setRenderEffect(null)
                    else targetView.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                }
                start()
            }
        }

        
        fromView.animate()
            .alpha(0f)
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(DURATION_OUT)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                fromView.visibility = View.GONE
                fromView.scaleX = 1f
                fromView.scaleY = 1f
            }
            .start()
    }

    
    fun navigateBack(fromView: View, toView: View) {
        
        fromView.animate()
            .alpha(0f)
            .translationY(SLIDE_OFFSET * 1.2f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(DURATION_OUT)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .withEndAction {
                fromView.visibility = View.GONE
                fromView.translationY = 0f
                fromView.alpha = 1f
                fromView.scaleX = 1f
                fromView.scaleY = 1f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    fromView.setRenderEffect(null)
                }
            }
            .start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ValueAnimator.ofFloat(0.1f, MAX_BLUR).apply {
                duration = DURATION_OUT
                addUpdateListener { animator ->
                    val radius = animator.animatedValue as Float
                    fromView.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                }
                start()
            }
        }

        
        toView.alpha = 0f
        toView.translationY = -SLIDE_OFFSET
        toView.scaleX = 0.96f
        toView.scaleY = 0.96f
        toView.visibility = View.VISIBLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            toView.setRenderEffect(RenderEffect.createBlurEffect(MAX_BLUR, MAX_BLUR, Shader.TileMode.CLAMP))
            ValueAnimator.ofFloat(MAX_BLUR, 0.1f).apply {
                duration = DURATION_IN
                startDelay = 20L
                addUpdateListener { animator ->
                    val radius = animator.animatedValue as Float
                    if (radius <= 0.5f) {
                        toView.setRenderEffect(null)
                    } else {
                        toView.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                    }
                }
                start()
            }
        }

        toView.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(DURATION_IN)
            .setStartDelay(20L)
            .setInterpolator(DecelerateInterpolator(2.0f))
            .start()
    }
}
