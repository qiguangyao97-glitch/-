package com.example.gongderefuser.parser

import android.util.Log
import com.example.gongderefuser.model.OrderData

/**
 * 訂單文字解析器
 */
object OrderParser {

    data class DistanceDebug(
        val raw: String = "",
        val parsed: Double = 0.0,
        val corrected: Boolean = false,
        val correctionReason: String = ""
    )

    data class RegionInput(
        val fullText: String,
        val cardText: String,
        val typeText: String,
        val priceText: String,
        val tripText: String,
        val detailText: String = "",
        val sameDropoffText: String = "",
        val merchantText: String,
        val merchantWideText: String = "",
        val addressText: String,
        val addressWideText: String = "",
        val addressLowerText: String = ""
    )

    private data class AddressSelection(
        val text: String,
        val source: String
    )

    @Volatile
    private var lastDistanceDebug: DistanceDebug = DistanceDebug()

    fun distanceDebugInfo(): String {
        val debug = lastDistanceDebug
        return buildString {
            appendLine("distanceRaw=${debug.raw}")
            appendLine("distanceParsed=${debug.parsed}")
            appendLine("distanceCorrected=${debug.corrected}")
            appendLine("distanceCorrectionReason=${debug.correctionReason}")
        }.trim()
    }

    /**
     * 从画面文字中解析订单
     */
    fun parse(screenText: String): OrderData? {

        try {
            val normalizedText = normalize(screenText)

            val price = parsePrice(normalizedText)
            val minutes = parseMinutes(normalizedText)
            val distance = parseDistance(normalizedText)
            val deliveryCount = parseDeliveryCount(normalizedText)
            val isExclusive = deliveryCount <= 1
            val isTargetOffer = isLikelyTargetOffer(normalizedText)
            val isAddOnOrder = isAddOnOrder(normalizedText)
            val addressLines = parseAddressLines(normalizedText)
            val address = addressLines.joinToString("\n").ifBlank { normalizedText }
            val storeName = parseStoreName(normalizedText, addressLines)
            val isSameLocationStack = hasSameLocationStackFeature(normalizedText)

            // 基础检查
            if (
                !isTargetOffer ||
                price <= 0 ||
                minutes <= 0 ||
                distance <= 0
            ) {
                return null
            }

            return OrderData(
                price = price,
                minutes = minutes,
                distance = distance,
                isStackOrder = deliveryCount > 1,
                isSameLocationStack = isSameLocationStack,
                deliveryCount = deliveryCount,
                isExclusive = isExclusive,
                isTargetOffer = isTargetOffer,
                isAddOnOrder = isAddOnOrder,
                address = address,
                storeName = storeName
            )

        } catch (e: Exception) {

            e.printStackTrace()

            return null
        }
    }

