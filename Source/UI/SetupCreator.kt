package ui

import bakemon.FormulaScope
import bakemon.Setup
import creator.BarFill
import creator.CategoryStyle
import creator.CritMode
import creator.DamagePreset
import creator.DraftException
import creator.EntryKind
import creator.Growth
import creator.MoveLimit
import creator.Option
import creator.Reference
import creator.RuleAnswers
import creator.RulePart
import creator.RuleTemplate
import creator.SetupDraft
import creator.StageStyle
import creator.StatAnswer
import creator.StatPreset
import creator.TurnStyle
import creator.Validation
import util.Json
import java.io.File

/**
 * The setup creator (terminal screens over the UI-independent `creator` package):
 *  - home: the setups with their status (OK / N warnings / N errors / incomplete), create a new setup from the rule
 *    templates, edit, rename the folder, delete (two confirmations);
 *  - setup editor: Rules (template questions, one value, raw JSON), Types, Statuses, Moves, Abilities, Items, Fields,
 *    Bakemons, plus Checklist, Validate, Save, Discard changes and raw JSON files;
 *  - each section: a paged, searchable list; add (wizard), edit (form), copy, rename id (updating references), delete
 *    (showing references, offering to remove them), raw JSON view / replace.
 * Changes are made in a draft and written by Save only. [saved] and [renamed] tell the caller which setup folders
 * changed on disk, so a loaded setup can be reloaded.
 */
class SetupCreator(private val root: File) {
    /** Setup folders written in this session (absolute paths, after renames). */
    val saved: MutableSet<File> = mutableSetOf()

    /** Setup folders renamed in this session: old absolute path -> new one. */
    val renamed: MutableMap<File, File> = linkedMapOf()

    /** Setup folders deleted in this session. */
    val deleted: MutableSet<File> = mutableSetOf()

    /** The form folders are stored in ([saved], [renamed], [deleted]): absolute and normalized. */
    private fun key(dir: File): File = dir.absoluteFile.normalize()

    // ---- home -----------------------------------------------------------------------------------------------------

    /** The home screen; returns when the user goes back. */
    fun run() {
        while (true) {
            Terminal.header("Setup creator")
            println(Terminal.dim("Setups in ${root.path}"))
            val dirs = Setup.discover(root)
            val rows = Terminal.table(dirs.map { listOf(it.name, status(it)) })
            val actions = listOf("Create a new setup", "Rename a setup folder", "Delete a setup")
            if (dirs.isEmpty()) println(Terminal.dim("  No setups yet."))
            else println(Terminal.dim("Pick a setup to edit it:"))
            val index = Terminal.menu("", rows + actions, "Back")
            when {
                index < 0 -> return
                index < dirs.size -> CreatorInput.guard { editSetup(dirs[index]) }
                index == dirs.size -> CreatorInput.guard { createNew() }
                index == dirs.size + 1 -> pickSetup(dirs, "Rename which setup folder?")?.let { CreatorInput.guard { renameFolder(it) } }
                else -> pickSetup(dirs, "Delete which setup?")?.let { CreatorInput.guard { deleteSetup(it) } }
            }
        }
    }

    private fun pickSetup(dirs: List<File>, title: String): File? {
        if (dirs.isEmpty()) {
            Terminal.warn("There are no setups.")
            return null
        }
        val index = Terminal.menu(title, dirs.map { it.name }, "Cancel")
        return if (index < 0) null else dirs[index]
    }

    /** "OK", "2 warnings", "3 errors", "incomplete (2 things missing)" or "1 file cannot be read", coloured. */
    internal fun status(dir: File): String {
        val draft = try {
            SetupDraft.load(dir)
        } catch (e: Exception) {
            return Terminal.color("cannot be read: ${e.message}", "red")
        }
        if (draft.problems.isNotEmpty()) return Terminal.color("${CreatorInput.count(draft.problems.size, "file")} cannot be read", "red")
        val missing = draft.checklist().filter { !it.done && !it.optional }
        if (missing.isNotEmpty()) return Terminal.color("incomplete (${CreatorInput.count(missing.size, "thing")} missing)", "yellow")
        val report = Setup.check(dir).second
        return when {
            report.errors.isNotEmpty() -> Terminal.color(CreatorInput.count(report.errors.size, "error"), "red")
            report.warnings.isNotEmpty() -> Terminal.color(CreatorInput.count(report.warnings.size, "warning"), "yellow")
            else -> Terminal.color("OK", "green")
        }
    }

    private fun renameFolder(dir: File) {
        val name = Terminal.ask("New folder name for '${dir.name}' (empty = cancel)").trim()
        if (name.isEmpty() || name == dir.name) return
        val target = try {
            SetupDraft.renameSetupFolder(dir, name)
        } catch (e: DraftException) {
            Terminal.error(e.message ?: "The folder could not be renamed.")
            return
        }
        val old = key(dir)
        val new = key(target)
        renamed.entries.filter { it.value == old }.forEach { it.setValue(new) }
        renamed[old] = new
        if (saved.remove(old)) saved += new
        println("Renamed the folder to '${target.name}'. (The setup's name shown in menus is set in Rules.)")
    }

    private fun deleteSetup(dir: File) {
        val teams = File(dir, "Teams").listFiles()?.count { it.isFile } ?: 0
        Terminal.warn("This deletes the folder ${dir.path} with every file in it (${CreatorInput.count(teams, "saved team")}).")
        if (!Terminal.confirm("Delete the setup '${dir.name}'?", false)) return
        if (!Terminal.confirm("Are you sure? This cannot be undone", false)) return
        if (dir.deleteRecursively()) {
            deleted += key(dir)
            println("Deleted '${dir.name}'.")
        } else {
            Terminal.error("Some files could not be deleted (is one of them open in another program?).")
        }
    }

    // ---- new setup --------------------------------------------------------------------------------------------------

