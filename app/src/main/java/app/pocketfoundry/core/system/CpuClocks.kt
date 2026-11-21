package app.pocketfoundry.core.system

import java.io.File

/**
 * What the cores ran at while something ran, group by group (each of Linux's cpufreq policies): the highest clock seen,
 * the ceilings the phone set meanwhile, lowest and highest, and the cores' own top. A phone that keeps a game's cores
 * below their top shows it here: a vivo X200 Ultra on battery let its fastest two run at 1.4 to 2.44 of 4.32 GHz.
 */
class CpuClocks(private val root: File = File(ROOT)) {
    /** [cores] as Linux lists them ("6-7"); kHz. [allowedKhz]: the lowest ceiling, [mostAllowedKhz] the highest. */
    class Group(val cores: String, val peakKhz: Long, val allowedKhz: Long, val mostAllowedKhz: Long,
                val topKhz: Long) {
        val capped get() = allowedKhz in 1 until topKhz
    }

    private class Seen(val cores: String, val topKhz: Long) {
        var peakKhz = 0L
        var allowedKhz = Long.MAX_VALUE
        var mostAllowedKhz = 0L
    }

    private val seen = LinkedHashMap<String, Seen>()

    /** Reads every group's clock and ceiling now. */
    fun sample() {
        val policies = root.listFiles { f -> f.name.startsWith("policy") }?.sortedBy { it.name } ?: return
        for (policy in policies) {
            val top = read(policy, "cpuinfo_max_freq") ?: continue
            val group = seen.getOrPut(policy.name) {
                Seen(runCatching { File(policy, "related_cpus").readText().trim() }.getOrDefault(policy.name)
                         .let(::range), top)
            }
            read(policy, "scaling_cur_freq")?.let { group.peakKhz = maxOf(group.peakKhz, it) }
            read(policy, "scaling_max_freq")?.let {
                group.allowedKhz = minOf(group.allowedKhz, it)
                group.mostAllowedKhz = maxOf(group.mostAllowedKhz, it)
            }
        }
    }

    fun groups(): List<Group> = seen.values.map {
        Group(it.cores, it.peakKhz, it.allowedKhz.takeIf { a -> a != Long.MAX_VALUE } ?: 0, it.mostAllowedKhz,
              it.topKhz)
    }

    companion object {
        private const val ROOT = "/sys/devices/system/cpu/cpufreq"

        /**
         * The phone's fast cores: those of every group but the slowest. None where all share one top clock, or it
         * can't be read.
         */
        fun fastCores(root: File = File(ROOT)): IntArray {
            val groups = root.listFiles { f -> f.name.startsWith("policy") }.orEmpty().mapNotNull { policy ->
                val top = read(policy, "cpuinfo_max_freq")
                val cores = runCatching { File(policy, "related_cpus").readText().trim() }.getOrNull()
                    ?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() }
                if (top != null && !cores.isNullOrEmpty()) top to cores else null
            }
            val slowest = groups.minOfOrNull { it.first } ?: return IntArray(0)
            return groups.filter { it.first > slowest }.flatMap { it.second }.sorted().toIntArray()
        }

        private fun read(policy: File, name: String) =
            runCatching { File(policy, name).readText().trim().toLong() }.getOrNull()

        /** "6 7" -> "6-7", "0 1 2 3 4 5" -> "0-5": Linux's list of a policy's cores, as a range when it is one. */
        internal fun range(cpus: String): String {
            val numbers = cpus.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            if (numbers.isEmpty()) return cpus
            val contiguous = numbers.zipWithNext().all { (a, b) -> b == a + 1 }
            return if (contiguous && numbers.size > 1) "${numbers.first()}-${numbers.last()}"
                   else numbers.joinToString(",")
        }
    }
}
