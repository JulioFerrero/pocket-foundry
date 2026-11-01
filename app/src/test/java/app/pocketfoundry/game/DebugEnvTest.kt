package app.pocketfoundry.game

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugEnvTest {
    @Test fun `only the lines for the process count`() {
        val lines = listOf("game.MESA_DEBUG=1", "x11.FOO=2", "# game.OFF=1", "gamex.A=1", "game.TU_DEBUG=noconform")
        assertEquals(mapOf("MESA_DEBUG" to "1", "TU_DEBUG" to "noconform"), DebugEnv.parse(lines, "game"))
        assertEquals(mapOf("FOO" to "2"), DebugEnv.parse(lines, "x11"))
    }

    @Test fun `the test program line`() {
        assertEquals(mapOf("program" to "/bin/true"), DebugEnv.parse(listOf("test.program=/bin/true"), "test"))
    }

    @Test fun `the value keeps later equals signs`() {
        assertEquals(mapOf("A" to "b=c"), DebugEnv.parse(listOf("game.A=b=c"), "game"))
    }

    @Test fun `lines without a value are dropped`() {
        assertEquals(emptyMap<String, String>(), DebugEnv.parse(listOf("game.A", "game.", "game", ""), "game"))
    }

    @Test fun `an empty value is kept`() {
        assertEquals(mapOf("A" to ""), DebugEnv.parse(listOf("game.A="), "game"))
    }

    @Test fun `the line is trimmed but spaces around the equals sign stay`() {
        assertEquals(mapOf("A " to " b"), DebugEnv.parse(listOf("  game.A = b \t"), "game"))
    }

    @Test fun `the last of a repeated key wins`() {
        assertEquals(mapOf("A" to "2"), DebugEnv.parse(listOf("game.A=1", "game.A=2"), "game"))
    }
}
