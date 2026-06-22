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
        val merchantAddressBlockText: String,
        val addressText: String,
        val addressWideText: String,
        val addressLowerText: String,
        val ocrPreprocessDebugInfo: String = ""
    )

    data class AnchorDebugInfo(
        val anchorSource: String,
        val pickupDetected: Boolean,
        val dropoffDetected: Boolean,
        val sameDropoffMatched: Boolean = false,
        val merchantRows: Int? = null,
        val addressRows: Int? = null,
        val selectedTemplate: String? = null,
        val lineHeight: Int? = null,
        val pickupY: Int? = null,
        val dropoffY: Int? = null,
        val baseGap: Int? = null,
        val detectedGap: Int? = null,
        val dropoffShift: Int? = null,
        val anchorConflict: Boolean = false,
        val anchorConflictReason: String? = null,
        val pickupDetectDebug: String? = null,
        val dropoffDetectDebug: String? = null,
        val templateCloseY: Int? = null,
        val actualCloseY: Int? = null,
        val closeShiftY: Int? = null,
        val pickupAnchorRect: Rect? = null,
        val dropoffAnchorRect: Rect? = null,
        val pickupSearchRect: Rect? = null,
        val dropoffSearchRect: Rect? = null,
        val cardRect: Rect? = null,
        val closeButtonRect: Rect? = null,
        val priceRect: Rect? = null,
        val tripRect: Rect? = null,
        val merchantRect: Rect? = null,
        val addressRect: Rect? = null,
        val merchantAddressBlockRect: Rect? = null,
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
        val activeTemplate = OcrTemplateRepository.getActiveTemplate(context)
        DiagnosticLogStore.append(
            context,
            "OCR_TEMPLATE_SOURCE",
            "templateSource=${activeTemplate.source.name} templateVersion=${activeTemplate.version} loadedAt=${activeTemplate.loadedAt} hasUserSavedTemplate=${activeTemplate.hasUserSavedTemplate} fallbackReason=${activeTemplate.fallbackReason}"
        )
        DiagnosticLogStore.append(
            context,
            "OCR_TEMPLATE_ACTIVE",
            OcrTemplateRepository.templateSummary(activeTemplate.regions)
        )
        val closeSearchRect = closeSearchRect(context, bitmap)
        logOcrRegionUsage(context, "closeSearch", true, closeSearchRect, 0, "closeSearch")
        val closeButton = findCloseButton(context, bitmap, closeSearchRect)
        val anchorDebugInfo = buildAnchorDebugInfo(context, bitmap, closeButton)
        val upperRegions = buildUpperRegions(context, bitmap, closeButton)
        val hasAnchoredCard = upperRegions != null
        if (upperRegions == null) {
            val diagnostics = listOf(
                region(bitmap, "closeSearch", closeSearchRect, prepare = true)
            )
            val debugRegions = listOfNotNull(closeButton?.let { DebugRegion("closeButtonDetected", it.rect) }) +
                    diagnostics.map { DebugRegion(actualDebugName(it.name), Rect(it.rect)) }
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
                        merchantAddressBlockText = "",
                        addressText = "",
                        addressWideText = "",
                        addressLowerText = "",
                        ocrPreprocessDebugInfo = preprocessDebugInfo(diagnostics)
                    )
                )
            }
            return
        }
        runRegions(upperRegions) { upperResults ->
            val sameDropoffText = upperResults["sameDropoff"].orEmpty()
            val sameDropoffMatched = isSameDropoffText(sameDropoffText)
            val lowerBuild = buildLowerRegions(context, bitmap, closeButton, sameDropoffMatched)
            val lowerRegions = lowerBuild.regions
            val allRegions = upperRegions + lowerRegions
            val visibleRegions = allRegions.filter { it.name != "sameDropoff" || sameDropoffMatched }
            val debugRegions = listOfNotNull(closeButton?.let { DebugRegion("closeButtonDetected", it.rect) }) +
                    lowerBuild.debugRegions +
                    visibleRegions.map { DebugRegion(actualDebugName(it.name), Rect(it.rect)) }
            if (lowerRegions.isEmpty()) {
                callback(
                    OrderRegionText(
                        fullText = "",
                        isPairOffer = false,
                        hasAnchoredCard = true,
                        anchorDebugInfo = lowerBuild.debugInfo,
                        debugRegions = debugRegions,
                        cardText = "",
                        typeText = upperResults["type"].orEmpty(),
                        priceText = upperResults["price"].orEmpty(),
                        tripText = upperResults["trip"].orEmpty(),
                        detailText = "",
                        sameDropoffText = sameDropoffText,
                        merchantText = "",
                        merchantWideText = "",
                        merchantAddressBlockText = "",
                        addressText = "",
                        addressWideText = "",
                        addressLowerText = "",
                        ocrPreprocessDebugInfo = preprocessDebugInfo(allRegions)
                    )
                )
                return@runRegions
            }
            runRegions(lowerRegions) { lowerResults ->
                callback(
                    OrderRegionText(
                        fullText = listOf(
                            upperResults["type"].orEmpty(),
                            upperResults["price"].orEmpty(),
                            upperResults["trip"].orEmpty(),
                            sameDropoffText,
                            lowerResults["merchant"].orEmpty(),
                            lowerResults["address"].orEmpty(),
                            lowerResults["merchantAddressBlock"].orEmpty()
                        ).filter { it.isNotBlank() }.joinToString("\n"),
                        isPairOffer = false,
                        hasAnchoredCard = hasAnchoredCard,
                        anchorDebugInfo = lowerBuild.debugInfo,
                        debugRegions = debugRegions,
                        cardText = "",
                        typeText = upperResults["type"].orEmpty(),
                        priceText = upperResults["price"].orEmpty(),
                        tripText = upperResults["trip"].orEmpty(),
                        detailText = "",
                        sameDropoffText = sameDropoffText,
                        merchantText = lowerResults["merchant"].orEmpty(),
                        merchantWideText = lowerResults["merchantWide"].orEmpty(),
                        merchantAddressBlockText = lowerResults["merchantAddressBlock"].orEmpty(),
                        addressText = lowerResults["address"].orEmpty(),
                        addressWideText = lowerResults["addressWide"].orEmpty(),
                        addressLowerText = "",
                        ocrPreprocessDebugInfo = preprocessDebugInfo(allRegions)
                    )
                )
            }
        }
    }

    private fun runRegions(regions: List<RegionCrop>, callback: (Map<String, String>) -> Unit) {
        val results = mutableMapOf<String, String>()
        var remaining = regions.size
        val lock = Any()
        if (regions.isEmpty()) {
            callback(emptyMap())
            return
        }

        fun finishOne(key: String, text: String) {
            synchronized(lock) {
                results[key] = text
                remaining -= 1
                if (remaining == 0) {
                    callback(results.toMap())
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

    private fun isSameDropoffText(text: String): Boolean {
        return text.contains("同") && (text.contains("送達") || text.contains("送达") || text.contains("相同"))
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
        val pickupRect: Rect,
        val dropoffRect: Rect,
        val pickupSearchRect: Rect,
        val dropoffSearchRect: Rect,
        val pickupDetection: ShapeDetectionResult,
        val dropoffDetection: ShapeDetectionResult
    )

    private data class ShapeCandidate(
        val rect: Rect,
        val centerX: Int,
        val centerY: Int,
        val darkPixels: Int,
        val area: Int,
        val fillRatio: Float,
        val centerDarkRatio: Float,
        val aspectRatio: Float,
        val edgePixelRatio: Float,
        val circularity: Float,
        val rectangularity: Float,
        val cornerRatio: Float,
        val outerDarkRatio: Float,
        val ringScore: Float,
        val candidateScore: Int,
        val centerWhiteRatio: Float,
        val centerBlackThinLineRatio: Float,
        val centerBlackIsThinLine: Boolean,
        val hollowIconScore: Int,
        val sourceRectWidth: Int,
        val sourceRectHeight: Int,
        val fromSplitComponent: Boolean
    )

    private data class CandidateDebug(
        val index: Int,
        val candidate: ShapeCandidate,
        val accepted: Boolean,
        val rejectReason: String
    )

    private data class ShapeDetectionResult(
        val center: Pair<Int, Int>?,
        val actualRect: Rect?,
        val acceptedRects: List<Rect>,
        val rejectedByShapeRects: List<Rect>,
        val rejectedBySizeRects: List<Rect>,
        val candidates: List<CandidateDebug>,
        val expectedSize: Int,
        val candidateCount: Int,
        val acceptedCount: Int,
        val rejectedByShape: Int,
        val rejectedBySize: Int
    ) {
        val detected: Boolean get() = center != null && actualRect != null
    }

    private data class AnchorConflict(
        val hasConflict: Boolean,
        val reason: String? = null,
        val distance: Int? = null
    )

    private data class DeliveryAnchorProbe(
        val anchor: DeliveryAnchor?,
        val pickupDetection: ShapeDetectionResult,
        val dropoffDetection: ShapeDetectionResult,
        val pickupSearchRect: Rect?,
        val dropoffSearchRect: Rect?
    )

    private data class LayoutDecision(
        val merchantRows: Int,
        val addressRows: Int,
        val selectedTemplate: String,
        val baseGap: Int,
        val detectedGap: Int?,
        val dropoffShift: Int?,
        val fallbackUsed: Boolean
    )

    private data class LowerBuildResult(
        val regions: List<RegionCrop>,
        val debugInfo: AnchorDebugInfo,
        val debugRegions: List<DebugRegion>
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

    private fun buildUpperRegions(
        context: Context,
        bitmap: Bitmap,
        closeButton: CloseButton?
    ): List<RegionCrop>? {
        val close = closeButton ?: return null
        val card = calibratedPixelRect(context, bitmap, "card", close.deltaY)
            ?.let { expandCardRect(bitmap, it) }
            ?: findCardRect(bitmap, close)
        val lineHeight = estimateLineHeight(context, bitmap, card)
        val typeRect = calibratedPixelRect(context, bitmap, "type", close.deltaY)
            ?: rect(bitmap, 0.03f, 0.50f, 0.55f, 0.60f)
        val priceRect = calibratedPixelRect(context, bitmap, "price", close.deltaY)
            ?: rect(bitmap, 0.03f, 0.56f, 0.50f, 0.66f)
        val tripRect = calibratedPixelRect(context, bitmap, "trip", close.deltaY)
            ?: rect(bitmap, 0.12f, 0.64f, 0.94f, 0.74f)
        val sameDropoffRect = calibratedPixelRect(context, bitmap, "sameDropoff")
            ?: rect(bitmap, 0.16f, 0.82f, 0.92f, 0.88f)
        logOcrRegionUsage(context, "card", true, card, close.deltaY, "card")
        logOcrRegionUsage(context, "type", true, typeRect, close.deltaY, "type")
        logOcrRegionUsage(context, "price", true, priceRect, close.deltaY, "price")
        logOcrRegionUsage(context, "trip", true, tripRect, close.deltaY, "trip")
        logOcrRegionUsage(context, "sameDropoff", true, sameDropoffRect, 0, "sameDropoff")

        return listOf(
            RegionCrop("card", card, blankBitmap()),
            region(bitmap, "type", typeRect, prepare = true),
            region(bitmap, "price", priceRect, prepare = true),
            region(bitmap, "trip", tripRect, prepare = true),
            region(bitmap, "sameDropoff", sameDropoffRect, prepare = true)
        )
    }

    private fun buildLowerRegions(
        context: Context,
        bitmap: Bitmap,
        closeButton: CloseButton?,
        sameDropoffMatched: Boolean
    ): LowerBuildResult {
        val close = closeButton ?: return LowerBuildResult(
            regions = emptyList(),
            debugInfo = buildAnchorDebugInfo(context, bitmap, closeButton),
            debugRegions = emptyList()
        )
        val templateClose = calibratedPixelRect(context, bitmap, "closeButton")
        val card = calibratedPixelRect(context, bitmap, "card", close.deltaY)
            ?.let { expandCardRect(bitmap, it) }
            ?: findCardRect(bitmap, close)
        val lineHeight = estimateLineHeight(context, bitmap, card)
        val sameDropoffShiftY = if (sameDropoffMatched) -lineHeight else 0
        val lowerShiftY = sameDropoffShiftY
        val lowerTotalShiftY = close.deltaY + sameDropoffShiftY
        val basePickup = calibratedPixelRect(context, bitmap, "pickupAnchor", lowerShiftY)
        val baseDropoff = calibratedPixelRect(context, bitmap, "dropoffAnchor", lowerShiftY)
        val anchorProbe = findSeparatedDeliveryAnchor(context, bitmap, lowerShiftY, lineHeight)
        val anchor = anchorProbe.anchor
        val conflict = detectAnchorConflict(anchor, lineHeight)
        val decision = decideLayoutFromGeometry(
            anchor = anchor,
            conflict = conflict,
            basePickupY = basePickup?.centerY(),
            baseDropoffY = baseDropoff?.centerY(),
            lineHeight = lineHeight
        )
        val tripActualRect = calibratedPixelRect(context, bitmap, "trip", close.deltaY)
        val merchantLong = guardMerchantBelowTrip(
            bitmap = bitmap,
            merchant = m2A2TextRect(context, bitmap, "merchant", lowerTotalShiftY, lineHeight),
            trip = tripActualRect,
            lineHeight = lineHeight
        )
        val merchantShort = oneLineRect(bitmap, merchantLong, lineHeight)
        val addressLong = m2A2TextRect(context, bitmap, "address", lowerTotalShiftY, lineHeight)
        val addressShort = oneLineRect(bitmap, addressLong, lineHeight)
        val merchantRect = if (decision.merchantRows >= 2) merchantLong else merchantShort
        val addressRect = if (decision.addressRows >= 2) addressLong else addressShort
        val merchantAddressBlockRect = merchantAddressBlockRect(
            context = context,
            bitmap = bitmap,
            shiftY = lowerTotalShiftY,
            merchantLong = merchantLong,
            addressLong = addressLong
        )
        logOcrRegionUsage(context, "merchant", true, merchantRect, lowerTotalShiftY, "merchant")
        logOcrRegionUsage(context, "address", true, addressRect, lowerTotalShiftY, "address")
        logOcrRegionUsage(context, "merchantAddressBlock", true, merchantAddressBlockRect, lowerTotalShiftY, "merchantAddressBlock")
        val debugInfo = AnchorDebugInfo(
            anchorSource = if (decision.fallbackUsed) "GEOMETRY_FALLBACK_M2_A2" else "GEOMETRY_PICKUP_DROPOFF",
            pickupDetected = anchorProbe.pickupDetection.detected,
            dropoffDetected = anchorProbe.dropoffDetection.detected,
            sameDropoffMatched = sameDropoffMatched,
            merchantRows = decision.merchantRows,
            addressRows = decision.addressRows,
            selectedTemplate = decision.selectedTemplate,
            lineHeight = lineHeight,
            pickupY = anchor?.pickupY,
            dropoffY = anchor?.dropoffY,
            baseGap = decision.baseGap,
            detectedGap = decision.detectedGap,
            dropoffShift = decision.dropoffShift,
            anchorConflict = conflict.hasConflict,
            anchorConflictReason = conflict.reason,
            pickupDetectDebug = detectorDebugText("PICKUP_DETECT_DEBUG", anchorProbe.pickupDetection),
            dropoffDetectDebug = detectorDebugText("DROPOFF_DETECT_DEBUG", anchorProbe.dropoffDetection),
            templateCloseY = templateClose?.centerY(),
            actualCloseY = close.rect.centerY(),
            closeShiftY = close.deltaY,
            pickupAnchorRect = anchor?.pickupRect,
            dropoffAnchorRect = anchor?.dropoffRect,
            pickupSearchRect = anchorProbe.pickupSearchRect ?: pickupCircleSearchRect(context, bitmap, lowerShiftY),
            dropoffSearchRect = anchorProbe.dropoffSearchRect ?: dropoffSquareSearchRect(context, bitmap, lowerShiftY),
            cardRect = card,
            closeButtonRect = close.rect,
            priceRect = calibratedPixelRect(context, bitmap, "price", close.deltaY),
            tripRect = tripActualRect,
            merchantRect = merchantRect,
            addressRect = addressRect,
            merchantAddressBlockRect = merchantAddressBlockRect
        )
        val debugRegions = buildList {
            debugInfo.pickupSearchRect?.let { add(DebugRegion("pickupSearchRect", it)) }
            debugInfo.dropoffSearchRect?.let { add(DebugRegion("dropoffSearchRect", it)) }
            addAll(candidateDebugRegions("pickupCandidate", anchorProbe.pickupDetection))
            addAll(candidateDebugRegions("dropoffCandidate", anchorProbe.dropoffDetection))
            if (anchorProbe.pickupDetection.detected) {
                anchor?.pickupRect?.let { add(DebugRegion("pickupDetectedRect", it)) }
            } else {
                debugInfo.pickupSearchRect?.let { add(DebugRegion("pickupNotDetected", it)) }
            }
            if (anchorProbe.dropoffDetection.detected) {
                anchor?.dropoffRect?.let { add(DebugRegion("dropoffDetectedRect", it)) }
            } else {
                debugInfo.dropoffSearchRect?.let { add(DebugRegion("dropoffNotDetected", it)) }
            }
        }
        return LowerBuildResult(
            regions = listOf(
                region(bitmap, "merchant", merchantRect, prepare = true),
                region(bitmap, "address", addressRect, prepare = true),
                region(bitmap, "merchantAddressBlock", merchantAddressBlockRect, prepare = true)
            ),
            debugInfo = debugInfo,
            debugRegions = debugRegions
        )
    }

    private fun oneLineRect(bitmap: Bitmap, longRect: Rect, lineHeight: Int): Rect {
        val height = minOf(longRect.height(), (lineHeight * 1.25f).toInt().coerceAtLeast(1))
        return rect(bitmap, longRect.left, longRect.top, longRect.right, longRect.top + height)
    }

    private fun guardMerchantBelowTrip(bitmap: Bitmap, merchant: Rect, trip: Rect?, lineHeight: Int): Rect {
        val minTop = (trip?.bottom ?: return merchant) + 20
        if (merchant.top > minTop) return merchant
        val height = merchant.height().coerceAtLeast((lineHeight * 1.6f).toInt().coerceAtLeast(1))
        return rect(
            bitmap = bitmap,
            left = merchant.left,
            top = minTop,
            right = merchant.right,
            bottom = minTop + height
        )
    }

    private fun merchantAddressBlockRect(
        context: Context,
        bitmap: Bitmap,
        shiftY: Int,
        merchantLong: Rect,
        addressLong: Rect
    ): Rect {
        return calibratedPixelRect(context, bitmap, "merchantAddressBlock", shiftY)
            ?: rect(
                bitmap = bitmap,
                left = minOf(merchantLong.left, addressLong.left),
                top = minOf(merchantLong.top, addressLong.top),
                right = maxOf(merchantLong.right, addressLong.right),
                bottom = maxOf(merchantLong.bottom, addressLong.bottom)
            )
    }

    private fun logOcrRegionUsage(
        context: Context,
        regionName: String,
        usedByMainFlow: Boolean,
        rect: Rect,
        shiftY: Int,
        cropName: String
    ) {
        val source = OcrTemplateRepository.getActiveTemplate(context).source.name
        DiagnosticLogStore.append(
            context,
            "OCR_REGION_USAGE",
            "regionName=$regionName usedByMainFlow=$usedByMainFlow source=$source rect=${rect.left},${rect.top},${rect.right},${rect.bottom} shiftY=$shiftY cropName=$cropName"
        )
    }

    private fun candidateDebugRegions(prefix: String, detection: ShapeDetectionResult): List<DebugRegion> {
        return buildList {
            detection.candidates.forEach { item ->
                val c = item.candidate
                val state = if (item.accepted) "ACCEPTED" else item.rejectReason
                add(
                    DebugRegion(
                        "${prefix}#${item.index} ${c.rect.width()}x${c.rect.height()} fill=${fmt(c.fillRatio)} center=${fmt(c.centerDarkRatio)} $state",
                        c.rect
                    )
                )
            }
        }
    }

    private fun m2A2TextRect(
        context: Context,
        bitmap: Bitmap,
        name: String,
        shiftY: Int,
        lineHeight: Int
    ): Rect {
        return when (name) {
            "merchant" -> widenedMerchantRect(context, bitmap, shiftY, lineHeight)
            "address" -> widenedAddressRect(context, bitmap, shiftY, lineHeight)
            else -> calibratedPixelRect(context, bitmap, name, shiftY) ?: rect(bitmap, 0, 0, 1, 1)
        }
    }

    private fun decideLayoutFromGeometry(
        anchor: DeliveryAnchor?,
        conflict: AnchorConflict,
        basePickupY: Int?,
        baseDropoffY: Int?,
        lineHeight: Int
    ): LayoutDecision {
        val baseGap = if (basePickupY != null && baseDropoffY != null) {
            (baseDropoffY - basePickupY).coerceAtLeast(1)
        } else {
            (lineHeight * 2.0f).toInt().coerceAtLeast(1)
        }
        if (anchor == null || baseDropoffY == null || conflict.hasConflict) {
            return LayoutDecision(
                merchantRows = 2,
                addressRows = 2,
                selectedTemplate = "M2+A2",
                baseGap = baseGap,
                detectedGap = null,
                dropoffShift = null,
                fallbackUsed = true
            )
        }
        val dropoffShift = anchor.dropoffY - baseDropoffY
        val addressRows = if (dropoffShift >= lineHeight * 0.5f) 1 else 2
        val detectedGap = (anchor.dropoffY - anchor.pickupY).coerceAtLeast(1)
        val merchantRows = if (detectedGap <= baseGap - lineHeight * 0.5f) 1 else 2
        return LayoutDecision(
            merchantRows = merchantRows,
            addressRows = addressRows,
            selectedTemplate = "M${merchantRows}+A${addressRows}",
            baseGap = baseGap,
            detectedGap = detectedGap,
            dropoffShift = dropoffShift,
            fallbackUsed = false
        )
    }

    private fun findSeparatedDeliveryAnchor(
        context: Context,
        bitmap: Bitmap,
        lowerShiftY: Int,
        lineHeight: Int
    ): DeliveryAnchorProbe {
        val emptyDetection = emptyShapeDetection()
        val pickupTemplate = calibratedPixelRect(context, bitmap, "pickupAnchor", lowerShiftY)
            ?: return DeliveryAnchorProbe(null, emptyDetection, emptyDetection, null, null)
        val dropoffTemplate = calibratedPixelRect(context, bitmap, "dropoffAnchor", lowerShiftY)
            ?: return DeliveryAnchorProbe(null, emptyDetection, emptyDetection, null, null)
        val fixedLineX = ((pickupTemplate.centerX() + dropoffTemplate.centerX()) / 2f).toInt()
        val pickupSearch = pickupCircleSearchRect(context, bitmap, lowerShiftY)
        val dropoffSearch = dropoffSquareSearchRect(context, bitmap, lowerShiftY)
        val pickupDetection = detectPickupCircle(bitmap, pickupSearch, fixedLineX, lineHeight)
        val dropoffDetection = detectDropoffSquare(bitmap, dropoffSearch, fixedLineX, lineHeight)
        if (!pickupDetection.detected || !dropoffDetection.detected) {
            return DeliveryAnchorProbe(null, pickupDetection, dropoffDetection, pickupSearch, dropoffSearch)
        }
        val pickupCenter = pickupDetection.center
            ?: return DeliveryAnchorProbe(null, pickupDetection, dropoffDetection, pickupSearch, dropoffSearch)
        val dropoffCenter = dropoffDetection.center
            ?: return DeliveryAnchorProbe(null, pickupDetection, dropoffDetection, pickupSearch, dropoffSearch)
        val pickupRect = centerTemplateAtFixedX(bitmap, pickupTemplate, fixedLineX, pickupCenter.second)
        val dropoffRect = centerTemplateAtFixedX(bitmap, dropoffTemplate, fixedLineX, dropoffCenter.second)
        val anchor = DeliveryAnchor(
            lineX = fixedLineX,
            top = minOf(pickupRect.centerY(), dropoffRect.centerY()),
            bottom = maxOf(pickupRect.centerY(), dropoffRect.centerY()),
            pickupY = pickupRect.centerY(),
            dropoffY = dropoffRect.centerY(),
            pickupRect = pickupRect,
            dropoffRect = dropoffRect,
            pickupSearchRect = pickupSearch,
            dropoffSearchRect = dropoffSearch,
            pickupDetection = pickupDetection,
            dropoffDetection = dropoffDetection
        )
        return DeliveryAnchorProbe(anchor, pickupDetection, dropoffDetection, pickupSearch, dropoffSearch)
    }

    private fun detectPickupCircle(
        bitmap: Bitmap,
        searchRect: Rect,
        fixedLineX: Int,
        lineHeight: Int
    ): ShapeDetectionResult {
        return detectShape(
            bitmap = bitmap,
            searchRect = searchRect,
            fixedLineX = fixedLineX,
            lineHeight = lineHeight,
            expectCircle = true
        )
    }

    private fun detectDropoffSquare(
        bitmap: Bitmap,
        searchRect: Rect,
        fixedLineX: Int,
        lineHeight: Int
    ): ShapeDetectionResult {
        return detectShape(
            bitmap = bitmap,
            searchRect = searchRect,
            fixedLineX = fixedLineX,
            lineHeight = lineHeight,
            expectCircle = false
        )
    }

    private fun detectShape(
        bitmap: Bitmap,
        searchRect: Rect,
        fixedLineX: Int,
        lineHeight: Int,
        expectCircle: Boolean
    ): ShapeDetectionResult {
        val candidates = collectDarkComponents(bitmap, searchRect, lineHeight)
        var rejectedByShape = 0
        var rejectedBySize = 0
        val acceptedRects = mutableListOf<Rect>()
        val rejectedByShapeRects = mutableListOf<Rect>()
        val rejectedBySizeRects = mutableListOf<Rect>()
        val candidateDebug = mutableListOf<CandidateDebug>()
        val expectedSize = expectedAnchorSize(lineHeight)
        val accepted = candidates.mapIndexedNotNull { index, rawCandidate ->
            val candidate = rawCandidate.copy(
                candidateScore = scoreAnchorCandidate(rawCandidate, fixedLineX, expectedSize)
            )
            val sizeRejectReason = anchorSizeRejectReason(candidate, lineHeight, expectCircle)
            val shapeRejectReason = anchorShapeRejectReason(candidate, lineHeight)
            when {
                sizeRejectReason != null -> {
                    rejectedBySize += 1
                    rejectedBySizeRects.add(candidate.rect)
                    candidateDebug.add(CandidateDebug(index + 1, candidate, accepted = false, rejectReason = sizeRejectReason))
                    null
                }
                shapeRejectReason != null -> {
                    rejectedByShape += 1
                    rejectedByShapeRects.add(candidate.rect)
                    candidateDebug.add(CandidateDebug(index + 1, candidate, accepted = false, rejectReason = shapeRejectReason))
                    null
                }
                else -> {
                    acceptedRects.add(candidate.rect)
                    candidateDebug.add(CandidateDebug(index + 1, candidate, accepted = true, rejectReason = "ACCEPTED"))
                    candidate
                }
            }
        }
        val selected = accepted.maxByOrNull { candidate -> candidate.candidateScore }
        val actualRect = selected?.let {
            centerRect(bitmap, fixedLineX, it.centerY, it.rect.width().coerceAtLeast(2), it.rect.height().coerceAtLeast(2))
        }
        return ShapeDetectionResult(
            center = selected?.let { fixedLineX to it.centerY },
            actualRect = actualRect,
            acceptedRects = acceptedRects,
            rejectedByShapeRects = rejectedByShapeRects,
            rejectedBySizeRects = rejectedBySizeRects,
            candidates = candidateDebug,
            expectedSize = expectedSize,
            candidateCount = candidates.size,
            acceptedCount = accepted.size,
            rejectedByShape = rejectedByShape,
            rejectedBySize = rejectedBySize
        )
    }

    private fun collectDarkComponents(bitmap: Bitmap, searchRect: Rect, lineHeight: Int): List<ShapeCandidate> {
        val width = searchRect.width().coerceAtLeast(1)
        val height = searchRect.height().coerceAtLeast(1)
        val visited = BooleanArray(width * height)
        val candidates = mutableListOf<ShapeCandidate>()
        fun index(localX: Int, localY: Int) = localY * width + localX

        var localY = 0
        while (localY < height) {
            var localX = 0
            while (localX < width) {
                val idx = index(localX, localY)
                val pixelX = searchRect.left + localX
                val pixelY = searchRect.top + localY
                if (!visited[idx] && isAnchorDarkPixel(bitmap.getPixel(pixelX, pixelY))) {
                    val stack = ArrayDeque<Pair<Int, Int>>()
                    stack.add(localX to localY)
                    visited[idx] = true
                    var minX = localX
                    var maxX = localX
                    var minY = localY
                    var maxY = localY
                    var darkPixels = 0
                    while (stack.isNotEmpty()) {
                        val (cx, cy) = stack.removeLast()
                        darkPixels += 1
                        minX = minOf(minX, cx)
                        maxX = maxOf(maxX, cx)
                        minY = minOf(minY, cy)
                        maxY = maxOf(maxY, cy)
                        for (ny in (cy - 1)..(cy + 1)) {
                            for (nx in (cx - 1)..(cx + 1)) {
                                if (nx !in 0 until width || ny !in 0 until height) continue
                                val nextIdx = index(nx, ny)
                                if (visited[nextIdx]) continue
                                visited[nextIdx] = true
                                if (isAnchorDarkPixel(bitmap.getPixel(searchRect.left + nx, searchRect.top + ny))) {
                                    stack.add(nx to ny)
                                }
                            }
                        }
                    }
                    val rect = Rect(
                        searchRect.left + minX,
                        searchRect.top + minY,
                        searchRect.left + maxX + 1,
                        searchRect.top + maxY + 1
                    )
                    val candidate = shapeCandidateFromRect(bitmap, rect, darkPixels)
                    candidates.add(candidate)
                    candidates.addAll(splitElongatedComponent(bitmap, rect, lineHeight))
                } else {
                    visited[idx] = true
                }
                localX += 1
            }
            localY += 1
        }
        return candidates
    }

    private fun shapeCandidateFromRect(
        bitmap: Bitmap,
        rect: Rect,
        knownDarkPixels: Int? = null,
        sourceRect: Rect = rect,
        fromSplitComponent: Boolean = false
    ): ShapeCandidate {
        val darkPixels = knownDarkPixels ?: countAnchorDarkPixels(bitmap, rect)
        val area = rect.width().coerceAtLeast(1) * rect.height().coerceAtLeast(1)
        val aspect = rect.width().toFloat() / rect.height().toFloat().coerceAtLeast(1f)
        val fill = darkPixels.toFloat() / area.toFloat()
        val centerRatio = centerDarkRatio(bitmap, rect)
        val edgeRatio = edgeDarkRatio(bitmap, rect, darkPixels)
        val outerRatio = outerDarkRatio(bitmap, rect, centerRatio)
        val ring = (outerRatio - centerRatio).coerceAtLeast(0f)
        val centerWhiteRatio = 1f - centerRatio
        val thinLineRatio = centerBlackThinLineRatio(bitmap, rect)
        val centerBlackIsThinLine = centerRatio > 0.25f && thinLineRatio <= 0.35f
        val hollowIconScore = hollowIconScore(
            centerWhiteRatio = centerWhiteRatio,
            outerDarkRatio = outerRatio,
            ringScore = ring,
            centerDarkRatio = centerRatio,
            centerBlackIsThinLine = centerBlackIsThinLine
        )
        val roundness = minOf(aspect, 1f / aspect.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        val circularity = (roundness * (0.45f + edgeRatio * 0.55f)).coerceIn(0f, 1f)
        val rectangularity = (roundness * (0.35f + edgeRatio * 0.65f)).coerceIn(0f, 1f)
        val candidateScore = scoreAnchorCandidate(
            centerX = rect.centerX(),
            rect = rect,
            aspectRatio = aspect,
            fillRatio = fill,
            hollowIconScore = hollowIconScore,
            edgePixelRatio = edgeRatio,
            fromSplitComponent = fromSplitComponent,
            fixedLineX = rect.centerX(),
            expectedSize = rect.width().coerceAtLeast(rect.height())
        )
        return ShapeCandidate(
            rect = rect,
            centerX = rect.centerX(),
            centerY = rect.centerY(),
            darkPixels = darkPixels,
            area = area,
            fillRatio = fill,
            centerDarkRatio = centerRatio,
            aspectRatio = aspect,
            edgePixelRatio = edgeRatio,
            circularity = circularity,
            rectangularity = rectangularity,
            cornerRatio = cornerDarkRatio(bitmap, rect),
            outerDarkRatio = outerRatio,
            ringScore = ring,
            candidateScore = candidateScore,
            centerWhiteRatio = centerWhiteRatio,
            centerBlackThinLineRatio = thinLineRatio,
            centerBlackIsThinLine = centerBlackIsThinLine,
            hollowIconScore = hollowIconScore,
            sourceRectWidth = sourceRect.width(),
            sourceRectHeight = sourceRect.height(),
            fromSplitComponent = fromSplitComponent
        )
    }

    private fun splitElongatedComponent(bitmap: Bitmap, rect: Rect, lineHeight: Int): List<ShapeCandidate> {
        val aspect = rect.width().toFloat() / rect.height().toFloat().coerceAtLeast(1f)
        val elongated = aspect < 0.35f || aspect > 3.2f
        if (!elongated) return emptyList()
        val window = minOf(
            maxOf(rect.width(), rect.height()),
            (lineHeight * 0.72f).toInt().coerceAtLeast(16)
        ).coerceAtLeast(8)
        val step = (window * 0.35f).toInt().coerceAtLeast(4)
        val local = mutableListOf<ShapeCandidate>()
        if (rect.height() >= rect.width()) {
            var top = rect.top
            while (top < rect.bottom) {
                val centerY = (top + window / 2).coerceAtMost(rect.bottom)
                val slice = rect(
                    bitmap,
                    rect.centerX() - window / 2,
                    centerY - window / 2,
                    rect.centerX() + window / 2,
                    centerY + window / 2
                )
                val dark = countAnchorDarkPixels(bitmap, slice)
                if (dark >= 3) {
                    local.add(shapeCandidateFromRect(bitmap, slice, dark, sourceRect = rect, fromSplitComponent = true))
                }
                top += step
            }
        } else {
            var left = rect.left
            while (left < rect.right) {
                val centerX = (left + window / 2).coerceAtMost(rect.right)
                val slice = rect(
                    bitmap,
                    centerX - window / 2,
                    rect.centerY() - window / 2,
                    centerX + window / 2,
                    rect.centerY() + window / 2
                )
                val dark = countAnchorDarkPixels(bitmap, slice)
                if (dark >= 3) {
                    local.add(shapeCandidateFromRect(bitmap, slice, dark, sourceRect = rect, fromSplitComponent = true))
                }
                left += step
            }
        }
        return local
    }

    private fun countAnchorDarkPixels(bitmap: Bitmap, rect: Rect): Int {
        var count = 0
        var y = rect.top.coerceAtLeast(0)
        while (y < rect.bottom.coerceAtMost(bitmap.height)) {
            var x = rect.left.coerceAtLeast(0)
            while (x < rect.right.coerceAtMost(bitmap.width)) {
                if (isAnchorDarkPixel(bitmap.getPixel(x, y))) count += 1
                x += 1
            }
            y += 1
        }
        return count
    }

    private fun cornerDarkRatio(bitmap: Bitmap, rect: Rect): Float {
        val cornerSize = (minOf(rect.width(), rect.height()) * 0.28f).toInt().coerceAtLeast(2)
        val corners = listOf(
            Rect(rect.left, rect.top, rect.left + cornerSize, rect.top + cornerSize),
            Rect(rect.right - cornerSize, rect.top, rect.right, rect.top + cornerSize),
            Rect(rect.left, rect.bottom - cornerSize, rect.left + cornerSize, rect.bottom),
            Rect(rect.right - cornerSize, rect.bottom - cornerSize, rect.right, rect.bottom)
        )
        var dark = 0
        var total = 0
        corners.forEach { corner ->
            var y = corner.top.coerceAtLeast(0)
            while (y < corner.bottom.coerceAtMost(bitmap.height)) {
                var x = corner.left.coerceAtLeast(0)
                while (x < corner.right.coerceAtMost(bitmap.width)) {
                    total += 1
                    if (isAnchorDarkPixel(bitmap.getPixel(x, y))) dark += 1
                    x += 1
                }
                y += 1
            }
        }
        return if (total == 0) 0f else dark.toFloat() / total.toFloat()
    }

    private fun edgeDarkRatio(bitmap: Bitmap, rect: Rect, darkPixels: Int): Float {
        if (darkPixels <= 0) return 0f
        val band = (minOf(rect.width(), rect.height()) * 0.24f).toInt().coerceAtLeast(1)
        var edgeDark = 0
        var y = rect.top.coerceAtLeast(0)
        while (y < rect.bottom.coerceAtMost(bitmap.height)) {
            var x = rect.left.coerceAtLeast(0)
            while (x < rect.right.coerceAtMost(bitmap.width)) {
                val nearEdge = x - rect.left < band ||
                    rect.right - x <= band ||
                    y - rect.top < band ||
                    rect.bottom - y <= band
                if (nearEdge && isAnchorDarkPixel(bitmap.getPixel(x, y))) edgeDark += 1
                x += 1
            }
            y += 1
        }
        return (edgeDark.toFloat() / darkPixels.toFloat()).coerceIn(0f, 1f)
    }

    private fun centerDarkRatio(bitmap: Bitmap, rect: Rect): Float {
        val insetX = (rect.width() * 0.25f).toInt()
        val insetY = (rect.height() * 0.25f).toInt()
        val center = Rect(
            rect.left + insetX,
            rect.top + insetY,
            rect.right - insetX,
            rect.bottom - insetY
        )
        val safe = rect(bitmap, center.left, center.top, center.right, center.bottom)
        val total = safe.width().coerceAtLeast(1) * safe.height().coerceAtLeast(1)
        return countAnchorDarkPixels(bitmap, safe).toFloat() / total.toFloat()
    }

    private fun centerBlackThinLineRatio(bitmap: Bitmap, rect: Rect): Float {
        val centerWidth = (rect.width() * 0.5f).toInt().coerceAtLeast(1)
        val centerHeight = (rect.height() * 0.5f).toInt().coerceAtLeast(1)
        val center = rect(
            bitmap,
            rect.centerX() - centerWidth / 2,
            rect.centerY() - centerHeight / 2,
            rect.centerX() - centerWidth / 2 + centerWidth,
            rect.centerY() - centerHeight / 2 + centerHeight
        )
        val columnHasDark = BooleanArray(center.width().coerceAtLeast(1))
        var localX = 0
        while (localX < center.width()) {
            val x = center.left + localX
            var y = center.top
            while (y < center.bottom) {
                if (isAnchorDarkPixel(bitmap.getPixel(x, y))) {
                    columnHasDark[localX] = true
                    break
                }
                y += 1
            }
            localX += 1
        }
        var maxRun = 0
        var currentRun = 0
        columnHasDark.forEach { hasDark ->
            if (hasDark) {
                currentRun += 1
                maxRun = maxOf(maxRun, currentRun)
            } else {
                currentRun = 0
            }
        }
        return maxRun.toFloat() / center.width().coerceAtLeast(1).toFloat()
    }

    private fun outerDarkRatio(bitmap: Bitmap, rect: Rect, centerRatio: Float): Float {
        val centerWidth = (rect.width() * 0.5f).toInt().coerceAtLeast(1)
        val centerHeight = (rect.height() * 0.5f).toInt().coerceAtLeast(1)
        val center = rect(
            bitmap,
            rect.centerX() - centerWidth / 2,
            rect.centerY() - centerHeight / 2,
            rect.centerX() - centerWidth / 2 + centerWidth,
            rect.centerY() - centerHeight / 2 + centerHeight
        )
        val totalArea = rect.width().coerceAtLeast(1) * rect.height().coerceAtLeast(1)
        val centerArea = center.width().coerceAtLeast(1) * center.height().coerceAtLeast(1)
        val outerArea = (totalArea - centerArea).coerceAtLeast(1)
        val totalDark = countAnchorDarkPixels(bitmap, rect)
        val centerDark = (centerRatio * centerArea).toInt()
        return ((totalDark - centerDark).coerceAtLeast(0)).toFloat() / outerArea.toFloat()
    }

    private fun expectedAnchorSize(lineHeight: Int): Int {
        return (lineHeight * 0.36f).toInt().coerceAtLeast(8)
    }

    private fun hollowIconScore(
        centerWhiteRatio: Float,
        outerDarkRatio: Float,
        ringScore: Float,
        centerDarkRatio: Float,
        centerBlackIsThinLine: Boolean
    ): Int {
        val centerWhiteScore = (centerWhiteRatio * 100f).toInt()
        val outerScore = (outerDarkRatio * 120f).toInt()
        val ringScoreInt = (ringScore * 160f).toInt()
        val thinLineBonus = if (centerBlackIsThinLine) 25 else 0
        val centerPenalty = if (centerDarkRatio > 0.55f && !centerBlackIsThinLine) -80 else 0
        return outerScore + centerWhiteScore + ringScoreInt + thinLineBonus + centerPenalty
    }

    private fun anchorSizeRejectReason(candidate: ShapeCandidate, lineHeight: Int, expectCircle: Boolean): String? {
        val minAnchorSize = maxOf(8, (lineHeight * 0.12f).toInt())
        val maxAnchorSize = (lineHeight * 0.8f).toInt().coerceAtLeast(minAnchorSize)
        return when {
            candidate.rect.width() < minAnchorSize -> "SIZE_TOO_SMALL"
            candidate.rect.height() < minAnchorSize -> "SIZE_TOO_SMALL"
            candidate.rect.width() > maxAnchorSize -> "SIZE_TOO_LARGE"
            candidate.rect.height() > maxAnchorSize -> "SIZE_TOO_LARGE"
            else -> null
        }
    }

    private fun anchorShapeRejectReason(candidate: ShapeCandidate, lineHeight: Int): String? {
        val sourceAspect = candidate.sourceRectWidth.toFloat() / candidate.sourceRectHeight.coerceAtLeast(1).toFloat()
        val expectedSize = expectedAnchorSize(lineHeight)
        val actualSize = (candidate.rect.width() + candidate.rect.height()) / 2
        return when {
            candidate.aspectRatio !in 0.6f..1.6f -> "ASPECT_RATIO_OUT_OF_RANGE"
            candidate.fillRatio < 0.03f -> "FILL_RATIO_TOO_LOW"
            candidate.fillRatio > 0.75f -> "FILL_RATIO_TOO_HIGH"
            candidate.fromSplitComponent && (sourceAspect < 0.35f || sourceAspect > 2.8f) -> "SPLIT_FROM_ELONGATED_LINE"
            actualSize > expectedSize * 1.7f -> "ANCHOR_SIZE_TOO_FAR_FROM_EXPECTED"
            else -> null
        }
    }

    private fun scoreAnchorCandidate(
        candidate: ShapeCandidate,
        fixedLineX: Int,
        expectedSize: Int
    ): Int {
        return scoreAnchorCandidate(
            centerX = candidate.centerX,
            rect = candidate.rect,
            aspectRatio = candidate.aspectRatio,
            fillRatio = candidate.fillRatio,
            hollowIconScore = candidate.hollowIconScore,
            edgePixelRatio = candidate.edgePixelRatio,
            fromSplitComponent = candidate.fromSplitComponent,
            fixedLineX = fixedLineX,
            expectedSize = expectedSize
        )
    }

    private fun scoreAnchorCandidate(
        centerX: Int,
        rect: Rect,
        aspectRatio: Float,
        fillRatio: Float,
        hollowIconScore: Int,
        edgePixelRatio: Float,
        fromSplitComponent: Boolean,
        fixedLineX: Int,
        expectedSize: Int
    ): Int {
        val actualSize = (rect.width() + rect.height()) / 2
        val sizeScore = 160 - kotlin.math.abs(actualSize - expectedSize) * 8
        val aspectScore = 100 - (kotlin.math.abs(1f - aspectRatio) * 100f).toInt()
        val xScore = 100 - kotlin.math.abs(centerX - fixedLineX) * 4
        val fillScore = when {
            fillRatio in 0.08f..0.35f -> 60
            fillRatio in 0.04f..0.50f -> 30
            else -> -60
        }
        val splitPenalty = if (fromSplitComponent) -120 else 0
        val edgeScore = (edgePixelRatio * 20f).toInt()
        return sizeScore + aspectScore + xScore + fillScore + hollowIconScore + splitPenalty + edgeScore
    }

    private fun detectAnchorConflict(anchor: DeliveryAnchor?, lineHeight: Int): AnchorConflict {
        anchor ?: return AnchorConflict(false)
        val distance = kotlin.math.abs(anchor.dropoffY - anchor.pickupY)
        return if (distance < lineHeight * 0.25f) {
            AnchorConflict(
                hasConflict = true,
                reason = "SAME_OR_TOO_CLOSE",
                distance = distance
            )
        } else {
            AnchorConflict(false, distance = distance)
        }
    }

    private fun detectorDebugText(tag: String, detection: ShapeDetectionResult): String {
        return buildString {
            appendLine(tag)
            appendLine("candidateCount=${detection.candidateCount}")
            appendLine("acceptedCount=${detection.acceptedCount}")
            appendLine("rejectedByShape=${detection.rejectedByShape}")
            appendLine("rejectedBySize=${detection.rejectedBySize}")
            appendLine("selectedCenterX=${detection.center?.first ?: ""}")
            appendLine("selectedCenterY=${detection.center?.second ?: ""}")
            appendLine("thresholds=minAnchorSize=max(8,lineHeight*0.12) maxAnchorSize=lineHeight*0.8 aspectRatio=0.6..1.6 fillRatio=0.03..0.75 ringScore=preferred hollow icon")
            detection.candidates.forEach { item ->
                val c = item.candidate
                val actualSize = (c.rect.width() + c.rect.height()) / 2
                appendLine("Candidate#${item.index}")
                appendLine("centerX=${c.centerX}")
                appendLine("centerY=${c.centerY}")
                appendLine("width=${c.rect.width()}")
                appendLine("height=${c.rect.height()}")
                appendLine("area=${c.area}")
                appendLine("expectedSize=${detection.expectedSize}")
                appendLine("actualSize=$actualSize")
                appendLine("aspectRatio=${fmt(c.aspectRatio)}")
                appendLine("fillRatio=${fmt(c.fillRatio)}")
                appendLine("centerDarkRatio=${fmt(c.centerDarkRatio)}")
                appendLine("centerWhiteRatio=${fmt(c.centerWhiteRatio)}")
                appendLine("centerBlackThinLineRatio=${fmt(c.centerBlackThinLineRatio)}")
                appendLine("centerBlackIsThinLine=${c.centerBlackIsThinLine}")
                appendLine("outerDarkRatio=${fmt(c.outerDarkRatio)}")
                appendLine("ringScore=${fmt(c.ringScore)}")
                appendLine("hollowIconScore=${c.hollowIconScore}")
                appendLine("candidateScore=${c.candidateScore}")
                appendLine("fromSplitComponent=${c.fromSplitComponent}")
                appendLine("sourceRectWidth=${c.sourceRectWidth}")
                appendLine("sourceRectHeight=${c.sourceRectHeight}")
                val sourceAspect = c.sourceRectWidth.toFloat() / c.sourceRectHeight.coerceAtLeast(1).toFloat()
                appendLine("sourceAspect=${fmt(sourceAspect)}")
                appendLine("edgePixelRatio=${fmt(c.edgePixelRatio)}")
                appendLine("circularity=${fmt(c.circularity)}")
                appendLine("rectangularity=${fmt(c.rectangularity)}")
                appendLine("rejectReason=${item.rejectReason}")
            }
        }.trim()
    }

    private fun fmt(value: Float): String {
        return "%.3f".format(java.util.Locale.US, value)
    }

    private fun emptyShapeDetection(): ShapeDetectionResult {
        return ShapeDetectionResult(
            center = null,
            actualRect = null,
            acceptedRects = emptyList(),
            rejectedByShapeRects = emptyList(),
            rejectedBySizeRects = emptyList(),
            candidates = emptyList(),
            expectedSize = 0,
            candidateCount = 0,
            acceptedCount = 0,
            rejectedByShape = 0,
            rejectedBySize = 0
        )
    }

    private fun pickupCircleSearchRect(context: Context, bitmap: Bitmap, shiftY: Int): Rect {
        return calibratedPixelRect(context, bitmap, "pickupCircleSearch", shiftY)
            ?: calibratedPixelRect(context, bitmap, "pickupAnchor", shiftY)
                ?.let { expandAroundCenter(bitmap, it, scaleX = 1.65f, scaleY = 1.8f) }
            ?: rect(bitmap, 0.05f, 0.70f, 0.19f, 0.81f)
    }

    private fun dropoffSquareSearchRect(context: Context, bitmap: Bitmap, shiftY: Int): Rect {
        return calibratedPixelRect(context, bitmap, "dropoffSquareSearch", shiftY)
            ?: calibratedPixelRect(context, bitmap, "dropoffAnchor", shiftY)
                ?.let { expandAroundCenter(bitmap, it, scaleX = 1.65f, scaleY = 1.8f) }
            ?: rect(bitmap, 0.05f, 0.78f, 0.19f, 0.87f)
    }

    private fun centerTemplateAtFixedX(bitmap: Bitmap, template: Rect, centerX: Int, centerY: Int): Rect {
        val halfWidth = template.width() / 2
        val halfHeight = template.height() / 2
        return rect(
            bitmap,
            centerX - halfWidth,
            centerY - halfHeight,
            centerX + halfWidth,
            centerY + halfHeight
        )
    }

    private fun pointRect(centerX: Int, centerY: Int): Rect {
        return Rect(centerX - 1, centerY - 1, centerX + 1, centerY + 1)
    }

    private fun expandAroundCenter(bitmap: Bitmap, source: Rect, scaleX: Float, scaleY: Float): Rect {
        val halfWidth = (source.width() * scaleX / 2f).toInt().coerceAtLeast(source.width() / 2)
        val halfHeight = (source.height() * scaleY / 2f).toInt().coerceAtLeast(source.height() / 2)
        return rect(
            bitmap,
            source.centerX() - halfWidth,
            source.centerY() - halfHeight,
            source.centerX() + halfWidth,
            source.centerY() + halfHeight
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
        val fixedClose = templateClose?.let {
            centerRect(bitmap, close.centerX(), close.centerY(), it.width(), it.height())
        } ?: close
        val deltaY = templateClose?.let { fixedClose.centerY() - it.centerY() } ?: 0
        return CloseButton(fixedClose, searchRect, deltaY)
    }

    private fun centerRect(bitmap: Bitmap, centerX: Int, centerY: Int, width: Int, height: Int): Rect {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        return rect(
            bitmap = bitmap,
            left = centerX - safeWidth / 2,
            top = centerY - safeHeight / 2,
            right = centerX - safeWidth / 2 + safeWidth,
            bottom = centerY - safeHeight / 2 + safeHeight
        )
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
        val closeOnlyCard = calibratedPixelRect(context, bitmap, "card", closeShiftY)
            ?.let { expandCardRect(bitmap, it) }
            ?: findCardRect(bitmap, closeButton)
        val closeOnlyLineHeight = estimateLineHeight(context, bitmap, closeOnlyCard)
        return AnchorDebugInfo(
            anchorSource = "CLOSE_BUTTON_Y_TEMPLATE",
            pickupDetected = false,
            dropoffDetected = false,
            templateCloseY = templateCloseY,
            actualCloseY = actualCloseY,
            closeShiftY = closeShiftY,
            pickupAnchorRect = null,
            dropoffAnchorRect = null,
            cardRect = closeOnlyCard,
            closeButtonRect = closeButton.rect,
            priceRect = calibratedPixelRect(context, bitmap, "price", closeShiftY),
            tripRect = calibratedPixelRect(context, bitmap, "trip", closeShiftY),
            merchantRect = widenedMerchantRect(context, bitmap, closeShiftY, closeOnlyLineHeight),
            addressRect = widenedAddressRect(context, bitmap, closeShiftY, closeOnlyLineHeight),
            addressWideRect = calibratedPixelRect(context, bitmap, "addressWide", closeShiftY)
        )

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
                pickupRect = centerTemplateAtFixedX(bitmap, pickup, pickup.centerX(), precisePickup.second),
                dropoffRect = centerTemplateAtFixedX(bitmap, dropoff, dropoff.centerX(), preciseDropoff.second),
                pickupSearchRect = searchRect,
                dropoffSearchRect = searchRect,
                pickupDetection = legacyDetection(precisePickup, centerTemplateAtFixedX(bitmap, pickup, pickup.centerX(), precisePickup.second)),
                dropoffDetection = legacyDetection(preciseDropoff, centerTemplateAtFixedX(bitmap, dropoff, dropoff.centerX(), preciseDropoff.second))
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
            pickupRect = pointRect(lineX, iconCenters.first ?: anchorTop),
            dropoffRect = pointRect(lineX, iconCenters.second ?: anchorBottom),
            pickupSearchRect = Rect(left, top, right, bottom),
            dropoffSearchRect = Rect(left, top, right, bottom),
            pickupDetection = legacyDetection(lineX to (iconCenters.first ?: anchorTop), pointRect(lineX, iconCenters.first ?: anchorTop)),
            dropoffDetection = legacyDetection(lineX to (iconCenters.second ?: anchorBottom), pointRect(lineX, iconCenters.second ?: anchorBottom))
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
            pickupRect = pointRect(pickup.first, pickup.second),
            dropoffRect = pointRect(dropoff.first, dropoff.second),
            pickupSearchRect = searchRect,
            dropoffSearchRect = searchRect,
            pickupDetection = legacyDetection(pickup, pointRect(pickup.first, pickup.second)),
            dropoffDetection = legacyDetection(dropoff, pointRect(dropoff.first, dropoff.second))
        )
    }

    private fun legacyDetection(center: Pair<Int, Int>, rect: Rect): ShapeDetectionResult {
        return ShapeDetectionResult(
            center = center,
            actualRect = rect,
            acceptedRects = listOf(rect),
            rejectedByShapeRects = emptyList(),
            rejectedBySizeRects = emptyList(),
            candidates = emptyList(),
            expectedSize = rect.width().coerceAtLeast(rect.height()),
            candidateCount = 1,
            acceptedCount = 1,
            rejectedByShape = 0,
            rejectedBySize = 0
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
        val normalized = OcrTemplateRepository.getActiveTemplate(context).regions[name] ?: return null
        val rect = normalizedToPixelRect(bitmap, normalized)
        return if (shiftY == 0) {
            rect
        } else {
            rect(bitmap, rect.left, rect.top + shiftY, rect.right, rect.bottom + shiftY)
        }
    }

    private fun expandCardRect(bitmap: Bitmap, template: Rect): Rect {
        val verticalPadding = (bitmap.height * 0.018f).toInt().coerceAtLeast(18)
        return rect(
            bitmap = bitmap,
            left = 0,
            top = template.top - verticalPadding,
            right = bitmap.width,
            bottom = template.bottom + verticalPadding
        )
    }

    private fun widenedMerchantRect(
        context: Context,
        bitmap: Bitmap,
        shiftY: Int,
        lineHeight: Int
    ): Rect {
        val base = calibratedPixelRect(context, bitmap, "merchant", shiftY)
            ?: return rect(bitmap, 0.15f, 0.72f, 0.94f, 0.80f)
        val targetHeight = maxOf(base.height(), (lineHeight * 2.15f).toInt())
        return rect(
            bitmap = bitmap,
            left = base.left,
            top = base.top - (lineHeight * 0.18f).toInt(),
            right = base.right,
            bottom = base.top - (lineHeight * 0.18f).toInt() + targetHeight
        )
    }

    private fun widenedAddressRect(
        context: Context,
        bitmap: Bitmap,
        shiftY: Int,
        lineHeight: Int
    ): Rect {
        val base = calibratedPixelRect(context, bitmap, "address", shiftY)
            ?: return rect(bitmap, 0.15f, 0.78f, 0.94f, 0.88f)
        val sameDropoffTop = calibratedPixelRect(context, bitmap, "sameDropoff", shiftY)?.top
        val targetBottom = minOf(
            base.top + maxOf(base.height(), (lineHeight * 3.25f).toInt()),
            sameDropoffTop?.minus((lineHeight * 0.12f).toInt()) ?: bitmap.height
        )
        return rect(
            bitmap = bitmap,
            left = base.left,
            top = base.top - (lineHeight * 0.20f).toInt(),
            right = base.right,
            bottom = targetBottom.coerceAtLeast(base.top + lineHeight)
        )
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
        closeButton: CloseButton?,
        sameDropoffMatched: Boolean = false
    ): List<DebugRegion> {
        closeButton ?: return emptyList()
        val card = calibratedPixelRect(context, bitmap, "card", closeButton.deltaY)
            ?.let { expandCardRect(bitmap, it) }
            ?: findCardRect(bitmap, closeButton)
        val lineHeight = estimateLineHeight(context, bitmap, card)
        val lowerShiftY = if (sameDropoffMatched) -lineHeight else 0
        return listOfNotNull(
            calibratedPixelRect(context, bitmap, "deliveryAnchorSearch", lowerShiftY)?.let { DebugRegion("deliveryAnchorSearchActual", it) },
            calibratedPixelRect(context, bitmap, "pickupAnchor", lowerShiftY)?.let { DebugRegion("pickupAnchorShiftedReference", it) },
            calibratedPixelRect(context, bitmap, "dropoffAnchor", lowerShiftY)?.let { DebugRegion("dropoffAnchorShiftedReference", it) }
        )
    }

    private fun actualDebugName(name: String): String {
        return when (name) {
            "card" -> "cardActual"
            "type" -> "typeActual"
            "price" -> "priceActual"
            "trip" -> "tripActual"
            "merchant" -> "merchantActual"
            "merchantWide" -> "merchantWideActual"
            "merchantAddressBlock" -> "merchantAddressBlockActual"
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
        val calibrated = OcrTemplateRepository.getActiveTemplate(context).regions
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
        val calibrated = OcrTemplateRepository.getActiveTemplate(context).regions
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
        val rows = iconDistance.toFloat() / lineHeight.toFloat().coerceAtLeast(1f)
        return when {
            rows <= 1.6f -> OfferLayoutTemplate(1, 1, lineHeight)
            rows <= 2.25f -> OfferLayoutTemplate(1, 2, lineHeight)
            rows <= 2.95f -> OfferLayoutTemplate(2, 1, lineHeight)
            else -> OfferLayoutTemplate(2, 2, lineHeight)
        }
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
        val calibrated = OcrTemplateRepository.getActiveTemplate(context).regions
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

    private fun isAnchorDarkPixel(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val darkNeutral = max < 118 && max - min < 58
        val darkBlue = blue < 150 && red < 105 && green < 125 && blue >= red
        val blackStroke = red < 95 && green < 95 && blue < 95
        return darkNeutral || darkBlue || blackStroke
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
            "merchant", "merchantWide", "merchantAddressBlock", "address", "addressWide", "addressLower" -> 1.22f
            else -> 1.25f
        }
        val translate = when (cropName) {
            "type", "trip" -> -44f
            "sameDropoff" -> -40f
            "price" -> -38f
            "merchant", "merchantWide", "merchantAddressBlock", "address", "addressWide", "addressLower" -> -12f
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
            "merchant", "merchantWide", "merchantAddressBlock", "address", "addressWide", "addressLower" -> sharpen(output)
            else -> output
        }
    }

    private fun ocrScaleFor(cropName: String): Int {
        return when (cropName) {
            "type", "trip", "sameDropoff" -> 3
            "price" -> 1
            "merchant", "merchantWide", "merchantAddressBlock", "address", "addressWide", "addressLower" -> 2
            else -> 2
        }
    }

    private fun preprocessModeFor(cropName: String): String {
        return when (cropName) {
            "type", "trip" -> "TYPE_TRIP_STRONG"
            "price" -> "NONE"
            "sameDropoff" -> "SAME_DROPOFF_STRONG"
            "merchant", "merchantWide", "merchantAddressBlock", "address", "addressWide", "addressLower" -> "TEXT_LIGHT"
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
