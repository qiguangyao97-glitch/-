package com.example.gongderefuser.analyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleSettingsTest {

    @Test
    fun serializeEntriesKeepsMultilineAddressAsOneEntry() {
        val entries = listOf(
            RuleSettings.ListEntry(
                keyword = "333台灣桃園市龜山區長庚里長庚十街\n47號",
                note = "兩行地址"
            )
        )

        val parsed = RuleSettings.parseEntries(RuleSettings.serializeEntries(entries))

        assertEquals(1, parsed.size)
        assertEquals(entries.first(), parsed.first())
    }

    @Test
    fun matchingEntryFindsExistingLineInsideMultilineKeyword() {
        val entries = listOf(
            RuleSettings.ListEntry(keyword = "333台灣桃園市龜山區長庚里長庚十街")
        )

        val exists = RuleSettings.containsMatchingEntry(
            entries = entries,
            keyword = "麥味登 龜山丘比特\n333台灣桃園市龜山區長庚里長庚十街"
        )

        assertEquals(true, exists)
    }
}
