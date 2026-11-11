package app.pocketfoundry.core.drivers

import android.content.Context
import android.util.Log
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.TAG
import org.json.JSONArray
import org.json.JSONObject

/**
 * An ending holds for the runtime it happened on, a check for the version it was made for (DriverCheck): a new
 * runtime brings new drivers.
 */
internal object DriverRecords {
    private const val CHOICE = "driver"
    private const val OPTIONS = "driverOptionsById"
    private const val GAMES = "driverGames"
    private const val CHECKS = "driverChecks"
    /** The ids of the catalog's checkboxes ticked for each driver. */
    private const val SETTINGS = "driverSettings"
    /** The Options field before each driver had its own: the driver in use gets it. */
    private const val OLD_OPTIONS = "driverOptions"

    /** Null means following Drivers.recommended. */
    fun choice(context: Context): String? = Settings.prefs(context).getString(CHOICE, null)

    fun setChoice(context: Context, id: String?) = Settings.prefs(context).edit().putString(CHOICE, id).apply()

    fun options(context: Context, id: String): String = read(context, OPTIONS).optString(id)

    fun setOptions(context: Context, id: String, text: String) =
        update(context, OPTIONS) { if (text.isEmpty()) it.remove(id) else it.put(id, text) }

    fun moveOldOptions(context: Context, toId: String) {
        val prefs = Settings.prefs(context)
        val old = prefs.getString(OLD_OPTIONS, "").orEmpty()
        if (old.isEmpty()) return
        setOptions(context, toId, old)
        prefs.edit().remove(OLD_OPTIONS).apply()
    }

    fun outcome(context: Context, id: String): Drivers.Outcome? {
        val ending = read(context, GAMES).optJSONObject(id) ?: return null
        if (ending.optString("runtime") != Runtime.version(context)) return null
        return Drivers.Outcome.entries.find { it.name == ending.optString("outcome") }
    }

    fun setOutcome(context: Context, id: String, outcome: Drivers.Outcome) = update(context, GAMES) {
        it.put(id, JSONObject().put("outcome", outcome.name).put("runtime", Runtime.version(context)))
    }

    fun settings(context: Context, id: String): Set<String> =
        read(context, SETTINGS).optJSONArray(id)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty().toSet()

    fun setSetting(context: Context, id: String, setting: String, on: Boolean) = update(context, SETTINGS) {
        val ticked = settings(context, id).let { if (on) it + setting else it - setting }
        if (ticked.isEmpty()) it.remove(id) else it.put(id, JSONArray(ticked.sorted()))
    }

    fun check(context: Context, id: String): JSONObject? = read(context, CHECKS).optJSONObject(id)

    fun setCheck(context: Context, id: String, check: JSONObject) = update(context, CHECKS) { it.put(id, check) }

    /** A removed driver's id comes back if the same file is imported again: it starts with nothing known. */
    fun remove(context: Context, id: String) {
        for (key in listOf(OPTIONS, GAMES, CHECKS, SETTINGS)) update(context, key) { it.remove(id) }
    }

    /** A new build of [id]: how the old one played and what its check found don't hold for it. */
    fun forgetGames(context: Context, id: String) {
        for (key in listOf(GAMES, CHECKS)) update(context, key) { it.remove(id) }
    }

    /** What's known about [from], and the choice of it, now belongs to [to]. */
    fun rename(context: Context, from: String, to: String) {
        if (choice(context) == from) setChoice(context, to)
        for (key in listOf(OPTIONS, GAMES, CHECKS, SETTINGS)) update(context, key) {
            if (it.has(from) && !it.has(to)) it.put(to, it.get(from))
            it.remove(from)
        }
    }

    private fun update(context: Context, key: String, change: (JSONObject) -> Unit) =
        Settings.prefs(context).edit().putString(key, read(context, key).also(change).toString()).apply()

    private fun read(context: Context, key: String): JSONObject {
        val text = Settings.prefs(context).getString(key, "{}").orEmpty()
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "$key unreadable, starting over: $text", e)
            JSONObject()
        }
    }
}
