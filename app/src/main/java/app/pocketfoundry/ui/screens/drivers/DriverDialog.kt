package app.pocketfoundry.ui.screens.drivers

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.core.drivers.Catalog
import app.pocketfoundry.core.drivers.DriverCheck
import app.pocketfoundry.core.drivers.DriverImport
import app.pocketfoundry.core.drivers.DriverRecords
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.drivers.Drivers.Driver
import app.pocketfoundry.core.drivers.Gpu
import app.pocketfoundry.core.game.Session
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.X11Prefs
import app.pocketfoundry.core.system.background
import app.pocketfoundry.ui.kit.ButtonDrawable
import app.pocketfoundry.ui.kit.Choice
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.MATCH
import app.pocketfoundry.ui.kit.Progress
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.WRAP
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.capped
import app.pocketfoundry.ui.kit.checkBox
import app.pocketfoundry.ui.kit.column
import app.pocketfoundry.ui.kit.groove
import app.pocketfoundry.ui.kit.heading
import app.pocketfoundry.ui.kit.immersive
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.link
import app.pocketfoundry.ui.kit.pad
import app.pocketfoundry.ui.kit.row
import app.pocketfoundry.ui.kit.screenWidthDp
import app.pocketfoundry.ui.kit.shown
import app.pocketfoundry.ui.kit.spacer
import app.pocketfoundry.ui.kit.stack
import app.pocketfoundry.ui.kit.withNote
import java.util.Locale

/**
 * Every driver for this phone, each with its version and state, and the one looked at: Running (what runs with it: the
 * driver list's fixes for this GPU, the driver check, the resolution, how the textures are compressed, its settings),
 * About (who made it, where it was tested) and Settings (the catalog's checkboxes for it, Options). The bar below acts
 * on that one. A short screen scrolls the panes.
 */
class DriverDialog(private val launcher: LauncherActivity) {
    private val host = launcher.host
    private val paths = Paths(launcher)
    private val list = stack(launcher, Frame.DEEP, padding = LIST_PADDING, gap = ROW_GAP)
    private var rows = emptyMap<String, View>()
    private var viewing = ""

    private val tabs = Choice(launcher, listOf("Running", "About", "Settings"), width = 100) { showTab(it) }
    private val running = column(launcher)
    private val about = column(launcher)
    private val settings = column(launcher)

    private val name = heading(launcher, "", 20f)
    private val chip = label(launcher, "", 12f, weight = 600).apply { pad(6, 2) }
    private val version = label(launcher, "", 14f, Factorio.MUTED).capped(1)

    private val facts = column(launcher, gap = FACT_GAP)
    private val byline = row(launcher) {}
    private val text = label(launcher, "", 14f)
    private val tested = row(launcher) {}
    private val footer = label(launcher, "", 13f, Factorio.FAINT).capped(2)

    private val checkboxes = column(launcher, gap = 6)
    private val options = optionsField()

    private val useButton = button(launcher, "Use", Tone.GREEN) { use(viewing) }
    private val removeButton = button(launcher, "Remove") { remove(viewing) }
    private val checkButton = button(launcher, "Check") { checkDriver(viewing) }
    private val importButton = button(launcher, "Import") { launcher.pickFiles { if (host.isOpen) import(it.first()) } }
    private val status = label(launcher, "Applies when Factorio next starts.", 14f, Factorio.MUTED).capped(2)
    private val downloading = Progress(launcher)

    init {
        running.add(facts, top = 14)
        about.apply {
            add(byline, top = 12)
            add(text, top = 6)
            add(tested, top = 10)
            add(View(launcher), height = 0, weight = 1f)
            add(groove(launcher), height = 2, top = 10)
            add(footer, top = 6)
        }
        settings.apply {
            add(checkboxes, top = 12)
            add(section("Options"), top = 14)
            add(stack(launcher, Frame.SHALLOW, padding = 4) { add(options) }, top = 5)
            add(label(launcher, "For testing: NAME=value pairs, set when the game starts.", 13f, Factorio.FAINT),
                top = 4)
            add(View(launcher), height = 0, weight = 1f)
            add(row(launcher) {
                spacer()
                add(button(launcher, "Reset", size = 14f) { reset(viewing) }, height = 34)
            })
        }
    }

