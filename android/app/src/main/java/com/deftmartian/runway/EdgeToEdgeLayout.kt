package com.deftmartian.runway

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal data class InsetPadding(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun plus(insets: Insets): InsetPadding = InsetPadding(
        left = left + insets.left,
        top = top + insets.top,
        right = right + insets.right,
        bottom = bottom + insets.bottom,
    )
}

internal object EdgeToEdgeLayout {
    fun applySystemBarPadding(view: View) {
        val basePadding = InsetPadding(
            left = view.paddingLeft,
            top = view.paddingTop,
            right = view.paddingRight,
            bottom = view.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val safeArea = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            val padding = basePadding.plus(safeArea)
            target.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
