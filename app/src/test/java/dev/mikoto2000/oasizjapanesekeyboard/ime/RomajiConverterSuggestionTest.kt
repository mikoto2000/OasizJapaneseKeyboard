package dev.mikoto2000.oasizjapanesekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class RomajiConverterSuggestionTest {
    @Test
    fun convertsExtendedRomajiSequences() {
        val cases = mapOf(
            "tyu" to "ちゅ",
            "thu" to "てゅ",
            "thi" to "てぃ",
            "dhu" to "でゅ",
            "she" to "しぇ",
            "che" to "ちぇ",
            "je" to "じぇ",
            "ltsu" to "っ",
        )

        for ((romaji, expected) in cases) {
            val converter = RomajiConverter()
            romaji.forEach(converter::pushChar)
            assertEquals(romaji, expected, converter.getComposing())
        }
    }

    @Test
    fun keepsSecondNForFollowingSyllable() {
        val converter = RomajiConverter()
        "konnichiha".forEach(converter::pushChar)

        assertEquals("こんにちは", converter.getComposing())
    }

    @Test
    fun flushesDoubleNAsSingleKanaN() {
        val converter = RomajiConverter()
        "nn".forEach(converter::pushChar)

        assertEquals("ん", converter.flush())
    }

    @Test
    fun appendsProlongedSoundMarkWithoutFinishingComposition() {
        val converter = RomajiConverter()
        "ko".forEach(converter::pushChar)

        converter.appendKana("ー")

        assertEquals("こー", converter.getComposing())
    }

    @Test
    fun finalizesPendingNBeforeProlongedSoundMark() {
        val converter = RomajiConverter()
        "kon".forEach(converter::pushChar)

        converter.appendKana("ー")

        assertEquals("こんー", converter.getComposing())
    }
}
