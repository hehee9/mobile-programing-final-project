package com.sch.mobile.travelrecord.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {
    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private var normalizedScale = MIN_SCALE
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    init {
        scaleType = ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (normalizedScale > MIN_SCALE + 0.05f) {
                    resetZoom()
                } else {
                    scaleTo(DOUBLE_TAP_SCALE, event.x, event.y)
                }
                return true
            }
        })
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        resetZoom()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (normalizedScale > MIN_SCALE + 0.02f) {
            parent.requestDisallowInterceptTouchEvent(true)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress && normalizedScale > MIN_SCALE + 0.02f) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    matrix.postTranslate(dx, dy)
                    fixTranslation()
                    imageMatrix = matrix
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (normalizedScale <= MIN_SCALE + 0.02f) {
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return true
    }

    fun resetZoom() {
        val drawable = drawable ?: return
        val viewWidth = width
        val viewHeight = height
        if (viewWidth <= 0 || viewHeight <= 0) return
        val drawableWidth = drawable.intrinsicWidth
        val drawableHeight = drawable.intrinsicHeight
        if (drawableWidth <= 0 || drawableHeight <= 0) return
        val scale = min(viewWidth / drawableWidth.toFloat(), viewHeight / drawableHeight.toFloat())
        val dx = (viewWidth - drawableWidth * scale) / 2f
        val dy = (viewHeight - drawableHeight * scale) / 2f
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)
        normalizedScale = MIN_SCALE
        imageMatrix = matrix
    }

    private fun scaleTo(targetScale: Float, focusX: Float, focusY: Float) {
        val safeTarget = max(MIN_SCALE, min(MAX_SCALE, targetScale))
        val factor = safeTarget / normalizedScale
        normalizedScale = safeTarget
        matrix.postScale(factor, factor, focusX, focusY)
        fixTranslation()
        imageMatrix = matrix
    }

    private fun fixTranslation() {
        val rect = imageRect ?: return
        val deltaX = getCorrection(rect.width(), rect.left, width.toFloat())
        val deltaY = getCorrection(rect.height(), rect.top, height.toFloat())
        matrix.postTranslate(deltaX, deltaY)
    }

    private fun getCorrection(contentSize: Float, start: Float, viewSize: Float): Float {
        if (contentSize <= viewSize) return (viewSize - contentSize) / 2f - start
        if (start > 0) return -start
        val end = start + contentSize
        if (end < viewSize) return viewSize - end
        return 0f
    }

    private val imageRect: RectF?
        get() {
            val drawable = drawable ?: return null
            val rect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
            matrix.mapRect(rect)
            return rect
        }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var scaleFactor = detector.scaleFactor
            var nextScale = normalizedScale * scaleFactor
            if (nextScale > MAX_SCALE) {
                scaleFactor = MAX_SCALE / normalizedScale
                nextScale = MAX_SCALE
            } else if (nextScale < MIN_SCALE) {
                scaleFactor = MIN_SCALE / normalizedScale
                nextScale = MIN_SCALE
            }
            normalizedScale = nextScale
            matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            fixTranslation()
            imageMatrix = matrix
            return true
        }
    }

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 4f
        private const val DOUBLE_TAP_SCALE = 2.5f
    }
}
