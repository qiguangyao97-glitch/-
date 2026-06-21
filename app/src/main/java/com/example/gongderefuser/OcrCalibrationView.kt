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
    private var selectedName: String = "card"
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
        selectedName = OcrCalibrationStore.editableRegionNames.first()
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

        visibleRegionNames().forEach { name ->
            val normalized = regions[name] ?: return@forEach
            val rect = toViewRect(normalized)
            val color = regionColor(name)
            fillPaint.color = withAlpha(color, if (name == selectedName) 56 else 26)
            strokePaint.color = color
            strokePaint.strokeWidth = if (name == selectedName) 7f else 4f
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, strokePaint)
            drawCenterPoint(canvas, rect, color)
            drawSpecialHint(canvas, name, rect, color)
            if (name == selectedName) {
                drawLabel(canvas, name, rect, color)
                canvas.drawCircle(rect.right, rect.bottom, 18f, strokePaint)
            }
        }
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
                val before = RectF(regions.getValue(selectedName))
                val rect = RectF(before)
                if (mode == DragMode.Resize) {
                    rect.right += dx
                    rect.bottom += dy
                } else if (mode == DragMode.Move) {
                    rect.offset(dx, dy)
                }
                regions[selectedName] = clamp(rect)
                applyLinkedMove(selectedName, regions.getValue(selectedName).centerX() - before.centerX(), regions.getValue(selectedName).centerY() - before.centerY())
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
        return visibleRegionNames().lastOrNull {
            isSelectableProxy(it) && regions[it]?.let { rect -> toViewRect(rect).contains(x, y) } == true
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
        val minWidth = 0.008f
        val minHeight = 0.008f
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

    private fun drawCenterPoint(canvas: Canvas, rect: RectF, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = 3f
        val cx = rect.centerX()
        val cy = rect.centerY()
        canvas.drawLine(cx - 14f, cy, cx + 14f, cy, strokePaint)
        canvas.drawLine(cx, cy - 14f, cx, cy + 14f, strokePaint)
        fillPaint.color = color
        canvas.drawCircle(cx, cy, 5f, fillPaint)
    }

    private fun drawSpecialHint(canvas: Canvas, name: String, rect: RectF, color: Int) {
        when (name) {
            "closeSearch" -> drawCloseSearchStack(canvas, rect, color)
            "closeButton" -> drawCenteredText(canvas, "X", rect, color, 32f)
            "pickupAnchor" -> {
                fillPaint.color = withAlpha(color, 220)
                canvas.drawCircle(rect.centerX(), rect.centerY(), (rect.height() * 0.32f).coerceAtLeast(7f), fillPaint)
            }
            "dropoffAnchor" -> {
                fillPaint.color = withAlpha(color, 220)
                val size = (rect.height() * 0.56f).coerceAtLeast(12f)
                canvas.drawRect(rect.centerX() - size / 2f, rect.centerY() - size / 2f, rect.centerX() + size / 2f, rect.centerY() + size / 2f, fillPaint)
            }
        }
    }

    private fun drawCloseSearchStack(canvas: Canvas, rect: RectF, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = 3f
        val squareHeight = rect.height() / 3f
        for (index in 0 until 3) {
            val top = rect.top + squareHeight * index
            val square = RectF(rect.left, top, rect.right, top + squareHeight)
            canvas.drawRect(square, strokePaint)
            drawCenteredText(canvas, "X", square, color, 22f)
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, rect: RectF, color: Int, size: Float) {
        labelPaint.color = color
        labelPaint.textSize = size
        labelPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        labelPaint.textAlign = Paint.Align.CENTER
        val y = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(text, rect.centerX(), y, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.typeface = android.graphics.Typeface.DEFAULT
        labelPaint.color = Color.WHITE
        labelPaint.textSize = 28f
    }

    private fun visibleRegionNames(): List<String> {
        return when (selectedName) {
            "closeSearch" -> listOf("closeSearch", "closeButton")
            "closeButton" -> listOf("closeSearch", "closeButton")
            "price" -> listOf("type", "price")
            "type" -> listOf("type", "price")
            "merchant" -> listOf("pickupAnchor", "merchant")
            "pickupAnchor" -> listOf("pickupAnchor", "merchant")
            "address" -> listOf("dropoffAnchor", "address", "addressWide")
            "addressWide" -> listOf("dropoffAnchor", "address", "addressWide")
            "dropoffAnchor" -> listOf("dropoffAnchor", "address", "addressWide")
            "deliveryAnchorSearch" -> listOf("pickupAnchor", "dropoffAnchor", "deliveryAnchorSearch")
            else -> listOf(selectedName)
        }.distinct().filter { regions.containsKey(it) }
    }

    private fun isSelectableProxy(name: String): Boolean {
        return name in OcrCalibrationStore.editableRegionNames
    }

    private fun applyLinkedMove(name: String, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val linked = when (name) {
            "closeButton" -> listOf("closeSearch")
            "price" -> listOf("type")
            "merchant" -> listOf("pickupAnchor")
            "address" -> listOf("dropoffAnchor", "addressWide")
            else -> emptyList()
        }
        linked.forEach { linkedName ->
            regions[linkedName]?.let {
                val moved = RectF(it)
                moved.offset(dx, dy)
                regions[linkedName] = clamp(moved)
            }
        }
    }

    private fun regionColor(name: String): Int {
        return when (name) {
            "actionButton" -> Color.rgb(255, 45, 85)
            "closeButton" -> Color.rgb(7, 56, 135)
            "deliveryAnchor" -> Color.rgb(0, 122, 255)
            "pickupAnchor", "pickupAnchorShiftedReference" -> Color.rgb(90, 200, 250)
            "dropoffAnchor", "dropoffAnchorShiftedReference" -> Color.rgb(88, 86, 214)
            "deliveryAnchorSearch" -> Color.rgb(0, 180, 255)
            "card" -> Color.rgb(0, 122, 255)
            "type" -> Color.rgb(128, 0, 255)
            "price" -> Color.rgb(255, 149, 0)
            "trip" -> Color.rgb(255, 214, 10)
            "sameDropoff" -> Color.rgb(48, 209, 88)
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
