package com.example.gongderefuser.parser

import com.example.gongderefuser.analyzer.OrderAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OrderParserTest {

    @Test
    fun parseCorrectsMergedMinutePrefix() {
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
        assertEquals(33, order.minutes)
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
        assertEquals("叠单（2单）", OrderAnalyzer.analyzeResult(order).orderType)
    }

    @Test
    fun parseCorrectsDistanceWhenDecimalPointIsMissing() {
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
        assertEquals("夹单（爽单）", OrderAnalyzer.analyzeResult(order).orderType)
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
    fun parseCorrectsTraditionalSecondAddressLineWithFloorSuffix() {
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
            "333台灣桃園市龜山區文青里文青路\n353號21樓之1",
            order!!.address
        )
    }

    @Test
    fun parseCorrectsGuishanInMerchantName() {
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
        assertEquals("麥味登 龜山丘比特", order!!.storeName)
    }

    @Test
    fun parseTrimsNoisyAddressTailAfterStreetWithoutHouseNumber() {
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
            "333台灣桃園市龜山區長庚里長庚十街",
            order!!.address
        )
    }

    @Test
    fun parseTrimsGenericNoisyTailAfterRoadName() {
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
            "333台灣桃園市龜山區文化里文化二路",
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
    fun parseScreenshotAddressSamplesFromLinkouGuishan() {
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
            "台灣桃園市龜山區金湖街47\n巷15弄7號",
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
    fun parseUsesCardDetailWhenSmallMerchantAndAddressRegionsAreNoisy() {
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

        assertNotNull(order)
        assertEquals("Pizza Hut必勝客(林口文青店)", order!!.storeName)
        assertEquals(
            "台灣桃園市龜山區長庚醫護\n新村170號",
            order.address
        )
    }
}
