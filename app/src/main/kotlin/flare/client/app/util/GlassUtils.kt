package flare.client.app.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import eightbitlab.com.blurview.BlurView
import flare.client.app.R
import flare.client.app.ui.widget.LiquidGlassShader

object GlassUtils {

    data class MenuItem(val id: Int, val title: CharSequence, val onClick: () -> Unit)

    fun setupGlassView(
        blurView: BlurView,
        radius: Float,
        isNightMode: Boolean,
        foregroundColor: Int,
        intensity: Float = 1.6f,
        index: Float = 1.5f,
        glassHeight: Float = 0.1f,
        hasInnerShadow: Boolean = true,
        blurRadius: Float = 3f
    ) {
        val context = blurView.context
        val activity = context as? Activity ?: run {
            var ctx = context
            while (ctx is android.content.ContextWrapper) {
                if (ctx is Activity) return@run ctx
                ctx = ctx.baseContext
            }
            null
        }

        activity?.let {
            val windowBg = it.window?.decorView?.background
            val blurTargetView = it.findViewById<View>(R.id.blur_target) as? eightbitlab.com.blurview.BlurTarget
            if (blurTargetView != null) {
                val builder = blurView.setupWith(blurTargetView)
                    .setBlurRadius(blurRadius)
                    .setBlurAutoUpdate(true)

                if (windowBg != null) {
                    builder.setFrameClearDrawable(windowBg)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val shader = LiquidGlassShader(blurView)
                    val dp = context.resources.displayMetrics.density
                    
                    val updateShader = {
                        if (blurView.width > 0 && blurView.height > 0) {
                            shader.update(
                                left = 0f, top = 0f,
                                right = blurView.width.toFloat(), bottom = blurView.height.toFloat(),
                                radiusLeftTop = radius, radiusRightTop = radius,
                                radiusRightBottom = radius, radiusLeftBottom = radius,
                                thickness = 2f * dp,
                                intensity = intensity, index = index,
                                glassHeight = glassHeight,
                                foregroundColor = foregroundColor,
                                isNightMode = isNightMode,
                                hasInnerShadow = hasInnerShadow
                            )
                        }
                    }
                    
                    blurView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        updateShader()
                    }
                    blurView.post { updateShader() }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val colorMatrix = android.graphics.ColorMatrix().apply { setSaturation(1.45f) }
                    blurView.setRenderEffect(
                        android.graphics.RenderEffect.createColorFilterEffect(
                            android.graphics.ColorMatrixColorFilter(colorMatrix)
                        )
                    )
                } else {
                    val overlayColor = if (isNightMode)
                        Color.argb(80, 20, 20, 25)
                    else
                        Color.argb(120, 255, 255, 255)
                    builder.setOverlayColor(overlayColor)
                }
            }
        }

