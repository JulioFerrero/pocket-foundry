package app.pocketfoundry.ui.controller

import org.json.JSONArray
import org.json.JSONObject

/**
 * A set of controls in the saved layout (PadLayout), only what the player changed: places and sizes, controls removed
 * or added, latches and long presses, keys moved into or out of the drawer, what a key sends, its width and the set it
 * brings; and the keys the player made, whole. The rest follows the defaults when those change.
 */
internal object LayoutJson {
    fun write(controls: List<Control>): JSONObject {
        val json = JSONObject()
        val own = JSONArray()
        for (c in controls.filter { it.own }) {
            own.put(JSONObject().put("id", c.id).put("codes", JSONArray(c.codes.toList())).put("units", c.units.toDouble())
                        .apply { c.shows?.let { put("shows", it) } })
        }
        if (own.length() > 0) json.put("own", own)
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
        changes(controls.filter { it.longPressMs > 0 }) { it.longPressMs }?.let { json.put("long", it) }
        // The drawer a key is in, by its id; out of the one it was in: an empty id.
        changes(controls.filter { it.drawer != it.defaultDrawer }) { it.drawer.orEmpty() }?.let { json.put("drawer", it) }
        val catalog = controls.filter { !it.own }
        changes(catalog.filter { !it.codes.contentEquals(it.defaultCodes) }) { JSONArray(it.codes.toList()) }
            ?.let { json.put("codes", it) }
        changes(catalog.filter { it.units != it.defaultUnits }) { it.units.toDouble() }?.let { json.put("units", it) }
        // A key that brings nothing where it brought a set: an empty name.
        changes(catalog.filter { it.shows != it.defaultShows }) { it.shows.orEmpty() }?.let { json.put("shows", it) }
        return json
    }

    /** Reads [json] whole, or throws; the returned step puts it on [controls], the keys the player made added to them. */
    fun read(json: JSONObject, controls: MutableList<Control>): () -> Unit {
        val ownJson = json.optJSONArray("own") ?: JSONArray()
        val own = (0 until ownJson.length()).map { i ->
            val o = ownJson.getJSONObject(i)
            Keys.own(o.getString("id"), o.getJSONArray("codes").ints(), o.optDouble("units", 1.0).toFloat(),
                     o.optString("shows").ifEmpty { null })
        }
        val all = controls + own
        val removed = json.optJSONArray("removed").strings()
        val added = json.optJSONArray("added").strings()
        val placed = json.optJSONObject("controls") ?: JSONObject()
        val placements = all.associateWith { c ->
            placed.optJSONArray(c.id)?.let { a ->
                require(a.length() == 3) { "${c.id} has ${a.length()} numbers" }
                FloatArray(3) { a.getDouble(it).toFloat() }
            }
        }
        val latch = json.optJSONObject("latch") ?: JSONObject()
        val long = json.optJSONObject("long") ?: JSONObject()
        val drawer = json.optJSONObject("drawer") ?: JSONObject()
        val codes = json.optJSONObject("codes") ?: JSONObject()
        val sent = all.associateWith { c -> codes.optJSONArray(c.id)?.ints() }
        val units = json.optJSONObject("units") ?: JSONObject()
        val shows = json.optJSONObject("shows") ?: JSONObject()
        return {
            controls += own
            for ((c, placement) in placements) {
                c.shown = when (c.id) {
                    in removed -> false
                    in added -> true
                    else -> c.defaultShown
                }
                c.latch = latch.optBoolean(c.id, c.defaultLatch)
                c.longPressMs = long.optInt(c.id, 0)
                c.drawer = if (drawer.has(c.id)) drawer.getString(c.id).ifEmpty { null } else c.defaultDrawer
                sent[c]?.let { c.codes = it }
                if (units.has(c.id)) c.units = units.getDouble(c.id).toFloat()
                if (shows.has(c.id)) c.shows = shows.getString(c.id).ifEmpty { null }
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

    private fun changes(controls: List<Control>, value: (Control) -> Any): JSONObject? {
        val json = JSONObject()
        for (c in controls) json.put(c.id, value(c))
        return json.takeIf { it.length() > 0 }
    }

    private fun JSONArray?.strings(): Set<String> = if (this == null) emptySet() else (0 until length()).map(::getString).toSet()
    private fun JSONArray.ints() = IntArray(length()) { getInt(it) }
}