    /** The rule template questions, then the new setup is saved and opened (checklist first). Returns its folder. */
    fun createNew(): File? {
        Terminal.header("Create a new setup")
        CreatorInput.help(
            "A new setup starts from rule templates: answer a few questions (Enter accepts the suggestion in brackets). " +
                "It starts empty: afterwards you add types, moves, bakemons and so on.",
        )
        val name = Terminal.ask("Name of the new setup (empty = cancel)").trim()
        if (name.isEmpty()) return null
        println(Terminal.dim("It will be saved in the folder '${SetupDraft.folderNameFor(root, name)}'."))
        val styles = TurnStyle.entries.map { Option(it.id, it.label, it.help) }
        val style = TurnStyle.entries[CreatorInput.choose("How do turns work?", styles, 0)]
        var answers = RuleAnswers.forStyle(style).copy(name = name)
        val presets = StatPreset.entries.map { Option(it.name, it.label, it.help) }
        val preset = StatPreset.entries[CreatorInput.choose("Which stats do monsters have?", presets, StatPreset.entries.indexOf(answers.statPreset))]
        answers = answers.withStatPreset(preset)
        if (preset == StatPreset.CUSTOM) answers = editStats(answers, isNew = true)
        val rules = RulesForm(answers, isNew = true, custom = emptySet())
        if (Terminal.confirm("Answer the other rule questions now? (no = the recommended rules; you can change them later)", false)) {
            rules.walk(RulePart.entries.filter { it != RulePart.BASICS && it != RulePart.STATS })
        }
        answers = rules.run("Create the setup") ?: return null.also { println("No setup was created.") }
        val draft = SetupDraft.create(root, answers.name, answers)
        val result = draft.save()
        saved += key(draft.dir)
        println(Terminal.color("Created the setup '${draft.displayName}' in ${draft.dir.path} (${CreatorInput.count(result.written.size, "file")}).", "green"))
        editSetup(draft.dir, draft, checklistFirst = true)
        return draft.dir
    }

    // ---- setup editor -----------------------------------------------------------------------------------------------

    /** The editor of the setup in [dir] ([start]: an already loaded draft). */
    fun editSetup(dir: File, start: SetupDraft? = null, checklistFirst: Boolean = false) {
        var draft = start ?: SetupDraft.load(dir)
        if (checklistFirst) checklist(draft)
        while (true) {
            val forms = EntryForms(draft)
            Terminal.header("Setup: ${draft.displayName}" + Terminal.dim(" (folder ${draft.folderName})") +
                if (draft.isDirty) Terminal.color(" - unsaved changes", "yellow") else "")
            if (draft.problems.isNotEmpty()) {
                Terminal.error("These files have JSON syntax errors and cannot be edited until repaired (Raw JSON files):")
                draft.problems.forEach { Terminal.error("  $it") }
            }
            val missing = draft.checklist().filter { !it.done && !it.optional }
            if (missing.isEmpty()) println(Terminal.color("Everything needed to battle is there (Validate checks the details).", "green"))
            else Terminal.warn("Still needed to battle: ${missing.joinToString("; ") { it.text }}")
            val sections = EntryKind.entries
            val options = listOf("Rules - " + rulesSummary(draft)) +
                sections.map { "${it.plural} (${draft.ids(it).size})" } +
                listOf("Checklist", "Validate", "Save", "Discard changes", "Raw JSON files (advanced)")
            val index = Terminal.menu("", options, "Back")
            val ok = CreatorInput.guard {
                when {
                    index < 0 -> {}
                    index == 0 -> rulesScreen(draft)
                    index <= sections.size -> section(draft, forms, sections[index - 1])
                    else -> when (options[index]) {
                        "Checklist" -> checklist(draft)
                        "Validate" -> {
                            println("Checking...")
                            showValidation(draft.validate(), draft)
                            Terminal.pause()
                        }
                        "Save" -> save(draft)
                        "Discard changes" -> if (!draft.isDirty) println("There is nothing to discard.")
                        else if (Terminal.confirm("Discard every change since the last save?", false)) {
                            draft = SetupDraft.load(draft.dir)
                            println("Changes discarded.")
                        }
                        else -> rawFiles(draft)
                    }
                }
            }
            if (index < 0 && ok) {
                if (!draft.isDirty) return
                val choice = Terminal.menu("You have unsaved changes.", listOf("Save them", "Leave without saving"), "Stay here")
                if (choice == 1) return
                if (choice == 0 && save(draft, confirm = false)) return
            }
        }
    }

    private fun rulesSummary(draft: SetupDraft): String {
        val a = draft.recogniseRules().answers
        val limit = when (a.moveLimit) {
            MoveLimit.PP -> "PP"
            MoveLimit.COOLDOWNS -> "cooldowns"
            MoveLimit.NONE -> "no move limits"
        }
        return "${a.turnSystem.label}, ${CreatorInput.count(a.stats.size, "stat")}, $limit"
    }

    /** Shows the changes, saves, then shows the validation. Returns false if not saved. */
    private fun save(draft: SetupDraft, confirm: Boolean = true): Boolean {
        if (!draft.isDirty) {
            println("There are no unsaved changes.")
            return true
        }
        Terminal.header("Save ${draft.displayName}")
        println("Changes:")
        CreatorInput.printLines(draft.changes().map { it.summary() }, 30)
        if (confirm && !Terminal.confirm("Save these changes?", true)) return false
        val result = try {
            draft.save()
        } catch (e: java.io.IOException) {
            Terminal.error("The setup could not be saved: ${e.message}")
            return false
        }
        saved += key(draft.dir)
        println(Terminal.color("Saved: ${result.written.joinToString(", ").ifEmpty { "nothing new" }}", "green"))
        showValidation(result.validation, draft)
        return true
    }

    private fun showValidation(v: Validation, draft: SetupDraft) {
        when {
            v.ok && v.warnings.isEmpty() -> println(Terminal.color("The setup is valid: ready to battle.", "green"))
            v.ok -> println(Terminal.color("The setup is valid: ready to battle", "green") + " (${CreatorInput.count(v.warnings.size, "warning")}).")
            else -> Terminal.error("The setup has ${CreatorInput.count(v.errors.size, "error")}: it cannot be used for battles yet.")
        }
        CreatorInput.printLines(v.errors, 25, "  ! ", "red")
        CreatorInput.printLines(v.warnings, 10, "  warning: ", "yellow")
        val missing = draft.checklist().filter { !it.done && !it.optional }
        if (!v.ok && missing.isNotEmpty()) Terminal.warn("See the Checklist: ${missing.joinToString("; ") { it.text }}")
    }

    /** What is still needed, with links to the sections. */
    private fun checklist(draft: SetupDraft) {
        while (true) {
            Terminal.header("Checklist: what ${draft.displayName} needs to battle")
            val items = draft.checklist()
            items.forEach { item ->
                val mark = if (item.done) Terminal.color("[x]", "green") else if (item.optional) "[-]" else Terminal.color("[ ]", "yellow")
                println("  $mark ${item.text}")
            }
            if (items.all { it.done || it.optional }) println(Terminal.color("Everything needed is there. Use Validate to check the details, then Save.", "green"))
            val links = items.filter { !it.done }.mapNotNull { it.kind }.distinct()
            val forms = EntryForms(draft)
            val index = Terminal.menu("", links.map { "Go to ${it.plural}" } + "Validate", "Back to the setup")
            when {
                index < 0 -> return
                index < links.size -> CreatorInput.guard { section(draft, forms, links[index]) }
                else -> {
                    println("Checking...")
                    showValidation(draft.validate(), draft)
                    Terminal.pause()
                }
            }
        }
    }

    // ---- sections ---------------------------------------------------------------------------------------------------

