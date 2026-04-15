package flare.client.app.util

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import flare.client.app.R

object FontManager {
    private var geologicaRegular: Typeface? = null
    private var geologicaMedium: Typeface? = null

    fun getGeologicaRegular(context: Context): Typeface {
        if (geologicaRegular == null) {
            geologicaRegular = try {
                ResourcesCompat.getFont(context, R.font.geologica_regular)
            } catch (e: Exception) {
                e.printStackTrace()
                Typeface.DEFAULT
            }
        }
        return geologicaRegular ?: Typeface.DEFAULT
    }

    fun getGeologicaMedium(context: Context): Typeface {
        if (geologicaMedium == null) {
            geologicaMedium = try {
                ResourcesCompat.getFont(context, R.font.geologica_medium)
            } catch (e: Exception) {
                e.printStackTrace()
                Typeface.DEFAULT_BOLD
            }
        }
        return geologicaMedium ?: Typeface.DEFAULT_BOLD
    }
}
