package app.pocketfoundry.game

import app.pocketfoundry.core.game.Glibc
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class GlibcTest {
    @Test fun `east of UTC is a negative POSIX offset`() {
        assertEquals("UTC-02:00", Glibc.posixTimeZone(120))
    }

    @Test fun `west of UTC is a positive one`() {
        assertEquals("UTC+05:00", Glibc.posixTimeZone(-300))
    }

    @Test fun `UTC itself`() {
        assertEquals("UTC-00:00", Glibc.posixTimeZone(0))
    }

    @Test fun `half and quarter hours keep their minutes`() {
        assertEquals("UTC-05:30", Glibc.posixTimeZone(330))
        assertEquals("UTC-05:45", Glibc.posixTimeZone(345))
        assertEquals("UTC+03:30", Glibc.posixTimeZone(-210))
        assertEquals("UTC-14:00", Glibc.posixTimeZone(840))
    }

    @Test fun `the digits stay ASCII in any language`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("UTC-02:00", Glibc.posixTimeZone(120))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