    fun show() {
        status.shown = Session.inGame
        viewing = Drivers.chosen(launcher)
        val phone = listOfNotNull(Gpu.name, "Android ${Catalog.android}").joinToString(" · ")
        val title = withNote(TITLE, phone, Factorio.FAINT, 15, gap = "   ")
        host.show(title, minOf(WIDTH, launcher.screenWidthDp - MARGIN), "Close", importButton, removeButton,
                  checkButton, useButton) {
            add(row(launcher, Gravity.TOP) {
                add(pane(list), LIST_WIDTH, MATCH)
                add(stack(launcher, Frame.DEEP, padding = 10) {
                    add(tabs)
                    add(row(launcher, Gravity.CENTER_VERTICAL) {
                        add(name)
                        add(chip, start = 10)
                    }, top = 10)
                    add(version, top = 1)
                    add(pane(running), height = 0, weight = 1f)
                    add(pane(about), height = 0, weight = 1f)
                    add(pane(settings), height = 0, weight = 1f)
                }, 0, MATCH, weight = 1f, start = 8)
            }, height = PANES_HEIGHT, weight = 1f)
            add(status)
            add(downloading.view)
        }
        downloading.view.shown = false
        tabs.selected = 0
        showTab(0)
        listDrivers()
        options.clearFocus()   // the dialog would otherwise open typing in it
    }

    private fun showTab(tab: Int) {
        (running.parent as View).shown = tab == 0
        (about.parent as View).shown = tab == 1
        (settings.parent as View).shown = tab == 2
    }

    private fun pane(content: View) = ScrollView(launcher).apply {
        isFillViewport = true
        addView(content)
    }

    private fun section(title: String) = label(launcher, title, 13f, Factorio.MUTED, 600)

