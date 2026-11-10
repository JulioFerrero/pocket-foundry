package app.pocketfoundry.ui.screens.drivers

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
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
import app.pocketfoundry.core.system.appVersion
import app.pocketfoundry.core.system.background
import app.pocketfoundry.ui.kit.ButtonDrawable
import app.pocketfoundry.ui.kit.Choice
import app.pocketfoundry.ui.kit.Dropdown
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every driver for this phone, what each is for and when it was updated, and the one looked at: About (who made it,
 * where it was tested) and Settings (the catalog's checkboxes for it, Options). The bar below acts on that one. A
 * short screen scrolls the panes.
 */
class DriverDialog(private val launcher: LauncherActivity) {
    private val host = launcher.host
    private val paths = Paths(launcher)
    private val list = stack(launcher, Frame.DEEP, padding = LIST_PADDING, gap = ROW_GAP)
    private var rows = emptyMap<String, View>()
    private var viewing = ""

    private val sort = Dropdown(launcher, SORTS) { sorting = it; listDrivers() }.apply { selected = sorting }

    private val tabs = Choice(launcher, listOf("About", "Settings"), width = 100) { showTab(it) }
    private val about = column(launcher)
    private val settings = column(launcher)

    private val name = heading(launcher, "", 20f)
    private val version = label(launcher, "", 14f, Factorio.MUTED)
    private val chip = label(launcher, "", 12f, Tone.GREEN.dark, 600).apply { pad(6, 2) }
    private val byline = row(launcher) {}
    private val text = label(launcher, "", 14f)
    private val tested = row(launcher) {}
    private val footer = label(launcher, "", 13f, Factorio.FAINT).capped(2)

    private val checkboxes = column(launcher, gap = 6)
    private val options = optionsField()
    private val checked = label(launcher, "", 13f, Factorio.FAINT).capped(2)

    private val useButton = button(launcher, "Use", Tone.GREEN) { use(viewing) }
    private val removeButton = button(launcher, "Remove") { remove(viewing) }
    private val checkButton = button(launcher, "Check") { checkDriver(viewing) }
    private val importButton = button(launcher, "Import") { launcher.pickFiles { if (host.isOpen) import(it.first()) } }
    private val status = label(launcher, "Applies when Factorio next starts.", 14f, Factorio.MUTED).capped(2)
    private val downloading = Progress(launcher)

    init {
        about.apply {
            add(row(launcher, Gravity.CENTER_VERTICAL) {
                add(name)
                add(version, start = 8)
                add(chip, start = 10)
            }, top = 8)
            add(byline, top = 2)
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
            add(checked, top = 10)
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
        val title = Gpu.name?.let { withNote(TITLE, it, Factorio.FAINT, 15, gap = "   ") } ?: TITLE
        val sortRow = row(launcher) {
            add(label(launcher, "Sort", 14f, Factorio.FAINT), gravity = Gravity.CENTER_VERTICAL)
            add(sort, SORT_WIDTH, 34, start = 8)
        }
        host.show(title, minOf(WIDTH, launcher.screenWidthDp - MARGIN), "Close", importButton, removeButton,
                  checkButton, useButton, titleEnd = sortRow) {
            add(row(launcher, Gravity.TOP) {
                add(pane(list), LIST_WIDTH, MATCH)
                add(stack(launcher, Frame.DEEP, padding = 10) {
                    add(tabs)
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
        (about.parent as View).shown = tab == 0
        (settings.parent as View).shown = tab == 1
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
        list.add(columns(label(launcher, "Driver", 13f, Factorio.FAINT), label(launcher, "For", 13f, Factorio.FAINT),
                         label(launcher, "Updated", 13f, Factorio.FAINT)).apply { pad(ROW_SIDE, 0) }, top = 2)
        val sections = listOf(
            "For your GPU" to drivers.filter(Drivers::forThisGpu),
            "To try" to drivers.filter(Drivers::toTry),
            "With the app" to drivers.filter { it.origin == Drivers.Origin.APP },
            "Imported" to drivers.filter { it.origin == Drivers.Origin.IMPORTED },
        ).filter { it.second.isNotEmpty() }
        rows = buildMap {
            for ((section, members) in sections) {
                list.add(label(launcher, section, 14f, Factorio.MUTED, 600), start = 4, top = 4)
                for (driver in sorted(members)) {
                    put(driver.id, list.add(driverRow(driver, driver.id == chosen), height = ROW))
                }
            }
        }
        view(viewing)
    }

    private fun sorted(drivers: List<Driver>) = when (sorting) {
        BY_UPDATE -> drivers.sortedByDescending { it.entry?.updated.orEmpty() }
        BY_NAME -> drivers.sortedBy { it.name.lowercase() }
        // The catalog's own order, which is the order a game tries them in.
        else -> drivers.sortedBy { it.entry?.priority ?: 0 }
    }

    /** One button, three columns: the name, what it's for, when it was updated. */
    private fun driverRow(driver: Driver, inUse: Boolean) = columns(
        label(launcher, if (inUse) withNote(driver.name, "In use", sizeSp = 12) else driver.name, 15f, Color.BLACK,
              600),
        label(launcher, driver.forGpus, 13f, NOTE),
        label(launcher, updated(driver), 13f, NOTE),
    ).apply {
        background = ButtonDrawable(launcher, Tone.GREY)
        isClickable = true
        isFocusable = true
        pad(ROW_SIDE, 0)
        setOnClickListener { view(driver.id) }
    }

    private fun columns(name: TextView, forGpus: TextView, updated: TextView) = row(launcher) {
        add(name.oneLine(), 0, WRAP, weight = 1f)
        add(forGpus.oneLine(), FOR_WIDTH, WRAP, start = 8)
        add(updated.oneLine(), UPDATED_WIDTH, WRAP, start = 8)
    }

    private fun TextView.oneLine() = apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun updated(driver: Driver): String = when (driver.origin) {
        Drivers.Origin.APP -> launcher.appVersion().substringBefore('-')
        Drivers.Origin.CATALOG -> driver.entry?.updated?.let(::shortDate).orEmpty()
        Drivers.Origin.IMPORTED ->
            shortDate(LocalDate.ofEpochDay(DriverImport.folder(paths, driver.id).lastModified() / DAY_MS).toString())
    }

    /** "3 Nov" this year, "Nov 2025" before. */
    private fun shortDate(day: String): String = runCatching {
        val date = LocalDate.parse(day)
        val pattern = if (date.year == LocalDate.now().year) "d MMM" else "MMM yyyy"
        date.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
    }.getOrDefault(day)

    /** Doesn't rebuild the list, so focus stays where it is. */
    private fun view(id: String) {
        viewing = id
        rows.forEach { (row, view) ->
            (view.background as ButtonDrawable).tone = if (row == id) Tone.ORANGE else Tone.GREY
        }
        val driver = Drivers.find(launcher, id) ?: return
        val entry = driver.entry
        val inUse = id == Drivers.chosen(launcher)
        val downloaded = driver.origin != Drivers.Origin.CATALOG || DriverImport.downloaded(paths, id) != null

        name.text = driver.name
        version.text = if (driver.origin == Drivers.Origin.CATALOG) driver.note else ""
        val recommended = id == Drivers.recommended(launcher)
        chip.text = when {
            recommended -> "Recommended"
            Drivers.toTry(driver) -> "To try"
            else -> ""
        }
        chip.setBackgroundColor(if (recommended) Tone.GREEN.face else Factorio.BAR)
        chip.setTextColor(if (recommended) Tone.GREEN.dark else 0xFF3A2508.toInt())
        chip.shown = chip.text.isNotEmpty()
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

    private fun showByline(driver: Driver) {
        byline.removeAllViews()
        val entry = driver.entry
        when {
            entry != null -> {
                byline.add(label(launcher, "by ", 13f, Factorio.FAINT))
                byline.add(link(launcher, entry.madeBy, entry.source, 13f))
                if (entry.mesa.isNotEmpty() && entry.version != entry.mesa) {
                    byline.add(label(launcher, " · Mesa ${entry.mesa}", 13f, Factorio.FAINT))
                }
            }
            // MaliBridge's licence asks for this credit wherever the app shows credits.
            Drivers.isPhone(driver.id) -> byline.add(link(launcher, "Uses MaliBridge by ASEbrahim", MALIBRIDGE, 13f))
            driver.origin == Drivers.Origin.APP ->
                byline.add(label(launcher, "Comes with the app", 13f, Factorio.FAINT))
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
        val size = entry?.size?.takeIf { it > 0 }?.let { "%.1f MB".format(Locale.ROOT, it / 1e6) }
        return listOfNotNull(
            entry?.updated?.let { "Updated ${shortDate(it)}" },
            size?.let { if (downloaded) it else "Not downloaded, $it" },
            "Last game: $lastGame",
        ).joinToString(" · ")
    }

    private fun showSettings(driver: Driver) {
        checkboxes.removeAllViews()
        val ticked = DriverRecords.settings(launcher, driver.id)
        for (setting in driver.entry?.settings.orEmpty()) {
            checkboxes.add(row(launcher) {
                add(label(launcher, setting.label, 14f), 0, WRAP, weight = 1f)
                add(checkBox(launcher, setting.id in ticked) {
                    DriverRecords.setSetting(launcher, driver.id, setting.id, it)
                })
            })
            if (setting.about.isNotEmpty()) checkboxes.add(label(launcher, setting.about, 13f, Factorio.FAINT))
        }
        checkboxes.shown = checkboxes.childCount > 0
        options.setText(DriverRecords.options(launcher, driver.id))
        checked.text = "Driver check: " +
            (DriverCheck.summary(launcher, driver.id) ?: "not checked yet")
        checked.shown = Drivers.usesZink(paths, driver.id)
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
        val SORTS = listOf("Best for your GPU", "Recently updated", "Name")
        const val BY_UPDATE = 1
        const val BY_NAME = 2
        /** Kept while the app runs. */
        var sorting = 0
        const val NOTE = 0xFF2A2A2A.toInt()
        const val MAX_TAGS = 2
        const val DAY_MS = 86_400_000L
        const val WIDTH = 880
        const val MARGIN = 32           // beside the dialog on a narrow screen
        const val LIST_WIDTH = 400
        const val PANES_HEIGHT = 330
        const val FOR_WIDTH = 130
        const val UPDATED_WIDTH = 60
        const val SORT_WIDTH = 190
        const val ROW = 36
        const val ROW_GAP = 3
        const val ROW_SIDE = 14
        const val LIST_PADDING = 6
    }
}
