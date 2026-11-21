package app.pocketfoundry.core.system

import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CpuClocksTest {
    @get:Rule val folder = TemporaryFolder()

    private fun policy(name: String, cores: String, topKhz: Long?) = File(folder.root, name).apply {
        mkdirs()
        File(this, "related_cpus").writeText("$cores\n")
        if (topKhz != null) File(this, "cpuinfo_max_freq").writeText("$topKhz\n")
    }

    @Test fun `the fast cores are every group's but the slowest, as on a Xiaomi 12T`() {
        policy("policy0", "0 1 2 3", 2_000_000)
        policy("policy4", "4 5 6", 2_850_000)
        policy("policy7", "7", 2_850_000)
        assertArrayEquals(intArrayOf(4, 5, 6, 7), CpuClocks.fastCores(folder.root))
    }

    @Test fun `one group, or none readable, has no fast cores`() {
        assertArrayEquals(intArrayOf(), CpuClocks.fastCores(folder.root))
        policy("policy0", "0 1 2 3 4 5 6 7", 2_000_000)
        assertArrayEquals(intArrayOf(), CpuClocks.fastCores(folder.root))
        policy("policy4", "4 5", null)
        assertArrayEquals(intArrayOf(), CpuClocks.fastCores(folder.root))
    }
}
