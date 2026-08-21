package de.skilltoremember.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {

    @Test
    fun `parse liest das Versions-Manifest`() {
        val info = UpdateChecker.parse(
            """{"versionCode": 42, "versionName": "0.2.0", "apkUrl": "https://example.org/app.apk"}""",
        )!!
        assertEquals(42L, info.versionCode)
        assertEquals("0.2.0", info.versionName)
        assertEquals("https://example.org/app.apk", info.apkUrl)
    }

    @Test
    fun `parse liefert null bei kaputtem oder unvollstaendigem JSON`() {
        assertNull(UpdateChecker.parse("kein json"))
        assertNull(UpdateChecker.parse("""{"versionName": "0.2.0"}"""))
        assertNull(UpdateChecker.parse(""))
    }
}
