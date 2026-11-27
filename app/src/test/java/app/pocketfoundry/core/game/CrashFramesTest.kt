package app.pocketfoundry.core.game

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashFramesTest {
    @Test fun `each bare address of the trace becomes a library and an offset, an unmapped one says so`() {
        // A Pixel 11's MobileGlues crash (#65), with made-up maps.
        val log = """
               0.132 Error CrashHandler.cpp:614: Received SIGSEGV
            Factorio crashed. Generating symbolized stacktrace, please wait ...
            0x76ada73c0f
            0x76af97ec53
            0x7693a762a7
            Stack trace logging done
               0.137 Error Util.cpp:95: Unexpected error occurred.
        """.trimIndent()
        val maps = """
            76ada40000-76ada80000 r-xp 00010000 fd:05 42   /system/lib64/libEGL.so
            76af000000-76b0000000 r-xp 00000000 fd:05 43   /system/lib64/libGLESv2_angle.so
            76b0000000-76b0001000 rw-p 00000000 00:00 0    [anon:libc_malloc]
        """.trimIndent()
        assertEquals(listOf("0x76ada73c0f libEGL.so+0x43c0f", "0x76af97ec53 libGLESv2_angle.so+0x97ec53",
                            "0x7693a762a7 nowhere mapped"), CrashFrames.frames(log, maps))
    }

    @Test fun `no trace, no frames`() {
        assertEquals(emptyList<String>(), CrashFrames.frames("   0.000 Factorio 2.1.24\n", "76ad-76ae r-xp 0 0 0 /a.so"))
    }
}
