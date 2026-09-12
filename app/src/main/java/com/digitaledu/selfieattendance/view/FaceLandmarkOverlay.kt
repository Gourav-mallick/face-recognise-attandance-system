package com.digitaledu.selfieattendance.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class FaceLandmarkOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(57, 255, 120)
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }
    private var points: List<PointF> = emptyList()
    private var sourceWidth = 0
    private var sourceHeight = 0

    private var isFrontCamera = false

    fun show(landmarks: List<PointF>, imageWidth: Int, imageHeight: Int, isFrontCamera: Boolean = false) {
        points = landmarks.map { PointF(it.x, it.y) }
        sourceWidth = imageWidth
        sourceHeight = imageHeight
        this.isFrontCamera = isFrontCamera
        postInvalidateOnAnimation()
    }

    fun clear() {
        points = emptyList()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty() || sourceWidth <= 0 || sourceHeight <= 0) return
        // PreviewView uses FILL_CENTER by default, so mirror its center-crop mapping.
        val scale = max(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val offsetX = (width - sourceWidth * scale) / 2f
        val offsetY = (height - sourceHeight * scale) / 2f
        val radius = 5f * resources.displayMetrics.density
        points.forEach { point ->
            val mappedX = point.x * scale + offsetX
            val drawX = if (isFrontCamera) width - mappedX else mappedX
            val drawY = point.y * scale + offsetY
            canvas.drawCircle(drawX, drawY, radius, dotPaint)
        }
    }
}
