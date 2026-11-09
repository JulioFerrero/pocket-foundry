package app.pocketfoundry.core.system

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * The runtime the app is built with (runtime/build.sh) has every file the app reads from it, where Paths says. Skipped
 * where the runtime isn't built.
 */
class RuntimeContractTest {
    private val paths = Paths(File("/files"), File("/lib"))

    @Test fun `every file the app reads is in the runtime`() {
        for (file in paths.shipped) {
            val path = file.relativeTo(paths.files).path
            assertTrue("$path isn't in the runtime", entries.any { it.removeSuffix("/") == path })
        }
    }

    @Test fun `Mesa's library names its version`() {
        assertTrue(entries.any { it.startsWith("usr/lib/libgallium-") && it.endsWith(".so") })
    }

    @Test fun `the runtime says where it was built to be unpacked`() {
        assertTrue(File(apk, "assets/runtime.prefix").readText().trim().endsWith("/files/usr"))
    }

    companion object {
        private val apk = File("../runtime/out/apk")
        private lateinit var entries: List<String>

        @BeforeClass @JvmStatic fun list() {
            val archive = File(apk, "assets/runtime.tar.xz")
            assumeTrue("the runtime isn't built", archive.exists())
            val tar = ProcessBuilder("sh", "-c", "xz -dc '${archive.path}' | tar -t").redirectErrorStream(true).start()
            entries = tar.inputStream.bufferedReader().readLines()
            assertTrue("xz or tar failed", tar.waitFor() == 0)
        }
    }
}
