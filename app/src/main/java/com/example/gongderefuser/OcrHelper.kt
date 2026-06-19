package com.example.gongderefuser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

object OcrHelper {

    data class DebugRegion(
        val name: String,
        val rect: Rect
    )

    data class OrderRegionText(
        val fullText: String,
        val isPairOffer: Boolean,
        val hasAnchoredCard: Boolean,
        val anchorDebugInfo: AnchorDebugInfo,
        val debugRegions: List<DebugRegion>,
        val cardText: String,
        val typeText: String,
        val priceText: String,
        val tripText: String,
        val detailText: String,
        val sameDropoffText: String,
        val merchantText: String,
        val merchantWideText: String,
        val addressText: String,
        val addressWideText: String,
        val addressLowerText: String,
        val ocrPreprocessDebugInfo: String = ""
    )

    data class AnchorDebugInfo(
        val anchorSource: String,
        val pickupDetected: Boolean,
        val dropoffDetected: Boolean,
        val templateCloseY: Int?,
        val actualCloseY: Int?,
        val closeShiftY: Int?,
        val pickupAnchorRect: Rect?,
        val dropoffAnchorRect: Rect?,
        val cardRect: Rect? = null,
        val closeButtonRect: Rect? = null,
        val priceRect: Rect? = null,
        val tripRect: Rect? = null,
        val merchantRect: Rect? = null,
        val addressRect: Rect? = null,
        val addressWideRect: Rect? = null
    )

    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    private const val ENABLE_OCR_PREPROCESS = true

    fun runOrderRegions(context: Context, bitmap: Bitmap, callback: (OrderRegionText) -> Unit) {
        runOrderRegionsInternal(context.applicationContext, bitmap, callback)
    }

