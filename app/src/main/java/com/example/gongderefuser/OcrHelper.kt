package com.example.gongderefuser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

object OcrHelper {

    data class OrderRegionText(
        val fullText: String,
        val isPairOffer: Boolean,
        val hasAnchoredCard: Boolean,
        val cardText: String,
        val typeText: String,
        val priceText: String,
        val tripText: String,
        val detailText: String,
        val merchantText: String,
        val merchantWideText: String,
        val addressText: String,
        val addressWideText: String,
        val addressLowerText: String
    )

    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    fun run(bitmap: Bitmap, callback: (String) -> Unit) {

        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                callback(result.text)
            }
            .addOnFailureListener {
                callback("")
            }
    }

    fun runOrderRegions(context: Context, bitmap: Bitmap, callback: (OrderRegionText) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                runOrderRegions(context.applicationContext, bitmap, result.text, callback)
            }
            .addOnFailureListener {
                runOrderRegions(context.applicationContext, bitmap, "", callback)
            }
    }

    private fun runOrderRegions(
        context: Context,
        bitmap: Bitmap,
        fullText: String,
        callback: (OrderRegionText) -> Unit
    ) {
        val button = findActionButton(bitmap)
        val isPairOffer = fullText.contains("配對") ||
                fullText.contains("配对") ||
                button?.isPair == true ||
                looksLikePairActionButton(bitmap)
        val profile = if (isPairOffer) RegionProfile.Pair else RegionProfile.Accept
        val anchoredRegions = buildAnchoredRegions(bitmap, button, isPairOffer)
        val hasAnchoredCard = anchoredRegions != null
        val regions = anchoredRegions ?: buildFallbackRegions(bitmap, profile)
        val results = mutableMapOf<String, String>()
        var remaining = regions.size
        val lock = Any()

        fun finishOne(key: String, text: String) {
            synchronized(lock) {
                results[key] = text
                remaining -= 1
                if (remaining == 0) {
                    callback(
                        OrderRegionText(
                            fullText = fullText,
                            isPairOffer = isPairOffer,
                            hasAnchoredCard = hasAnchoredCard,
                            cardText = results["card"].orEmpty(),
                            typeText = results["type"].orEmpty(),
                            priceText = results["price"].orEmpty(),
                            tripText = results["trip"].orEmpty(),
                            detailText = results["detail"].orEmpty(),
                            merchantText = results["merchant"].orEmpty(),
                            merchantWideText = results["merchantWide"].orEmpty(),
                            addressText = results["address"].orEmpty(),
                            addressWideText = results["addressWide"].orEmpty(),
                            addressLowerText = results["addressLower"].orEmpty()
                        )
                    )
                }
            }
        }

        regions.forEach { (key, regionBitmap) ->
            if (regionBitmap.width <= 1 && regionBitmap.height <= 1) {
                finishOne(key, "")
            } else {
                runMlKitRegion(regionBitmap) { text -> finishOne(key, text) }
            }
        }
    }

    private fun runMlKitRegion(bitmap: Bitmap, callback: (String) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                callback(result.text)
            }
            .addOnFailureListener {
                callback("")
            }
    }

    private data class ActionButton(
        val rect: Rect,
        val isPair: Boolean
    )

    private data class DeliveryAnchor(
        val lineX: Int,
        val top: Int,
        val bottom: Int,
        val pickupY: Int,
        val dropoffY: Int
    )

    private fun buildFallbackRegions(bitmap: Bitmap, profile: RegionProfile): List<Pair<String, Bitmap>> {
        return listOf(
            "card" to crop(bitmap, 0.03f, profile.cardTop, 0.97f, 0.97f),
            "type" to prepareForOcr(crop(bitmap, 0.06f, profile.typeTop, 0.56f, profile.typeBottom)),
            "price" to prepareForOcr(crop(bitmap, 0.05f, profile.priceTop, 0.45f, profile.priceBottom)),
            "trip" to prepareForOcr(crop(bitmap, 0.05f, profile.tripTop, 0.92f, profile.tripBottom)),
            "detail" to prepareForOcr(crop(bitmap, 0.00f, profile.merchantTop - 0.01f, 1.00f, profile.addressBottom + 0.04f)),
            "merchantWide" to blankBitmap(),
            "merchant" to blankBitmap(),
            "addressWide" to blankBitmap(),
            "address" to blankBitmap(),
            "addressLower" to blankBitmap()
        )
    }

    private fun buildAnchoredRegions(
        bitmap: Bitmap,
        button: ActionButton?,
        isPairOffer: Boolean
    ): List<Pair<String, Bitmap>>? {
        val actionButton = button ?: return null
        val card = findCardRect(bitmap, actionButton)
        val cardHeight = card.height().coerceAtLeast(1)
        val cardWidth = card.width().coerceAtLeast(1)
        val buttonTop = actionButton.rect.top.coerceIn(card.top, card.bottom)
        val anchor = findDeliveryAnchor(bitmap, card, buttonTop)

        fun x(relative: Float): Int = (card.left + cardWidth * relative).toInt()
        fun y(relative: Float): Int = (card.top + cardHeight * relative).toInt()

        val detailTop = anchor?.let { (it.top - cardHeight * 0.05f).toInt() }
            ?: y(if (isPairOffer) 0.49f else 0.51f)
        val detailBottom = (buttonTop - cardHeight * 0.03f).toInt()
            .coerceAtLeast(detailTop + 1)
        // The pickup/dropoff icons stay at a fixed X within the offer card. Use
        // them only for Y anchors; keep text crops on fixed card-relative X
        // positions so leading letters/numbers are not cut off by line jitter.
        val detailWideLeft = x(0.105f)
        val detailLeft = x(0.13f)
        val detailSafeLeft = x(0.145f)
        val detailRight = (card.right - cardWidth * 0.05f).toInt()

        val merchantTop = anchor?.let { (it.pickupY - cardHeight * 0.065f).toInt() }
            ?: y(if (isPairOffer) 0.48f else 0.50f)
        val merchantBottom = anchor?.let { (it.pickupY + cardHeight * 0.075f).toInt() }
            ?: y(if (isPairOffer) 0.62f else 0.64f)
        val addressTop = anchor?.let { (it.dropoffY - cardHeight * 0.115f).toInt() }
            ?: y(if (isPairOffer) 0.58f else 0.60f)
        val addressBottom = detailBottom

        return listOf(
            "card" to crop(bitmap, card),
            "type" to prepareForOcr(crop(bitmap, x(0.03f), y(0.02f), x(0.55f), y(0.14f))),
            "price" to prepareForOcr(crop(bitmap, x(0.03f), y(0.13f), x(0.50f), y(0.33f))),
            "trip" to prepareForOcr(crop(bitmap, x(0.03f), y(0.32f), x(0.92f), y(0.48f))),
            "detail" to prepareForOcr(crop(bitmap, detailLeft, detailTop, detailRight, detailBottom)),
            "merchantWide" to prepareForOcr(crop(bitmap, detailWideLeft, merchantTop, detailRight, merchantBottom)),
            "merchant" to prepareForOcr(crop(bitmap, detailSafeLeft, merchantTop, detailRight, merchantBottom)),
            "addressWide" to prepareForOcr(crop(bitmap, detailWideLeft, addressTop, detailRight, addressBottom)),
            "address" to prepareForOcr(crop(bitmap, detailSafeLeft, addressTop, detailRight, addressBottom)),
            "addressLower" to prepareForOcr(crop(bitmap, detailLeft, (addressTop + cardHeight * 0.09f).toInt(), detailRight, addressBottom))
        )
    }

    private fun blankBitmap(): Bitmap {
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
    }

    private sealed class RegionProfile(
        val cardTop: Float,
        val typeTop: Float,
        val typeBottom: Float,
        val priceTop: Float,
        val priceBottom: Float,
        val tripTop: Float,
        val tripBottom: Float,
        val merchantTop: Float,
        val merchantBottom: Float,
        val addressTop: Float,
        val addressBottom: Float,
        val addressSecondTop: Float,
        val addressSecondBottom: Float
    ) {
        object Accept : RegionProfile(
            cardTop = 0.56f,
            typeTop = 0.58f,
            typeBottom = 0.64f,
            priceTop = 0.63f,
            priceBottom = 0.71f,
            tripTop = 0.73f,
            tripBottom = 0.79f,
            merchantTop = 0.80f,
            merchantBottom = 0.84f,
            addressTop = 0.84f,
            addressBottom = 0.955f,
            addressSecondTop = 0.855f,
            addressSecondBottom = 0.965f
        )

        object Pair : RegionProfile(
            cardTop = 0.54f,
            typeTop = 0.56f,
            typeBottom = 0.62f,
            priceTop = 0.61f,
            priceBottom = 0.69f,
            tripTop = 0.70f,
            tripBottom = 0.76f,
            merchantTop = 0.77f,
            merchantBottom = 0.81f,
            addressTop = 0.81f,
            addressBottom = 0.94f,
            addressSecondTop = 0.835f,
            addressSecondBottom = 0.95f
        )
    }

    private fun crop(
        bitmap: Bitmap,
        leftRatio: Float,
        topRatio: Float,
        rightRatio: Float,
        bottomRatio: Float
    ): Bitmap {
        val left = (bitmap.width * leftRatio).toInt().coerceIn(0, bitmap.width - 1)
        val top = (bitmap.height * topRatio).toInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * rightRatio).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bitmap.height * bottomRatio).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun crop(bitmap: Bitmap, rect: Rect): Bitmap {
        return crop(bitmap, rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun crop(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Bitmap {
        val safeLeft = left.coerceIn(0, bitmap.width - 1)
        val safeTop = top.coerceIn(0, bitmap.height - 1)
        val safeRight = right.coerceIn(safeLeft + 1, bitmap.width)
        val safeBottom = bottom.coerceIn(safeTop + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)
    }

    private fun findActionButton(bitmap: Bitmap): ActionButton? {
        val searchLeft = (bitmap.width * 0.08f).toInt()
        val searchRight = (bitmap.width * 0.96f).toInt()
        val searchTop = (bitmap.height * 0.58f).toInt()
        val searchBottom = (bitmap.height * 0.97f).toInt()
        val minRunWidth = (bitmap.width * 0.48f).toInt()
        val groups = mutableListOf<Pair<IntRange, Boolean>>()
        var groupStart = -1
        var quietRows = 0
        var groupPair = false

        var y = searchTop
        while (y < searchBottom) {
            val greenRun = longestColorRun(bitmap, y, searchLeft, searchRight) { color ->
                isGreenActionPixel(color)
            }
            val blackRun = longestColorRun(bitmap, y, searchLeft, searchRight) { color ->
                isBlackActionPixel(color)
            }
            val isCandidate = greenRun.width >= minRunWidth || blackRun.width >= minRunWidth
            val isPairRow = blackRun.width > greenRun.width
            if (isCandidate) {
                if (groupStart < 0) groupStart = y
                groupPair = groupPair || isPairRow
                quietRows = 0
            } else if (groupStart >= 0) {
                quietRows += 1
                if (quietRows >= 6) {
                    groups.add((groupStart..(y - quietRows)) to groupPair)
                    groupStart = -1
                    quietRows = 0
                    groupPair = false
                }
            }
            y += 2
        }
        if (groupStart >= 0) groups.add((groupStart..searchBottom) to groupPair)

        val selected = groups
            .filter { it.first.last - it.first.first >= bitmap.height * 0.025f }
            .maxByOrNull { it.first.last }
            ?: return null

        val centerY = ((selected.first.first + selected.first.last) / 2).coerceIn(0, bitmap.height - 1)
        val run = if (selected.second) {
            longestColorRun(bitmap, centerY, searchLeft, searchRight, ::isBlackActionPixel)
        } else {
            longestColorRun(bitmap, centerY, searchLeft, searchRight, ::isGreenActionPixel)
        }
        if (run.width < minRunWidth) return null

        val left = run.start - 12
        val right = run.end + 12
        val top = selected.first.first - 8
        val bottom = selected.first.last + 8
        return ActionButton(
            Rect(
                left.coerceIn(0, bitmap.width - 1),
                top.coerceIn(0, bitmap.height - 1),
                right.coerceIn(left + 1, bitmap.width),
                bottom.coerceIn(top + 1, bitmap.height)
            ),
            selected.second
        )
    }

    private fun findCardRect(bitmap: Bitmap, button: ActionButton): Rect {
        val left = (bitmap.width * 0.035f).toInt().coerceAtLeast(0)
        val right = (bitmap.width * 0.965f).toInt().coerceAtMost(bitmap.width)
        val bottom = (button.rect.bottom + bitmap.height * 0.035f).toInt().coerceAtMost(bitmap.height)
        val span = (right - left).coerceAtLeast(1)
        val borderThreshold = (span * 0.34f).toInt()
        val fallbackTop = (button.rect.top - bitmap.height * if (button.isPair) 0.34f else 0.33f)
            .toInt()
            .coerceIn(0, button.rect.top - 1)

        var top = fallbackTop
        var y = (button.rect.top - bitmap.height * 0.055f).toInt().coerceAtLeast(0)
        while (y > (bitmap.height * 0.33f).toInt()) {
            var count = 0
            var x = left
            while (x < right) {
                if (isCardBorderPixel(bitmap.getPixel(x, y), button.isPair)) count += 1
                x += 2
            }
            if (count * 2 >= borderThreshold) {
                top = (y - bitmap.height * 0.006f).toInt().coerceAtLeast(0)
                break
            }
            y -= 2
        }

        return Rect(left, top, right, bottom)
    }

    private fun findDeliveryAnchor(bitmap: Bitmap, card: Rect, buttonTop: Int): DeliveryAnchor? {
        val left = (card.left + card.width() * 0.04f).toInt().coerceAtLeast(0)
        val right = (card.left + card.width() * 0.20f).toInt().coerceAtMost(bitmap.width)
        val top = (card.top + card.height() * 0.45f).toInt().coerceAtLeast(0)
        val bottom = (buttonTop - card.height() * 0.04f).toInt().coerceIn(top + 1, bitmap.height)
        var bestX = left
        var bestCount = 0
        var x = left
        while (x < right) {
            var count = 0
            var y = top
            while (y < bottom) {
                if (isDarkPixel(bitmap.getPixel(x, y))) count += 1
                y += 2
            }
            if (count > bestCount) {
                bestCount = count
                bestX = x
            }
            x += 2
        }
        if (bestCount < (bottom - top) / 18) return null

        var anchorTop = bottom
        var anchorBottom = top
        var y = top
        while (y < bottom) {
            var dark = false
            x = (bestX - 7).coerceAtLeast(0)
            while (x <= (bestX + 7).coerceAtMost(bitmap.width - 1)) {
                if (isDarkPixel(bitmap.getPixel(x, y))) {
                    dark = true
                    break
                }
                x += 2
            }
            if (dark) {
                anchorTop = minOf(anchorTop, y)
                anchorBottom = maxOf(anchorBottom, y)
            }
            y += 2
        }
        if (anchorBottom <= anchorTop) return null
        val iconCenters = findDeliveryIconCenters(bitmap, bestX, card, top, bottom)
        return DeliveryAnchor(
            lineX = bestX,
            top = anchorTop,
            bottom = anchorBottom,
            pickupY = iconCenters.first ?: anchorTop,
            dropoffY = iconCenters.second ?: anchorBottom
        )
    }

    private fun findDeliveryIconCenters(
        bitmap: Bitmap,
        lineX: Int,
        card: Rect,
        top: Int,
        bottom: Int
    ): Pair<Int?, Int?> {
        val halfWidth = (card.width() * 0.055f).toInt().coerceAtLeast(12)
        val left = (lineX - halfWidth).coerceAtLeast(0)
        val right = (lineX + halfWidth).coerceAtMost(bitmap.width - 1)
        val minWideDarkPixels = 5
        val groups = mutableListOf<IntRange>()
        var groupStart = -1
        var quietRows = 0
        var y = top
        while (y < bottom) {
            var darkCount = 0
            var x = left
            while (x <= right) {
                if (isDarkPixel(bitmap.getPixel(x, y))) darkCount += 1
                x += 3
            }
            if (darkCount >= minWideDarkPixels) {
                if (groupStart < 0) groupStart = y
                quietRows = 0
            } else if (groupStart >= 0) {
                quietRows += 1
                if (quietRows >= 5) {
                    groups.add(groupStart..(y - quietRows))
                    groupStart = -1
                    quietRows = 0
                }
            }
            y += 2
        }
        if (groupStart >= 0) groups.add(groupStart..bottom)

        val meaningful = groups.filter { it.last - it.first >= 8 }
        val pickup = meaningful.firstOrNull()?.let { (it.first + it.last) / 2 }
        val dropoff = meaningful.lastOrNull()?.let { (it.first + it.last) / 2 }
        return pickup to dropoff
    }

    private fun isActionButtonPixel(color: Int): Boolean {
        return isGreenActionPixel(color) || isBlackActionPixel(color)
    }

    private data class ColorRun(val start: Int, val end: Int) {
        val width: Int get() = end - start
    }

    private fun longestColorRun(
        bitmap: Bitmap,
        y: Int,
        left: Int,
        right: Int,
        predicate: (Int) -> Boolean
    ): ColorRun {
        var bestStart = left
        var bestEnd = left
        var currentStart = -1
        var x = left
        while (x < right) {
            if (predicate(bitmap.getPixel(x, y))) {
                if (currentStart < 0) currentStart = x
            } else if (currentStart >= 0) {
                if (x - currentStart > bestEnd - bestStart) {
                    bestStart = currentStart
                    bestEnd = x
                }
                currentStart = -1
            }
            x += 2
        }
        if (currentStart >= 0 && right - currentStart > bestEnd - bestStart) {
            bestStart = currentStart
            bestEnd = right
        }
        return ColorRun(bestStart, bestEnd)
    }

    private fun isGreenActionPixel(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return green > 105 && green > red + 32 && green > blue + 24 && red < 130
    }

    private fun isBlackActionPixel(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return red < 45 && green < 45 && blue < 45
    }

    private fun isCardBorderPixel(color: Int, pair: Boolean): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return if (pair) {
            red < 70 && green < 70 && blue < 70
        } else {
            green > 115 && green > red + 34 && green > blue + 24 && red < 130
        }
    }

    private fun isDarkPixel(color: Int): Boolean {
        return Color.red(color) < 65 && Color.green(color) < 65 && Color.blue(color) < 65
    }

    private fun sampleDarkButtonRatio(bitmap: Bitmap, rect: Rect): Float {
        var dark = 0
        var total = 0
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                if (isDarkPixel(bitmap.getPixel(x, y))) dark += 1
                total += 1
                x += 12
            }
            y += 8
        }
        return if (total == 0) 0f else dark.toFloat() / total
    }

    private fun looksLikePairActionButton(bitmap: Bitmap): Boolean {
        val left = (bitmap.width * 0.22f).toInt().coerceIn(0, bitmap.width - 1)
        val right = (bitmap.width * 0.94f).toInt().coerceIn(left + 1, bitmap.width)
        val top = (bitmap.height * 0.88f).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = (bitmap.height * 0.95f).toInt().coerceIn(top + 1, bitmap.height)
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        var count = 0
        val stepX = ((right - left) / 24).coerceAtLeast(1)
        val stepY = ((bottom - top) / 8).coerceAtLeast(1)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = bitmap.getPixel(x, y)
                redSum += android.graphics.Color.red(color)
                greenSum += android.graphics.Color.green(color)
                blueSum += android.graphics.Color.blue(color)
                count += 1
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return false
        val red = redSum / count
        val green = greenSum / count
        val blue = blueSum / count
        val brightness = (red + green + blue) / 3
        val greenDominant = green > red + 24 && green > blue + 24
        return brightness < 95 && !greenDominant
    }

    private fun prepareForOcr(bitmap: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width * 3, bitmap.height * 3, true)
        val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(0f)
                    postConcat(ColorMatrix(floatArrayOf(
                        1.45f, 0f, 0f, 0f, -36f,
                        0f, 1.45f, 0f, 0f, -36f,
                        0f, 0f, 1.45f, 0f, -36f,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
            )
        }
        Canvas(output).drawBitmap(scaled, 0f, 0f, paint)
        return output
    }
}
