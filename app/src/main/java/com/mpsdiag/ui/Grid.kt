package com.mpsdiag.ui

import android.view.View
import android.widget.LinearLayout

/** Lays [tiles] out in rows of [columns] inside [container], with a gap between them. */
object Grid {
    fun fill(container: LinearLayout, tiles: List<View>, columns: Int = 2) {
        val gap = container.resources.getDimensionPixelSize(com.mpsdiag.R.dimen.gap_small)
        tiles.chunked(columns).forEach { rowTiles ->
            val row = LinearLayout(container.context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            rowTiles.forEachIndexed { i, tile ->
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                if (i > 0) lp.marginStart = gap
                row.addView(tile, lp)
            }
            // Keep the last tile half-width when the count is odd.
            repeat(columns - rowTiles.size) {
                row.addView(View(container.context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap })
            }
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = gap })
        }
    }
}
