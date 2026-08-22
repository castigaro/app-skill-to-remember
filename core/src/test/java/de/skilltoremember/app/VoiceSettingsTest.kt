package de.skilltoremember.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.skilltoremember.app.api.ChatApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceSettingsTest {

    @Test
    fun `Sofort-Zuhoeren ist ab Werk aus und schaltbar`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(VoiceSettings.isAutoListenEnabled(context))
        VoiceSettings.setAutoListenEnabled(context, true)
        assertTrue(VoiceSettings.isAutoListenEnabled(context))
    }

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
