package com.alicontrol.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonFieldsTest {

    // -- stringArray --

    @Test
    fun readsAnArrayOfStrings() {
        assertEquals(listOf("a", "b"), JsonFields.stringArray("{\"ids\":[\"a\",\"b\"]}", "ids"))
    }

    @Test
    fun readsAnEmptyArray() {
        assertEquals(emptyList<String>(), JsonFields.stringArray("{\"ids\":[]}", "ids"))
        assertEquals(emptyList<String>(), JsonFields.stringArray("{\"ids\": [ ]}", "ids"))
    }

    @Test
    fun toleratesWhitespaceAndKeyOrder() {
        assertEquals(
            listOf("telemetry.cpu", "media"),
            JsonFields.stringArray("{ \"type\":\"capabilities\" , \"ids\" : [ \"telemetry.cpu\" , \"media\" ] }", "ids")
        )
        assertEquals(listOf("x"), JsonFields.stringArray("{\"ids\":[\"x\"],\"type\":\"capabilities\"}", "ids"))
    }

    @Test
    fun anAbsentOrMalformedArrayIsNullNotEmpty() {
        assertNull(JsonFields.stringArray("{\"other\":[\"a\"]}", "ids"))
        assertNull(JsonFields.stringArray("{\"ids\":\"a\"}", "ids"))
        assertNull(JsonFields.stringArray("{\"ids\":[1,2]}", "ids"))
        assertNull(JsonFields.stringArray("{\"ids\":[\"a\",2]}", "ids"))
        assertNull(JsonFields.stringArray("{\"ids\":[\"a\",]}", "ids"))
        assertNull(JsonFields.stringArray("{\"ids\":[\"a\" \"b\"]}", "ids"))
        assertNull(JsonFields.stringArray("{\"ids\":[\"a\"", "ids"))
    }

    @Test
    fun anArrayWhoseNameAppearsInsideAStringValueIsNotMistakenForTheRealField() {
        val json = "{\"name\":\"x \\\"ids\\\": [\\\"evil\\\"] y\",\"ids\":[\"real\"]}"
        assertEquals(listOf("real"), JsonFields.stringArray(json, "ids"))
    }

    @Test
    fun arrayElementsMayContainEscapedCharacters() {
        assertEquals(listOf("a\"b", "c\\d"), JsonFields.stringArray("{\"ids\":[\"a\\\"b\",\"c\\\\d\"]}", "ids"))
    }

    // -- unescape --

    @Test
    fun quotesAndBackslashesRoundTrip() {
        val original = "say \"hi\" \\ bye"
        assertEquals(original, JsonFields.unescape(JsonFields.escape(original)))
    }

    @Test
    fun escapedBackslashFollowedByAQuoteIsNotMisread() {
        // \\" is "backslash, end of string" in JSON, never "backslash-quote".
        assertEquals("\\", JsonFields.unescape("\\\\"))
        assertEquals("\\\"", JsonFields.unescape("\\\\\\\""))
    }

    @Test
    fun anEscapedBackslashFollowedByNIsNotANewline() {
        assertEquals("\\n", JsonFields.unescape("\\\\n"))
    }

    @Test
    fun decodesTheStandardJsonEscapes() {
        assertEquals("AC/DC", JsonFields.unescape("AC\\/DC"))
        assertEquals("a\nb\tc\rd", JsonFields.unescape("a\\nb\\tc\\rd"))
        assertEquals("\b\u000C", JsonFields.unescape("\\b\\f"))
    }

    @Test
    fun decodesUnicodeEscapes() {
        assertEquals("é", JsonFields.unescape("\\u00e9"))
        assertEquals("東", JsonFields.unescape("\\u6771"))
        assertEquals("🎧", JsonFields.unescape("\\ud83c\\udfa7")) // a surrogate pair stays a pair
    }

    @Test
    fun malformedOrTruncatedEscapesAreKeptVerbatimNotGuessedAt() {
        assertEquals("\\u12", JsonFields.unescape("\\u12"))
        assertEquals("\\uZZZZ", JsonFields.unescape("\\uZZZZ"))
        assertEquals("\\q", JsonFields.unescape("\\q"))
        assertEquals("trailing\\", JsonFields.unescape("trailing\\"))
    }

    @Test
    fun plainTextIsReturnedUntouched() {
        assertEquals("Bohemian Rhapsody — 東京", JsonFields.unescape("Bohemian Rhapsody — 東京"))
    }

    @Test
    fun stringFieldsDecodeEscapesTheAgentMightEmit() {
        // The Windows agent only escapes " and \, but a spec-compliant producer may do more.
        assertEquals("AC/DC", JsonFields.string("{\"title\":\"AC\\/DC\"}", "title"))
        assertEquals("a\"b", JsonFields.string("{\"title\":\"a\\\"b\"}", "title"))
    }
}
