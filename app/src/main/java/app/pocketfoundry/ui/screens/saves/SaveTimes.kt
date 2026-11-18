package app.pocketfoundry.ui.screens.saves

import android.content.Context
import android.text.format.DateFormat
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Today 15:14", "Yesterday 15:14", or the day. */
fun whenSaved(context: Context, save: File): String {
    val day = day(save)
    val clock = DateFormat.getTimeFormat(context).format(save.lastModified())
    return when (day) {
        LocalDate.now() -> "Today $clock"
        LocalDate.now().minusDays(1) -> "Yesterday $clock"
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == LocalDate.now().year) "d MMM" else "d MMM yyyy",
                                                       Locale.getDefault()))
    }
}

/** For a list: "15:14" today, "Yesterday", "12 Nov", "Nov 2025". */
fun whenSavedShort(context: Context, save: File): String {
    val day = day(save)
    return when {
        day == LocalDate.now() -> DateFormat.getTimeFormat(context).format(save.lastModified())
        day == LocalDate.now().minusDays(1) -> "Yesterday"
        day.year == LocalDate.now().year -> day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        else -> day.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))
    }
}

private fun day(save: File) = Instant.ofEpochMilli(save.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()