    fun parse(input: RegionInput): OrderData? {
        return try {
            val typeText = normalize(input.typeText)
            val priceText = normalize(input.priceText)
            val tripText = normalize(input.tripText)
            val sameDropoffText = normalize(input.sameDropoffText)
            val rawMerchant = input.merchantText.trim()
            val rawAddress = input.addressText.trim()
            val combinedText = listOf(
                priceText,
                tripText,
                typeText
            ).joinToString("\n")

            val price = parsePriceFromSources(priceText)
            val minutes = parseMinutesFromSources(tripText)
            val distance = parseDistanceFromSources(tripText)
            val deliveryCount = parseDeliveryCount(typeText)
            val isExclusive = deliveryCount <= 1
            val isTargetOffer = isLikelyTargetOffer(combinedText)
            val isAddOnOrder = isAddOnOrder(combinedText)
            val storeName = rawMerchant
            val address = rawAddress
            val merchantStatus = if (storeName.isNotBlank()) "OK" else "PARSE_FAILED"
            val addressStatus = if (address.isNotBlank()) "OK" else "PARSE_FAILED"
            val sameDropoffMatched = matchesSameDropoff(sameDropoffText)
            val isSameLocationStack = sameDropoffMatched || hasSameLocationStackFeature(combinedText)
            runCatching {
                Log.d(
                    "UBER_TEXT_DIRECT_DEBUG",
                    """
                    商家OCR原文=$rawMerchant
                    最終商家=$storeName
                    商家狀態=$merchantStatus
                    地址OCR原文=$rawAddress
                    最終地址=$address
                    地址狀態=$addressStatus
                    """.trimIndent()
                )
            }

            OrderData(
                price = price,
                minutes = minutes,
                distance = distance,
                isStackOrder = deliveryCount > 1,
                isSameLocationStack = isSameLocationStack,
                sameDropoffText = sameDropoffText,
                sameDropoffMatched = sameDropoffMatched,
                deliveryCount = deliveryCount,
                isExclusive = isExclusive,
                isTargetOffer = isTargetOffer,
                isAddOnOrder = isAddOnOrder,
                address = address,
                addressSource = "ADDRESS_DIRECT",
                storeName = storeName,
                priceStatus = fieldStatus(priceText, price > 0),
                tripStatus = fieldStatus(tripText, minutes > 0 && distance > 0.0),
                merchantStatus = merchantStatus,
                addressStatus = addressStatus,
                typeStatus = fieldStatus(typeText, typeText.isNotBlank())
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun fieldStatus(source: String, parsed: Boolean): String {
        return when {
            parsed -> "OK"
            source.isBlank() -> "MISSING"
            else -> "PARSE_FAILED"
        }
    }

    private fun matchesSameDropoff(text: String): Boolean {
        if (text.isBlank()) return false
        return listOf('訂', '單', '相', '同').all { text.contains(it) }
    }

    private fun mergeRegionText(primary: String, secondary: String): String {
        return listOf(primary, secondary)
            .flatMap { it.lines() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
    }

    private fun selectRegionMerchant(
        merchantText: String,
        merchantWideText: String
    ): String {
        return parseRegionStoreName(merchantText)
            .ifBlank { parseRegionStoreName(merchantWideText) }
    }

    private fun selectRegionAddress(
        addressText: String,
        addressWideText: String,
        addressLowerText: String,
        storeName: String
    ): AddressSelection {
        val addressLines = parseAddressLines(addressText)
            .filter { line -> line != storeName }
        val wideLines = parseAddressLines(addressWideText)
            .filter { line -> line != storeName }
        val lowerLines = parseAddressLines(addressLowerText)
            .filter { line -> line != storeName }

        val (selectedLines, source) = when {
            addressLines.isNotEmpty() -> addressLines to "ADDRESS"
            wideLines.isNotEmpty() -> wideLines to "ADDRESS_WIDE"
            else -> lowerLines to "ADDRESS_LOWER"
        }
        return AddressSelection(selectedLines.joinToString("\n"), source)
    }

    fun buildFailureMessage(screenText: String): String {
        val normalizedText = normalize(screenText)
        val price = parsePrice(normalizedText)
        val minutes = parseMinutes(normalizedText)
        val distance = parseDistance(normalizedText)
        val isTargetOffer = isLikelyTargetOffer(normalizedText)

        val missingItems = mutableListOf<String>()
        if (!isTargetOffer) missingItems.add("接單卡片特征")
        if (price <= 0) missingItems.add("金额")
        if (minutes <= 0) missingItems.add("分钟")
        if (distance <= 0.0) missingItems.add("公里")

        return buildString {
            appendLine("未识别到完整订单")
            appendLine("缺少：${missingItems.joinToString("、")}")
            appendLine()
            appendLine("OCR文字：")
            append(normalizedText.ifBlank { "空白" })
        }
    }

    private fun normalize(text: String): String {
        return text
            .map { char ->
                when (char) {
                    in '０'..'９' -> '0' + (char - '０')
                    else -> char
                }
            }
            .joinToString("")
            .replace('（', '(')
            .replace('）', ')')
            .replace('＄', '$')
            .replace('，', ',')
            .replace(Regex("[\\t\\u00A0]+"), " ")
            .lines()
            .joinToString("\n") { line ->
                line.trim().replace(Regex(" {2,}"), " ")
            }
    }

    private fun parsePrice(text: String): Int {
        val normalizedPriceText = normalizePriceOcr(text)
        val patterns = listOf(
            Regex("(?:NT\\$|TWD\\$|\\$)\\s*([0-9]{2,4})", RegexOption.IGNORE_CASE),
            Regex("金額\\s*([0-9]{2,4})"),
            Regex("費用\\s*([0-9]{2,4})")
        )

        patterns.firstNotNullOfOrNull { regex ->
            regex.find(normalizedPriceText)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.takeIf { it in 20..500 }
        }?.let { return it }

        return Regex("\\d+")
            .findAll(normalizedPriceText)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 20..500 }
            .maxOrNull()
            ?: 0
    }

    private fun normalizePriceOcr(text: String): String {
        return text.map { char ->
            when (char) {
                'l', 'I', '|' -> '1'
                'O', 'o' -> '0'
                'S' -> '5'
                'B' -> '8'
                else -> char
            }
        }.joinToString("")
    }

    private fun parsePriceFromSources(vararg sources: String): Int {
        return sources.firstNotNullOfOrNull { source ->
            parsePrice(source).takeIf { it > 0 }
        } ?: 0
    }

    private fun parseMinutes(text: String): Int {
        val minuteKeyword = "(?:分鐘|分钟|分鍾|分锺|分鈡|分鋒|分)"
        val distanceKeyword = "(?:公里|公裏|公哩|里|km)"
        val patterns = listOf(
            Regex("([0-9]{1,3})\\s*$minuteKeyword\\s*\\(\\s*[0-9]+(?:\\.[0-9]+)?\\s*$distanceKeyword\\s*\\)\\s*(?:總計|总计)?", RegexOption.IGNORE_CASE),
            Regex("([0-9]{1,3})\\s*$minuteKeyword", RegexOption.IGNORE_CASE),
            Regex("(?:^|\\n|\\s|O|o|0)\\s*([0-9]{1,3})\\s*\\(\\s*[0-9]+(?:\\.[0-9]+)?\\s*\\)")
        )

        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 0
    }

    private fun parseMinutesFromSources(vararg sources: String): Int {
        return sources.firstNotNullOfOrNull { source ->
            parseMinutes(source).takeIf { it > 0 }
        } ?: 0
    }

    private fun normalizeMinutes(minutes: Int): Int {
        // Temporarily disabled in parsing flow: keep OCR numeric output unchanged.
        if (minutes in 91..99) return minutes % 10
        if (minutes in 1..180) return minutes

        val lastTwoDigits = minutes % 100
        return if (lastTwoDigits in 5..99) {
            lastTwoDigits
        } else {
            minutes
        }
    }

    private fun parseDistance(text: String): Double {
        val minuteKeyword = "(?:分鐘|分钟|分鍾|分锺|分鈡|分鋒|分)"
        val distanceKeyword = "(?:公里|公裏|公哩|里|km)"
        val patterns = listOf(
            Regex("[0-9]{1,3}\\s*$minuteKeyword\\s*\\(\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$distanceKeyword\\s*\\)\\s*(?:總計|总计)?", RegexOption.IGNORE_CASE),
            Regex("\\(\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$distanceKeyword\\s*\\)", RegexOption.IGNORE_CASE),
            Regex("([0-9]+(?:\\.[0-9]+)?)\\s*$distanceKeyword", RegexOption.IGNORE_CASE),
            Regex("(?:^|\\n|\\s|O|o|0)\\s*[0-9]{1,3}\\s*\\(\\s*([0-9]+(?:\\.[0-9]+)?)\\s*\\)")
        )

        val raw = patterns.firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.getOrNull(1)
        }.orEmpty()
        val parsed = raw.toDoubleOrNull() ?: 0.0
        val corrected = correctMissingDistanceDecimal(raw, parsed)
        lastDistanceDebug = DistanceDebug(
            raw = raw,
            parsed = corrected,
            corrected = corrected != parsed,
            correctionReason = if (corrected != parsed) "missing_decimal_point" else ""
        )
        return corrected
    }

    private fun parseDistanceFromSources(vararg sources: String): Double {
        return sources.firstNotNullOfOrNull { source ->
            parseDistance(source).takeIf { it > 0.0 }
        } ?: 0.0
    }

    private fun normalizeDistance(distance: Double): Double {
        // Temporarily disabled in parsing flow: keep OCR numeric output unchanged.
        if (distance % 1.0 != 0.0) return distance

        val integerDistance = distance.toInt()
        return if (integerDistance in 21..99) {
            integerDistance / 10.0
        } else {
            distance
        }
    }

    private fun correctMissingDistanceDecimal(raw: String, parsed: Double): Double {
        if (raw.contains(".")) return parsed
        if (parsed < 30.0) return parsed
        if (!Regex("^[0-9]{2}$").matches(raw)) return parsed

        val restored = raw.toDoubleOrNull()?.div(10.0) ?: return parsed
        return if (restored in 1.0..20.0) restored else parsed
    }

    private fun parseDeliveryCount(text: String): Int {
        val patterns = listOf(
            Regex("[\\(（]\\s*([0-9]+)\\s*[\\)）]"),
            Regex("[\\(（]\\s*([二兩两])\\s*[\\)）]")
        )

        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.get(1)?.let(::parseDeliveryCountToken)
        } ?: 1
    }

    private fun parseDeliveryCountToken(token: String): Int? {
        val rawCount = when (token) {
            "二", "兩", "两" -> 2
            else -> token.toIntOrNull()
        } ?: return null
        return normalizeDeliveryCount(rawCount)
    }

    private fun normalizeDeliveryCount(count: Int): Int {
        return when {
            count <= 1 -> 1
            else -> count
        }
    }

    private fun parseAddressLines(text: String): List<String> {
        return text
            .lines()
            .map { it.trim().replace(Regex("\\s{2,}"), " ") }
            .filter { it.isNotBlank() }
            .filter(::isValidAddressLine)
    }

    private fun parseDetailLines(text: String): List<String> {
        return text
            .lines()
            .map(::cleanDetailLine)
            .filter { line -> isUsefulDetailLine(line) || isAddressContinuationLine(line) }
            .distinct()
    }

    private fun parseCardDetailLines(text: String): List<String> {
        val lines = text
            .lines()
            .map(::cleanDetailLine)
            .filter { it.isNotBlank() }
        val totalIndex = lines.indexOfFirst { line ->
            (line.contains("總計") || line.contains("总计")) &&
                    (line.contains("分鐘") || line.contains("分钟") || line.contains("公里"))
        }
        val detailStart = if (totalIndex >= 0) {
            totalIndex + 1
        } else {
            lines.indexOfFirst { it.startsWith("$") }.takeIf { it >= 0 }?.plus(2) ?: 0
        }
        return lines
            .drop(detailStart)
            .takeWhile { line ->
                !line.contains("接受") &&
                        !line.contains("配對") &&
                        !line.contains("配对")
            }
            .filter { line -> isUsefulDetailLine(line) || isAddressContinuationLine(line) }
            .distinct()
    }

    private fun parseRegionAddressLines(
        addressText: String,
        addressLowerText: String,
        detailLines: List<String>,
        storeName: String,
        detailStoreCandidate: String
    ): List<String> {
        val directAddressLines = parseAddressLines(listOf(addressText, addressLowerText).joinToString("\n"))
        val bottomAddressLines = parseBottomAddressLines(detailLines)
        val effectiveStore = detailStoreCandidate.ifBlank { storeName }
        val detailAddressLines = if (effectiveStore.isBlank()) {
            val firstAddressIndex = detailLines.indexOfFirst(::looksLikeAddressLine)
            if (firstAddressIndex >= 0) detailLines.drop(firstAddressIndex) else emptyList()
        } else {
            val storeIndex = detailLines.indexOfFirst { it == effectiveStore }
            if (storeIndex >= 0) {
                detailLines.drop(storeIndex + 1)
            } else {
                val firstAddressIndex = detailLines.indexOfFirst(::looksLikeAddressLine)
                if (firstAddressIndex >= 0) detailLines.drop(firstAddressIndex) else emptyList()
            }
        }
        val merged = dedupeAddressLines(mergeAddressFragments(bottomAddressLines + detailAddressLines + directAddressLines)
            .map(::cleanDetailLine)
            .map(::correctAddressLine)
            .filter { line ->
                isValidAddressLine(line) &&
                        line != storeName
            }
        )

        val firstAddressIndex = merged.indexOfFirst(::looksLikeAddressLine)
        val addressLines = if (firstAddressIndex >= 0) {
            merged.drop(firstAddressIndex)
        } else {
            merged
        }
        return orderAddressLines(addressLines)
    }

    private fun mergeAddressFragments(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        lines.map(::cleanDetailLine).filter { it.isNotBlank() }.forEach { line ->
            val normalized = correctAddressLine(line)
            val shouldAppendToPrevious = result.isNotEmpty() &&
                    normalized.matches(Regex("^[路街巷弄]$"))
            if (shouldAppendToPrevious) {
                result[result.lastIndex] = result.last() + normalized
            } else {
                result.add(line)
            }
        }
        return result
    }

    private fun dedupeAddressLines(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        lines.forEach { line ->
            val key = normalizeAddressDedupKey(line)
            if (key.isBlank()) return@forEach
            val existingIndex = result.indexOfFirst { existing ->
                val existingKey = normalizeAddressDedupKey(existing)
                existingKey == key ||
                        existingKey.contains(key) ||
                        key.contains(existingKey) ||
                        addressKeysLikelySame(existingKey, key)
            }
            if (existingIndex < 0) {
                result.add(line)
            } else if (line.length > result[existingIndex].length) {
                result[existingIndex] = line
            }
        }
        return result
    }

    private fun addressKeysLikelySame(a: String, b: String): Boolean {
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        if (shorter.length < 8) return false
        return longer.startsWith(shorter) || levenshteinDistance(
            longer.take(shorter.length),
            shorter,
            1
        ) <= 1
    }

    private fun normalizeAddressDedupKey(line: String): String {
        return line
            .lowercase()
            .replace(Regex("[^\\p{IsHan}a-z0-9]"), "")
    }

    private fun parseBottomAddressLines(detailLines: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (line in detailLines.asReversed()) {
            val corrected = correctAddressLine(cleanDetailLine(line))
            if (isValidAddressLine(corrected)) {
                result.add(corrected)
                if (result.size >= 2) break
            } else if (result.isNotEmpty()) {
                break
            }
        }
        return result.asReversed()
    }

    private fun parseStoreNameFromDetail(detailLines: List<String>): String {
        val addressIndex = detailLines.indexOfFirst(::looksLikeAddressLine)
        val merchantSearchLines = if (addressIndex > 0) {
            detailLines.take(addressIndex)
        } else {
            detailLines
        }
        return merchantSearchLines
            .asReversed()
            .map(::correctMerchantName)
            .firstOrNull(::isLikelyStoreName)
            .orEmpty()
    }

    private fun chooseStoreName(
        merchantCandidate: String,
        detailStoreCandidate: String,
        detailLines: List<String>
    ): String {
        val merchantLooksBad = merchantCandidate.isBlank() ||
                looksLikeAddressLine(merchantCandidate) ||
                overlapsAddressCandidate(merchantCandidate, detailLines) ||
                looksLikeNoisyMerchantCandidate(merchantCandidate)

        if (merchantLooksBad) return detailStoreCandidate
        if (
            detailStoreCandidate.isNotBlank() &&
            detailStoreCandidate.length > merchantCandidate.length &&
            detailStoreCandidate.contains(merchantCandidate)
        ) {
            return detailStoreCandidate
        }
        return merchantCandidate
    }

    private fun looksLikeNoisyMerchantCandidate(candidate: String): Boolean {
        if (candidate.isBlank()) return true
        if (isUiNoiseLine(candidate)) return true
        val hasMerchantHint = Regex("[A-Za-z]|麥|麦|Pizza|Hut|必勝|必胜|飯|麵|便當|早餐|早點|茶|咖啡|飲|鍋|堡|雞|滷|壽司").containsMatchIn(candidate)
        if (hasMerchantHint) return false
        val suspiciousChars = candidate.count { it in "出图圖体體用智四弧列辆輛門約搔技" }
        return suspiciousChars >= 2 || candidate.length >= 6 && suspiciousChars * 2 >= candidate.length
    }

    private fun overlapsAddressCandidate(candidate: String, detailLines: List<String>): Boolean {
        return detailLines.any { line ->
            line != candidate &&
                    looksLikeAddressLine(line) &&
                    (line.contains(candidate) || candidate.contains(line))
        }
    }

    private fun orderAddressLines(lines: List<String>): List<String> {
        if (lines.size <= 1) return lines
        val firstPrimaryIndex = lines.indexOfFirst(::looksLikePrimaryAddressLine)
        val ordered = if (firstPrimaryIndex > 0) {
            lines.drop(firstPrimaryIndex) + lines.take(firstPrimaryIndex)
        } else {
            lines
        }
        val deduped = dedupeAddressLines(ordered)
        if (deduped.size <= 2) return deduped

        val primary = deduped.firstOrNull(::looksLikePrimaryAddressLine) ?: deduped.first()
        val primaryKey = normalizeAddressDedupKey(primary)
        val tail = deduped
            .dropWhile { it != primary }
            .drop(1)
            .firstOrNull { line ->
                val key = normalizeAddressDedupKey(line)
                key.isNotBlank() &&
                        !addressKeysLikelySame(primaryKey, key) &&
                        !looksLikePrimaryAddressLine(line)
            }
            ?: deduped.firstOrNull { line ->
                line != primary &&
                        !addressKeysLikelySame(primaryKey, normalizeAddressDedupKey(line))
            }

        return listOfNotNull(primary, tail).take(2)
    }

    private fun cleanDetailLine(line: String): String {
        return line
            .trim()
            .replace(Regex("^[●•·\\-\\|丨!！lI\\s]+"), "")
            .replace(Regex("^[B口□■▢▣]\\s*(?=(?:[0-9]{3}|台灣|台湾|Taiwan|桃園|新北|龜山|林口))"), "")
            .replace(Regex("^[89]\\s*(?=(?:333|台灣|台湾|Taiwan|桃園|新北|龜山|林口))"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun correctAddressLine(line: String): String {
        // Strong OCR correction is temporarily disabled; keep only basic numeric/spacing cleanup.
        return line
            .map { char ->
                when (char) {
                    in '０'..'９' -> '0' + (char - '０')
                    else -> char
                }
            }
            .joinToString("")
            .replace(Regex("(?<=[0-9])\\s+(?=[0-9])"), "")
            .replace(Regex("(?<=[0-9])\\s+之\\s*(?=[0-9])"), "之")
            .replace(Regex("(?<=[0-9])\\s+(?=[號号樓楼])"), "")
            .replace(Regex("(?<=[號号樓楼之])\\s+(?=[0-9])"), "")
            .replace(Regex("(?<=[樓楼])\\s+(?=之)"), "")
    }

    private fun trimInvalidAddressTail(line: String): String {
        val streetMatch = Regex(".*[路街巷弄]").find(line) ?: return line
        val base = streetMatch.value
        val tail = line.removePrefix(base).trim()
        if (tail.isBlank()) return line

        val validTailPatterns = listOf(
            Regex("^[0-9]+$"),
            Regex("^[一二三四五六七八九十]+$"),
            Regex("^[一二三四五六七八九十0-9]+段$"),
            Regex("^[一二三四五六七八九十0-9]+段[0-9]+號(?:[0-9]+樓(?:之[0-9]+)?|[0-9]*樓?|之[0-9]+)?$"),
            Regex("^[0-9]+號(?:[0-9]+樓(?:之[0-9]+)?|[0-9]*樓?|之[0-9]+)?$"),
            Regex("^[0-9]+巷(?:[0-9]+弄)?[0-9]+號(?:[0-9]+樓(?:之[0-9]+)?)?$"),
            Regex("^[0-9]+弄[0-9]+號(?:[0-9]+樓(?:之[0-9]+)?)?$"),
            Regex("^[0-9]+樓(?:之[0-9]+)?$"),
            Regex("^之[0-9]+$")
        )
        if (validTailPatterns.any { it.matches(tail) }) return line

        return if (looksLikePrimaryAddressLine(base)) base else line
    }

    private fun isValidAddressLine(line: String): Boolean {
        if (!isUsefulDetailLine(line)) return false
        if (line.contains("$") || line.contains("分鐘") || line.contains("分钟") || line.contains("公里")) {
            return false
        }
        val addressKeywords = Regex("[市縣县區区鄉乡鎮镇路街巷弄號号樓楼大道段]")
        val addressTailPattern = Regex("^[0-9]+(?:[號号](?:[0-9]+[樓楼](?:之[0-9]+)?|[0-9]*[樓楼]?|之[0-9]+)?|[樓楼](?:之?[0-9]+)?|之[0-9]+)$")
        val roadAddressPattern = Regex("[路街巷弄大道段].*[0-9]+(?:[號号])?(?:[0-9]+[樓楼](?:之[0-9]+)?)?")
        val pureNumberNoise = Regex("^[0-9]{2,}$")
        val hasNumber = Regex("[0-9]").containsMatchIn(line)
        val hasStreetLevelKeyword = Regex("[路街巷弄號号樓楼大道段]").containsMatchIn(line)
        val hasCityLevelPrefix = Regex("^(?:[0-9]+)?(?:台灣|台湾|Taiwan)?(?:桃園市|新北市|臺北市|台北市)").containsMatchIn(line) ||
                line.contains("桃園市") ||
                line.contains("新北市")

        return !pureNumberNoise.matches(line) &&
                (hasNumber || hasStreetLevelKeyword || hasCityLevelPrefix) &&
                (line.length >= 4 && addressKeywords.containsMatchIn(line) ||
                        addressTailPattern.containsMatchIn(line) ||
                        roadAddressPattern.containsMatchIn(line))
    }

    private fun correctKnownAddressPhrase(line: String, phrase: String): String {
        // Temporarily unused while forced address phrase correction is disabled.
        if (line.contains(phrase)) return line
        if (shouldUseExactAddressPhraseOnly(phrase)) return line
        if (line.length < phrase.length) return line
        for (start in 0..(line.length - phrase.length)) {
            val candidate = line.substring(start, start + phrase.length)
            if (levenshteinDistance(candidate, phrase, 1) <= 1) {
                return line.replaceRange(start, start + phrase.length, phrase)
            }
        }
        return line
    }

    private fun shouldUseExactAddressPhraseOnly(phrase: String): Boolean {
        return Regex("[路街巷弄段里]|大道|醫院|医院|醫護|医护").containsMatchIn(phrase)
    }

    private fun levenshteinDistance(a: String, b: String, limit: Int): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost
                )
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > limit) return limit + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun isUsefulDetailLine(line: String): Boolean {
        return line.length >= 2 &&
                !isUiNoiseLine(line) &&
                !line.contains("$") &&
                !line.contains("分鐘") &&
                !line.contains("分钟") &&
                !line.contains("公里") &&
                !line.contains("總計") &&
                !line.contains("总计") &&
                !line.contains("接受") &&
                !line.contains("配對") &&
                !line.contains("配对") &&
                !line.startsWith("外送") &&
                line != "X" &&
                line != "×"
    }

    private fun isAddressContinuationLine(line: String): Boolean {
        return line.matches(Regex("^[路街巷弄]$"))
    }

    private fun looksLikeAddressLine(line: String): Boolean {
        return isValidAddressLine(correctAddressLine(cleanDetailLine(line)))
    }

    private fun looksLikePrimaryAddressLine(line: String): Boolean {
        return Regex("[市縣县區区鄉乡鎮镇里路街巷大道段]").containsMatchIn(line)
    }

    private fun parseStoreName(text: String, addressLines: List<String>): String {
        val lines = text
            .lines()
            .map { it.trim().replace(Regex("\\s{2,}"), " ") }
            .filter { it.isNotBlank() }

        val totalIndex = lines.indexOfFirst { line ->
            (line.contains("總計") || line.contains("总计")) &&
                    (line.contains("分鐘") || line.contains("分钟") || line.contains("公里"))
        }
        if (totalIndex >= 0) {
            val linesAfterTotal = lines.drop(totalIndex + 1)
            if (linesAfterTotal.any { line -> addressLines.any { address -> address == line } }) {
                linesAfterTotal
                    .takeWhile { line -> addressLines.none { address -> address == line } }
                    .firstOrNull(::isLikelyRawStoreName)
                    ?.let { return it }
            }
        }

        val firstAddressIndex = lines.indexOfFirst { line ->
            addressLines.any { address -> address == line }
        }
        if (firstAddressIndex > 0) {
            lines.subList(0, firstAddressIndex)
                .asReversed()
                .firstOrNull(::isLikelyRawStoreName)
                ?.let { return it }
        }

        return lines
            .firstOrNull(::isLikelyRawStoreName)
            .orEmpty()
    }

    private fun parseRegionStoreName(text: String): String {
        return text
            .lines()
            .map { it.trim().replace(Regex("\\s{2,}"), " ") }
            .filter(::isLikelyRawStoreName)
            .maxByOrNull { it.length }
            .orEmpty()
    }

    private fun isLikelyRawStoreName(line: String): Boolean {
        val normalized = line.trim()
        val looksLikeRawAddress = Regex("(台灣|台湾|Taiwan|桃園市|新北市|龜山區|林口區|[路街巷弄號号楼樓])").containsMatchIn(normalized) &&
                Regex("[0-9]").containsMatchIn(normalized)
        return normalized.length >= 2 &&
                !isUiNoiseLine(normalized) &&
                !normalized.contains("$") &&
                !normalized.contains("分鐘") &&
                !normalized.contains("分钟") &&
                !normalized.contains("公里") &&
                !normalized.startsWith("外送") &&
                !looksLikeRawAddress &&
                !normalized.contains("接受") &&
                !normalized.contains("配對") &&
                !normalized.contains("配对") &&
                !normalized.contains("總計") &&
                !normalized.contains("总计")
    }

    private fun cleanMerchantCandidate(line: String): String {
        return line
            .trim()
            .replace(Regex("^[●•·\\-\\|丨!！lI\\s]+"), "")
            .replace(Regex("^[90oO]\\s*(?=[\\p{IsHan}A-Za-z])"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun correctMerchantName(line: String): String {
        // Strong OCR correction and merchant dictionary matching are temporarily disabled.
        return line.trim()
    }

    private fun isLikelyStoreName(line: String): Boolean {
        val normalized = line.trim()
        return normalized.length >= 2 &&
                !isUiNoiseLine(normalized) &&
                !line.contains("$") &&
                !line.contains("分鐘") &&
                !line.contains("分钟") &&
                !line.contains("公里") &&
                !line.startsWith("外送") &&
                !looksLikeAddressText(line) &&
                !line.contains("接受") &&
                !line.contains("配對") &&
                !line.contains("配对") &&
                !line.contains("總計") &&
                !line.contains("总计")
    }

    private fun isUiNoiseLine(line: String): Boolean {
        val normalized = line
            .trim()
            .replace(Regex("\\s+"), "")
            .replace("：", "")
            .replace(":", "")
        if (normalized.isBlank()) return true

        val exactNoiseWords = setOf(
            "獨享", "独享", "獨亨", "独亨", "獨学", "独学",
            "外送", "外送2", "外送(2)", "外送（2）",
            "配對", "配对", "接受", "按受", "接收", "技受", "搔受",
            "类型", "類型", "金額", "金额", "時間", "时间",
            "距離", "距离", "預計時薪", "预计时薪", "评分", "評分",
            "商家", "地址", "行程資訊", "行程资讯", "截图分析", "識別記錄", "识别记录"
        )
        if (normalized in exactNoiseWords) return true
        if (Regex("^[A-Za-zYTP?\"'!|丨lI]*外送(?:\\(?[0-9二兩两]+\\)?)?(?:獨享|独享|獨亨|独亨)?$").matches(normalized)) {
            return true
        }
        return false
    }

    private fun looksLikeAddressText(line: String): Boolean {
        val cleaned = correctAddressLine(cleanDetailLine(line))
        return Regex("(台灣|台湾|Taiwan|桃園市|新北市|龜山區|林口區|[路街巷弄號楼樓])").containsMatchIn(cleaned) &&
                Regex("[0-9]").containsMatchIn(cleaned)
    }

    private fun isAddOnOrder(text: String): Boolean {
        return text.contains("新增外送訂單") ||
                text.contains("新增外送订单") ||
                Regex("(?m)^\\s*\\+\\s*\\$?\\s*[0-9]{2,4}\\s*$").containsMatchIn(text)
    }

    private fun hasSameLocationStackFeature(text: String): Boolean {
        val patterns = listOf(
            "取貨地點相同",
            "取货地点相同",
            "取餐地點相同",
            "取餐地点相同",
            "配送地點相同",
            "配送地点相同",
            "送達地點相同",
            "送达地点相同",
            "同一取貨",
            "同一取货",
            "同一配送"
        )

        return patterns.any { pattern ->
            text.contains(pattern)
        }
    }

    private fun isLikelyTargetOffer(text: String): Boolean {
        val hasDeliveryBadge = Regex("外送(?:\\s*\\(?\\s*[0-9]+\\s*\\)?)?").containsMatchIn(text)
        val hasActionButton = text.contains("接受") ||
                text.contains("配對") ||
                text.contains("配对")
        val hasOfferNumbers = parsePrice(text) > 0 &&
                parseMinutes(text) > 0 &&
                parseDistance(text) > 0
        val hasStoreOrCloseButton = text.contains("Pizza Hut") ||
                Regex("(?:^|\\n)\\s*[Xx]\\s*(?:\\n|$)").containsMatchIn(text)

        return hasDeliveryBadge && (hasActionButton || hasOfferNumbers) ||
                hasOfferNumbers && hasStoreOrCloseButton
    }

    private val linkouGuishanAddressLexicon = listOf(
        "桃園市",
        "新北市",
        "龜山區",
        "林口區",
        "樂善里",
        "長庚里",
        "文化里",
        "南勢里",
        "湖南里",
        "仁愛里",
        "麗林里",
        "東林里",
        "文青里",
        "大埔里",
        "大同里",
        "大湖里",
        "大崗里",
        "大華里",
        "龍壽里",
        "舊路里",
        "頂湖里",
        "公西里",
        "龜山里",
        "樂學里",
        "文青路",
        "文青一路",
        "文青二路",
        "文青路一段",
        "文青路二段",
        "文化一路",
        "文化二路",
        "文化三路",
        "文化北路",
        "文化北路一段",
        "文化北路二段",
        "文化南路",
        "文化南路一段",
        "文化南路二段",
        "復興一路",
        "復興二路",
        "復興三路",
        "復興北路",
        "復興街",
        "忠孝路",
        "忠義路",
        "公園路",
        "中山路",
        "中正路",
        "中華路",
        "中興路",
        "民權路",
        "民族路",
        "仁愛路",
        "四維路",
        "自強路",
        "南上路",
        "南祥路",
        "頂湖路",
        "頂湖一街",
        "頂湖二街",
        "頂湖三街",
        "頂湖五街",
        "金湖街",
        "文禾路",
        "文桃路",
        "長庚路",
        "長庚街",
        "萬壽路",
        "萬壽路一段",
        "萬壽路二段",
        "龍壽街",
        "大湖路",
        "大同路",
        "大崗路",
        "大華路",
        "樂學路",
        "牛角坡路",
        "科技一路",
        "科技二路",
        "科技三路",
        "華亞一路",
        "華亞二路",
        "華亞三路",
        "長庚十街",
        "長庚十一街",
        "長庚十二街",
        "長庚醫院",
        "林口長庚",
        "長庚大學",
        "長庚科技大學",
        "環球購物中心",
        "三井Outlet",
        "環球A8",
        "林口三井",
        "機場捷運",
        "長庚醫院站",
        "林口站",
        "體育大學站"
    )
}