    private fun str(e: Map<String, Any?>, key: String): String? = e[key] as? String

    /** One list row per entry: id, table cells and the text searched. */
    private class EntryRow(val id: String, val cells: List<String>, val search: String)

    private fun entryRows(draft: SetupDraft, forms: EntryForms, kind: EntryKind): List<EntryRow> = draft.entries(kind).mapNotNull { e ->
        val id = str(e, "id") ?: return@mapNotNull null
        val name = str(e, "name") ?: id
        val idCell = Terminal.dim(id)
        when (kind) {
            EntryKind.TYPE -> EntryRow(id, listOf(forms.typeName(id), idCell), "$name $id")
            EntryKind.STATUS -> {
                val kindText = str(e, "kind") ?: ""
                EntryRow(id, listOf(name, idCell, kindText, forms.durationText(e["duration"]?.let { (it as? String)?.toDoubleOrNull() ?: it })), "$name $id $kindText")
            }
            EntryKind.MOVE -> {
                val hint = forms.moveHint(e)
                EntryRow(id, listOf(name, idCell, hint), "$name $id $hint")
            }
            EntryKind.ABILITY, EntryKind.ITEM -> {
                val d = str(e, "description") ?: ""
                EntryRow(id, listOf(name, idCell, Terminal.dim(CreatorInput.short(d, 50))), "$name $id $d")
            }
            EntryKind.FIELD -> {
                val where = if (str(e, "scope") == "side") "one side" else "whole battle"
                EntryRow(id, listOf(name, idCell, where, str(e, "group")?.let { "group $it" } ?: ""), "$name $id ${str(e, "group") ?: ""}")
            }
            EntryKind.BAKEMON -> {
                @Suppress("UNCHECKED_CAST")
                val types = (e["types"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val total = (e["baseStats"] as? Map<*, *>)?.values?.sumOf { (it as? Number)?.toDouble() ?: 0.0 } ?: 0.0
                EntryRow(id, listOf(name, idCell, forms.typeNames(types), "total ${Describe.num(total)}"),
                    "$name $id " + types.joinToString(" ") { "$it ${draft.nameOf(EntryKind.TYPE, it)}" })
            }
        }
    }

    /** A section: the paged, searchable list of entries of [kind], with Add and the section's extras. */
    private fun section(draft: SetupDraft, forms: EntryForms, kind: EntryKind) {
        val file = draft.file(kind.fileName)
        if (file.error != null) {
            Terminal.error("${kind.fileName} has a JSON syntax error and cannot be edited until repaired (Raw JSON files):")
            Terminal.error("  ${file.error}")
            Terminal.pause()
            return
        }
        val state = CreatorInput.ListState()
        while (true) {
            val all = entryRows(draft, forms, kind)
            Terminal.header("${kind.plural} of ${draft.displayName} (${all.size})")
            CreatorInput.help(SECTION_HELP[kind])
            val visible = all.filter { CreatorInput.matches(state.query, it.search) }
            val rows = Terminal.table(visible.map { it.cells })
            val actions = mutableListOf("Add " + CreatorInput.article(kind.label))
            if (kind == EntryKind.TYPE) actions += listOf("Show the whole type chart", "Effectiveness messages")
            when (val p = CreatorInput.page(rows, actions, state)) {
                is CreatorInput.Picked.Row -> CreatorInput.guard { entryScreen(draft, forms, kind, visible[p.index].id) }
                is CreatorInput.Picked.Action -> CreatorInput.guard {
                    when (actions[p.index]) {
                        "Show the whole type chart" -> typeChart(draft)
                        "Effectiveness messages" -> typeMessages(draft)
                        else -> forms.add(kind)
                    }
                }
                CreatorInput.Picked.Back -> return
                CreatorInput.Picked.Again -> {}
            }
        }
    }

    /** One entry: its summary, then edit / copy / rename id / delete / raw JSON. */
    private fun entryScreen(draft: SetupDraft, forms: EntryForms, kind: EntryKind, startId: String) {
        var id = startId
        val label = kind.label.replaceFirstChar { it.uppercase() }
        while (true) {
            val entry = draft.entry(kind, id) ?: return
            Terminal.header("$label: ${draft.nameOf(kind, id)} " + Terminal.dim("(id $id)"))
            forms.editorFor(kind, entry).summary().forEach { line ->
                val lines = line.split('\n')
                println("  " + lines.first())
                lines.drop(1).forEach { println("    $it") }
            }
            val actions = listOf("Edit", "Copy", "Rename id", "Delete", "View the raw JSON", "Replace the raw JSON (advanced)")
            when (Terminal.menu("", actions, "Back")) {
                -1 -> return
                0 -> forms.edit(kind, id)
                1 -> {
                    val name = CreatorInput.name("Name of the copy", "${draft.nameOf(kind, id)} copy")
                    val newId = draft.copy(kind, id, name)
                    println("Copied to '$name' (id $newId). Not saved yet.")
                }
                2 -> renameId(draft, kind, id)?.let { id = it }
                3 -> if (deleteEntry(draft, kind, id)) return
                4 -> {
                    CreatorInput.printJson(entry)
                    Terminal.pause()
                }
                5 -> replaceRaw(draft, kind, id)
            }
        }
    }

    private fun printReferences(refs: List<Reference>) {
        CreatorInput.printLines(refs.map { it.toString() + if (it.inFormula) Terminal.dim(" (inside a formula)") else "" }, 20, "  - ")
    }

    private fun renameId(draft: SetupDraft, kind: EntryKind, id: String): String? {
        val suggestion = SetupDraft.snakeCase(draft.nameOf(kind, id)).takeIf { it.isNotEmpty() && it != id && draft.idProblem(kind, it) == null }
        CreatorInput.help("The id is how files refer to it (lower_snake_case by convention). Every reference is updated.")
        val newId = Terminal.ask("New id (empty = cancel)", suggestion).trim()
        if (newId.isEmpty() || newId == id) return null
        draft.idProblem(kind, newId, except = id)?.let {
            Terminal.error(it)
            return null
        }
        val refs = draft.references(kind, id)
        if (refs.isEmpty()) println("Nothing refers to '$id'.")
        else {
            println("These ${CreatorInput.count(refs.size, "reference")} will be updated:")
            printReferences(refs)
        }
        if (!Terminal.confirm("Rename '$id' to '$newId'?", true)) return null
        val updated = draft.rename(kind, id, newId)
        println("Renamed to '$newId' (${CreatorInput.count(updated.size, "reference")} updated). Not saved yet.")
        return newId
    }

    /** Returns true if the entry was deleted. */
    private fun deleteEntry(draft: SetupDraft, kind: EntryKind, id: String): Boolean {
        val name = draft.nameOf(kind, id)
        val refs = draft.references(kind, id)
        val remove: Boolean
        if (refs.isEmpty()) {
            if (!Terminal.confirm("Delete the ${kind.label} '$name'? Nothing refers to it", false)) return false
            remove = false
        } else {
            println("'$name' is used in ${CreatorInput.count(refs.size, "place")}:")
            printReferences(refs)
            val inFormulas = refs.count { !it.removable }
            val options = mutableListOf<String>()
            if (refs.any { it.removable }) options += "Delete it and remove these references" +
                if (inFormulas > 0) " (${CreatorInput.count(inFormulas, "reference")} inside formulas must be fixed by hand)" else ""
            options += "Delete it and keep the references (the setup has errors until you fix them)"
            val choice = Terminal.menu("What should happen to them?", options, "Cancel")
            if (choice < 0) return false
            remove = options[choice].startsWith("Delete it and remove")
        }
        val left = draft.delete(kind, id, remove)
        println("Deleted '$name'. Not saved yet.")
        val teams = refs.filter { it.file.startsWith("Teams/") && it !in left }.map { it.file.removePrefix("Teams/").removeSuffix(".json") }.distinct()
        if (remove && teams.isNotEmpty()) {
            Terminal.warn("Saved teams that used it: ${teams.joinToString(", ")}. Check them in the team creator (a member may need another choice).")
        }
        if (left.isNotEmpty()) {
            Terminal.warn("Still referring to '$id' (fix them, or Validate will report errors):")
            printReferences(left)
        }
        return true
    }

    private fun replaceRaw(draft: SetupDraft, kind: EntryKind, id: String) {
        CreatorInput.help("Advanced: the whole entry as JSON (README.md has every key). The id must stay '$id' (use Rename id to change it).")
        val value = CreatorInput.json() ?: return
        if (value !is Map<*, *>) {
            Terminal.error("An entry must be a JSON object { ... }.")
            return
        }
        if (value["id"] != id) {
            Terminal.error("The id must stay '$id' (use Rename id to change it).")
            return
        }
        draft.replace(kind, id, value.entries.associate { (k, v) -> k.toString() to v })
        println("Replaced. Not saved yet.")
    }

    // ---- types extras -----------------------------------------------------------------------------------------------

    private fun typeChart(draft: SetupDraft) {
        val types = draft.infos(EntryKind.TYPE)
        Terminal.header("Type chart (attacker in rows, defender in columns; '.' = x1)")
        if (types.isEmpty()) {
            println("  no types")
            Terminal.pause()
            return
        }
        val chart = draft.typeChart()
        val short = types.map { t -> t.name.filter { it.isLetterOrDigit() }.uppercase().take(3).ifEmpty { t.id.take(3) } }
        println("ATK\\DEF " + short.joinToString("") { it.padStart(4) })
        types.forEachIndexed { row, a ->
            val cells = types.joinToString("") { d ->
                val m = chart[a.id]?.get(d.id) ?: 1.0
                val text = (if (m == 1.0) "." else Describe.num(m).removePrefix("0")).padStart(4)
                when {
                    m == 1.0 -> Terminal.dim(text)
                    m > 1.0 -> Terminal.color(text, "green")
                    else -> Terminal.color(text, "red")
                }
            }
            println("  " + short[row].padEnd(6) + cells)
        }
        println(Terminal.dim(Terminal.wrap(types.indices.joinToString(", ") { "${short[it]} = ${types[it].name}" }, 78)))
        Terminal.pause()
    }

    private fun typeMessages(draft: SetupDraft) {
        val keys = listOf(
            Triple("strong", "Super effective", "It's super effective!"),
            Triple("weak", "Not very effective", "It's not very effective..."),
            Triple("immune", "No effect", "It doesn't affect {target}..."),
        )
        while (true) {
            Terminal.header("Effectiveness messages")
            CreatorInput.help("Shown after a hit; {target} is the monster hit. Empty = the engine's default line.")
            val messages = draft.typeMessages()
            val index = Terminal.menu("", keys.map { (k, label, _) -> "$label: " + (messages[k]?.let { "\"$it\"" } ?: Terminal.dim("default")) }, "Back")
            if (index < 0) return
            val (key, label, example) = keys[index]
            val text = CreatorInput.text(label, messages[key], allowNone = true, helpText = "e.g. $example")
            draft.setTypeMessage(key, text?.takeIf { it.isNotBlank() })
        }
    }

    // ---- raw JSON files ---------------------------------------------------------------------------------------------

    private fun rawFiles(draft: SetupDraft) {
        while (true) {
            Terminal.header("Raw JSON files of ${draft.displayName} (advanced)")
            CreatorInput.help("View or replace a whole file. README.md describes every format. Replacing is checked for JSON syntax only: use Validate.")
            val names = draft.fileNames()
            val options = names.map { n ->
                val f = draft.file(n)
                n + when {
                    f.error != null -> Terminal.color(" (syntax error: read-only until replaced)", "red")
                    !f.exists && !f.changed -> Terminal.dim(" (no file)")
                    f.changed -> Terminal.color(" (changed)", "yellow")
                    else -> ""
                }
            }
            val index = Terminal.menu("", options, "Back")
            if (index < 0) return
            val name = names[index]
            when (Terminal.menu(name, listOf("View", "Replace"), "Back")) {
                0 -> {
                    val f = draft.file(name)
                    f.error?.let { Terminal.error(it) }
                    val text = draft.fileText(name)
                    if (text == null) println(Terminal.dim("  (the file does not exist yet)")) else text.trimEnd().lines().forEach { println("  $it") }
                    Terminal.pause()
                }
                1 -> {
                    val text = CreatorInput.jsonText() ?: continue
                    try {
                        draft.setFileText(name, text)
                        println("$name replaced. Not saved yet.")
                    } catch (e: DraftException) {
                        Terminal.error(e.message ?: "invalid JSON")
                    }
                }
            }
        }
    }

    // ---- rules ------------------------------------------------------------------------------------------------------

    private fun rulesScreen(draft: SetupDraft) {
        if (draft.file(SetupDraft.RULES).error != null) {
            Terminal.error("Rules.json has a JSON syntax error: repair it in Raw JSON files.")
            Terminal.pause()
            return
        }
        while (true) {
            val recognition = draft.recogniseRules()
            Terminal.header("Rules of ${draft.displayName}")
            val form = RulesForm(recognition.answers, isNew = false, custom = recognition.custom)
            form.summary().forEach { println("  $it") }
            if (recognition.custom.isNotEmpty()) {
                println(Terminal.dim("  (edited by hand) = written by hand in Rules.json; the questions only replace it if you agree."))
            }
            val actions = listOf(
                "Change the rules (template questions)", "Edit one value (advanced)", "Rename a stat id (updates every reference)",
                "View the raw JSON", "Replace the raw JSON (advanced)",
            )
            when (Terminal.menu("", actions, "Back")) {
                -1 -> return
                0 -> CreatorInput.guard { reEditRules(draft, recognition.answers, recognition.custom) }
                1 -> CreatorInput.guard { editRuleValue(draft) }
                2 -> CreatorInput.guard { renameStatId(draft) }
                3 -> {
                    CreatorInput.printJson(draft.rules())
                    Terminal.pause()
                }
                4 -> {
                    val value = CreatorInput.json() ?: continue
                    if (value !is Map<*, *>) Terminal.error("Rules.json must be a JSON object { ... }.")
                    else {
                        draft.setRules(value.entries.associate { (k, v) -> k.toString() to v })
                        println("Rules.json replaced. Not saved yet.")
                    }
                }
            }
        }
    }

    private fun reEditRules(draft: SetupDraft, old: RuleAnswers, custom: Set<RulePart>) {
        val moves = draft.infos(EntryKind.MOVE).map { Option(it.id, it.name) }
        val new = RulesForm(old, isNew = false, custom = custom, moves = moves).run("Apply these rules") ?: return
        var parts = RuleTemplate.changedParts(old, new)
        if (parts.isEmpty()) {
            println("Nothing changed.")
            return
        }
        val handEdited = parts.intersect(custom)
        if (handEdited.isNotEmpty()) {
            Terminal.warn("These parts of Rules.json were written by hand: ${handEdited.joinToString(", ") { it.label }}.")
            Terminal.warn("Applying the questions replaces them with generated rules (your hand-written formulas are lost).")
            if (!Terminal.confirm("Replace them?", false)) parts = parts - handEdited
        }
        if (parts.isEmpty()) {
            println("Nothing was changed.")
            return
        }
        val oldStats = old.stats.map { it.id }
        draft.setRules(RuleTemplate.apply(draft.rules(), new, parts))
        println("Updated: ${parts.joinToString(", ") { it.label }}. Not saved yet.")
        if (RulePart.STATS in parts) fixBaseStats(draft, oldStats, new.stats)
        val fallback = new.fallbackMove
        if (RulePart.MOVE_LIMITS in parts && new.moveLimit == MoveLimit.PP && fallback != null && !draft.contains(EntryKind.MOVE, fallback)) {
            if (Terminal.confirm("Create the fallback move '$fallback' (a typeless Desperate Strike that hurts the user)?", true)) {
                RuleTemplate.starterEntries(new)[EntryKind.MOVE]?.forEach { if (!draft.contains(EntryKind.MOVE, it["id"] as String)) draft.add(EntryKind.MOVE, it) }
            }
        }
    }

    /** New stats get a base value in every bakemon; removed stats are taken out of base stats. */
    private fun fixBaseStats(draft: SetupDraft, oldStats: List<String>, newStats: List<StatAnswer>) {
        val added = newStats.filter { it.id !in oldStats }
        val removed = oldStats.filter { id -> newStats.none { it.id == id } }
        val species = draft.entries(EntryKind.BAKEMON)
        if (species.isEmpty() || (added.isEmpty() && removed.isEmpty())) return
        val values = added.associate { s ->
            s.id to ((CreatorInput.number("Base ${s.name} of every existing bakemon", 50.0, 0.0, null) as? CreatorInput.NumberAnswer.Value)?.value as? Double ?: 50.0)
        }
        for (e in species) {
            val id = e["id"] as? String ?: continue
            @Suppress("UNCHECKED_CAST")
            val stats = LinkedHashMap((e["baseStats"] as? Map<String, Any?>) ?: emptyMap())
            removed.forEach { stats.remove(it) }
            values.forEach { (k, v) -> stats.putIfAbsent(k, v) }
            e["baseStats"] = stats
            draft.replace(EntryKind.BAKEMON, id, e)
        }
        if (removed.isNotEmpty()) println("Removed ${removed.joinToString(", ")} from the base stats of every bakemon.")
        if (added.isNotEmpty()) println("Every bakemon got a base value for ${added.joinToString(", ") { it.name }} (edit them in Bakemons).")
        val refs = removed.flatMap { draft.statReferences(it) }
        if (refs.isNotEmpty()) {
            Terminal.warn("The removed stats are still used here (fix them, or Validate will report errors):")
            printReferences(refs)
        }
    }

    private fun renameStatId(draft: SetupDraft) {
        val stats = draft.recogniseRules().answers.stats
        if (stats.isEmpty()) {
            Terminal.warn("Rules.json has no stats.")
            return
        }
        val index = Terminal.menu("Rename which stat id?", stats.map { "${it.name} " + Terminal.dim("(${it.id})") }, "Cancel")
        if (index < 0) return
        val old = stats[index].id
        val new = Terminal.ask("New id (letters, digits and _; empty = cancel)").trim()
        if (new.isEmpty() || new == old) return
        val refs = draft.statReferences(old)
        println("These ${CreatorInput.count(refs.size, "reference")} will be updated:")
        printReferences(refs)
        if (!Terminal.confirm("Rename the stat '$old' to '$new'?", true)) return
        val updated = draft.renameStat(old, new)
        println("Renamed (${CreatorInput.count(updated.size, "reference")} updated). Not saved yet.")
    }

    /** Edit or remove one top-level value of Rules.json; formulas are checked right away. */
    private fun editRuleValue(draft: SetupDraft) {
        while (true) {
            val rules = draft.rules()
            Terminal.header("Rules.json values (advanced)")
            CreatorInput.help("README.md \"Rules.json\" explains every value. Formulas are checked as you type them.")
            val keys = rules.keys.toList()
            val options = keys.map { k -> "$k: " + CreatorInput.short(Json.stringify(rules[k], pretty = false), 70) } + "Add a value"
            val index = Terminal.menu("", options, "Back")
            if (index < 0) return
            if (index == keys.size) {
                val key = Terminal.ask("Name of the value (e.g. startFields; empty = cancel)").trim()
                if (key.isEmpty()) continue
                val value = CreatorInput.json("Its value as JSON (an empty line cancels)") ?: continue
                rules[key] = value
                draft.setRules(rules)
                continue
            }
            val key = keys[index]
            when (Terminal.menu(key, listOf("Change it", "Remove it"), "Back")) {
                0 -> {
                    val value = askRuleValue(draft, key, rules[key]) ?: continue
                    rules[key] = value
                    draft.setRules(rules)
                    println("$key changed. Not saved yet.")
                }
                1 -> if (Terminal.confirm("Remove '$key' (the engine default is used)?", false)) {
                    rules.remove(key)
                    draft.setRules(rules)
                }
            }
        }
    }

    private fun askRuleValue(draft: SetupDraft, key: String, current: Any?): Any? {
        val scope = FORMULA_KEYS[key]
        return when {
            current is Boolean -> Terminal.confirm(key, current)
            scope != null || current is Number -> {
                val check: ((String) -> List<String>)? = scope?.let { s -> { text -> draft.checkFormula(text, s) } }
                when (val a = CreatorInput.number(key, current, checkFormula = check)) {
                    is CreatorInput.NumberAnswer.Value -> a.value
                    else -> null
                }
            }
            current is String -> CreatorInput.text(key, current)
            else -> CreatorInput.json("The new value as JSON (an empty line cancels)")
        }
    }

    companion object {
        private val SECTION_HELP = mapOf(
            EntryKind.TYPE to "Types decide how effective moves are (the type chart). Pick a type to edit its matchups.",
            EntryKind.STATUS to "Statuses are conditions like burn, sleep, buffs and debuffs that moves and abilities apply.",
            EntryKind.MOVE to "Moves are what monsters use in battle. Type text to search by name, id, type or category.",
            EntryKind.ABILITY to "Abilities are passive powers; each bakemon lists the ones it may have.",
            EntryKind.ITEM to "Items are held by monsters (if the rules allow held items).",
            EntryKind.FIELD to "Fields are weather, terrains, screens and hazards active on the whole battle or one side.",
            EntryKind.BAKEMON to "Bakemons are the species: types, base stats, abilities and learnable moves.",
        )

        private const val NEW_MOVE = "\u0000new"

        /** Rules.json values that are formulas, with their scope (README "Formulas"). */
        private val FORMULA_KEYS = mapOf(
            "damage" to FormulaScope.DAMAGE, "hitChance" to FormulaScope.HIT, "critChance" to FormulaScope.HIT,
            "critMultiplier" to FormulaScope.HIT,
        )
    }

    // ---- stats of the template ------------------------------------------------------------------------------------

    /**
     * The stats editor of the rule template. In a new setup any stat can change; in an existing one stat ids are
     * renamed with "Rename a stat id" (which updates every reference), so only names, new stats and removals are here.
     */
    internal fun editStats(start: RuleAnswers, isNew: Boolean): RuleAnswers {
        var a = start
        while (true) {
            Terminal.header("Stats")
            println("  " + a.stats.joinToString(", ") { "${it.name} " + Terminal.dim("(${it.id})") })
            println("  HP stat: ${statLabel(a, a.hpStat)}, speed stat: ${statLabel(a, a.speedStat)}")
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            actions += "Use a ready-made list (Classic / Action Bar / Simple)" to {
                val presets = StatPreset.entries.filter { it != StatPreset.CUSTOM }
                val i = CreatorInput.choose("Stats", presets.map { Option(it.name, it.label, it.help) }, null, "Cancel")
                if (i >= 0) a = a.withStatPreset(presets[i])
            }
            actions += "Add a stat" to {
                val name = Terminal.ask("Name of the new stat (empty = cancel)").trim()
                if (name.isNotEmpty()) {
                    var id = SetupDraft.snakeCase(name).ifEmpty { "stat" }
                    var n = 2
                    while (a.stats.any { it.id == id }) id = SetupDraft.snakeCase(name) + "_" + n++
                    a = a.copy(stats = a.stats + StatAnswer(id, name), statPreset = StatPreset.CUSTOM)
                    println("Added ${name} (id $id).")
                }
            }
            actions += "Rename a stat (its name)" to {
                val i = Terminal.menu("Which stat?", a.stats.map { it.name }, "Cancel")
                if (i >= 0) {
                    val name = CreatorInput.name("New name", a.stats[i].name)
                    a = a.copy(stats = a.stats.mapIndexed { j, s -> if (j == i) s.copy(name = name) else s }, statPreset = StatPreset.CUSTOM)
                }
            }
            if (isNew) actions += "Change a stat id" to {
                val i = Terminal.menu("Which stat?", a.stats.map { "${it.name} (${it.id})" }, "Cancel")
                if (i >= 0) {
                    val old = a.stats[i].id
                    val id = Terminal.ask("New id (letters, digits and _)", old).trim()
                    when {
                        !Regex("[A-Za-z_][A-Za-z0-9_]*").matches(id) -> Terminal.warn("An id uses letters, digits and _ (not starting with a digit).")
                        a.stats.any { it.id == id && it.id != old } -> Terminal.warn("There is already a stat '$id'.")
                        else -> a = renameAnswerStat(a, old, id)
                    }
                }
            }
            actions += "Remove a stat" to {
                val i = Terminal.menu("Remove which stat?", a.stats.map { it.name }, "Cancel")
                if (i >= 0) {
                    val id = a.stats[i].id
                    if (id == a.hpStat || id == a.speedStat) Terminal.warn("${a.stats[i].name} is the HP or speed stat: choose another one first.")
                    else a = a.copy(stats = a.stats.filterIndexed { j, _ -> j != i }, statPreset = StatPreset.CUSTOM)
                }
            }
            actions += "Which stat is HP" to {
                CreatorInput.chooseValue("HP stat (a monster faints at 0)", a.stats.map { Option(it.id, it.name) }, a.hpStat)?.let { a = a.copy(hpStat = it) }
            }
            actions += "Which stat is speed" to {
                CreatorInput.chooseValue("Speed stat (turn order / action bar)", a.stats.map { Option(it.id, it.name) }, a.speedStat)?.let { a = a.copy(speedStat = it) }
            }
            val index = CreatorInput.menu(actions.map { it.first }, "Done", default = -1)
            if (index < 0) return a
            actions[index].second()
        }
    }

    private fun statLabel(a: RuleAnswers, id: String) = a.stats.firstOrNull { it.id == id }?.name ?: "$id (missing)"

    private fun renameAnswerStat(a: RuleAnswers, old: String, new: String): RuleAnswers {
        fun r(s: String) = if (s == old) new else s
        return a.copy(
            stats = a.stats.map { if (it.id == old) it.copy(id = new) else it }, statPreset = StatPreset.CUSTOM,
            hpStat = r(a.hpStat), speedStat = r(a.speedStat), attackStat = r(a.attackStat), defenseStat = r(a.defenseStat),
            specialAttackStat = r(a.specialAttackStat), specialDefenseStat = r(a.specialDefenseStat),
            critRateStat = r(a.critRateStat), critDamageStat = r(a.critDamageStat),
        )
    }

    /**
     * The rule template as a form: one row per part (summary in plain words; "(edited by hand)" for [custom] parts);
     * picking a row asks that part's questions (Enter keeps each answer).
     */
    inner class RulesForm(
        start: RuleAnswers,
        private val isNew: Boolean,
        private val custom: Set<RulePart>,
        /** The moves of the setup (to pick the fallback move from); empty for a new setup. */
        private val moves: List<Option> = emptyList(),
    ) {
        private val first = start
        var a = start

        private fun visible(part: RulePart) = part != RulePart.ACTION_BAR || a.turnSystem == TurnStyle.ACTION_BAR

        private fun mark(part: RulePart) = if (part in custom) Terminal.color(" (edited by hand)", "yellow") else ""

        /** "Label: summary" lines. */
        fun summary(): List<String> = RulePart.entries.filter(::visible).map { "${it.label}: ${text(it)}${mark(it)}" }

        /** Asks the questions of [parts] in order. */
        fun walk(parts: List<RulePart>) {
            for (part in parts) if (visible(part)) {
                println()
                println(Terminal.bold(part.label))
                ask(part)
            }
        }

        /** The form; the answers on Done (only when they make valid rules), null on Cancel. */
        fun run(doneLabel: String): RuleAnswers? {
            val done = CreatorInput.form(
                title = { if (isNew) "Rules of the new setup '${a.name}'" else "Change the rules" },
                intro = { CreatorInput.help("Pick a line to answer its questions (Enter keeps the answer in brackets).") },
                rows = { RulePart.entries.filter(::visible).map { p -> CreatorInput.Row(p.label, text(p) + mark(p)) { ask(p) } } },
                changed = { a != first },
                problems = { RuleTemplate.problems(a) },
                doneLabel = doneLabel,
                confirmProblems = false,
            )
            if (!done) return null
            val problems = RuleTemplate.problems(a)
            if (problems.isNotEmpty()) {
                Terminal.error("These answers do not make valid rules yet:")
                problems.forEach { Terminal.error("  - $it") }
                return run(doneLabel)
            }
            return a
        }

        private fun stat(id: String) = statLabel(a, id)
        private fun onOff(b: Boolean) = if (b) "yes" else "no"

        fun text(part: RulePart): String = when (part) {
            RulePart.BASICS -> "${a.name}, ${a.turnSystem.label}, ${a.maxMoves} moves per monster, held items ${onOff(a.heldItems)}"
            RulePart.TEAM -> "${a.teamMin}-${a.teamMax} monsters, ${a.active} on the field per side, switching ${onOff(a.switching)}" +
                (if (a.speciesClause) ", species clause" else "") + (if (a.itemClause) ", item clause" else "")
            RulePart.LEVELS -> if (a.levels) "levels ${a.levelMin}-${a.levelMax} (default ${a.levelDefault})" else "no levels"
            RulePart.STATS -> a.stats.joinToString(", ") { it.name } + " (HP: ${stat(a.hpStat)}, speed: ${stat(a.speedStat)})"
            RulePart.GROWTH -> a.growth.label + (if (a.statPoints) ", stat points ${a.statPointsTotal} (at most ${a.statPointsMax} per stat)" else ", no stat points")
            RulePart.STAGES -> a.stages.label
            RulePart.CATEGORIES -> a.categories.label + if (a.categories == CategoryStyle.PHYSICAL_SPECIAL)
                " (${stat(a.attackStat)}/${stat(a.defenseStat)}, ${stat(a.specialAttackStat)}/${stat(a.specialDefenseStat)})" else " (${stat(a.attackStat)}/${stat(a.defenseStat)})"
            RulePart.DAMAGE -> "${a.damage.label}, types ${if (a.typeEffectiveness) "matter" else "do not matter"}" +
                (if (a.sameTypeBonus != 1.0) ", same-type bonus x${Describe.num(a.sameTypeBonus)}" else ", no same-type bonus") +
                (if (a.randomSpread) ", small random spread" else ", no randomness")
            RulePart.ACCURACY -> if (a.movesCanMiss) "moves can miss" else "moves never miss"
            RulePart.CRITS -> when (a.crits) {
                CritMode.NONE -> "none"
                CritMode.FIXED -> "${Describe.num(a.critChancePercent)}% chance, x${Describe.num(a.critMultiplier)} damage"
                CritMode.FROM_STATS -> "from ${stat(a.critRateStat)} and ${stat(a.critDamageStat)}"
            }
            RulePart.HIT_TYPES -> if (a.hitTypes) "yes (glancing x0.7, crushing x1.3)" else "no"
            RulePart.MOVE_LIMITS -> when (a.moveLimit) {
                MoveLimit.PP -> "PP" + (a.fallbackMove?.let { ", fallback move '$it'" } ?: ", no fallback move")
                MoveLimit.COOLDOWNS -> "cooldowns"
                MoveLimit.NONE -> "none (moves can always be used)"
            }
            RulePart.ACTION_BAR -> a.barFill.label + if (a.barRandomStart) ", bars start a little filled at random" else ", bars start empty"
            RulePart.TURN_LIMIT -> if (a.turnLimit == 0) "no limit" else "a draw after ${a.turnLimit} turns"
        }

        private fun <E : Enum<E>> pick(title: String, values: List<E>, label: (E) -> String, help: (E) -> String, current: E): E {
            val options = values.map { Option(it.name, label(it), help(it)) }
            val i = CreatorInput.choose(title, options, values.indexOf(current).takeIf { it >= 0 })
            return values[i]
        }

        /** The fallback move: one of the setup's moves, or a new Desperate Strike created for you. */
        private fun pickFallback(): String {
            val fresh = generateSequence(1) { it + 1 }.map { if (it == 1) "desperate_strike" else "desperate_strike_$it" }.first { id -> moves.none { it.value == id } }
            if (moves.isEmpty()) return a.fallbackMove ?: fresh
            val options = moves + Option(NEW_MOVE, "A new Desperate Strike move", "created for you: typeless, never misses, hurts the user")
            val chosen = CreatorInput.chooseValue("Which move?", options, a.fallbackMove?.takeIf { id -> moves.any { it.value == id } } ?: NEW_MOVE, null)
            return if (chosen == null || chosen == NEW_MOVE) fresh else chosen
        }

        private fun statChoice(title: String, current: String): String =
            CreatorInput.chooseValue(title, a.stats.map { Option(it.id, it.name) }, current, null) ?: current

        fun ask(part: RulePart) {
            when (part) {
                RulePart.BASICS -> {
                    a = a.copy(name = CreatorInput.name("Name of the setup", a.name))
                    a = a.copy(description = CreatorInput.text("Description", a.description.ifEmpty { null }, allowNone = true, helpText = "One line shown under the setup's name.") ?: "")
                    val style = pick("How do turns work?", TurnStyle.entries, { it.label }, { it.help }, a.turnSystem)
                    if (style != a.turnSystem) {
                        a = if (isNew && Terminal.confirm("Use the recommended rules for ${style.label}?", true)) {
                            RuleAnswers.forStyle(style).copy(name = a.name, description = a.description)
                        } else a.copy(turnSystem = style)
                    }
                    a = a.copy(maxMoves = CreatorInput.int("Moves a monster can know", 1..20, a.maxMoves))
                    a = a.copy(heldItems = CreatorInput.yesNo("Can monsters hold an item?", a.heldItems))
                }
                RulePart.TEAM -> {
                    val min = CreatorInput.int("Smallest team", 1..99, a.teamMin, "Monsters per team: at least...")
                    val max = CreatorInput.int("Largest team", min..99, maxOf(min, a.teamMax))
                    val active = CreatorInput.int("Monsters on the field per side", 1..max, minOf(a.active, max), "1 = singles, 2 = doubles; action bar battles often have 4.")
                    a = a.copy(teamMin = min, teamMax = max, active = active)
                    a = a.copy(switching = CreatorInput.yesNo("Can players switch monsters during a battle?", a.switching))
                    a = a.copy(speciesClause = CreatorInput.yesNo("Species clause (no species twice in a team)?", a.speciesClause))
                    a = a.copy(itemClause = CreatorInput.yesNo("Item clause (no item twice in a team)?", a.itemClause))
                }
                RulePart.LEVELS -> {
                    a = a.copy(levels = CreatorInput.yesNo("Do monsters have levels?", a.levels, "No = every monster is level 1 and stats come from base values only."))
                    if (a.levels) {
                        val min = CreatorInput.int("Lowest level", 0..1000, a.levelMin)
                        val max = CreatorInput.int("Highest level", min..1000, maxOf(min, a.levelMax))
                        val def = CreatorInput.int("Default level of a new team member", min..max, a.levelDefault.coerceIn(min, max))
                        a = a.copy(levelMin = min, levelMax = max, levelDefault = def)
                    }
                }
                RulePart.STATS -> a = editStats(a, isNew)
                RulePart.GROWTH -> {
                    a = a.copy(growth = pick("How do stats grow with the level?", Growth.entries, { it.label }, { it.help }, a.growth))
                    if (a.growth != Growth.FLAT && a.stats.size > 1) {
                        val chosen = CreatorInput.pickMany("Stats that grow with the level", a.stats.map { Option(it.id, it.name) },
                            a.stats.filter { it.grows }.map { it.id }, intro = "The others keep their base value at every level (e.g. crit rate in action bar games).")
                        if (chosen != null) a = a.copy(stats = a.stats.map { it.copy(grows = it.id in chosen) })
                    }
                    a = a.copy(statPoints = CreatorInput.yesNo("Stat points (like training points or runes: players spread extra points)?", a.statPoints))
                    if (a.statPoints) {
                        val total = CreatorInput.int("Points per monster", 1..100000, a.statPointsTotal)
                        a = a.copy(statPointsTotal = total, statPointsMax = CreatorInput.int("At most per stat", 1..total, minOf(a.statPointsMax, total)))
                    }
                }
                RulePart.STAGES -> a = a.copy(stages = pick("Stat stages (raise / lower a stat during battle)", StageStyle.entries, { it.label }, { "" }, a.stages))
                RulePart.CATEGORIES -> {
                    a = a.copy(categories = pick("Move categories", CategoryStyle.entries, { it.label }, { it.help }, a.categories))
                    a = a.copy(attackStat = statChoice("Stat that attacks", a.attackStat), defenseStat = statChoice("Stat that defends", a.defenseStat))
                    if (a.categories == CategoryStyle.PHYSICAL_SPECIAL) {
                        a = a.copy(specialAttackStat = statChoice("Stat for special attacks", a.specialAttackStat), specialDefenseStat = statChoice("Stat for special defense", a.specialDefenseStat))
                    }
                }
                RulePart.DAMAGE -> {
                    a = a.copy(damage = pick("Damage formula", DamagePreset.entries, { it.label }, { it.help }, a.damage))
                    a = a.copy(typeEffectiveness = CreatorInput.yesNo("Do types change the damage (type chart)?", a.typeEffectiveness))
                    val bonus = CreatorInput.number("Same-type bonus (1 = none, 1.5 = 50% more when the move has the user's type)", a.sameTypeBonus, 0.0, 10.0)
                    if (bonus is CreatorInput.NumberAnswer.Value) a = a.copy(sameTypeBonus = bonus.value as Double)
                    a = a.copy(randomSpread = CreatorInput.yesNo("A small random spread on damage?", a.randomSpread))
                }
                RulePart.ACCURACY -> a = a.copy(movesCanMiss = CreatorInput.yesNo("Can moves miss (each move has an accuracy)?", a.movesCanMiss))
                RulePart.CRITS -> {
                    val modes = CritMode.entries.filter { it != CritMode.FROM_STATS || a.stats.size > 1 }
                    a = a.copy(crits = pick("Critical hits", modes, { it.label }, { it.help }, a.crits))
                    when (a.crits) {
                        CritMode.FIXED -> {
                            (CreatorInput.number("Chance (%)", a.critChancePercent, 0.0, 100.0) as? CreatorInput.NumberAnswer.Value)?.let { a = a.copy(critChancePercent = it.value as Double) }
                            (CreatorInput.number("Damage multiplier", a.critMultiplier, 0.0, 10.0) as? CreatorInput.NumberAnswer.Value)?.let { a = a.copy(critMultiplier = it.value as Double) }
                        }
                        CritMode.FROM_STATS -> a = a.copy(
                            critRateStat = statChoice("Stat with the crit chance (%)", a.critRateStat),
                            critDamageStat = statChoice("Stat with the extra crit damage (%)", a.critDamageStat),
                        )
                        CritMode.NONE -> {}
                    }
                }
                RulePart.HIT_TYPES -> a = a.copy(hitTypes = CreatorInput.yesNo("Glancing and crushing hits?", a.hitTypes,
                    "Action Bar style: weak matchups may glance (x0.7), strong ones may crush (x1.3)."))
                RulePart.MOVE_LIMITS -> {
                    a = a.copy(moveLimit = pick("What limits how often a move is used?", MoveLimit.entries, { it.label }, { it.help }, a.moveLimit))
                    if (a.moveLimit == MoveLimit.PP) {
                        val wants = CreatorInput.yesNo("A fallback move for when every move is out of PP?", a.fallbackMove != null,
                            "Used when no other move is left (e.g. a typeless Desperate Strike that hurts the user).")
                        a = a.copy(fallbackMove = if (!wants) null else pickFallback())
                    }
                }
                RulePart.ACTION_BAR -> {
                    a = a.copy(barFill = pick("How fast do action bars fill?", BarFill.entries, { it.label }, { "" }, a.barFill))
                    a = a.copy(barRandomStart = CreatorInput.yesNo("Start each bar a little filled at random (0-15%)?", a.barRandomStart))
                }
                RulePart.TURN_LIMIT -> a = a.copy(turnLimit = CreatorInput.int("Turn limit", 0..100000, a.turnLimit, "After this many turns the battle is a draw (0 = no limit)."))
            }
        }
    }
}
