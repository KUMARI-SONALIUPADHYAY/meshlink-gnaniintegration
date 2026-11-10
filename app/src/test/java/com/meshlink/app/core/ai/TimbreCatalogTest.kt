package com.meshlink.app.core.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimbreCatalogTest {
    @Test fun hindiVoicesAreAcceptedOnlyForHindi() {
        assertTrue(TimbreCatalog.isSupported("hi-IN", "Nalini"))
        assertFalse(TimbreCatalog.isSupported("en-IN", "Nalini"))
    }

    @Test fun allPublishedLanguageEntriesHaveVoices() {
        assertTrue(TimbreCatalog.languages.all { (code, _) -> TimbreCatalog.voicesFor(code).isNotEmpty() })
    }

    @Test fun catalogContainsFortyTwoDistinctV25Voices() {
        assertTrue(TimbreCatalog.voicesByLanguage.values.flatten().distinct().size == 42)
    }

    @Test fun hinglishUsesItsPublishedVoice() {
        assertTrue(TimbreCatalog.isSupported("hi-en", "Poorvi"))
        assertFalse(TimbreCatalog.isSupported("hi-en", "Nalini"))
    }
}