    private fun optionsField() = EditText(launcher).apply {
        hint = "NAME=value …"
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setTextColor(Factorio.TEXT)
        setHintTextColor(0xFF8A8580.toInt())
        background = null
        pad(horizontal = 8, vertical = 6)
        isSingleLine = true
        // No suggestions, without the password-field trick: some phones type those on a secure keyboard that can't
        // paste. NO_EXTRACT_UI: the dialog moves above the keyboard instead.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                DriverRecords.setOptions(launcher, viewing, s.toString().trim())
            }
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        })
        setOnEditorActionListener { view, _, _ ->
            view.clearFocus()
            launcher.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
            launcher.immersive()
            true
        }
    }

    private fun listDrivers() {
        val chosen = Drivers.chosen(launcher)
        val drivers = Drivers.all(launcher)
        if (drivers.none { it.id == viewing }) viewing = chosen
        list.removeAllViews()
        val sections = listOf(
            "For your GPU" to drivers.filter(Drivers::forThisGpu),
            "To try" to drivers.filter(Drivers::toTry),
            "For any GPU" to drivers.filter(Drivers::forAnyGpu),
            "Imported" to drivers.filter { it.origin == Drivers.Origin.IMPORTED },
        ).filter { it.second.isNotEmpty() }
        rows = buildMap {
            for ((section, members) in sections) {
                list.add(label(launcher, section, 14f, Factorio.MUTED, 600), start = 4, top = 4)
                // The catalog's own order, which is the order a game tries them in.
                for (driver in members.sortedBy { it.entry?.priority ?: 0 }) {
                    put(driver.id, list.add(driverRow(driver, chosen)))
                }
            }
        }
        view(viewing)
    }

    /** One button: the name and version, and the driver's state at the end. */
    private fun driverRow(driver: Driver, chosen: String) = row(launcher) {
        add(label(launcher, nameAndVersion(driver), 15f, Color.BLACK, 600).apply {
            capped(2)
            setLineSpacing(0f, LINE_SPACING)
        }, 0, WRAP, weight = 1f)
        add(label(launcher, "", 12f, weight = 600).apply { pad(6, 2); paint(state(driver, chosen)) }, start = 8)
    }.apply {
        background = ButtonDrawable(launcher, Tone.GREY)
        isClickable = true
        isFocusable = true
        pad(ROW_SIDE, ROW_PAD)
        setOnClickListener { view(driver.id) }
    }

    /** One text, so the two lines sit close together and centre as one. */
    private fun nameAndVersion(driver: Driver): CharSequence =
        SpannableStringBuilder(withNote(driver.name, Drivers.version(launcher, driver), NOTE, 12, gap = "\n")).apply {
            setSpan(TypefaceSpan(Factorio.font(launcher, 400)), driver.name.length + 1, length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private class State(val text: String, val face: Int, val ink: Int)

    private fun state(driver: Driver, chosen: String): State {
        val downloaded = driver.origin != Drivers.Origin.CATALOG || DriverImport.downloaded(paths, driver.id) != null
        return when {
            driver.id == chosen -> State("In use", Tone.GREEN.face, Tone.GREEN.dark)
            DriverRecords.outcome(launcher, driver.id) == Drivers.Outcome.DIDNT_START ->
                State("Didn't start", Tone.RED.face, Tone.RED.dark)
            !downloaded && Drivers.toTry(driver) -> State("To try", Factorio.BAR, TRY_INK)
            !downloaded -> State(size(driver.entry) ?: "Download", CHIP, CHIP_INK)
            Drivers.pending(launcher, driver.id) != null -> State("Update", Factorio.LINK, UPDATE_INK)
            else -> State("Ready", CHIP, CHIP_INK)
        }
    }

    private fun TextView.paint(state: State) {
        text = state.text
        setBackgroundColor(state.face)
        setTextColor(state.ink)
    }

    private fun size(entry: Catalog.Entry?) =
        entry?.size?.takeIf { it > 0 }?.let { "%.1f MB".format(Locale.ROOT, it / 1e6) }

    /** Doesn't rebuild the list, so focus stays where it is. */
    private fun view(id: String) {
        viewing = id
        rows.forEach { (row, view) ->
            (view.background as ButtonDrawable).tone = if (row == id) Tone.ORANGE else Tone.GREY
        }
        val driver = Drivers.find(launcher, id) ?: return
        val inUse = id == Drivers.chosen(launcher)
        val downloaded = driver.origin != Drivers.Origin.CATALOG || DriverImport.downloaded(paths, id) != null

        name.text = driver.name
        chip.paint(state(driver, Drivers.chosen(launcher)))
        val build = when {
            driver.origin != Drivers.Origin.CATALOG -> null
            !downloaded -> "not downloaded"
            Drivers.pending(launcher, id) != null -> "newer build next game"
            else -> "up to date"
        }
        version.text = listOfNotNull(Drivers.version(launcher, driver).ifEmpty { null }, build).joinToString(" · ")

        showRunning(driver, inUse)
        showAbout(driver, downloaded)
        showSettings(driver)
        useButton.text = when {
            inUse -> "In use"
            !downloaded && Drivers.toTry(driver) -> "Try"
            !downloaded -> "Download"
            else -> "Use"
        }
        useButton.isEnabled = !inUse && Drivers.fits(driver)
        removeButton.shown = driver.origin != Drivers.Origin.APP && downloaded
        checkButton.shown = downloaded && Drivers.usesZink(paths, id)
        checkButton.isEnabled = !Session.inGame
    }

    /** What runs with [driver] on top of it, a row each; what doesn't apply has no row. */
    private fun showRunning(driver: Driver, inUse: Boolean) {
        facts.removeAllViews()
        val id = driver.id
        // Fixes are for a GPU: none comes with the CPU.
        if (driver.origin == Drivers.Origin.CATALOG && !Drivers.forAnyGpu(driver)) {
            val fixes = Catalog.fixesFor(Catalog.get(launcher), id, Gpu.name, Catalog.android)
            fact("Fix", fixes.joinToString(", ") { it.name }.ifEmpty { "None needed" })
        }
        if (Drivers.usesZink(paths, id, driver.entry)) {
            fact("Driver check", DriverCheck.brief(launcher, id) ?: "Not checked yet")
        }
        if (inUse) fact("Resolution", resolution(id))
        when (Drivers.compression(paths, id, DriverCheck.transcodes(launcher, id))) {
            Drivers.Compression.CPU -> fact("Textures", "Compressed by the CPU")
            Drivers.Compression.NONE -> fact("Textures", "Not compressed")
            Drivers.Compression.GPU -> {}
        }
        val offered = driver.entry?.settings.orEmpty()
        if (offered.isNotEmpty()) {
            val ticked = DriverRecords.settings(launcher, id)
            fact("Settings", offered.filter { it.id in ticked }.joinToString(", ") { it.label }.ifEmpty { "Default" })
        }
        DriverRecords.options(launcher, id).takeIf { it.isNotEmpty() }?.let { fact("Options", it) }
        if (facts.childCount == 0) facts.add(label(launcher, "Nothing else runs with it.", 14f, Factorio.FAINT))
    }

    private fun fact(what: String, value: CharSequence) = facts.add(row(launcher, Gravity.TOP) {
        add(label(launcher, what, 15f, Factorio.FAINT), FACT_LABEL, WRAP)
        add(label(launcher, value, 15f), 0, WRAP, weight = 1f)
    })

    /** The game's resolution now, and where it came from. */
    private fun resolution(id: String): CharSequence {
        val x11 = X11Prefs(launcher)
        val settings = Settings(launcher)
        val (width, height) = x11.gameSize(x11.resolutionScale)
        val default = x11.resolutionScale == x11.defaultResolutionScale
        val why = when {
            !settings.graphicsChosen && settings.graphicsDriver == id && DriverCheck.speed(launcher, id).isNotEmpty() ->
                if (default) "speed test kept the default" else "lowered by the speed test"
            default -> "default"
            settings.graphicsChosen -> "your choice"
            else -> null
        }
        val size = "$width × $height"
        return if (why == null) size else withNote(size, "· $why", Factorio.FAINT, 13, gap = " ")
    }

    private fun showAbout(driver: Driver, downloaded: Boolean) {
        val entry = driver.entry
        showByline(driver)
        text.text = when {
            Drivers.toTry(driver) ->
                "Not tried on your ${Gpu.name} yet: it was made for ${entry?.forGpus}. " + driver.about
            else -> driver.about.ifEmpty { "A driver you imported." }
        }
        // A phone tested on several versions is one tag.
        val phones = entry?.tested.orEmpty().filter { it.result == "plays" || it.result == "menu" }
            .distinctBy { it.phone }
        tested.shown = phones.isNotEmpty()
        tested.removeAllViews()
        tested.add(section("Tested on"))
        for (test in phones.take(MAX_TAGS)) tested.add(tag(test.phone), start = 6)
        if (phones.size > MAX_TAGS) {
            tested.add(label(launcher, "+${phones.size - MAX_TAGS}", 13f, Factorio.FAINT), start = 8)
        }
        footer.text = footer(driver, entry, downloaded)
    }

    private fun showByline(driver: Driver) {
        byline.removeAllViews()
        val entry = driver.entry
        when {
            entry != null -> {
                byline.add(label(launcher, "by ", 13f, Factorio.FAINT))
                byline.add(link(launcher, entry.madeBy, entry.source, 13f))
                if (entry.mesa.isNotEmpty() && entry.version != entry.mesa) {
                    byline.add(label(launcher, " · on Mesa ${entry.mesa.substringBefore('-')}", 13f, Factorio.FAINT))
                }
            }
            // MaliBridge's licence asks for this credit wherever the app shows credits.
            Drivers.isPhone(driver.id) -> byline.add(link(launcher, "Uses MaliBridge by ASEbrahim", MALIBRIDGE, 13f))
            else -> byline.add(label(launcher, "Imported from ${driver.note}", 13f, Factorio.FAINT).capped(1))
        }
    }

    private fun tag(text: String) = label(launcher, text, 13f).apply {
        setBackgroundColor(Factorio.INSET_LIGHT)
        pad(9, 2)
    }

    private fun footer(driver: Driver, entry: Catalog.Entry?, downloaded: Boolean): String {
        val lastGame = when (DriverRecords.outcome(launcher, driver.id)) {
            Drivers.Outcome.RAN -> "ran fine"
            Drivers.Outcome.STOPPED -> "stopped unexpectedly"
            Drivers.Outcome.DIDNT_START -> "didn't start"
            null -> "not tried yet"
        }
        // A size not downloaded yet is in the state next to the name.
        return listOfNotNull(size(entry)?.takeIf { downloaded }, "Last game: $lastGame").joinToString(" · ")
    }

    private fun showSettings(driver: Driver) {
        checkboxes.removeAllViews()
        val ticked = DriverRecords.settings(launcher, driver.id)
        for (setting in driver.entry?.settings.orEmpty()) {
            checkboxes.add(row(launcher) {
                add(label(launcher, setting.label, 14f), 0, WRAP, weight = 1f)
                add(checkBox(launcher, setting.id in ticked) {
                    DriverRecords.setSetting(launcher, driver.id, setting.id, it)
                    showRunning(driver, driver.id == Drivers.chosen(launcher))
                })
            })
            if (setting.about.isNotEmpty()) checkboxes.add(label(launcher, setting.about, 13f, Factorio.FAINT))
        }
        checkboxes.shown = checkboxes.childCount > 0
        options.setText(DriverRecords.options(launcher, driver.id))
    }

    private fun reset(id: String) {
        DriverRecords.setOptions(launcher, id, "")
        for (setting in Drivers.find(launcher, id)?.entry?.settings.orEmpty()) {
            DriverRecords.setSetting(launcher, id, setting.id, false)
        }
        view(id)
    }

    /** A catalog driver not downloaded yet downloads now; the next game uses it either way. */
    private fun use(id: String) {
        val entry = Drivers.pending(launcher, id).takeIf { DriverImport.downloaded(paths, id) == null }
        if (entry == null) return choose(id)
        useButton.isEnabled = false
        status.shown = false
        downloading.show("Downloading ${entry.name}", 0f)
        downloading.view.shown = true
        background("driver-download", {
            Drivers.download(launcher, entry) { percent ->
                launcher.runOnUiThread { downloading.show("Downloading ${entry.name}", percent / 100f) }
            }
        }) { result ->
            downloading.view.shown = false
            say(result.fold({ "${entry.name} is ready." }, { "${entry.name} didn't download: ${it.message}" }),
                error = result.isFailure)
            if (result.isSuccess) choose(id) else view(id)
        }
    }

    private fun choose(id: String) {
        Drivers.choose(launcher, id)
        listDrivers()
    }

    private fun remove(id: String) {
        Drivers.remove(launcher, id)
        viewing = ""
        listDrivers()
    }

    /** Draws [id]'s test picture and times its frames now, without the game. */
    private fun checkDriver(id: String) {
        checkButton.isEnabled = false
        say("Drawing a test picture, then timing frames…")
        background("driver-check", { Session.checkDriver(launcher, id) }) { result ->
            say(result.fold({ "Checked. Logs has the details." }, { it.message ?: "The check didn't run. See Logs." }),
                error = result.isFailure)
            // A check can unpack a new runtime, under which past games don't count: the automatic choice may move.
            listDrivers()
        }
    }

    private fun say(text: String, error: Boolean = false) {
        status.text = text
        status.setTextColor(if (error) Factorio.ERROR else Factorio.MUTED)
        status.shown = true
    }

    private fun import(uri: Uri) {
        removeButton.isEnabled = false
        importButton.isEnabled = false
        say("Checking the driver…")
        background("driver", { DriverImport.import(launcher, uri) }) { result ->
            importButton.isEnabled = true
            removeButton.isEnabled = true
            say(result.fold({ "Added ${it.name}." }, { it.message ?: "That didn't work. See Logs." }),
                error = result.isFailure)
            result.onSuccess { viewing = it.id; choose(it.id) }.onFailure { listDrivers() }
        }
    }

    private companion object {
        const val TITLE = "Graphics driver"
        const val MALIBRIDGE = "https://github.com/ASEbrahim/MaliBridge"
        const val NOTE = 0xFF2A2A2A.toInt()
        const val CHIP = 0xFF555455.toInt()
        const val CHIP_INK = 0xFFE6E1DA.toInt()
        const val TRY_INK = 0xFF3A2508.toInt()
        const val UPDATE_INK = 0xFF10303D.toInt()
        const val MAX_TAGS = 2
        const val WIDTH = 880
        const val MARGIN = 32           // beside the dialog on a narrow screen
        const val LIST_WIDTH = 340
        const val PANES_HEIGHT = 330
        const val ROW_GAP = 3
        const val ROW_SIDE = 12
        /** Above and below a row's text: the row is as tall as its text needs, plus this. */
        const val ROW_PAD = 11
        const val LIST_PADDING = 6
        const val FACT_LABEL = 118
        const val FACT_GAP = 9
        const val LINE_SPACING = 0.9f
    }
}
