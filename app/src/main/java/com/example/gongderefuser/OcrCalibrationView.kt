package com.example.gongderefuser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class OcrCalibrationView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val regions = linkedMapOf<String, RectF>()
    private val imageRect = RectF()
    private var selectedName: String = "actionButton"
    private var lastX = 0f
    private var lastY = 0f
    private var mode = DragMode.None

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = 28f
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setImageAndRegions(source: Bitmap, savedRegions: Map<String, RectF>) {
        bitmap = source
        regions.clear()
        OcrCalibrationStore.regionNames.forEach { name ->
            regions[name] = RectF(savedRegions[name] ?: OcrCalibrationStore.defaultRegions().getValue(name))
        }
        selectedName = regions.keys.firstOrNull { it == "actionButton" } ?: regions.keys.first()
        requestLayout()
        invalidate()
    }

    fun currentRegions(): Map<String, RectF> {
        return regions.mapValues { RectF(it.value) }
    }

    fun selectRegion(name: String) {
        if (regions.containsKey(name)) {
            selectedName = name
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val source = bitmap
        val desiredHeight = if (source == null) {
            width
        } else {
            (width * source.height.toFloat() / source.width.toFloat()).toInt()
        }
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> desiredHeight.coerceAtMost(heightSize)
            else -> desiredHeight
        }
        setMeasuredDimension(width, height.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap ?: return
        updateImageRect(source)
        canvas.drawBitmap(source, null, imageRect, imagePaint)

        val normalized = regions[selectedName] ?: return
        val rect = toViewRect(normalized)
        val color = regionColor(selectedName)
        fillPaint.color = withAlpha(color, 48)
        strokePaint.color = color
        strokePaint.strokeWidth = 7f
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, strokePaint)
        drawLabel(canvas, selectedName, rect, color)
        canvas.drawCircle(rect.right, rect.bottom, 18f, strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        updateImageRect(bitmap ?: return false)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val hit = hitRegion(event.x, event.y)
                if (hit != null) selectedName = hit
                val rect = toViewRect(regions.getValue(selectedName))
                mode = if (abs(event.x - rect.right) < 48f && abs(event.y - rect.bottom) < 48f) {
                    DragMode.Resize
                } else {
                    DragMode.Move
                }
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - lastX) / imageRect.width().coerceAtLeast(1f)
                val dy = (event.y - lastY) / imageRect.height().coerceAtLeast(1f)
                val rect = RectF(regions.getValue(selectedName))
                if (mode == DragMode.Resize) {
                    rect.right += dx
                    rect.bottom += dy
                } else if (mode == DragMode.Move) {
                    rect.offset(dx, dy)
                }
                regions[selectedName] = clamp(rect)
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                mode = DragMode.None
                return true
            }
        }
        return true
    }

    private fun hitRegion(x: Float, y: Float): String? {
        return selectedName.takeIf {
            regions[it]?.let { rect -> toViewRect(rect).contains(x, y) } == true
        }
    }

    private fun toViewRect(normalized: RectF): RectF {
        return RectF(
            imageRect.left + imageRect.width() * normalized.left,
            imageRect.top + imageRect.height() * normalized.top,
            imageRect.left + imageRect.width() * normalized.right,
            imageRect.top + imageRect.height() * normalized.bottom
        )
    }

    private fun updateImageRect(source: Bitmap) {
        val viewWidth = width.toFloat().coerceAtLeast(1f)
        val viewHeight = height.toFloat().coerceAtLeast(1f)
        val sourceRatio = source.width.toFloat() / source.height.toFloat()
        val viewRatio = viewWidth / viewHeight
        if (viewRatio > sourceRatio) {
            val imageWidth = viewHeight * sourceRatio
            val left = (viewWidth - imageWidth) / 2f
            imageRect.set(left, 0f, left + imageWidth, viewHeight)
        } else {
            val imageHeight = viewWidth / sourceRatio
            val top = (viewHeight - imageHeight) / 2f
            imageRect.set(0f, top, viewWidth, top + imageHeight)
        }
    }

    private fun clamp(rect: RectF): RectF {
        val minWidth = 0.03f
        val minHeight = 0.025f
        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom

        if (right - left < minWidth) right = left + minWidth
        if (bottom - top < minHeight) bottom = top + minHeight
        if (left < 0f) {
            right -= left
            left = 0f
        }
        if (top < 0f) {
            bottom -= top
            top = 0f
        }
        if (right > 1f) {
            left -= right - 1f
            right = 1f
        }
        if (bottom > 1f) {
            top -= bottom - 1f
            bottom = 1f
        }
        return RectF(
            left.coerceIn(0f, 1f - minWidth),
            top.coerceIn(0f, 1f - minHeight),
            right.coerceIn(left + minWidth, 1f),
            bottom.coerceIn(top + minHeight, 1f)
        )
    }

    private fun drawLabel(canvas: Canvas, name: String, rect: RectF, color: Int) {
        labelBgPaint.color = withAlpha(color, if (name == selectedName) 210 else 160)
        val padding = 8f
        val label = OcrCalibrationStore.displayName(name)
        val textWidth = labelPaint.measureText(label)
        val labelHeight = labelPaint.textSize + padding * 2
        val top = (rect.top - labelHeight).coerceAtLeast(0f)
        canvas.drawRect(rect.left, top, rect.left + textWidth + padding * 2, top + labelHeight, labelBgPaint)
        canvas.drawText(label, rect.left + padding, top + labelPaint.textSize + padding / 2, labelPaint)
    }

    private fun regionColor(name: String): Int {
        return when (name) {
            "actionButton" -> Color.rgb(255, 45, 85)
            "deliveryAnchor" -> Color.rgb(0, 122, 255)
            "pickupAnchor" -> Color.rgb(90, 200, 250)
            "dropoffAnchor" -> Color.rgb(88, 86, 214)
            "card" -> Color.rgb(0, 122, 255)
            "type" -> Color.rgb(128, 0, 255)
            "price" -> Color.rgb(255, 149, 0)
            "trip" -> Color.rgb(255, 214, 10)
            "merchant", "merchantWide" -> Color.rgb(52, 199, 89)
            "address", "addressWide", "addressLower" -> Color.rgb(255, 59, 48)
            else -> Color.rgb(90, 200, 250)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private enum class DragMode {
        None,
        Move,
        Resize
    }
}
