package dev.mikoto2000.oasizjapanesekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RomajiConverterSuggestionTest {
    @Test
    fun suggestsKanaBeforeVowelIsEntered() {
        val converter = RomajiConverter()
        converter.pushChar('k')

        val readings = converter.getSuggestionReadings()

        assertTrue("か" in readings)
        assertTrue("き" in readings)
        assertTrue("く" in readings)
    }

    @Test
    fun appendsUnfinishedRomajiSuggestionToConvertedKana() {
        val converter = RomajiConverter()
        "toky".forEach(converter::pushChar)

        assertEquals(listOf("ときゃ", "ときゅ", "ときょ"), converter.getSuggestionReadings())
    }

    @Test
    fun singleNIncludesFinalNReading() {
        val converter = RomajiConverter()
        "hon".forEach(converter::pushChar)

        assertTrue("ほん" in converter.getSuggestionReadings())
    }
}
