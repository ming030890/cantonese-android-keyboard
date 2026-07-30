package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import androidx.compose.ui.unit.IntSize

internal object HandwritingCanvasGeometry {
    private const val PREFS = "handwriting_canvas_geometry"
    private const val WIDTH = "width_px"
    private const val HEIGHT = "height_px"
    private const val ORIENTATION = "orientation"

    fun record(context: Context, size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(WIDTH, size.width)
            .putInt(HEIGHT, size.height)
            .putInt(ORIENTATION, context.resources.configuration.orientation)
            .apply()
    }

    fun read(context: Context): IntSize? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(ORIENTATION, -1) != context.resources.configuration.orientation) {
            return null
        }
        val width = prefs.getInt(WIDTH, 0)
        val height = prefs.getInt(HEIGHT, 0)
        return IntSize(width, height).takeIf {
            it.width > 0 &&
                it.height > 0 &&
                it.width <= context.resources.displayMetrics.widthPixels
        }
    }
}
