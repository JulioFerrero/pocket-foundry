package app.pocketfoundry.core.runtime

import app.pocketfoundry.core.controller.InputBridge
import app.pocketfoundry.core.drivers.DriverCheck
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.controller.PadButton
import app.pocketfoundry.core.game.AudioBridge
import app.pocketfoundry.core.logs.DetailedLogs
import app.pocketfoundry.core.logs.ScreenLog
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.ResolutionScale
import app.pocketfoundry.core.system.Settings
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The values the app shares with our C code and patches, which no compiler ties together: each side read from the
 * repository, so a rename on one side fails here instead of leaving a game without sound, pad or frame cap.
 */
class SourceContractTest {
    private val sdl = File("../runtime/sdl/pocketfoundry/SDL_pocketfoundry.c").readText()
    private val environment = Sdl.environment(Paths(File("/files"), File("/lib")))

    private fun assertIn(source: String, text: String, where: String) =
        assertTrue("$text isn't in $where", text in source)

    @Test fun `our SDL reads the sockets and the frame cap the app names`() {
        for (name in environment.keys.filter { it.startsWith("POCKETFOUNDRY_") }) {
            assertIn(sdl, "SDL_getenv(\"$name\")", "SDL_pocketfoundry.c")
        }
    }

    @Test fun `our SDL's audio driver is the one the app picks`() {
        assertIn(sdl, ".name = \"${environment.getValue("SDL_AUDIO_DRIVER")}\"", "SDL_pocketfoundry.c")
    }

    @Test fun `the sound and pad messages are spelled the same`() {
        assertIn(sdl, "#define AUDIO_MAGIC \"${AudioBridge.MAGIC}\"", "SDL_pocketfoundry.c")
        assertIn(sdl, "message[0] == '${InputBridge.BUTTON}'", "SDL_pocketfoundry.c")
        assertIn(sdl, "message[0] == '${InputBridge.AXIS}'", "SDL_pocketfoundry.c")
        assertIn(sdl, "message[4] = { '${InputBridge.RUMBLE}',", "SDL_pocketfoundry.c")
    }

    @Test fun `the pad SDL makes has the app's buttons`() {
        assertIn(sdl, "SDL_GAMEPAD_BUTTON_${PadButton.entries.last().name} + 1", "SDL_pocketfoundry.c")
    }

    @Test fun `the highest frame rate is the game's own, which SDL leaves uncapped`() {
        assertIn(sdl, "#define GAME_UPS ${Settings.FRAME_RATES.max()}", "SDL_pocketfoundry.c")
    }

    @Test fun `our glibc's loader restarts a program by the names the app sets`() {
        val restart = File("../runtime/glibc/patches/rtld-program-restart.patch").readText()
        assertIn(restart, "\"${Program.RESTART_PROGRAM}=\"", "rtld-program-restart.patch")
        val paths = File("../runtime/glibc/termux-patches/set-ld-variables.patch").readText()
        assertIn(paths, "getenv (\"${Program.RESTART_LIBRARIES}\")", "set-ld-variables.patch")
    }

    @Test fun `Detailed logs reach the Mesa and Termux X11 patches by one name`() {
        for (patch in listOf("../runtime/mesa/patches/0004-zink-log-presents.patch",
                             "../runtime/mesa/patches/0005-zink-hide-extensions.patch",
                             "../third_party/patches/termux-x11/0011-detailed-logs.patch")) {
            assertIn(File(patch).readText(), "\"${DetailedLogs.SWITCH}\"", File(patch).name)
        }
    }

    @Test fun `the driver check hides extensions and adds the layer by the names Mesa and our build use`() {
        assertIn(File("../runtime/mesa/patches/0005-zink-hide-extensions.patch").readText(),
                 "getenv(\"${DriverCheck.HIDE}\")", "mesa 0005")
        assertIn(File("../runtime/hybris/build-hybris.sh").readText(), "\"name\": \"${DriverCheck.TIMELINE_LAYER}\"",
                 "build-hybris.sh")
    }

    @Test fun `the driver check's program answers right or wrong as the app reads it`() {
        val program = File("../runtime/programs/pocketfoundry-drivercheck.c").readText()
        assertIn(program, "return right ? ${DriverCheck.RIGHT} : ${DriverCheck.WRONG};", "pocketfoundry-drivercheck.c")
    }

    @Test fun `libhybris reads Android's properties by the names the app sets`() {
        assertIn(File("../runtime/hybris/patches/0003-properties-android-values-from-the-app.patch").readText(),
                 "getenv(\"${Drivers.HYBRIS_PROPERTIES}\")", "hybris 0003")
        assertIn(File("../runtime/hybris/patches/0004-linker-android-10-config.patch").readText(),
                 "getenv(\"${Drivers.HYBRIS_VNDK}\")", "hybris 0004")
    }

    @Test fun `the screen's lines in screen log start as our Termux X11 patches write them`() {
        val patches = listOf("0010-screen-buffers-to-stderr.patch", "0011-detailed-logs.patch")
            .joinToString("") { File("../third_party/patches/termux-x11/$it").readText() }
        for (start in ScreenLog.SCREEN_LINES) assertIn(patches, "fprintf(stderr, \"$start", "termux-x11 0010/0011")
    }

    @Test fun `the smallest UI scale the app asks for is one our SDL takes`() {
        assertIn(File("../runtime/sdl/patches/0003-pocketfoundry-scale-below-one.patch").readText(),
                 "value >= ${ResolutionScale.MIN_UI_SCALE}f", "sdl 0003")
    }
}
