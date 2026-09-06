package io.iaw.lanshare

import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.annotation.MainThread
import androidx.core.view.OneShotPreDrawListener

/** Owns one reveal request for the current rail; requests never survive a rail rebuild. */
@MainThread
internal class ServerRailScroller(
    private val viewport: HorizontalScrollView,
    private val rail: LinearLayout,
    private val inset: Int,
) {
    private var pending: OneShotPreDrawListener? = null
    private var revision = 0L

    fun cancel() {
        revision++
        pending?.removeListener()
        pending = null
    }

    fun schedule(tile: View?, animate: Boolean) {
        cancel()
        if (tile == null) return
        val scheduledRevision = revision
        // post() can run after another render removed the tile, or before its new layout.
        pending = OneShotPreDrawListener.add(viewport) {
            if (scheduledRevision != revision) return@add
            pending = null
            revealCurrentTile(tile, animate)
        }
    }

    private fun revealCurrentTile(tile: View, animate: Boolean) {
        if (tile.parent !== rail || rail.parent !== viewport ||
            !viewport.isAttachedToWindow || !viewport.isShown ||
            !tile.isLaidOut || tile.isLayoutRequested || viewport.isLayoutRequested
        ) return

        val visibleWidth = viewport.width - viewport.paddingLeft - viewport.paddingRight
        if (visibleWidth <= 0 || tile.width <= 0) return
        val visibleLeft = viewport.scrollX
        val visibleRight = visibleLeft + visibleWidth
        // Tiles are direct children: use their current bounds, never convert an obsolete descendant.
        val target = when {
            tile.left - inset < visibleLeft -> tile.left - inset
            tile.right + inset > visibleRight -> tile.right + inset - visibleWidth
            else -> visibleLeft
        }.coerceIn(0, (rail.width - visibleWidth).coerceAtLeast(0))
        if (target == visibleLeft) return
        if (animate) viewport.smoothScrollTo(target, 0) else viewport.scrollTo(target, 0)
    }
}
