package app.pocketfoundry.core.game

/**
 * Where a Factorio crash happened, in libraries. Its crash handler can name only its own functions: frames in a driver,
 * libhybris or MobileGlues come out as bare addresses ("0x76ada73c0f"), which mean nothing once the process is gone.
 * While it writes its stack trace it's still alive, so the app reads its /proc/<pid>/maps then, and each address
 * becomes "libGLESv2_angle.so+0x3c0f": a file and an offset to look up (a Pixel 11's MobileGlues crash, #65).
 */
object CrashFrames {
    /** "libhybris-common.so+0x1fd14" for each of the trace's bare addresses in [log], by the process's [maps]. */
    fun frames(log: String, maps: String): List<String> {
        val start = log.lastIndexOf(TRACE_START).takeIf { it >= 0 } ?: return emptyList()
        val end = log.indexOf(TRACE_DONE, start).takeIf { it >= 0 } ?: log.length
        val regions = maps.lineSequence().mapNotNull(::region).toList()
        return log.substring(start, end).lineSequence().mapNotNull { ADDRESS.matchEntire(it.trim()) }.map { match ->
            val address = match.groupValues[1].toULong(16)
            val region = regions.firstOrNull { address >= it.start && address < it.end }
            val where = region?.let { "${it.file}+0x${(address - it.start + it.offset).toString(16)}" } ?: "nowhere mapped"
            "0x${address.toString(16)} $where"
        }.toList()
    }

    private class Region(val start: ULong, val end: ULong, val offset: ULong, val file: String)

    /** "76ada00000-76adb00000 r-xp 00040000 fd:05 1234  /system/lib64/libEGL.so": only regions from a file. */
    private fun region(line: String): Region? {
        val parts = line.trim().split(Regex("\\s+"), limit = 6)
        if (parts.size < 6 || !parts[5].startsWith("/")) return null
        val (start, end) = parts[0].split('-').takeIf { it.size == 2 } ?: return null
        return runCatching {
            Region(start.toULong(16), end.toULong(16), parts[2].toULong(16), parts[5].substringAfterLast('/'))
        }.getOrNull()
    }

    private const val TRACE_START = "Generating symbolized stacktrace"
    /** Factorio's line once its crash trace is out. */
    const val TRACE_DONE = "Stack trace logging done"
    private val ADDRESS = Regex("0x([0-9a-fA-F]+)")
}
