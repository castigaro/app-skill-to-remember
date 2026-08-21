package de.skilltoremember.app

import de.skilltoremember.app.api.ChatApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceSettingsTest {

    @Test
    fun `Vorlesen ohne Markdown-Reste`() {
        assertEquals("Hallo Welt", VoiceSettings.textForSpeech("**Hallo** `Welt`"))
    }

    @Test
    fun `Quellen-Block der Websuche wird nicht vorgelesen`() {
        val reply = "Es sind 12 Grad.\n\n${ChatApi.SOURCES_HEADING}\n• Wetterbericht — https://example.org"
        assertEquals("Es sind 12 Grad.", VoiceSettings.textForSpeech(reply))
    }
}