    private fun runOrderRegionsInternal(
        context: Context,
        bitmap: Bitmap,
        callback: (OrderRegionText) -> Unit
    ) {
        val closeSearchRect = closeSearchRect(context, bitmap)
        val closeButton = findCloseButton(context, bitmap, closeSearchRect)
        val anchorDebugInfo = buildAnchorDebugInfo(context, bitmap, closeButton)
        val anchoredRegions = buildAnchoredRegions(context, bitmap, closeButton)
        val hasAnchoredCard = anchoredRegions != null
        val regions = anchoredRegions.orEmpty()
        val debugRegions = calibrationDebugRegions(context, bitmap) +
                listOfNotNull(closeButton?.let { DebugRegion("closeButtonDetected", it.rect) }) +
                diagnosticAnchorDebugRegions(context, bitmap, closeButton) +
                regions.map { DebugRegion(actualDebugName(it.name), Rect(it.rect)) }
        if (regions.isEmpty()) {
            val diagnostics = listOf(
                region(bitmap, "closeSearch", closeSearchRect, prepare = true)
            )
            runDiagnosticRegions(diagnostics) { diagnosticText ->
                callback(
                    OrderRegionText(
                        fullText = diagnosticText,
                        isPairOffer = false,
                        hasAnchoredCard = false,
                        anchorDebugInfo = anchorDebugInfo,
                        debugRegions = debugRegions,
                        cardText = "",
                        typeText = "",
                        priceText = "",
                        tripText = "",
                        detailText = "",
                        sameDropoffText = "",
                        merchantText = "",
                        merchantWideText = "",
                        addressText = "",
                        addressWideText = "",
                        addressLowerText = "",
                        ocrPreprocessDebugInfo = preprocessDebugInfo(diagnostics)
                    )
                )
            }
            return
        }
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
                            fullText = "",
                            isPairOffer = false,
                            hasAnchoredCard = hasAnchoredCard,
                            anchorDebugInfo = anchorDebugInfo,
                            debugRegions = debugRegions,
                            cardText = results["card"].orEmpty(),
                            typeText = results["type"].orEmpty(),
                            priceText = results["price"].orEmpty(),
                            tripText = results["trip"].orEmpty(),
                            detailText = results["detail"].orEmpty(),
                            sameDropoffText = results["sameDropoff"].orEmpty(),
                            merchantText = results["merchant"].orEmpty(),
                            merchantWideText = results["merchantWide"].orEmpty(),
                            addressText = results["address"].orEmpty(),
                            addressWideText = results["addressWide"].orEmpty(),
                            addressLowerText = results["addressLower"].orEmpty(),
                            ocrPreprocessDebugInfo = preprocessDebugInfo(regions)
                        )
                    )
                }
            }
        }

        regions.forEach { region ->
            if (region.bitmap.width <= 1 && region.bitmap.height <= 1) {
                finishOne(region.name, "")
            } else {
                runMlKitRegion(region.bitmap) { text -> finishOne(region.name, text) }
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

    private fun runDiagnosticRegions(regions: List<RegionCrop>, callback: (String) -> Unit) {
        val results = mutableMapOf<String, String>()
        var remaining = regions.size
        val lock = Any()
        regions.forEach { region ->
            runMlKitRegion(region.bitmap) { text ->
                synchronized(lock) {
                    results[region.name] = text
                    remaining -= 1
                    if (remaining == 0) {
                        callback(
                            regions.joinToString("\n") { item ->
                                "===== ${item.name} =====\n${results[item.name].orEmpty()}"
                            }
                        )
                    }
                }
            }
        }
    }

    private data class CloseButton(
        val rect: Rect,
        val searchRect: Rect,
        val deltaY: Int
    )

    private data class DeliveryAnchor(
        val lineX: Int,
        val top: Int,
        val bottom: Int,
        val pickupY: Int,
        val dropoffY: Int,
        val searchRect: Rect
    )

    private data class RegionCrop(
        val name: String,
        val rect: Rect,
        val bitmap: Bitmap,
        val preprocessScale: Int = 1,
        val preprocessMode: String = "NONE"
    )

    private data class OfferLayoutTemplate(
        val merchantLines: Int,
        val addressLines: Int,
        val lineHeight: Int
    ) {
        val extraLines: Int get() = (merchantLines - 1) + (addressLines - 1)
    }

    private fun buildAnchoredRegions(
        context: Context,
        bitmap: Bitmap,
        closeButton: CloseButton?
    ): List<RegionCrop>? {
        val close = closeButton ?: return null
        val card = findCardRect(bitmap, close)
        val cardHeight = card.height().coerceAtLeast(1)
        val cardWidth = card.width().coerceAtLeast(1)
        val contentBottom = (card.bottom - cardHeight * 0.08f).toInt().coerceIn(card.top + 1, card.bottom)
        val anchor = findDeliveryAnchor(context, bitmap, card, contentBottom, close.deltaY) ?: return null

        fun x(relative: Float): Int = (card.left + cardWidth * relative).toInt()
        fun y(relative: Float): Int = (card.top + cardHeight * relative).toInt()

        val detailRight = (card.right - cardWidth * 0.05f).toInt()

        val merchantTop = (anchor.pickupY - cardHeight * 0.065f).toInt()
        val merchantBottom = (anchor.pickupY + cardHeight * 0.075f).toInt()
        val addressTop = (anchor.dropoffY - cardHeight * 0.115f).toInt()
        val addressBottom = (contentBottom - cardHeight * 0.03f).toInt()
            .coerceAtLeast(addressTop + 1)
        val merchantFallback = rect(bitmap, x(0.145f), merchantTop, detailRight, merchantBottom)
        val addressFallback = rect(bitmap, x(0.145f), addressTop, detailRight, addressBottom)
        val template = detectOfferLayoutTemplate(context, bitmap, card, anchor)
        val fallbackTemplate = template.copy(addressLines = 1)
        val merchantRect = anchoredTextRect(context, bitmap, "merchant", "pickupAnchor", anchor.lineX, anchor.pickupY, template.merchantLines, template.lineHeight, merchantFallback)
        val addressRect = anchoredTextRect(context, bitmap, "address", "dropoffAnchor", anchor.lineX, anchor.dropoffY, template.addressLines, template.lineHeight, addressFallback)
        val fallbackAddressRect = anchoredTextRect(context, bitmap, "addressWide", "dropoffAnchor", anchor.lineX, anchor.dropoffY, fallbackTemplate.addressLines, fallbackTemplate.lineHeight, addressFallback)
        val typeRect = anchoredTemplateRect(
            context = context,
            bitmap = bitmap,
            textName = "type",
            iconName = "pickupAnchor",
            detectedIconX = anchor.lineX,
            detectedIconY = anchor.pickupY,
            fallback = rect(bitmap, x(0.03f), y(0.02f), x(0.55f), y(0.14f))
        )
        val priceRect = anchoredTemplateRect(
            context = context,
            bitmap = bitmap,
            textName = "price",
            iconName = "pickupAnchor",
            detectedIconX = anchor.lineX,
            detectedIconY = anchor.pickupY,
            fallback = rect(bitmap, x(0.03f), y(0.13f), x(0.50f), y(0.33f))
        )
        val tripRect = anchoredTemplateRect(
            context = context,
            bitmap = bitmap,
            textName = "trip",
            iconName = "pickupAnchor",
            detectedIconX = anchor.lineX,
            detectedIconY = anchor.pickupY,
            fallback = rect(bitmap, x(0.03f), y(0.32f), x(0.92f), y(0.48f))
        )
        val sameDropoffRect = calibratedPixelRect(context, bitmap, "sameDropoff", close.deltaY)
            ?: rect(bitmap, x(0.20f), y(0.42f), x(0.92f), y(0.50f))

        return listOf(
            RegionCrop("card", card, blankBitmap()),
            RegionCrop("deliveryAnchorSearch", Rect(anchor.searchRect), blankBitmap()),
            region(bitmap, "type", typeRect, prepare = true),
            region(bitmap, "price", priceRect, prepare = true),
            region(bitmap, "trip", tripRect, prepare = true),
            region(bitmap, "sameDropoff", sameDropoffRect, prepare = true),
            region(bitmap, "merchant", merchantRect, prepare = true),
            region(bitmap, "address", addressRect, prepare = true),
            if (close.deltaY != 0) {
                region(bitmap, "addressWide", fallbackAddressRect, prepare = true)
            } else {
                RegionCrop("addressWide", fallbackAddressRect, blankBitmap())
            }
        )
    }

    private fun region(
        bitmap: Bitmap,
        name: String,
        leftRatio: Float,
        topRatio: Float,
        rightRatio: Float,
        bottomRatio: Float,
        prepare: Boolean = false
    ): RegionCrop {
        val rect = rect(bitmap, leftRatio, topRatio, rightRatio, bottomRatio)
        return region(bitmap, name, rect, prepare)
    }

    private fun region(
        bitmap: Bitmap,
        name: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        prepare: Boolean = false
    ): RegionCrop {
        return region(bitmap, name, rect(bitmap, left, top, right, bottom), prepare)
    }

    private fun region(bitmap: Bitmap, name: String, rect: Rect, prepare: Boolean = false): RegionCrop {
        val cropped = crop(bitmap, rect)
        val scale = if (prepare && ENABLE_OCR_PREPROCESS) ocrScaleFor(name) else 1
        val mode = if (prepare && ENABLE_OCR_PREPROCESS) preprocessModeFor(name) else "NONE"
        return RegionCrop(
            name = name,
            rect = Rect(rect),
            bitmap = if (prepare) preprocessForOcr(cropped, name) else cropped,
            preprocessScale = scale,
            preprocessMode = mode
        )
    }

    private fun blankBitmap(): Bitmap {
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
    }

    private fun crop(
        bitmap: Bitmap,
        leftRatio: Float,
        topRatio: Float,
        rightRatio: Float,
        bottomRatio: Float
    ): Bitmap {
        return crop(bitmap, rect(bitmap, leftRatio, topRatio, rightRatio, bottomRatio))
    }

    private fun crop(bitmap: Bitmap, rect: Rect): Bitmap {
        val safeRect = rect(bitmap, rect.left, rect.top, rect.right, rect.bottom)
        return Bitmap.createBitmap(
            bitmap,
            safeRect.left,
            safeRect.top,
            safeRect.width(),
            safeRect.height()
        )
    }

    private fun crop(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Bitmap {
        return crop(bitmap, rect(bitmap, left, top, right, bottom))
    }

    private fun rect(
        bitmap: Bitmap,
        leftRatio: Float,
        topRatio: Float,
        rightRatio: Float,
        bottomRatio: Float
    ): Rect {
        val left = (bitmap.width * leftRatio).toInt()
        val top = (bitmap.height * topRatio).toInt()
        val right = (bitmap.width * rightRatio).toInt()
        val bottom = (bitmap.height * bottomRatio).toInt()
        return rect(bitmap, left, top, right, bottom)
    }

    private fun rect(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Rect {
        val safeLeft = left.coerceIn(0, bitmap.width - 1)
        val safeTop = top.coerceIn(0, bitmap.height - 1)
        val safeRight = right.coerceIn(safeLeft + 1, bitmap.width)
        val safeBottom = bottom.coerceIn(safeTop + 1, bitmap.height)
        return Rect(safeLeft, safeTop, safeRight, safeBottom)
    }

    private fun closeSearchRect(context: Context, bitmap: Bitmap): Rect {
        return calibratedPixelRect(context, bitmap, "closeSearch") ?: rect(
            bitmap,
            (bitmap.width * 0.72f).toInt(),
            (bitmap.height * 0.28f).toInt(),
            (bitmap.width * 0.96f).toInt(),
            (bitmap.height * 0.88f).toInt()
        )
    }

    private fun findCloseButton(context: Context, bitmap: Bitmap, searchRect: Rect): CloseButton? {
        val groups = mutableListOf<Rect>()
        var groupStart = -1
        var groupEnd = -1
        var groupLeft = bitmap.width
        var groupRight = 0
        var quietRows = 0

        var y = searchRect.top
        while (y < searchRect.bottom) {
            var darkCount = 0
            var minX = bitmap.width
            var maxX = 0
            var x = searchRect.left
            while (x < searchRect.right) {
                if (isCloseButtonDarkPixel(bitmap.getPixel(x, y))) {
                    darkCount += 1
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                }
                x += 2
            }
            if (darkCount >= 3) {
                if (groupStart < 0) {
                    groupStart = y
                    groupLeft = minX
                    groupRight = maxX
                }
                groupEnd = y
                groupLeft = minOf(groupLeft, minX)
                groupRight = maxOf(groupRight, maxX)
                quietRows = 0
            } else if (groupStart >= 0) {
                quietRows += 1
                if (quietRows >= 8) {
                    groups.add(Rect(groupLeft, groupStart, groupRight, groupEnd))
                    groupStart = -1
                    groupEnd = -1
                    groupLeft = bitmap.width
                    groupRight = 0
                    quietRows = 0
                }
            }
            y += 2
        }
        if (groupStart >= 0) groups.add(Rect(groupLeft, groupStart, groupRight, groupEnd))

        val close = groups
            .map { rect(bitmap, it.left - 12, it.top - 12, it.right + 12, it.bottom + 12) }
            .filter { it.width() in 24..150 && it.height() in 24..150 }
            .filter { it.centerX() > bitmap.width * 0.74f }
            .minByOrNull { kotlin.math.abs(it.width() - it.height()) + kotlin.math.abs(it.centerX() - (bitmap.width * 0.86f).toInt()) / 3 }
            ?: return null
        val templateClose = calibratedPixelRect(context, bitmap, "closeButton")
        val deltaY = templateClose?.let { close.centerY() - it.centerY() } ?: 0
        return CloseButton(close, searchRect, deltaY)
    }

    private fun findCardRect(bitmap: Bitmap, close: CloseButton): Rect {
        val left = (bitmap.width * 0.035f).toInt().coerceAtLeast(0)
        val right = (bitmap.width * 0.965f).toInt().coerceAtMost(bitmap.width)
        val span = (right - left).coerceAtLeast(1)
        val borderThreshold = (span * 0.34f).toInt()
        val fallbackTop = (close.rect.top - bitmap.height * 0.018f).toInt().coerceAtLeast(0)

        var top = fallbackTop
        var y = close.rect.centerY().coerceAtLeast(0)
        val minY = (close.rect.centerY() - bitmap.height * 0.18f).toInt().coerceAtLeast(0)
        while (y > minY) {
            var count = 0
            var x = left
            while (x < right) {
                if (isCardBorderPixel(bitmap.getPixel(x, y))) count += 1
                x += 2
            }
            if (count * 2 >= borderThreshold) {
                top = (y - bitmap.height * 0.006f).toInt().coerceAtLeast(0)
                break
            }
            y -= 2
        }

        var bottom = (close.rect.centerY() + bitmap.height * 0.42f).toInt().coerceAtMost(bitmap.height)
        y = (close.rect.centerY() + bitmap.height * 0.18f).toInt().coerceAtMost(bitmap.height - 1)
        while (y < bitmap.height - 1) {
            var count = 0
            var x = left
            while (x < right) {
                if (isCardBorderPixel(bitmap.getPixel(x, y))) count += 1
                x += 2
            }
            if (count * 2 >= borderThreshold) {
                bottom = (y + bitmap.height * 0.006f).toInt().coerceAtMost(bitmap.height)
                break
            }
            y += 2
        }

        return Rect(left, top, right, bottom)
    }

    private fun buildAnchorDebugInfo(
        context: Context,
        bitmap: Bitmap,
        closeButton: CloseButton?
    ): AnchorDebugInfo {
        val templateClose = calibratedPixelRect(context, bitmap, "closeButton")
        val templateCloseY = templateClose?.centerY()
        val actualCloseY = closeButton?.rect?.centerY()
        val closeShiftY = closeButton?.deltaY
        if (closeButton == null || closeShiftY == null) {
            return AnchorDebugInfo(
                anchorSource = "FAILED",
                pickupDetected = false,
                dropoffDetected = false,
                templateCloseY = templateCloseY,
                actualCloseY = actualCloseY,
                closeShiftY = closeShiftY,
                pickupAnchorRect = null,
                dropoffAnchorRect = null,
                closeButtonRect = closeButton?.rect
            )
        }

        fun withRegionDebug(info: AnchorDebugInfo): AnchorDebugInfo {
            val card = findCardRect(bitmap, closeButton)
            val cardHeight = card.height().coerceAtLeast(1)
            val cardWidth = card.width().coerceAtLeast(1)
            val contentBottom = (card.bottom - cardHeight * 0.08f).toInt().coerceIn(card.top + 1, card.bottom)
            val anchor = findDeliveryAnchor(context, bitmap, card, contentBottom, closeShiftY)
            if (anchor == null) {
                return info.copy(
                    cardRect = card,
                    closeButtonRect = closeButton.rect
                )
            }

            fun x(relative: Float): Int = (card.left + cardWidth * relative).toInt()
            fun y(relative: Float): Int = (card.top + cardHeight * relative).toInt()
            val detailRight = (card.right - cardWidth * 0.05f).toInt()
            val merchantTop = (anchor.pickupY - cardHeight * 0.065f).toInt()
            val merchantBottom = (anchor.pickupY + cardHeight * 0.075f).toInt()
            val addressTop = (anchor.dropoffY - cardHeight * 0.115f).toInt()
            val addressBottom = (contentBottom - cardHeight * 0.03f).toInt()
                .coerceAtLeast(addressTop + 1)
            val merchantFallback = rect(bitmap, x(0.145f), merchantTop, detailRight, merchantBottom)
            val addressFallback = rect(bitmap, x(0.145f), addressTop, detailRight, addressBottom)
            val template = detectOfferLayoutTemplate(context, bitmap, card, anchor)
            val fallbackTemplate = template.copy(addressLines = 1)
            val merchantRect = anchoredTextRect(context, bitmap, "merchant", "pickupAnchor", anchor.lineX, anchor.pickupY, template.merchantLines, template.lineHeight, merchantFallback)
            val addressRect = anchoredTextRect(context, bitmap, "address", "dropoffAnchor", anchor.lineX, anchor.dropoffY, template.addressLines, template.lineHeight, addressFallback)
            val addressWideRect = anchoredTextRect(context, bitmap, "addressWide", "dropoffAnchor", anchor.lineX, anchor.dropoffY, fallbackTemplate.addressLines, fallbackTemplate.lineHeight, addressFallback)
            val priceRect = anchoredTemplateRect(
                context = context,
                bitmap = bitmap,
                textName = "price",
                iconName = "pickupAnchor",
                detectedIconX = anchor.lineX,
                detectedIconY = anchor.pickupY,
                fallback = rect(bitmap, x(0.03f), y(0.13f), x(0.50f), y(0.33f))
            )
            val tripRect = anchoredTemplateRect(
                context = context,
                bitmap = bitmap,
                textName = "trip",
                iconName = "pickupAnchor",
                detectedIconX = anchor.lineX,
                detectedIconY = anchor.pickupY,
                fallback = rect(bitmap, x(0.03f), y(0.32f), x(0.92f), y(0.48f))
            )
            return info.copy(
                cardRect = card,
                closeButtonRect = closeButton.rect,
                priceRect = priceRect,
                tripRect = tripRect,
                merchantRect = merchantRect,
                addressRect = addressRect,
                addressWideRect = addressWideRect
            )
        }

        val pickup = calibratedPixelRect(context, bitmap, "pickupAnchor", closeShiftY)
        val dropoff = calibratedPixelRect(context, bitmap, "dropoffAnchor", closeShiftY)
        val precisePickup = pickup?.let { findDarkIconCenter(bitmap, it) }
        val preciseDropoff = dropoff?.let { findDarkIconCenter(bitmap, it) }
        if (precisePickup != null && preciseDropoff != null) {
            return withRegionDebug(AnchorDebugInfo(
                anchorSource = "PRECISE_BOX",
                pickupDetected = true,
                dropoffDetected = true,
                templateCloseY = templateCloseY,
                actualCloseY = actualCloseY,
                closeShiftY = closeShiftY,
                pickupAnchorRect = pickup,
                dropoffAnchorRect = dropoff
            ))
        }

        val card = findCardRect(bitmap, closeButton)
        val contentBottom = (card.bottom - card.height() * 0.08f).toInt().coerceIn(card.top + 1, card.bottom)
        val calibrated = calibratedPixelRect(context, bitmap, "deliveryAnchorSearch", closeShiftY)
        val lineHeight = estimateLineHeight(context, bitmap, card)
        val xRects = listOfNotNull(calibrated, pickup, dropoff)
        val left = (xRects.minOfOrNull { it.left } ?: (card.left + card.width() * 0.04f).toInt())
            .minus((card.width() * 0.012f).toInt())
            .coerceAtLeast(0)
        val right = (xRects.maxOfOrNull { it.right } ?: (card.left + card.width() * 0.20f).toInt())
            .plus((card.width() * 0.012f).toInt())
            .coerceAtMost(bitmap.width)
        val calibratedTop = listOfNotNull(pickup?.top, dropoff?.top).minOrNull()
            ?: calibrated?.top
            ?: (card.top + card.height() * 0.50f).toInt()
        val calibratedBottom = listOfNotNull(pickup?.bottom, dropoff?.bottom).maxOrNull()
            ?: calibrated?.bottom
            ?: (contentBottom - card.height() * 0.04f).toInt()
        val tripGuard = maxOf(
            card.top + (card.height() * 0.48f).toInt(),
            (calibratedPixelRect(context, bitmap, "trip", closeShiftY)?.bottom ?: card.top) + lineHeight / 3
        )
        val top = maxOf(
            calibratedTop - lineHeight,
            tripGuard
        ).coerceIn(card.top, contentBottom - 1)
        val bottom = minOf(
            calibratedBottom + (lineHeight * 1.8f).toInt(),
            contentBottom - (card.height() * 0.025f).toInt()
        ).coerceIn(top + 1, contentBottom)

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

        if (bestCount >= (bottom - top) / 18) {
            val expectedGap = calibratedIconGap(context, bitmap, card)
            val scannedIconCenters = findDeliveryIconCenters(bitmap, bestX, card, top, bottom, expectedGap)
            return withRegionDebug(AnchorDebugInfo(
                anchorSource = "WIDE_SCAN",
                pickupDetected = precisePickup != null || scannedIconCenters.first != null,
                dropoffDetected = preciseDropoff != null || scannedIconCenters.second != null,
                templateCloseY = templateCloseY,
                actualCloseY = actualCloseY,
                closeShiftY = closeShiftY,
                pickupAnchorRect = pickup,
                dropoffAnchorRect = dropoff
            ))
        }

        return withRegionDebug(AnchorDebugInfo(
            anchorSource = if (pickup != null && dropoff != null) "TEMPLATE_ONLY" else "FAILED",
            pickupDetected = precisePickup != null,
            dropoffDetected = preciseDropoff != null,
            templateCloseY = templateCloseY,
            actualCloseY = actualCloseY,
            closeShiftY = closeShiftY,
            pickupAnchorRect = pickup,
            dropoffAnchorRect = dropoff
        ))
    }

    private fun findDeliveryAnchor(
        context: Context,
        bitmap: Bitmap,
        card: Rect,
        contentBottom: Int,
        closeShiftY: Int
    ): DeliveryAnchor? {
        val calibrated = calibratedPixelRect(context, bitmap, "deliveryAnchorSearch", closeShiftY)
        val pickup = calibratedPixelRect(context, bitmap, "pickupAnchor", closeShiftY)
        val dropoff = calibratedPixelRect(context, bitmap, "dropoffAnchor", closeShiftY)
        val lineHeight = estimateLineHeight(context, bitmap, card)
        val precisePickup = pickup?.let { findDarkIconCenter(bitmap, it) }
        val preciseDropoff = dropoff?.let { findDarkIconCenter(bitmap, it) }
        if (precisePickup != null && preciseDropoff != null) {
            val searchRect = Rect(
                minOf(pickup.left, dropoff.left),
                minOf(pickup.top, dropoff.top),
                maxOf(pickup.right, dropoff.right),
                maxOf(pickup.bottom, dropoff.bottom)
            )
            return DeliveryAnchor(
                lineX = ((precisePickup.first + preciseDropoff.first) / 2f).toInt(),
                top = minOf(precisePickup.second, preciseDropoff.second),
                bottom = maxOf(precisePickup.second, preciseDropoff.second),
                pickupY = precisePickup.second,
                dropoffY = preciseDropoff.second,
                searchRect = searchRect
            )
        }
        val templatePickup = precisePickup ?: pickup?.let { it.centerX() to it.centerY() }
        val templateDropoff = preciseDropoff ?: dropoff?.let { it.centerX() to it.centerY() }
        val xRects = listOfNotNull(calibrated, pickup, dropoff)
        val left = (xRects.minOfOrNull { it.left } ?: (card.left + card.width() * 0.04f).toInt())
            .minus((card.width() * 0.012f).toInt())
            .coerceAtLeast(0)
        val right = (xRects.maxOfOrNull { it.right } ?: (card.left + card.width() * 0.20f).toInt())
            .plus((card.width() * 0.012f).toInt())
            .coerceAtMost(bitmap.width)
        val calibratedTop = listOfNotNull(pickup?.top, dropoff?.top).minOrNull()
            ?: calibrated?.top
            ?: (card.top + card.height() * 0.50f).toInt()
        val calibratedBottom = listOfNotNull(pickup?.bottom, dropoff?.bottom).maxOrNull()
            ?: calibrated?.bottom
            ?: (contentBottom - card.height() * 0.04f).toInt()
        val tripGuard = maxOf(
            card.top + (card.height() * 0.48f).toInt(),
            (calibratedPixelRect(context, bitmap, "trip", closeShiftY)?.bottom ?: card.top) + lineHeight / 3
        )
        val top = maxOf(
            calibratedTop - lineHeight,
            tripGuard
        ).coerceIn(card.top, contentBottom - 1)
        val bottom = minOf(
            calibratedBottom + (lineHeight * 1.8f).toInt(),
            contentBottom - (card.height() * 0.025f).toInt()
        ).coerceIn(top + 1, contentBottom)
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
        if (bestCount < (bottom - top) / 18) {
            return templateAnchorOrNull(templatePickup, templateDropoff, Rect(left, top, right, bottom))
        }

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
        if (anchorBottom <= anchorTop) {
            return templateAnchorOrNull(templatePickup, templateDropoff, Rect(left, top, right, bottom))
        }
        val expectedGap = calibratedIconGap(context, bitmap, card)
        val scannedIconCenters = findDeliveryIconCenters(bitmap, bestX, card, top, bottom, expectedGap)
        val iconCenters = Pair(
            precisePickup?.second ?: scannedIconCenters.first ?: templatePickup?.second,
            preciseDropoff?.second ?: scannedIconCenters.second ?: templateDropoff?.second
        )
        val lineX = listOfNotNull(
            precisePickup?.first,
            preciseDropoff?.first,
            templatePickup?.first,
            templateDropoff?.first
        ).average().takeIf { !it.isNaN() }?.toInt() ?: bestX
        return DeliveryAnchor(
            lineX = lineX,
            top = anchorTop,
            bottom = anchorBottom,
            pickupY = iconCenters.first ?: anchorTop,
            dropoffY = iconCenters.second ?: anchorBottom,
            searchRect = Rect(left, top, right, bottom)
        )
    }

    private fun templateAnchorOrNull(
        pickup: Pair<Int, Int>?,
        dropoff: Pair<Int, Int>?,
        searchRect: Rect
    ): DeliveryAnchor? {
        if (pickup == null || dropoff == null) return null
        return DeliveryAnchor(
            lineX = ((pickup.first + dropoff.first) / 2f).toInt(),
            top = minOf(pickup.second, dropoff.second),
            bottom = maxOf(pickup.second, dropoff.second),
            pickupY = pickup.second,
            dropoffY = dropoff.second,
            searchRect = searchRect
        )
    }

    private fun calibratedIconGap(context: Context, bitmap: Bitmap, card: Rect): Int {
        val pickup = calibratedPixelRect(context, bitmap, "pickupAnchor")
        val dropoff = calibratedPixelRect(context, bitmap, "dropoffAnchor")
        val calibratedGap = if (pickup != null && dropoff != null) {
            dropoff.centerY() - pickup.centerY()
        } else {
            0
        }
        return calibratedGap
            .takeIf { it > 0 }
            ?.coerceIn((card.height() * 0.06f).toInt().coerceAtLeast(42), (card.height() * 0.22f).toInt().coerceAtLeast(96))
            ?: (card.height() * 0.12f).toInt().coerceAtLeast(72)
    }

    private fun calibratedPixelRect(
        context: Context,
        bitmap: Bitmap,
        name: String,
        shiftY: Int = 0
    ): Rect? {
        val normalized = OcrCalibrationStore.load(context)[name] ?: return null
        val rect = normalizedToPixelRect(bitmap, normalized)
        return if (shiftY == 0) {
            rect
        } else {
            rect(bitmap, rect.left, rect.top + shiftY, rect.right, rect.bottom + shiftY)
        }
    }

    private fun calibrationDebugRegions(context: Context, bitmap: Bitmap): List<DebugRegion> {
        return listOfNotNull(
            calibratedPixelRect(context, bitmap, "card")?.let { DebugRegion("card", it) },
            calibratedPixelRect(context, bitmap, "closeSearch")?.let { DebugRegion("closeSearch", it) },
            calibratedPixelRect(context, bitmap, "closeButton")?.let { DebugRegion("closeButton", it) },
            calibratedPixelRect(context, bitmap, "type")?.let { DebugRegion("type", it) },
            calibratedPixelRect(context, bitmap, "price")?.let { DebugRegion("price", it) },
            calibratedPixelRect(context, bitmap, "trip")?.let { DebugRegion("trip", it) },
            calibratedPixelRect(context, bitmap, "merchant")?.let { DebugRegion("merchant", it) },
            calibratedPixelRect(context, bitmap, "address")?.let { DebugRegion("address", it) },
            calibratedPixelRect(context, bitmap, "addressWide")?.let { DebugRegion("addressWide", it) },
            calibratedPixelRect(context, bitmap, "sameDropoff")?.let { DebugRegion("sameDropoff", it) },
            calibratedPixelRect(context, bitmap, "pickupAnchor")?.let { DebugRegion("pickupAnchor", it) },
            calibratedPixelRect(context, bitmap, "dropoffAnchor")?.let { DebugRegion("dropoffAnchor", it) },
            calibratedPixelRect(context, bitmap, "deliveryAnchorSearch")?.let { DebugRegion("deliveryAnchorSearch", it) }
        )
    }

    private fun diagnosticAnchorDebugRegions(
        context: Context,
        bitmap: Bitmap,
        closeButton: CloseButton?
    ): List<DebugRegion> {
        val shiftY = closeButton?.deltaY ?: return emptyList()
        return listOfNotNull(
            calibratedPixelRect(context, bitmap, "deliveryAnchorSearch", shiftY)?.let { DebugRegion("deliveryAnchorSearchActual", it) },
            calibratedPixelRect(context, bitmap, "pickupAnchor", shiftY)?.let { DebugRegion("pickupAnchorShiftedReference", it) },
            calibratedPixelRect(context, bitmap, "dropoffAnchor", shiftY)?.let { DebugRegion("dropoffAnchorShiftedReference", it) }
        )
    }

    private fun actualDebugName(name: String): String {
        return when (name) {
            "card" -> "cardActual"
            "type" -> "typeActual"
            "price" -> "priceActual"
            "trip" -> "tripActual"
            "merchant" -> "merchantActual"
            "address" -> "addressActual"
            "addressWide" -> "addressWideActual"
            "sameDropoff" -> "sameDropoffActual"
            "deliveryAnchorSearch" -> "deliveryAnchorSearchActual"
            else -> name
        }
    }

    private fun anchoredTextRect(
        context: Context,
        bitmap: Bitmap,
        textName: String,
        iconName: String,
        detectedIconX: Int,
        detectedIconY: Int,
        lineCount: Int,
        lineHeight: Int,
        fallback: Rect
    ): Rect {
        val calibrated = OcrCalibrationStore.load(context)
        val text = calibrated[textName]?.let { normalizedToPixelRect(bitmap, it) } ?: return fallback
        val icon = calibrated[iconName]?.let { normalizedToPixelRect(bitmap, it) } ?: return fallback
        val iconCenterX = icon.centerX()
        val iconCenterY = icon.centerY()
        val targetHeight = (lineHeight * (lineCount.coerceIn(1, 2) + 0.28f)).toInt()
            .coerceAtLeast((text.height() * 0.55f).toInt())
        val relativeCenterY = text.centerY() - iconCenterY
        val centerY = detectedIconY + relativeCenterY
        return rect(
            bitmap = bitmap,
            left = detectedIconX + (text.left - iconCenterX),
            top = centerY - targetHeight / 2,
            right = detectedIconX + (text.right - iconCenterX),
            bottom = centerY + targetHeight / 2
        )
    }

    private fun anchoredTemplateRect(
        context: Context,
        bitmap: Bitmap,
        textName: String,
        iconName: String,
        detectedIconX: Int,
        detectedIconY: Int,
        fallback: Rect
    ): Rect {
        val calibrated = OcrCalibrationStore.load(context)
        val text = calibrated[textName]?.let { normalizedToPixelRect(bitmap, it) } ?: return fallback
        val icon = calibrated[iconName]?.let { normalizedToPixelRect(bitmap, it) } ?: return fallback
        val dx = detectedIconX - icon.centerX()
        val dy = detectedIconY - icon.centerY()
        return rect(
            bitmap = bitmap,
            left = text.left + dx,
            top = text.top + dy,
            right = text.right + dx,
            bottom = text.bottom + dy
        )
    }

    private fun detectOfferLayoutTemplate(
        context: Context,
        bitmap: Bitmap,
        card: Rect,
        anchor: DeliveryAnchor?
    ): OfferLayoutTemplate {
        val lineHeight = estimateLineHeight(context, bitmap, card)
        if (anchor == null) return OfferLayoutTemplate(1, 2, lineHeight)

        val iconDistance = (anchor.dropoffY - anchor.pickupY).coerceAtLeast(1)
        val merchantLines = if (iconDistance > lineHeight * 2.35f) 2 else 1

        return OfferLayoutTemplate(
            merchantLines = merchantLines.coerceIn(1, 2),
            addressLines = 2,
            lineHeight = lineHeight
        )
    }

    private fun findDarkIconCenter(bitmap: Bitmap, rect: Rect): Pair<Int, Int>? {
        var darkCount = 0
        var minX = rect.right
        var maxX = rect.left
        var minY = rect.bottom
        var maxY = rect.top
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                if (isDarkPixel(bitmap.getPixel(x, y))) {
                    darkCount += 1
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
                x += 2
            }
            y += 2
        }
        val sampledArea = ((rect.width() / 2).coerceAtLeast(1) * (rect.height() / 2).coerceAtLeast(1))
        if (darkCount < 4 || darkCount < sampledArea * 0.04f) return null
        return ((minX + maxX) / 2) to ((minY + maxY) / 2)
    }

    private fun estimateLineHeight(context: Context, bitmap: Bitmap, card: Rect): Int {
        val calibrated = OcrCalibrationStore.load(context)
        val merchant = calibrated["merchant"]?.let { normalizedToPixelRect(bitmap, it) }
        val address = calibrated["address"]?.let { normalizedToPixelRect(bitmap, it) }
        val fromMerchant = merchant?.height()?.takeIf { it > 0 }?.div(2)
        val fromAddress = address?.height()?.takeIf { it > 0 }?.div(2)
        val fromCard = (card.height() * 0.07f).toInt()
        return listOfNotNull(fromMerchant, fromAddress, fromCard)
            .maxOrNull()
            ?.coerceIn((bitmap.height * 0.018f).toInt().coerceAtLeast(28), (bitmap.height * 0.055f).toInt().coerceAtLeast(54))
            ?: (bitmap.height * 0.032f).toInt().coerceAtLeast(42)
    }

    private fun normalizedToPixelRect(bitmap: Bitmap, rect: RectF): Rect {
        return rect(
            bitmap,
            (bitmap.width * rect.left).toInt(),
            (bitmap.height * rect.top).toInt(),
            (bitmap.width * rect.right).toInt(),
            (bitmap.height * rect.bottom).toInt()
        )
    }

    private fun findDeliveryIconCenters(
        bitmap: Bitmap,
        lineX: Int,
        card: Rect,
        top: Int,
        bottom: Int,
        expectedGap: Int
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
        if (meaningful.size >= 2) {
            val pickup = meaningful.first().let { (it.first + it.last) / 2 }
            val dropoff = meaningful.last().let { (it.first + it.last) / 2 }
            return pickup to dropoff
        }
        val single = meaningful.firstOrNull()?.let { (it.first + it.last) / 2 }
            ?: return null to null
        val middle = (top + bottom) / 2
        return if (single <= middle) {
            single to (single + expectedGap).coerceAtMost(bottom)
        } else {
            (single - expectedGap).coerceAtLeast(top) to single
        }
    }

    private fun isCloseButtonDarkPixel(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return red < 80 && green < 80 && blue < 80
    }

    private fun isCardBorderPixel(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val greenBorder = green > 115 && green > red + 34 && green > blue + 24 && red < 130
        val blackBorder = red < 70 && green < 70 && blue < 70
        return greenBorder || blackBorder
    }

    private fun isDarkPixel(color: Int): Boolean {
        return Color.red(color) < 65 && Color.green(color) < 65 && Color.blue(color) < 65
    }

    private fun preprocessDebugInfo(regions: List<RegionCrop>): String {
        return buildString {
            appendLine("ocrPreprocess=${if (ENABLE_OCR_PREPROCESS) "enabled" else "disabled"}")
            regions.forEach { region ->
                appendLine("cropName=${region.name}")
                appendLine("scale=${region.preprocessScale}")
                appendLine("preprocessMode=${region.preprocessMode}")
            }
        }.trim()
    }

    private fun preprocessForOcr(bitmap: Bitmap, cropName: String): Bitmap {
        if (!ENABLE_OCR_PREPROCESS) return bitmap
        if (cropName == "price") return bitmap

        val scale = ocrScaleFor(cropName)
        val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width * scale, bitmap.height * scale, true)
        val contrast = when (cropName) {
            "type", "trip" -> 1.62f
            "sameDropoff" -> 1.58f
            "price" -> 1.55f
            "merchant", "merchantWide", "address", "addressWide", "addressLower" -> 1.22f
            else -> 1.25f
        }
        val translate = when (cropName) {
            "type", "trip" -> -44f
            "sameDropoff" -> -40f
            "price" -> -38f
            "merchant", "merchantWide", "address", "addressWide", "addressLower" -> -12f
            else -> -18f
        }
        val grayscale = cropName == "type" ||
                cropName == "trip" ||
                cropName == "price" ||
                cropName == "sameDropoff"
        val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    if (grayscale) setSaturation(0f)
                    postConcat(ColorMatrix(floatArrayOf(
                        contrast, 0f, 0f, 0f, translate,
                        0f, contrast, 0f, 0f, translate,
                        0f, 0f, contrast, 0f, translate,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
            )
        }
        Canvas(output).drawBitmap(scaled, 0f, 0f, paint)
        return when (cropName) {
            "type", "trip", "price", "sameDropoff",
            "merchant", "merchantWide", "address", "addressWide", "addressLower" -> sharpen(output)
            else -> output
        }
    }

    private fun ocrScaleFor(cropName: String): Int {
        return when (cropName) {
            "type", "trip", "sameDropoff" -> 3
            "price" -> 1
            "merchant", "merchantWide", "address", "addressWide", "addressLower" -> 2
            else -> 2
        }
    }

    private fun preprocessModeFor(cropName: String): String {
        return when (cropName) {
            "type", "trip" -> "TYPE_TRIP_STRONG"
            "price" -> "NONE"
            "sameDropoff" -> "SAME_DROPOFF_STRONG"
            "merchant", "merchantWide", "address", "addressWide", "addressLower" -> "TEXT_LIGHT"
            else -> "DEFAULT"
        }
    }

    private fun sharpen(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(floatArrayOf(
                    1.12f, -0.04f, -0.04f, 0f, 0f,
                    -0.04f, 1.12f, -0.04f, 0f, 0f,
                    -0.04f, -0.04f, 1.12f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
            )
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }
}
