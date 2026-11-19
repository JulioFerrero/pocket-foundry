package app.pocketfoundry.ui.controller

import org.json.JSONArray
import org.json.JSONObject

/**
 * A set of controls in the saved layout (PadLayout), only what the player changed: places and sizes, controls removed
 * or added, latches, keys moved into or out of the drawer. The rest follows the defaults when those change.
 */
internal object LayoutJson {
    fun write(controls: List<Control>): JSONObject {
        val json = JSONObject()
        val placed = JSONObject()
        for (c in controls) {
            if (c.x != c.defaultX || c.y != c.defaultY || c.scale != 1f) {
                placed.put(c.id, JSONArray(listOf(c.x.toDouble(), c.y.toDouble(), c.scale.toDouble())))
            }
        }
        if (placed.length() > 0) json.put("controls", placed)
        val removed = controls.filter { it.defaultShown && !it.shown }.map { it.id }
        if (removed.isNotEmpty()) json.put("removed", JSONArray(removed))
        val added = controls.filter { !it.defaultShown && it.shown }.map { it.id }
        if (added.isNotEmpty()) json.put("added", JSONArray(added))
        changes(controls, { it.latch }, { it.defaultLatch })?.let { json.put("latch", it) }
        changes(controls, { it.inDrawer }, { it.defaultInDrawer })?.let { json.put("drawer", it) }
        return json
    }

    /** Reads [json] whole, or throws; the returned step puts it on [controls]. */
    fun read(json: JSONObject, controls: List<Control>): () -> Unit {
        val removed = json.optJSONArray("removed").strings()
        val added = json.optJSONArray("added").strings()
        val placed = json.optJSONObject("controls") ?: JSONObject()
        val placements = controls.associateWith { c ->
            placed.optJSONArray(c.id)?.let { a ->
                require(a.length() == 3) { "${c.id} has ${a.length()} numbers" }
                FloatArray(3) { a.getDouble(it).toFloat() }
            }
        }
        val latch = json.optJSONObject("latch") ?: JSONObject()
        val drawer = json.optJSONObject("drawer") ?: JSONObject()
        return {
            for ((c, placement) in placements) {
                c.shown = when (c.id) {
                    in removed -> false
                    in added -> true
                    else -> c.defaultShown
                }
                c.latch = latch.optBoolean(c.id, c.defaultLatch)
                c.inDrawer = drawer.optBoolean(c.id, c.defaultInDrawer)
                placement?.let { (x, y, scale) ->
                    c.x = x
                    c.y = y
                    c.scale = scale
                }
            }
        }
    }

    private fun changes(controls: List<Control>, now: (Control) -> Boolean, default: (Control) -> Boolean): JSONObject? {
        val json = JSONObject()
        for (c in controls) if (now(c) != default(c)) json.put(c.id, now(c))
        return json.takeIf { it.length() > 0 }
    }

    private fun JSONArray?.strings(): Set<String> = if (this == null) emptySet() else (0 until length()).map(::getString).toSet()
}
