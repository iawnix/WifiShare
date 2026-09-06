package io.iaw.lanshare

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ServerRailScrollerTest {
    private lateinit var activity: ActivityController<Activity>
    private lateinit var viewport: HorizontalScrollView
    private lateinit var rail: LinearLayout
    private lateinit var scroller: ServerRailScroller

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup()
        val context = activity.get()
        viewport = HorizontalScrollView(context)
        rail = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        viewport.addView(rail, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 80))
        context.setContentView(viewport)
        activity.visible()
        scroller = ServerRailScroller(viewport, rail, 8)
    }

    @After fun tearDown() {
        scroller.cancel()
        activity.pause().stop().destroy()
    }

    @Test fun removedDescendantReproducesTheReportedAndroidException() {
        val oldTile = addTiles().last()
        layoutRail()
        rail.removeAllViews()
        // The old post { offsetDescendantRectToMyCoords(oldTile, ...) } violated this contract.
        assertThrows(IllegalArgumentException::class.java) {
            rail.offsetDescendantRectToMyCoords(oldTile, Rect(0, 0, 100, 80))
        }
    }

    @Test fun twoStartupRendersBeforeFirstDrawOnlyRevealTheLatestTile() {
        val oldTile = addTiles().first()
        scroller.schedule(oldTile, animate = false)
        rail.removeAllViews()
        val currentTile = addTiles().last()
        scroller.schedule(currentTile, animate = false)
        layoutAndDraw()
        assertEquals(300, viewport.scrollX)
    }

    @Test fun detachedTargetIsIgnoredEvenWithoutExplicitCancellation() {
        val removed = addTiles().last()
        scroller.schedule(removed, animate = false)
        rail.removeAllViews()
        addTiles()
        layoutAndDraw()
        assertEquals(0, viewport.scrollX)
    }

    @Test fun rapidSelectionChangesOnlyRevealLastRequest() {
        val tiles = addTiles()
        scroller.schedule(tiles.last(), animate = false)
        scroller.schedule(tiles.first(), animate = false)
        layoutAndDraw()
        assertEquals(0, viewport.scrollX)
    }

    @Test fun cancellingOnPageExitPreventsDeferredScrolling() {
        scroller.schedule(addTiles().last(), animate = false)
        scroller.cancel()
        layoutAndDraw()
        assertEquals(0, viewport.scrollX)
    }

    @Test fun emptyRailCancelsPreviousSelection() {
        scroller.schedule(addTiles().last(), animate = false)
        scroller.schedule(null, animate = false)
        layoutAndDraw()
        assertEquals(0, viewport.scrollX)
    }

    @Test fun hiddenPageDoesNotScroll() {
        scroller.schedule(addTiles().last(), animate = false)
        layoutRail()
        viewport.visibility = View.INVISIBLE
        viewport.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(0, viewport.scrollX)
    }

    @Test fun tileAlreadyVisibleKeepsUserScrollPosition() {
        val tiles = addTiles()
        layoutRail()
        viewport.scrollTo(50, 0)
        scroller.schedule(tiles[1], animate = false)
        viewport.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(50, viewport.scrollX)
    }

    @Test fun selectingOffscreenTileScrollsBackIntoView() {
        val tiles = addTiles()
        layoutRail()
        viewport.scrollTo(300, 0)
        scroller.schedule(tiles.first(), animate = false)
        viewport.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(0, viewport.scrollX)
    }

    private fun addTiles(): List<View> = List(6) {
        View(activity.get()).also { rail.addView(it, LinearLayout.LayoutParams(100, 80)) }
    }

    private fun layoutRail() {
        viewport.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        )
        viewport.layout(0, 0, 300, 80)
    }

    private fun layoutAndDraw() {
        layoutRail()
        viewport.viewTreeObserver.dispatchOnPreDraw()
    }
}
