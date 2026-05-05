package com.radiozport.ninegfiles.ui.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * A FrameLayout that adds pinch-to-zoom and free (diagonal) panning to its
 * single RecyclerView child.
 *
 * Transform strategy:
 *  - Scale  → scaleX/Y on the child (pivot at top-left)
 *  - Horizontal pan → child.translationX  (managed here via OverScroller)
 *  - Vertical pan   → RecyclerView.scrollBy / fling  (RecyclerView owns its
 *                     own scroll state, so we delegate instead of translationY
 *                     to keep item recycling and page layout correct)
 *
 * Gestures:
 *  - Pinch           → zoom 1x – 5x
 *  - Double-tap      → toggle 2.5x / reset to 1x
 *  - Any drag while zoomed → free diagonal pan (X + Y simultaneously)
 *  - Fling while zoomed    → momentum on both axes
 */
class PdfZoomContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var scaleFactor = 1f
    private var transX = 0f

    // ── Fling / momentum ────────────────────────────────────────────────────

    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // ── Pinch-to-zoom ───────────────────────────────────────────────────────

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prev = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                if (scaleFactor != prev) {
                    transX = detector.focusX - (scaleFactor / prev) * (detector.focusX - transX)
                    clampTransX()
                }
                applyTransform()
                return true
            }
        })

    // ── Pan + fling (all directions when zoomed) ────────────────────────────

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                if (scaleFactor <= 1f) return false

                // Horizontal component → translationX on the container
                transX -= distanceX
                clampTransX()
                applyTransform()

                // Vertical component → RecyclerView's own scroll (preserves
                // item recycling and keeps scroll state consistent)
                (getChildAt(0) as? RecyclerView)?.scrollBy(0, distanceY.toInt())

                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                if (scaleFactor <= 1f) return false

                // Horizontal fling via OverScroller → translationX animation
                val minX = (-(scaleFactor - 1f) * width).toInt()
                scroller.fling(
                    transX.toInt(), 0,
                    velocityX.toInt(), 0,
                    minX, 0,
                    0, 0
                )
                postInvalidateOnAnimation()

                // Vertical fling delegated directly to RecyclerView
                (getChildAt(0) as? RecyclerView)?.fling(0, (-velocityY).toInt())

                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1f) {
                    scaleFactor = 1f
                    transX = 0f
                } else {
                    scaleFactor = DOUBLE_TAP_SCALE
                    transX = e.x - scaleFactor * e.x
                    clampTransX()
                }
                applyTransform()
                return true
            }
        })

    // ── Horizontal fling animation tick ─────────────────────────────────────

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            transX = scroller.currX.toFloat()
            clampTransX()
            applyTransform()
            postInvalidateOnAnimation()
        }
    }

    // ── Transform helpers ────────────────────────────────────────────────────

    private fun applyTransform() {
        val child = getChildAt(0) ?: return
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scaleFactor
        child.scaleY = scaleFactor
        child.translationX = transX
    }

    private fun clampTransX() {
        val minTransX = -(scaleFactor - 1f) * width
        transX = transX.coerceIn(minTransX, 0f)
    }

    // ── Touch interception ───────────────────────────────────────────────────

    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                if (!scroller.isFinished) scroller.abortAnimation()
            }
            MotionEvent.ACTION_POINTER_DOWN -> return true  // start of pinch
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount > 1) return true
                if (scaleFactor > 1f) {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    // Intercept as soon as either axis exceeds touch slop —
                    // this allows diagonal panning from the very first move.
                    if (dx > touchSlop || dy > touchSlop) return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !scroller.isFinished) {
            scroller.abortAnimation()
        }
        scaleDetector.onTouchEvent(ev)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(ev)
        }
        return true
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun resetZoom() {
        scroller.abortAnimation()
        scaleFactor = 1f
        transX = 0f
        applyTransform()
    }

    companion object {
        private const val MIN_SCALE       = 1f
        private const val MAX_SCALE       = 5f
        private const val DOUBLE_TAP_SCALE = 2.5f
    }
}