        blurView.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        blurView.clipToOutline = true
    }

    fun showGlassDialog(
        context: Context,
        @androidx.annotation.LayoutRes layoutRes: Int,
        maxWidthDp: Int = 340,
        blurRadius: Float = 12f,
        onViewCreated: (View, android.app.Dialog) -> Unit
    ) {
        val dialog = android.app.Dialog(context)
        val inflater = LayoutInflater.from(context)
        
        val isNightMode = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            
        val dp = context.resources.displayMetrics.density
        
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        val container = FrameLayout(context).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val marginV = (16 * dp).toInt()
                val marginH = (24 * dp).toInt()
                setMargins(marginH, marginV, marginH, marginV)
            }
            layoutParams = lp
        }

        val blurView = BlurView(context).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_dialog)
        }
        
        val contentView = inflater.inflate(layoutRes, container, false)
        
        container.addView(blurView)
        container.addView(contentView)
        root.addView(container)
        
        dialog.setContentView(root)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes?.blurBehindRadius = (blurRadius * 1.5f * dp).toInt()
            }

            window.attributes?.let { params ->
                val maxWidth = (maxWidthDp * dp).toInt()
                val screenWidth = context.resources.displayMetrics.widthPixels
                val horizontalMargin = (32 * dp).toInt()
                
                if (screenWidth > maxWidth + horizontalMargin) {
                    params.width = maxWidth
                } else {
                    params.width = ViewGroup.LayoutParams.MATCH_PARENT
                }
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                window.attributes = params
            }
        }
        
        val fgColor = if (isNightMode)
            Color.argb(160, 32, 34, 40)
        else
            Color.argb(70, 255, 255, 255)
            
        setupGlassView(
            blurView = blurView,
            radius = 24f * dp,
            isNightMode = isNightMode,
            foregroundColor = fgColor,
            blurRadius = blurRadius
        )
        
        onViewCreated(contentView, dialog)
        dialog.show()
    }

    fun showGlassMenu(
        anchor: View,
        items: List<MenuItem>
    ) {
        val context = anchor.context
        val inflater = LayoutInflater.from(context)
        val tempParent = android.widget.FrameLayout(context)
        val popupView = inflater.inflate(R.layout.layout_glass_menu, tempParent, false)
        val blurView = popupView.findViewById<BlurView>(R.id.glass_blur_view)
        val container = popupView.findViewById<ViewGroup>(R.id.menu_item_container)
        
        val dp = context.resources.displayMetrics.density
        val isNightMode = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            
        val fgColor = if (isNightMode)
            Color.argb(160, 32, 34, 40)
        else
            Color.argb(70, 255, 255, 255)

        setupGlassView(
            blurView = blurView,
            radius = 16f * dp,
            isNightMode = isNightMode,
            foregroundColor = fgColor
        )

        val lp = popupView.layoutParams
        val popupWidth = if (lp != null && lp.width > 0) lp.width else (160 * dp).toInt()

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val measuredHeight = popupView.measuredHeight
        val maxHeight = (260 * dp).toInt()
        val finalHeight = if (measuredHeight > maxHeight) maxHeight else ViewGroup.LayoutParams.WRAP_CONTENT

        val popupWindow = PopupWindow(
            popupView,
            popupWidth,
            finalHeight,
            true
        )
        popupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = 24f

        items.forEachIndexed { index, item ->
            val itemView = inflater.inflate(R.layout.item_glass_menu, container, false)
            val tvTitle = itemView.findViewById<TextView>(R.id.tv_menu_title)
            tvTitle.text = item.title
            itemView.setOnClickListener {
                item.onClick()
                popupWindow.dismiss()
            }
            container.addView(itemView)
            if (index < items.size - 1) {
                val divider = View(context)
                divider.layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt()).apply {
                    marginStart = (16 * dp).toInt()
                    marginEnd = (16 * dp).toInt()
                }
                val dividerColor = if (isNightMode)
                    Color.argb(20, 255, 255, 255)
                else
                    Color.argb(30, 0, 0, 0)
                divider.setBackgroundColor(dividerColor)
                container.addView(divider)
            }
        }

        popupWindow.showAsDropDown(anchor, -(12 * dp).toInt(), -(12 * dp).toInt(), Gravity.END)
    }

    fun showGlassTooltip(
        anchor: View,
        text: CharSequence
    ) {
        val context = anchor.context
        val inflater = LayoutInflater.from(context)
        val tempParent = FrameLayout(context)
        val popupView = inflater.inflate(R.layout.layout_glass_tooltip, tempParent, false)
        val blurView = popupView.findViewById<BlurView>(R.id.glass_blur_view)
        val tvText = popupView.findViewById<TextView>(R.id.tv_tooltip_text)
        tvText.text = text

        val dp = context.resources.displayMetrics.density
        val isNightMode = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            
        val fgColor = if (isNightMode)
            Color.argb(160, 32, 34, 40)
        else
            Color.argb(70, 255, 255, 255)

        setupGlassView(
            blurView = blurView,
            radius = 18f * dp,
            isNightMode = isNightMode,
            foregroundColor = fgColor,
            index = 1.52f,
            glassHeight = 0.12f
        )

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec((280 * dp).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = 24f
        popupWindow.animationStyle = android.R.style.Animation_Dialog
        
        val xOffset = (anchor.width - popupView.measuredWidth) / 2
        popupWindow.showAsDropDown(anchor, xOffset, (8 * dp).toInt(), Gravity.START)
    }

    fun attachTooltip(labelView: View, description: CharSequence) {
        val context = labelView.context
        val parent = labelView.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(labelView)
        
        val oldParams = labelView.layoutParams as? LinearLayout.LayoutParams
        val weight = oldParams?.weight ?: 0f
        
        parent.removeView(labelView)
        
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (weight > 0) weight else 1f)
            clipChildren = false
            clipToPadding = false
        }
        
        labelView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT, 
            0f
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        
        val infoButton = createGlassyInfoIcon(context)
        
        wrapper.addView(labelView)
        wrapper.addView(infoButton)
        
        parent.addView(wrapper, index)
        
        infoButton.setOnClickListener {
            showGlassTooltip(infoButton, description)
        }
    }

    private fun createGlassyInfoIcon(context: Context): View {
        val dp = context.resources.displayMetrics.density
        val size = (16f * dp).toInt()
        
        val isNightMode = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val frame = FrameLayout(context).apply {
            val lp = LinearLayout.LayoutParams(size, size)
            lp.gravity = Gravity.CENTER_VERTICAL
            lp.setMargins((8f * dp).toInt(), 0, (6f * dp).toInt(), 0)
            layoutParams = lp
            
            elevation = 3f * dp
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = if (isNightMode) Color.BLACK else Color.argb(100, 0, 0, 0)
                outlineSpotShadowColor = if (isNightMode) Color.BLACK else Color.argb(80, 0, 0, 0)
            }
            
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = false
            isClickable = true
            isFocusable = true
        }

        val glassBackground = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            val bg = ContextCompat.getDrawable(context, R.drawable.bg_glass_info_circle)?.mutate()
            if (bg != null && !isNightMode) {
                bg.setTint(Color.argb(40, 0, 0, 0))
            }
            background = bg
        }
        frame.addView(glassBackground)

        val icon = ImageView(context).apply {
            val iconSize = (13f * dp).toInt()
            val iconLp = FrameLayout.LayoutParams(iconSize, iconSize)
            iconLp.gravity = Gravity.CENTER
            layoutParams = iconLp
            
            setImageResource(R.drawable.ic_info_i)
            val iconColor = if (isNightMode) Color.WHITE else Color.BLACK
            imageTintList = ColorStateList.valueOf(iconColor)
            alpha = if (isNightMode) 0.85f else 0.75f
        }
        frame.addView(icon)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shader = LiquidGlassShader(glassBackground)
            
            val fgColor = if (isNightMode)
                Color.argb(120, 255, 255, 255)
            else
                Color.argb(140, 255, 255, 255)

            val updateShader = {
                if (glassBackground.width > 0 && glassBackground.height > 0) {
                    val r = glassBackground.width / 2f
                    shader.update(
                        left = 0f, top = 0f,
                        right = glassBackground.width.toFloat(), bottom = glassBackground.height.toFloat(),
                        radiusLeftTop = r, radiusRightTop = r,
                        radiusRightBottom = r, radiusLeftBottom = r,
                        thickness = 1.1f * dp,
                        intensity = 1.1f, index = 1.4f,
                        glassHeight = 0.06f,
                        foregroundColor = fgColor,
                        isNightMode = isNightMode,
                        hasInnerShadow = false,
                        clipToGlass = true
                    )
                }
            }

            glassBackground.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateShader()
            }
            glassBackground.post { updateShader() }
        }
        
        return frame
    }

    private fun getAccentColor(context: Context): Int? {
        val settings = flare.client.app.data.SettingsManager(context)
        if (!settings.isCustomColorEnabled) return null
        
        return when (settings.accentColorKey) {
            "material_you" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        context.getColor(android.R.color.system_accent1_500)
                    } catch (e: Exception) {
                        flare.client.app.ui.MainActivity.COLOR_DEFAULT
                    }
                } else {
                    flare.client.app.ui.MainActivity.COLOR_DEFAULT
                }
            }
            "green"  -> flare.client.app.ui.MainActivity.COLOR_GREEN
            "purple" -> flare.client.app.ui.MainActivity.COLOR_PURPLE
            "red"    -> flare.client.app.ui.MainActivity.COLOR_RED
            "pink"   -> flare.client.app.ui.MainActivity.COLOR_PINK
            "orange" -> flare.client.app.ui.MainActivity.COLOR_ORANGE
            else     -> flare.client.app.ui.MainActivity.COLOR_DEFAULT
        }
    }
}
