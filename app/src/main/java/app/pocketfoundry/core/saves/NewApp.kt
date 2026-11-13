package app.pocketfoundry.core.saves

import android.content.Context
import android.content.Intent
import app.pocketfoundry.core.system.Http
import app.pocketfoundry.core.system.REPOSITORY
import org.json.JSONObject

/** Pocket Foundry from 0.9 on: dev.pocketfoundry, another app to Android, which this one hands its saves to. */
object NewApp {
    const val ID = "dev.pocketfoundry"
    private const val LATEST = "https://api.github.com/repos/$REPOSITORY/releases/latest"

    /** When it's installed. */
    fun launch(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(ID)

    /** Off the main thread: whether GitHub's latest release is 0.9 or newer. Throws when GitHub can't be reached. */
    fun released(): Boolean {
        val latest = Http.get(LATEST, accept = "application/vnd.github+json", timeoutMs = 5_000).decodeToString()
        return isNewApp(JSONObject(latest).getString("tag_name"))
    }

    /** v0.9, v0.9.1 and v1.0 are the new app's; v0.8.18 and before, this one's. */
    internal fun isNewApp(tag: String): Boolean {
        val numbers = tag.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        return numbers.getOrElse(0) { 0 } > 0 || numbers.getOrElse(1) { 0 } >= 9
    }
}
