package com.radiozport.ninegfiles.ui.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * A FrameLayout that adds pinch-to-zoom and horizontal panning to its single
 * RecyclerView child. Zoom is applied via scaleX/Y + translationX on the child
 * so Android's view system correctly inverse-transforms touch coordinates,
 * keeping RecyclerView's own vertical scrolling fully functional while zoomed.
 *
 * Gesture handling:
 *  - Pinch          → zoom (1x – 5x)
 *  - Double-tap     → toggle 2.5x / reset to 1x
 *  - Horizontal drag while zoomed → pan left/right
 *  - Vertical drag  → passed through to RecyclerView (normal page scrolling)
 */
class PdfZoomContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var scaleFactor = 1f
    private var transX = 0f

    // ── Scale gesture (pinch-to-zoom) ──────────────────────────────────────

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                if (scaleFactor != prevScale) {
                    // Shift transX so the pinch focus point stays fixed on screen
                    transX = detector.focusX - (scaleFactor / prevScale) * (detector.focusX - transX)
                    clampTransX()
                }
                applyTransform()
                return true
            }
        })

    // ── Gesture detector (double-tap + horizontal pan) ──────────────────────

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                // Only steal horizontal scroll; vertical goes to the RecyclerView
                if (scaleFactor > 1f && abs(distanceX) >= abs(distanceY)) {
                    transX -= distanceX
                    clampTransX()
                    applyTransform()
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1f) {
                    // Reset
                    scaleFactor = 1f
                    transX = 0f
                } else {
                    // Zoom to 2.5× centred on the tap point
                    scaleFactor = DOUBLE_TAP_SCALE
                    transX = e.x - scaleFactor * e.x
                    clampTransX()
                }
                applyTransform()
                return true
            }
        })

    // ── Apply transform to the child RecyclerView ───────────────────────────

    private fun applyTransform() {
        val child = getChildAt(0) ?: return
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scaleFactor
        child.scaleY = scaleFactor
        child.translationX = transX
    }

    private fun clampTransX() {
        // Allow panning left until the right edge of the scaled content reaches screen right
        val minTransX = -(scaleFactor - 1f) * width
        transX = transX.coerceIn(minTransX, 0f)
    }

    // ── Touch interception ──────────────────────────────────────────────────

    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
            }
            // Two fingers down — intercept immediately for pinch handling
            MotionEvent.ACTION_POINTER_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount > 1) return true
                if (scaleFactor > 1f) {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    // Intercept horizontal drag while zoomed (for panning)
                    if (dx > dy && dx > TOUCH_SLOP) return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Give scale detector first priority so it can consume multi-touch
        scaleDetector.onTouchEvent(ev)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(ev)
        }
        return true
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun resetZoom() {
        scaleFactor = 1f
        transX = 0f
        applyTransform()
    }

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 5f
        private const val DOUBLE_TAP_SCALE = 2.5f
        private const val TOUCH_SLOP = 8f
    }
}
