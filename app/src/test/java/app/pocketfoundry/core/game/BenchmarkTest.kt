package app.pocketfoundry.core.game

import app.pocketfoundry.core.system.CpuClocks
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class BenchmarkTest {
    @Test fun `each run's time from Factorio's output, the best as updates a second`() {
        val log = File.createTempFile("benchmark", ".log").apply {
            // A vivo X200 Ultra on flame_Sla 10k, as Factorio wrote it.
            writeText("""
                  Performed 1000 updates in 14992.909 ms
                  avg: 14.993 ms, min: 9.435 ms, max: 45.992 ms
                  Performed 1000 updates in 14188.013 ms
                  avg: 14.188 ms, min: 8.753 ms, max: 40.041 ms
            """.trimIndent())
        }
        val runs = Benchmark.runs(log)
        assertEquals(listOf(14992.909, 14188.013), runs)
        assertEquals(70.48, Benchmark.Result("flame", runs, emptyList()).ups, 0.01)
    }

    @Test fun `a phone holding its cores below their top shows as capped`() {
        val root = createTempDirectory("cpufreq").toFile()
        fun policy(name: String, cpus: String, top: Long, allowed: Long, now: Long) = File(root, name).apply {
            mkdirs()
            File(this, "related_cpus").writeText("$cpus\n")
            File(this, "cpuinfo_max_freq").writeText("$top\n")
            File(this, "scaling_max_freq").writeText("$allowed\n")
            File(this, "scaling_cur_freq").writeText("$now\n")
        }
        policy("policy0", "0 1 2 3 4 5", 3532800, 3532800, 2000000)
        policy("policy6", "6 7", 4320000, 1401600, 1401600)
        val clocks = CpuClocks(root).apply { sample() }.groups()
        assertEquals(listOf("0-5", "6-7"), clocks.map { it.cores })
        assertEquals(listOf(false, true), clocks.map { it.capped })
        assertEquals(1401600L, clocks[1].peakKhz)
    }
}
