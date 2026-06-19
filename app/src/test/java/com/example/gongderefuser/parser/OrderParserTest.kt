package com.example.gongderefuser.parser

import com.example.gongderefuser.MerchantDictionaryStore
import com.example.gongderefuser.OcrCorrectionStore
import com.example.gongderefuser.analyzer.OrderAnalyzer
import org.junit.BeforeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class OrderParserTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOcrCorrections() {
            val file = listOf(
                File("app/src/main/assets/ocr_corrections.txt"),
                File("src/main/assets/ocr_corrections.txt")
            ).first { it.exists() }
            OcrCorrectionStore.loadFromText(
                file.readText(Charsets.UTF_8)
            )
            val merchantFile = listOf(
                File("app/src/main/assets/merchant_dictionary.txt"),
                File("src/main/assets/merchant_dictionary.txt")
            ).first { it.exists() }
            MerchantDictionaryStore.loadFromText(
                merchantFile.readText(Charsets.UTF_8)
            )
        }
    }

    @Test
    fun parsePriceRegionToleratesCommonOcrDigitConfusions() {
        val cases = mapOf(
            "99" to 99,
            "100" to 100,
            "86" to 86,
            "145" to 145,
            "${'$'}45" to 45,
            "${'$'}99" to 99,
            "99${'$'}" to 99,
            "金額99元" to 99,
            "訂單金額145元" to 145,
            "${'$'}l38" to 138,
            "${'$'}l08" to 108,
            "${'$'}l83" to 183,
            "${'$'}1O8" to 108,
            "${'$'}BO" to 80
        )

        cases.forEach { (priceText, expectedPrice) ->
            val order = OrderParser.parse(
                OrderParser.RegionInput(
                    fullText = "",
                    cardText = "",
                    typeText = "外送",
                    priceText = priceText,
                    tripText = "10分鐘 (2.0公里) 總計",
                    merchantText = "測試商家",
                    addressText = "333台灣桃園市龜山區文化一路83號"
                )
            )

            assertNotNull("priceText=$priceText", order)
            assertEquals("priceText=$priceText", expectedPrice, order!!.price)
        }
    }

    @Test
    fun parseRegionUsesRawMerchantAndAddressTextDirectly() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "",
                cardText = "",
                typeText = "外送",
                priceText = "${'$'}99",
                tripText = "20分鐘 (4.0公里) 總計",
                merchantText = "路易莎咖啡環球購物中心桃園A8",
                addressText = """
                    244台灣新北市林口區南勢里文化三路
                    一段247號
                """.trimIndent()
            )
        )

        assertNotNull(order)
        assertEquals("路易莎咖啡環球購物中心桃園A8", order!!.storeName)
        assertEquals("OK", order.merchantStatus)
        assertEquals(
            """
            244台灣新北市林口區南勢里文化三路
            一段247號
            """.trimIndent(),
            order.address
        )
        assertEquals("OK", order.addressStatus)
    }

    @Test
    fun parseKeepsMergedMinutePrefixWithoutForcedCorrection() {
        val order = OrderParser.parse(
            """
            外送 (2)
            ${'$'}110
            933分鐘 (8.7公里) 總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(110, order!!.price)
        assertEquals(933, order.minutes)
        assertEquals(8.7, order.distance, 0.001)
        assertEquals(2, order.deliveryCount)
    }

    @Test
    fun parseStackOrderWhenDeliveryCountLosesParentheses() {
        val order = OrderParser.parse(
            """
            外送 2
            ${'$'}110
            33分鐘 (8.7公里) 總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(2, order!!.deliveryCount)
        assertEquals(true, order.isStackOrder)
        assertEquals("2单", OrderAnalyzer.analyzeResult(order).orderType)
    }

    @Test
    fun parseSingleOrderIgnoresExclusiveText() {
        val order = OrderParser.parse(
            """
            外送
            獨享
            ${'$'}80
            20分鐘 (4.0公里) 總計
            麥味登 龜山華亞店
            333台灣桃園市龜山區文化一路83號
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(1, order!!.deliveryCount)
        assertEquals(false, order.isStackOrder)
        assertEquals("一单", OrderAnalyzer.analyzeResult(order).orderType)
    }

    @Test
    fun parseAddOnDeliveryOrder() {
        val order = OrderParser.parse(
            """
            新增外送订单
            外送
            +${'$'}45
            13分鐘 (3.3公里) 總計
            清心福全 林口復興店
            333台灣桃園市龜山區樂善里樂善二路431號
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(true, order!!.isAddOnOrder)
        assertEquals("新增外送订单", OrderAnalyzer.analyzeResult(order).orderType)
    }

    @Test
    fun parseDeliveryCountOnlyUsesDeliveryBadge() {
        val order = OrderParser.parse(
            """
            外送
            獨享
            背景文字 2筆訂單
            ${'$'}80
            20分鐘 (4.0公里) 總計
            麥味登 龜山華亞店
            333台灣桃園市龜山區文化一路83號
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(1, order!!.deliveryCount)
        assertEquals(false, order.isStackOrder)
    }

    @Test
    fun parseRestoresMissingDecimalForClearlyUnreasonableIntegerDistance() {
        val order = OrderParser.parse(
            """
            外送
            ${'$'}110
            33分鐘 (74公里) 總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(7.4, order!!.distance, 0.001)
    }

    @Test
    fun parseKeepsReasonableIntegerDistance() {
        val order = OrderParser.parse(
            """
            外送
            ${'$'}210
            55分鐘 (12公里) 總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(12.0, order!!.distance, 0.001)
    }

    @Test
    fun parseKeepsNormalDecimalDistanceAtTwelvePointFive() {
        val order = OrderParser.parse(
            """
            外送
            ${'$'}260
            45分鐘 (12.5 公里) 總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(12.5, order!!.distance, 0.001)
    }

    @Test
    fun parseRestoresMissingDecimalFor76KmTripText() {
        val order = OrderParser.parse(
            """
            外送
            ${'$'}110
            26分鐘(76 公里)總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(7.6, order!!.distance, 0.001)
    }

    @Test
    fun parseMinuteKeywordWhenOcrReadsFeng() {
        val order = OrderParser.parse(
            """
            外送
            ${'$'}56
            17分鋒(3.6公里)總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(17, order!!.minutes)
        assertEquals(3.6, order.distance, 0.001)
    }

    @Test
    fun parseMinuteKeywordWhenOcrReadsZhongVariant() {
        val order = OrderParser.parse(
            """
            外送
            ${'$'}56
            17分鈡(3.6公里)總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(17, order!!.minutes)
        assertEquals(3.6, order.distance, 0.001)
    }

    @Test
    fun parseNormalDecimalDistanceUnchanged() {
        val order = OrderParser.parse(
            """
            外送
            ${'$'}98
            30分鐘(6.5 公里)總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(30, order!!.minutes)
        assertEquals(6.5, order.distance, 0.001)
    }

    @Test
    fun parseRestores47KmWhenClearlyUnreasonable() {
        val order = OrderParser.parse(
            """
            外送
            ${'$'}56
            17分鐘(47公里)總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(4.7, order!!.distance, 0.001)
    }

    @Test
    fun parseDoesNotAlterNormalDecimalDistanceAboveTwenty() {
        val order = OrderParser.parse(
            """
            外送
            ${'$'}650
            90分鐘(23.5公里)總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(23.5, order!!.distance, 0.001)
    }

    @Test
    fun parseSameLocationStackAsGoodStackOrder() {
        val order = OrderParser.parse(
            """
            外送 (2)
            同一取貨地點
            好吃早餐店
            桃園市中正路100號
            ${'$'}160
            30分鐘 (6.8公里) 總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(true, order!!.isSameLocationStack)
        assertEquals("2单", OrderAnalyzer.analyzeResult(order).orderType)
    }

    @Test
    fun parseStoreNameFromLineBeforeAddress() {
        val order = OrderParser.parse(
            """
            外送
            阿明便當
            桃園市長庚十街88號
            ${'$'}120
            25分鐘 (5.4公里) 總計
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals("阿明便當", order!!.storeName)
        assertEquals("桃園市長庚十街88號", order.address)
    }

    @Test
    fun parseStoreNameAfterTotalLineBeforeDeliveryAddress() {
        val order = OrderParser.parse(
            """
            外送
            獨享
            ${'$'}135
            28分鐘 (7.4公里) 總計
            麥當勞 林口復興 McDonald's S346
            333台灣桃園市龜山區文青里文青路
            353號21樓 之1
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals("麥當勞 林口復興 McDonald's S346", order!!.storeName)
        assertEquals(
            "333台灣桃園市龜山區文青里文青路\n353號21樓之1",
            order.address
        )
    }

    @Test
    fun parseKeepsLongSecondAddressLineWithRoadAndNumber() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}45\n9分鐘 (1.1公里) 總計\n接受",
                cardText = "外送\n${'$'}45\n9分鐘 (1.1公里) 總計\nPizza Hut必勝客\n333台灣桃園市龜山區樂善里\n文化二路466號\n接受",
                typeText = "外送\n獨享",
                priceText = "${'$'}45",
                tripText = "9分鐘 (1.1公里) 總計",
                detailText = "Pizza Hut必勝客\n333台灣桃園市龜山區樂善里\n文化二路466號",
                merchantText = "Pizza Hut必勝客",
                addressText = "333台灣桃園市龜山區樂善里",
                addressLowerText = "文化二路466號"
            )
        )

        assertNotNull(order)
        assertEquals(
            "333台灣桃園市龜山區樂善里\n文化二路466號",
            order!!.address
        )
    }

    @Test
    fun parseKeepsRawAddressCharactersWithBasicFloorSpacingCleanup() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}135\n28分鐘 (7.4公里) 總計\n接受",
                cardText = "外送\n${'$'}135\n28分鐘 (7.4公里) 總計\n麥當勞\n333台灣桃園市龜山區文青里文青路\n３５３号２１楼 之１\n接受",
                typeText = "外送\n獨享",
                priceText = "${'$'}135",
                tripText = "28分鐘 (7.4公里) 總計",
                detailText = "麥當勞\n333台灣桃園市龜山區文青里文青路\n３５３号２１楼 之１",
                merchantText = "麥當勞",
                addressText = "333台灣桃園市龜山區文青里文青路",
                addressLowerText = "３５３号２１楼 之１"
            )
        )

        assertNotNull(order)
        assertEquals(
            "333台灣桃園市龜山區文青里文青路\n353号21楼之1",
            order!!.address
        )
    }

    @Test
    fun parseKeepsRawGuishanCharacterInMerchantName() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}70\n21分鐘 (4.5公里) 總計\n接受",
                cardText = "外送\n${'$'}70\n21分鐘 (4.5公里) 總計\n麥味登 龟山丘比特\n333台灣桃園市龜山區長庚里長庚十街",
                typeText = "外送\n獨享",
                priceText = "${'$'}70",
                tripText = "21分鐘 (4.5公里) 總計",
                detailText = "麥味登 龟山丘比特\n333台灣桃園市龜山區長庚里長庚十街",
                merchantText = "麥味登 龟山丘比特",
                addressText = "333台灣桃園市龜山區長庚里長庚十街",
                addressLowerText = ""
            )
        )

        assertNotNull(order)
        assertEquals("麥味登 龟山丘比特", order!!.storeName)
    }

    @Test
    fun parseKeepsNoisyAddressTailWithoutForcedCorrection() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}70\n21分鐘 (4.5公里) 總計\n接受",
                cardText = "外送\n${'$'}70\n21分鐘 (4.5公里) 總計\n麥味登 龟山丘比特\n333台灣桃園市龜山區長庚里長庚十街47號列",
                typeText = "外送\n獨享",
                priceText = "${'$'}70",
                tripText = "21分鐘 (4.5公里) 總計",
                detailText = "麥味登 龟山丘比特\n333台灣桃園市龜山區長庚里長庚十街47號列",
                merchantText = "麥味登 龟山丘比特",
                addressText = "333台灣桃園市龜山區長庚里長庚十街47號列",
                addressLowerText = ""
            )
        )

        assertNotNull(order)
        assertEquals(
            "333台灣桃園市龜山區長庚里長庚十街47號列",
            order!!.address
        )
    }

    @Test
    fun parseKeepsGenericNoisyTailWithoutForcedCorrection() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}45\n13分鐘 (2.2公里) 總計\n配對",
                cardText = "外送\n${'$'}45\n13分鐘 (2.2公里) 總計\n麥味登\n333台灣桃園市龜山區文化里文化二路ABC列車",
                typeText = "外送",
                priceText = "${'$'}45",
                tripText = "13分鐘 (2.2公里) 總計",
                detailText = "麥味登\n333台灣桃園市龜山區文化里文化二路ABC列車",
                merchantText = "麥味登",
                addressText = "333台灣桃園市龜山區文化里文化二路ABC列車",
                addressLowerText = ""
            )
        )

        assertNotNull(order)
        assertEquals(
            "333台灣桃園市龜山區文化里文化二路ABC列車",
            order!!.address
        )
    }

    @Test
    fun parseKeepsValidHouseNumberTailAfterRoadName() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}45\n13分鐘 (2.2公里) 總計\n配對",
                cardText = "外送\n${'$'}45\n13分鐘 (2.2公里) 總計\n麥味登\n333台灣桃園市龜山區文化里文化二路211號",
                typeText = "外送",
                priceText = "${'$'}45",
                tripText = "13分鐘 (2.2公里) 總計",
                detailText = "麥味登\n333台灣桃園市龜山區文化里文化二路211號",
                merchantText = "麥味登",
                addressText = "333台灣桃園市龜山區文化里文化二路211號",
                addressLowerText = ""
            )
        )

        assertNotNull(order)
        assertEquals(
            "333台灣桃園市龜山區文化里文化二路211號",
            order!!.address
        )
    }

    @Test
    fun parseIgnoresNoiseOnlyLowerAddressLine() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}45\n9分鐘 (1.1公里) 總計\n接受",
                cardText = "外送\n${'$'}45\n9分鐘 (1.1公里) 總計\nPizza Hut必勝客\n333台灣桃園市龜山區樂善里文化二路",
                typeText = "外送\n獨享",
                priceText = "${'$'}45",
                tripText = "9分鐘 (1.1公里) 總計",
                detailText = "Pizza Hut必勝客\n333台灣桃園市龜山區樂善里文化二路",
                merchantText = "Pizza Hut必勝客",
                addressText = "333台灣桃園市龜山區樂善里文化二路",
                addressLowerText = "1688"
            )
        )

        assertNotNull(order)
        assertEquals(
            "333台灣桃園市龜山區樂善里文化二路",
            order!!.address
        )
    }

    @Test
    fun parseScreenshotAddressSamplesWithoutForcedAddressCorrection() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}123\n29分鐘 (7.1公里) 總計\n接受",
                cardText = "外送\n${'$'}123\n29分鐘 (7.1公里) 總計\n麥當勞 林口復興 McDonald's S346\nTaiwan桃園市桃園市龜山区金湖街47\n巷15弄7號",
                typeText = "外送\n獨享",
                priceText = "${'$'}123",
                tripText = "29分鐘 (7.1公里) 總計",
                detailText = "麥當勞 林口復興 McDonald's S346\nTaiwan桃園市桃園市龜山区金湖街47\n巷15弄7號",
                merchantText = "麥當勞 林口復興 McDonald's S346",
                addressText = "Taiwan桃園市桃園市龜山区金湖街47",
                addressLowerText = "巷15弄7號"
            )
        )

        assertNotNull(order)
        assertEquals("麥當勞 林口復興 McDonald's S346", order!!.storeName)
        assertEquals(
            "Taiwan桃園市桃園市龜山区金湖街47\n巷15弄7號",
            order.address
        )
    }

    @Test
    fun parsePairOrderAddressSamples() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送 (2)\n${'$'}110\n33分鐘 (8.7公里) 總計\n配對",
                cardText = "外送 (2)\n${'$'}110\n33分鐘 (8.7公里) 總計\n麥味登 龜山丘比特\n333台灣桃園市龜山區大華里頂湖五街\n18號\n配對",
                typeText = "外送 (2)\n獨享",
                priceText = "${'$'}110",
                tripText = "33分鐘 (8.7公里) 總計",
                detailText = "麥味登 龜山丘比特\n333台灣桃園市龜山區大華里頂湖五街\n18號",
                merchantText = "麥味登 龜山丘比特",
                addressText = "333台灣桃園市龜山區大華里頂湖五街",
                addressLowerText = "18號"
            )
        )

        assertNotNull(order)
        assertEquals("麥味登 龜山丘比特", order!!.storeName)
        assertEquals(
            "333台灣桃園市龜山區大華里頂湖五街\n18號",
            order.address
        )
    }

    @Test
    fun parseNormalizesImpossibleDeliveryCountFromOcrEightToTwo() {
        val order = OrderParser.parse(
            """
            外送 (8)
            ${'$'}94
            28分鐘 (7.2公里) 總計
            儒食 日式丼飯專門 龜山總店
            333台灣桃園市龜山區大華里頂湖路
            60號
            配對
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(2, order!!.deliveryCount)
        assertEquals(true, order.isStackOrder)
    }

    @Test
    fun parseWanShouRoadOneSectionAddress() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}86\n26分鐘 (9.6公里) 總計\n配對",
                cardText = "外送\n${'$'}86\n26分鐘 (9.6公里) 總計\n早點來吃\n333台灣桃園市龜山區龍壽里萬壽路一\n段1376號\n配對",
                typeText = "外送",
                priceText = "${'$'}86",
                tripText = "26分鐘 (9.6公里) 總計",
                detailText = "早點來吃\n333台灣桃園市龜山區龍壽里萬壽路一\n段1376號",
                merchantText = "早點來吃",
                addressText = "333台灣桃園市龜山區龍壽里萬壽路一",
                addressLowerText = "段1376號"
            )
        )

        assertNotNull(order)
        assertEquals("早點來吃", order!!.storeName)
        assertEquals(
            "333台灣桃園市龜山區龍壽里萬壽路一\n段1376號",
            order.address
        )
    }

    @Test
    fun parseDoesNotUseCardDetailWhenRegionFieldsAreNoisy() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = """
                    Y?外送 獨享
                    ${'$'}46
                    LINE
                    914分鐘(25公里)總計
                    Pizza Hut必勝客(林口文青)
                    新村170號
                    搔受
                    Taiwan桃園市桃園市龜山區長庚醫護
                """.trimIndent(),
                cardText = """
                    TT外达
                    ${'$'}46
                    O4分鐘(2.5公里)總計
                    Pizza Hut必勝客(林口文青店)
                    Taiwan桃園市桃園市龜山區長庚醫護
                    新村170號
                    接受
                """.trimIndent(),
                typeText = "${'$'}46",
                priceText = "(O14分籍(25ク",
                tripText = "Pizza Hut必勝客(林口文青店)",
                detailText = """
                    Taiwan桃園市桃園市山區長庚醫護
                    新村170號
                    接受
                """.trimIndent(),
                merchantText = "智四用出图化出体中",
                addressText = """
                    新村170弧
                    B
                    接受
                """.trimIndent(),
                addressLowerText = "接受\nN"
            )
        )

        assertNull(order)
    }

    @Test
    fun parseDedupesAddressLinesFromOverlappingRegions() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}60\n18分鐘 (4.0公里) 總計\n接受",
                cardText = "外送\n${'$'}60\n18分鐘 (4.0公里) 總計\n回憶港式茶餐廳\n333台灣桃園市龜山區文青里文青路\n268號\n接受",
                typeText = "外送\n獨享",
                priceText = "${'$'}60",
                tripText = "18分鐘 (4.0公里) 總計",
                detailText = "回憶港式茶餐廳\n333台灣桃園市龜山區文青里文青路\n268號",
                merchantText = "回憶港式茶餐廳",
                addressText = "333台灣桃園市龜山區文青里文青路\n333台灣桃園市龜山區文青里文青路",
                addressLowerText = "268號"
            )
        )

        assertNotNull(order)
        assertEquals(
            "333台灣桃園市龜山區文青里文青路\n268號",
            order!!.address
        )
    }

    @Test
    fun parseDoesNotUseExclusiveTextAsMerchantWhenDetailHasStore() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = """
                    P外送
                    ${'$'}99
                    獨亨
                    28分鐘(8.4公里)總計
                    麥當勞 龜山忠義 McDonalds S542
                    333台灣桃園市龜山區樂善里樂學三路
                    71號
                    接受
                """.trimIndent(),
                cardText = """
                    Y?外送
                    ${'$'}99
                    獨亨
                    S28分鐘(8.4公里)總計
                    麥當勞 龜山忠義 McDonalds S542
                    71號
                    X
                    333台灣桃園市龜山區樂善里樂學三路
                    技受
                """.trimIndent(),
                typeText = "P\"外送 獨亨",
                priceText = "${'$'}99",
                tripText = "O28分鐘(8.4公里)總計",
                detailText = """
                    8分鐘(8.4公里)總計
                    系當勞龜山忠義 McDonalds S542
                    33台灣桃園市龜山區樂善里樂學三路
                    1號
                """.trimIndent(),
                merchantText = "麥當勞 龜山忠義 McDonalds S542",
                addressText = "",
                addressLowerText = ""
            )
        )

        assertNotNull(order)
        assertEquals(false, order!!.storeName in setOf("獨亨", "獨享", "Y?外送", "外送"))
    }

    @Test
    fun parseDoesNotUseUiLabelAsMerchantWhenDetailHasStore() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = """
                    外送 獨享
                    ${'$'}98
                    24分鐘 (7.7公里) 總計
                    类型
                    金額
                    时间
                    來吃早餐
                    333台灣桃園市龜山區文化一路83號
                    接受
                """.trimIndent(),
                cardText = """
                    Y?外送 獨享
                    ${'$'}98
                    O24分鐘 (7.7 公里) 總計
                    类型
                    金額
                    时间
                    來吃早餐
                    333台灣桃園市龜山區文化一路83號
                """.trimIndent(),
                typeText = "外送 獨享",
                priceText = "${'$'}98",
                tripText = "24分鐘 (7.7 公里) 總計",
                detailText = """
                    类型
                    金額
                    來吃早餐
                    333台灣桃園市龜山區文化一路83號
                """.trimIndent(),
                merchantText = "來吃早餐",
                addressText = "333台灣桃園市龜山區文化一路83號",
                addressLowerText = ""
            )
        )

        assertNotNull(order)
        assertEquals("來吃早餐", order!!.storeName)
    }

    @Test
    fun parseMergesRoadSuffixButKeepsRawOcrNumbersAndMerchant() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = """
                    P?外送
                    ${'$'}45
                    99分鐘 (1.2公里) 總計
                    Pizza Hut必勝客(株口文青店)
                    333台灣桃園市龜山區樂善里文化一
                    路
                    83號
                    接受
                """.trimIndent(),
                cardText = """
                    TT外达
                    ${'$'}45
                    99分鐘(1.2 公里)總計
                    Pizza Hut必勝客(林口文青店)
                    83號
                    333台灣桃園市龜山區樂善里文化一
                    路
                    接受
                """.trimIndent(),
                typeText = "外送\n獨享",
                priceText = "${'$'}45",
                tripText = "99分鐘(1.2 公里)總計",
                detailText = """
                    333台灣桃園市山區樂善里文化一
                    路
                    83號
                    接受
                """.trimIndent(),
                merchantText = "Pizza Hut必勝客(株口文貴店)",
                addressText = "333台灣桃園市龜山區樂善里文化一\n路",
                addressLowerText = "83號"
            )
        )

        assertNotNull(order)
        assertEquals(99, order!!.minutes)
        assertEquals("Pizza Hut必勝客(株口文貴店)", order.storeName)
        assertEquals(
            "333台灣桃園市龜山區樂善里文化一路\n83號",
            order.address
        )
    }

    @Test
    fun parseRealtimePairOrderKeepsHouseNumberFloorLine() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = """
                    YP外送
                    ${'$'}56
                    C17分鐘(4.7公里)總計
                    癮作炒泡麵屋 龜山總店
                    333台灣桃園市龜山區大華里文化三路
                    256號2樓
                    X
                    配對
                """.trimIndent(),
                cardText = """
                    YP外送
                    ${'$'}56
                    C17分鐘(4.7公里)總計
                    癮作炒泡麵屋 龜山總店
                    333台灣桃園市龜山區大華里文化三路
                    256號2樓
                    X
                    配對
                """.trimIndent(),
                typeText = "外送",
                priceText = "${'$'}56",
                tripText = "C17分鐘(4.7公里)總計",
                detailText = """
                    作炒泡翘屋巍山總店
                    333台灣桃園市山區大華里文化三路
                    256號2楼
                    日
                    配對
                """.trimIndent(),
                merchantText = "癮作炒泡麵屋 龜山總店",
                addressText = "333台灣桃園市龜山區大華里文化三路",
                addressLowerText = "256號2楼"
            )
        )

        assertNotNull(order)
        assertEquals(56, order!!.price)
        assertEquals(17, order.minutes)
        assertEquals(4.7, order.distance, 0.001)
        assertEquals("癮作炒泡麵屋 龜山總店", order.storeName)
        assertEquals(
            "333台灣桃園市龜山區大華里文化三路\n256號2楼",
            order.address
        )
    }

    @Test
    fun parseKeepsRawAddressLinesWithoutFuzzyDeduplication() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}45\n13分鐘 (2.2公里) 總計\n配對",
                cardText = """
                    外送
                    ${'$'}45
                    13分鐘 (2.2公里) 總計
                    麥味登 龜山華亞店
                    333台灣桃園市龜山區文化里文化二路
                    333台灣桃園市龜區文化里文化二路
                    211號
                    配對
                """.trimIndent(),
                typeText = "外送",
                priceText = "${'$'}45",
                tripText = "13分鐘 (2.2公里) 總計",
                detailText = """
                    麥味登 龜山華亞店
                    333台灣桃園市龜山區文化里文化二路
                    333台灣桃園市龜區文化里文化二路
                    211號
                """.trimIndent(),
                merchantText = "麥味登 龜山華亞店",
                addressText = """
                    333台灣桃園市龜山區文化里文化二路
                    333台灣桃園市龜區文化里文化二路
                """.trimIndent(),
                addressLowerText = "211號"
            )
        )

        assertNotNull(order)
        assertEquals(
            "333台灣桃園市龜山區文化里文化二路\n333台灣桃園市龜區文化里文化二路\n211號",
            order!!.address
        )
    }

    @Test
    fun parseUsesMerchantWideOnlyWhenMerchantRegionIsInvalid() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}120\n29分鐘 (6.7公里) 總計\n接受",
                cardText = "外送\n${'$'}120\n29分鐘 (6.7公里) 總計\nPChome\n333台灣桃園市龜山區樂善里文禾路\n175號\n接受",
                typeText = "外送",
                priceText = "${'$'}120",
                tripText = "29分鐘 (6.7公里) 總計",
                detailText = "333台灣桃園市龜山區樂善里文禾路\n175號",
                merchantWideText = "PChome",
                merchantText = "Chome",
                addressWideText = "B333台灣桃園市龜山區樂善里文禾路",
                addressText = "333台灣桃園市龜山區樂善里文禾路",
                addressLowerText = "175號"
            )
        )

        assertNotNull(order)
        assertEquals("Chome", order!!.storeName)
        assertEquals(
            "333台灣桃園市龜山區樂善里文禾路\n175號",
            order.address
        )

        val fallbackOrder = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}120\n29分鐘 (6.7公里) 總計\n接受",
                cardText = "外送\n${'$'}120\n29分鐘 (6.7公里) 總計\nPChome\n333台灣桃園市龜山區樂善里文禾路\n175號\n接受",
                typeText = "外送",
                priceText = "${'$'}120",
                tripText = "29分鐘 (6.7公里) 總計",
                detailText = "333台灣桃園市龜山區樂善里文禾路\n175號",
                merchantWideText = "PChome",
                merchantText = "外送",
                addressWideText = "B333台灣桃園市龜山區樂善里文禾路",
                addressText = "333台灣桃園市龜山區樂善里文禾路",
                addressLowerText = "175號"
            )
        )

        assertNotNull(fallbackOrder)
        assertEquals("PChome", fallbackOrder!!.storeName)
    }

    @Test
    fun parseCleansAddressIconNoiseWithoutDroppingValidNumberStart() {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = "外送\n${'$'}45\n13分鐘 (2.2公里) 總計\n配對",
                cardText = "外送\n${'$'}45\n13分鐘 (2.2公里) 總計\n麥味登 龜山華亞店\n333台灣桃園市龜山區文化里文化二路\n211號\n配對",
                typeText = "外送",
                priceText = "${'$'}45",
                tripText = "13分鐘 (2.2公里) 總計",
                detailText = "麥味登 龜山華亞店",
                merchantWideText = "9麥味登 龜山華亞店",
                merchantText = "麥味登 龜山華亞店",
                addressWideText = "口333台灣桃園市龜山區文化里文化二路",
                addressText = "333台灣桃園市龜山區文化里文化二路",
                addressLowerText = "211號"
            )
        )

        assertNotNull(order)
        assertEquals("麥味登 龜山華亞店", order!!.storeName)
        assertEquals(
            "333台灣桃園市龜山區文化里文化二路\n211號",
            order.address
        )
    }

    @Test
    fun parseRemovesPickupIconNoiseBeforeMerchantName() {
        val order = OrderParser.parse(
            """
            外送
            獨享
            ${'$'}45
            13分鐘 (2.2公里) 總計
            9麥味登 龜山華亞店
            333台灣桃園市龜山區文化里文化二路
            211號
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals("麥味登 龜山華亞店", order!!.storeName)
        assertEquals(false, order.isStackOrder)
    }

    @Test
    fun parseDoesNotUseExclusiveBadgeAsMerchantName() {
        val order = OrderParser.parse(
            """
            外送
            ${'$'}45
            13分鐘 (2.2公里) 總計
            獨享
            333台灣桃園市龜山區文化里文化二路
            211號
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals("", order!!.storeName)
        assertEquals(false, order.isStackOrder)
    }

    @Test
    fun parseExclusiveOrderDoesNotBecomeStackFromNoisyBadgeText() {
        val order = OrderParser.parse(
            """
            外送
            獨享
            ${'$'}60
            18分鐘 (4.0公里) 總計
            回憶港式茶餐廳
            333台灣桃園市龜山區文青里文青路
            268號
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(1, order!!.deliveryCount)
        assertEquals(false, order.isStackOrder)
    }

    @Test
    fun parseKeepsMerchantNameWithoutDictionaryCorrection() {
        val order = OrderParser.parse(
            """
            外送
            獨享
            ${'$'}45
            13分鐘 (2.2公里) 總計
            9Pizza Hut必勝客(株口文貴店)
            333台灣桃園市龜山區樂善里文化一路
            83號
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals("Pizza Hut必勝客(株口文貴店)", order!!.storeName)
        assertEquals(false, order.isStackOrder)
    }

    @Test
    fun rawMerchantNameDoesNotTriggerDifferentManualListKeyword() {
        val order = OrderParser.parse(
            """
            外送
            獨享
            ${'$'}45
            13分鐘 (2.2公里) 總計
            9Pizza Hut必勝客(株口文貴店)
            333台灣桃園市龜山區樂善里文化一路
            83號
            接受
            """.trimIndent()
        )

        assertNotNull(order)
        assertEquals(
            false,
            OrderAnalyzer.matchesManualListKeyword(
                text = order!!.storeName,
                keyword = "Pizza Hut必勝客(林口文青店)"
            )
        )
    }
}
