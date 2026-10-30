package creator

import bakemon.FieldDef
import bakemon.FieldManager
import bakemon.FieldRules
import bakemon.FieldScope
import bakemon.FormulaNames
import bakemon.FormulaScope
import bakemon.LoadReport
import bakemon.Rules
import bakemon.Setup
import bakemon.Stacking
import bakemon.TypeChart
import bakemon.TypeDef
import engine.EventType
import engine.ExpressionException
import engine.Formula
import util.Json
import util.JsonObj
import java.io.File
import java.nio.file.Files

/** Thrown by draft operations that cannot be done (unknown id, duplicate id, read-only file...). The message is for the user. */
class DraftException(message: String) : Exception(message)

/** The kinds of entries a setup holds, with the file and list key they live in (README "Writing a setup"). */
enum class EntryKind(val fileName: String, val listKey: String, val label: String, val plural: String) {
    TYPE("Types.json", "types", "type", "Types"),
    STATUS("Statuses.json", "statuses", "status", "Statuses"),
    MOVE("Moves.json", "moves", "move", "Moves"),
    ABILITY("Abilities.json", "abilities", "ability", "Abilities"),
    ITEM("Items.json", "items", "item", "Items"),
    FIELD("Fields.json", "fields", "field", "Fields"),
    BAKEMON("Bakemons.json", "bakemons", "bakemon", "Bakemons");
}

/** One file of a draft: its text and value as loaded (or last saved) and its current value. */
class DraftFile internal constructor(
    /** File name relative to the setup folder ("Moves.json", "Teams/My team.json"). */
    val name: String,
    internal var originalText: String?,
    internal var originalValue: Any?,
    internal var value: Any?,
    /** The JSON syntax error that makes this file read-only, or null. */
    var error: String?,
) {
    /** True if the file exists on disk (as loaded or last saved). */
    val exists: Boolean get() = originalText != null

    /** True if a save would write this file. */
    val changed: Boolean
        get() = error == null && value != null && (originalText == null || !JsonTools.equal(originalValue, value))
}

/** What changed in one file since it was loaded (or last saved): entries by id, plus "other" changes. */
data class FileChange(
    val file: String,
    /** The file does not exist yet. */
    val created: Boolean,
    val added: List<String>,
    val changed: List<String>,
    val removed: List<String>,
    /** Something other than list entries changed (rules, type chart or messages, a team, the file's layout). */
    val other: Boolean,
) {
    /** One line for a save confirmation, e.g. "Moves.json: 1 added (ember), 2 changed (tackle, growl)". */
    fun summary(): String {
        val parts = mutableListOf<String>()
        fun part(label: String, ids: List<String>) {
            if (ids.isNotEmpty()) parts += "${ids.size} $label (${ids.take(5).joinToString(", ")}${if (ids.size > 5) ", ..." else ""})"
        }
        part("added", added)
        part("changed", changed)
        part("removed", removed)
        if (other) parts += "other changes"
        return "$file: " + (if (created) "new file" + (if (parts.isEmpty()) "" else ", ") else "") + parts.joinToString(", ")
    }
}

/** Result of a validation: errors make the setup unusable for battles, warnings do not. */
class Validation(
    val errors: List<String>,
    val warnings: List<String>,
    /** The loaded setup after [SetupDraft.save] when it has no errors (null for a validation without saving). */
    val setup: Setup? = null,
) {
    val ok: Boolean get() = errors.isEmpty()

    /** "OK", "3 warnings" or "2 errors, 1 warning". */
    fun status(): String = when {
        errors.isNotEmpty() -> "${errors.size} error${if (errors.size == 1) "" else "s"}" +
            if (warnings.isNotEmpty()) ", ${warnings.size} warning${if (warnings.size == 1) "" else "s"}" else ""
        warnings.isNotEmpty() -> "${warnings.size} warning${if (warnings.size == 1) "" else "s"}"
        else -> "OK"
    }
}

/** What [SetupDraft.save] did. */
class SaveResult(val written: List<String>, val validation: Validation)

/**
 * One thing still needed before battles can work (see [SetupDraft.checklist]). [optional] items are advice: battles
 * work without them.
 */
data class ChecklistItem(val done: Boolean, val text: String, val kind: EntryKind?, val optional: Boolean = false)

/** Basic facts about an entry for lists in a UI. */
data class EntryInfo(val id: String, val name: String, val index: Int)

/**
 * An editable in-memory copy of a setup folder. It works on the RAW JSON trees of the files (as util.Json parses
 * them), not on the runtime model, so unknown keys, key order and entries the creator does not understand are kept.
 * A draft loads even an invalid or incomplete setup: a missing file is empty, and a file with a JSON syntax error is
 * reported in [problems] and kept read-only until its text is replaced ([setFileText]).
 *
 * Entry operations work on copies: [entry] returns a copy, and changes are made with [add], [replace], [remove]...
 * [save] writes only the files that changed, keeping the text of every unchanged part of a changed file.
 */
class SetupDraft private constructor(
    dir: File,
    /** True for a setup created by [create] and not saved yet. */
    isNew: Boolean,
    private val files: LinkedHashMap<String, DraftFile>,
) {
    /** The setup folder (it may not exist yet for a new setup). */
    var dir: File = dir
        private set

    var isNew: Boolean = isNew
        private set

    /** Folder name of the setup. */
    val folderName: String get() = dir.name

    /** Rules.name, or the folder name when it is blank. */
    val displayName: String get() = (rules()["name"] as? String)?.takeIf { it.isNotBlank() } ?: folderName

    /** Files that could not be read (JSON syntax errors); they are read-only. */
    val problems: List<String> get() = files.values.mapNotNull { it.error }

    /** True if a save would write something. */
    val isDirty: Boolean get() = files.values.any { it.changed } || (isNew && !dir.isDirectory)

    /** The rule template answers this setup was created with in this session (null if it was loaded). */
    var templateAnswers: RuleAnswers? = null
        internal set

    // ------------------------------------------------------------------------------------------------------------
    // Files

    /** Names of the files of the draft (setup files first, then Teams/...), whether they exist or not. */
    fun fileNames(): List<String> = files.keys.toList()

    /** The file [name] ("Moves.json", "Teams/x.json"). */
    fun file(name: String): DraftFile = files[name] ?: throw DraftException("Unknown file '$name'")

    /** A copy of the current value of file [name] (null if missing or unreadable). */
    fun fileValue(name: String): Any? = JsonTools.copy(file(name).value)

    /** Replaces the whole value of file [name]. */
    fun setFileValue(name: String, value: Any?) {
        val f = writable(name)
        f.value = JsonTools.copy(value)
    }

    /** The text file [name] would be saved with (its current text if unchanged; null if it would not be written). */
    fun fileText(name: String): String? {
        val f = file(name)
        if (f.error != null) return f.originalText
        if (f.value == null) return f.originalText
        return JsonSplice.render(f.originalText, f.originalValue, f.value)
    }

    /**
     * Replaces the content of file [name] with [text] (raw JSON). Throws DraftException with the syntax error if it is
     * not valid JSON. This is also the way to repair a file that could not be read.
     */
    fun setFileText(name: String, text: String) {
        val f = file(name)
        val value = try {
            Json.parse(text, name.substringAfterLast('/'))
        } catch (e: Exception) {
            throw DraftException(e.message ?: "invalid JSON")
        }
        if (f.error != null) {
            // Repaired: the broken text is still what is on disk, so the file counts as changed.
            f.error = null
            f.originalValue = null
            f.originalText = f.originalText ?: ""
            f.value = JsonTools.copy(value)
            forceWrite += name
            return
        }
        f.value = JsonTools.copy(value)
    }

    /** Files repaired from a syntax error: written on save even if the new value equals nothing on disk. */
    private val forceWrite = mutableSetOf<String>()

    private fun writable(name: String): DraftFile {
        val f = file(name)
        if (f.error != null) throw DraftException("${f.name} cannot be edited because it has a JSON syntax error: ${f.error}")
        return f
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rules

    /** A copy of Rules.json (an empty object if it is missing or not an object). */
    fun rules(): MutableMap<String, Any?> = asMap(fileValue(RULES)) ?: LinkedHashMap()

    /** Replaces Rules.json. */
    fun setRules(rules: Map<String, Any?>) = setFileValue(RULES, rules)

    /** The rule template answers that describe the current Rules.json, and the parts that were edited by hand. */
    fun recogniseRules(): RuleRecognition = RuleTemplate.recognise(rules())

    // ------------------------------------------------------------------------------------------------------------
    // Entries

    /** The current list of [kind] (created when [create] is true; null if missing and not created). */
    private fun list(kind: EntryKind, create: Boolean): MutableList<Any?>? {
        val f = if (create) writable(kind.fileName) else file(kind.fileName)
        if (f.error != null) return null
        when (val v = f.value) {
            is List<*> -> return asList(v)
            is Map<*, *> -> {
                val map = asMap(v)!!
                asList(map[kind.listKey])?.let { return it }
                if (!create) return null
                if (map[kind.listKey] != null) throw DraftException("${kind.fileName}: \"${kind.listKey}\" is not an array")
                map.remove(kind.listKey)
                return JsonTools.listAt(map, kind.listKey, listOf("types", "chart", "messages"))
            }
            null -> {
                if (!create) return null
                val list = ArrayList<Any?>()
                f.value = linkedMapOf<String, Any?>(kind.listKey to list)
                return list
            }
            else -> if (create) throw DraftException("${kind.fileName}: expected an array or an object with \"${kind.listKey}\"") else return null
        }
    }

    private fun indexOf(kind: EntryKind, id: String): Int =
        list(kind, false)?.indexOfFirst { (it as? Map<*, *>)?.get("id") == id } ?: -1

    /** Ids of the entries of [kind], in file order (entries without a string id are skipped). */
    fun ids(kind: EntryKind): List<String> = list(kind, false)?.mapNotNull { (it as? Map<*, *>)?.get("id") as? String } ?: emptyList()

    /** Id, display name and position of every entry with an id. */
    fun infos(kind: EntryKind): List<EntryInfo> = list(kind, false)?.mapIndexedNotNull { i, v ->
        val m = v as? Map<*, *> ?: return@mapIndexedNotNull null
        val id = m["id"] as? String ?: return@mapIndexedNotNull null
        EntryInfo(id, (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: id, i)
    } ?: emptyList()

    /** Copies of every object entry of [kind] (including entries without an id). */
    fun entries(kind: EntryKind): List<MutableMap<String, Any?>> =
        list(kind, false)?.mapNotNull { asMap(JsonTools.copy(it as? Map<*, *> ?: return@mapNotNull null)) } ?: emptyList()

    /** A copy of the entry [id] of [kind], or null. */
    fun entry(kind: EntryKind, id: String): MutableMap<String, Any?>? {
        val i = indexOf(kind, id)
        return if (i < 0) null else asMap(JsonTools.copy(list(kind, false)!![i]))
    }

    /** True if [kind] has an entry [id]. */
    fun contains(kind: EntryKind, id: String): Boolean = indexOf(kind, id) >= 0

    /** Display name of entry [id] (the id if it has no name or does not exist). */
    fun nameOf(kind: EntryKind, id: String): String =
        (entry(kind, id)?.get("name") as? String)?.takeIf { it.isNotBlank() } ?: id

    /**
     * Why [id] cannot be used as a new id of [kind] (empty, taken, reserved), or null if it can.
     * [except] is an id that may be reused (the entry being renamed).
     */
    fun idProblem(kind: EntryKind, id: String, except: String? = null): String? = when {
        id.isBlank() -> "The id must not be empty."
        id != id.trim() -> "The id must not start or end with spaces."
        id != except && contains(kind, id) -> "There is already a ${kind.label} with the id '$id'."
        (kind == EntryKind.STATUS || kind == EntryKind.FIELD) && id.equals("all", ignoreCase = true) ->
            "'all' is reserved (it means every ${kind.label})."
        else -> null
    }

    /** A free lower_snake_case ASCII id for an entry called [name] ("Fire Fang" -> "fire_fang", then "fire_fang_2"...). */
    fun idFromName(kind: EntryKind, name: String): String = uniqueId(kind, snakeCase(name).ifEmpty { kind.label })

    private fun uniqueId(kind: EntryKind, base: String): String {
        if (idProblem(kind, base) == null) return base
        var n = 2
        while (idProblem(kind, "${base}_$n") != null) n++
        return "${base}_$n"
    }

    /** Adds [entry] (it needs a free "id") at [index] (default: the end). The draft keeps a copy. */
    fun add(kind: EntryKind, entry: Map<String, Any?>, index: Int? = null) {
        val id = entry["id"] as? String ?: throw DraftException("The new ${kind.label} has no id.")
        idProblem(kind, id)?.let { throw DraftException(it) }
        val list = list(kind, true)!!
        val at = (index ?: list.size).coerceIn(0, list.size)
        list.add(at, JsonTools.copy(entry))
    }

    /** Replaces entry [id] with [entry], which must keep the same id (use [rename] to change ids). */
    fun replace(kind: EntryKind, id: String, entry: Map<String, Any?>) {
        if (entry["id"] != id) throw DraftException("The ${kind.label} must keep the id '$id' (use rename to change it).")
        val i = indexOf(kind, id)
        if (i < 0) throw DraftException("Unknown ${kind.label} '$id'.")
        val list = list(kind, true)!!
        list[i] = JsonTools.copy(entry)
    }

    /** Removes entry [id] without touching its references (see [delete]). Returns false if it did not exist. */
    fun remove(kind: EntryKind, id: String): Boolean {
        val i = indexOf(kind, id)
        if (i < 0) return false
        list(kind, true)!!.removeAt(i)
        return true
    }

    /**
     * Copies entry [id] right after it, with a new unique id (from [newName], or "<id>_copy") and the new name.
     * Returns the new id.
     */
    fun copy(kind: EntryKind, id: String, newName: String? = null): String {
        val original = entry(kind, id) ?: throw DraftException("Unknown ${kind.label} '$id'.")
        val name = newName ?: ((original["name"] as? String)?.let { "$it (copy)" })
        val newId = if (newName != null) idFromName(kind, newName) else uniqueId(kind, "${id}_copy")
        original["id"] = newId
        if (name != null) JsonTools.putOrdered(original, "name", name, listOf("id", "name"))
        add(kind, original, indexOf(kind, id) + 1)
        return newId
    }

    /** Moves entry [id] to position [index] (0 = first) among all the entries of its file. */
    fun moveTo(kind: EntryKind, id: String, index: Int) {
        val i = indexOf(kind, id)
        if (i < 0) throw DraftException("Unknown ${kind.label} '$id'.")
        val list = list(kind, true)!!
        val value = list.removeAt(i)
        list.add(index.coerceIn(0, list.size), value)
    }

    // ------------------------------------------------------------------------------------------------------------
    // References

    /** Every place where entry [id] of [kind] is used (in every file, including formulas and team files). */
    fun references(kind: EntryKind, id: String): List<Reference> = References.find(this, RefKind.of(kind), id)

    /** Every place where stat [statId] is used. */
    fun statReferences(statId: String): List<Reference> = References.find(this, RefKind.STAT, statId)

    /**
     * Renames entry [oldId] of [kind] to [newId] and updates every reference to it (lists of species, team files,
     * effects, rules, type chart, string literals in formulas...). Returns the references that were updated.
     */
    fun rename(kind: EntryKind, oldId: String, newId: String): List<Reference> {
        val i = indexOf(kind, oldId)
        if (i < 0) throw DraftException("Unknown ${kind.label} '$oldId'.")
        if (newId == oldId) return emptyList()
        idProblem(kind, newId)?.let { throw DraftException(it) }
        checkWritable(References.files(this))
        val updated = References.rename(this, RefKind.of(kind), oldId, newId)
        asMap(list(kind, true)!![i])!!["id"] = newId
        return updated
    }

    /**
     * Renames stat [oldId] to [newId] everywhere (Rules.json, base stats, stat points of teams, statModifiers,
     * statStage effects and formulas such as user.atk). Returns the updated references.
     */
    fun renameStat(oldId: String, newId: String): List<Reference> {
        if (newId == oldId) return emptyList()
        if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(newId)) {
            throw DraftException("A stat id must be a simple name (letters, digits and _, not starting with a digit).")
        }
        val stats = (rules()["stats"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("id") as? String ?: it as? String }
        if (newId in stats) throw DraftException("There is already a stat '$newId'.")
        checkWritable(References.files(this))
        return References.rename(this, RefKind.STAT, oldId, newId)
    }

    /**
     * Deletes entry [id] of [kind]. With [removeReferences], the references that can be removed are removed too
     * (list elements, effects, team members, chart cells...); references inside formulas cannot be removed
     * automatically. Returns the references that are left (they make the setup invalid until fixed).
     */
    fun delete(kind: EntryKind, id: String, removeReferences: Boolean): List<Reference> {
        if (indexOf(kind, id) < 0) throw DraftException("Unknown ${kind.label} '$id'.")
        val left = if (removeReferences) {
            checkWritable(References.files(this))
            References.remove(this, RefKind.of(kind), id)
        } else {
            references(kind, id)
        }
        remove(kind, id)
        return left
    }

    private fun checkWritable(names: Collection<String>) {
        for (name in names) writable(name)
    }

    /** Internal access for [References]: the live value of a file. */
    internal fun liveValue(name: String): Any? = files[name]?.takeIf { it.error == null }?.value

    /** Internal access for [References]: replaces the live value of a file. */
    internal fun setLiveValue(name: String, value: Any?) {
        writable(name).value = value
    }

    // ------------------------------------------------------------------------------------------------------------
    // Types.json extras

    /** The type chart: attacking type -> defending type -> multiplier (only the cells that are written). */
    fun typeChart(): Map<String, Map<String, Double>> {
        val chart = (file(TYPES).value as? Map<*, *>)?.get("chart") as? Map<*, *> ?: return emptyMap()
        return chart.entries.associate { (attacker, row) ->
            attacker.toString() to ((row as? Map<*, *>)?.entries?.mapNotNull { (d, v) ->
                (v as? Number)?.let { d.toString() to it.toDouble() }
            }?.toMap() ?: emptyMap())
        }
    }

    /** Sets one chart cell; null (or 1) removes it, which means neutral (x1). */
    fun setEffectiveness(attacker: String, defender: String, multiplier: Double?) {
        val root = typesObject()
        val chart = JsonTools.objectAt(root, "chart", listOf("types", "chart", "messages"))
        if (multiplier == null || multiplier == 1.0) {
            val row = asMap(chart[attacker]) ?: return
            row.remove(defender)
            if (row.isEmpty()) chart.remove(attacker)
        } else {
            JsonTools.objectAt(chart, attacker)[defender] = multiplier
        }
    }

    /** The Types.json messages (strong / weak / immune) that are written. */
    fun typeMessages(): Map<String, String> {
        val messages = (file(TYPES).value as? Map<*, *>)?.get("messages") as? Map<*, *> ?: return emptyMap()
        return messages.entries.mapNotNull { (k, v) -> (v as? String)?.let { k.toString() to it } }.toMap()
    }

    /** Sets a Types.json message ("strong", "weak" or "immune"); null removes it (the engine default is used). */
    fun setTypeMessage(key: String, text: String?) {
        val root = typesObject()
        if (text == null) {
            asMap(root["messages"])?.let {
                it.remove(key)
                if (it.isEmpty()) root.remove("messages")
            }
        } else {
            JsonTools.objectAt(root, "messages", listOf("types", "chart", "messages"))[key] = text
        }
    }

    /** Types.json as an object (a bare array is turned into {"types": [...]}). */
    private fun typesObject(): MutableMap<String, Any?> {
        val f = writable(TYPES)
        when (val v = f.value) {
            is Map<*, *> -> return asMap(v)!!
            is List<*> -> return linkedMapOf<String, Any?>("types" to v).also { f.value = it }
            else -> return linkedMapOf<String, Any?>("types" to ArrayList<Any?>()).also { f.value = it }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Teams

    /** Team file names of the setup ("Teams/My team.json"), as loaded. */
    fun teamFiles(): List<String> = files.keys.filter { it.startsWith("Teams/") }

    // ------------------------------------------------------------------------------------------------------------
    // Changes, saving, validation

    /** What changed since the draft was loaded (or last saved), one entry per changed file. */
    fun changes(): List<FileChange> = files.values.filter { it.changed || it.name in forceWrite }.map { f ->
        val kind = EntryKind.entries.firstOrNull { it.fileName == f.name }
        if (kind == null) return@map FileChange(f.name, !f.exists, emptyList(), emptyList(), emptyList(), other = true)
        val before = entriesById(f.originalValue, kind)
        val after = entriesById(f.value, kind)
        val added = after.keys.filter { it !in before }
        val removed = before.keys.filter { it !in after }
        val changed = after.keys.filter { it in before && !JsonTools.equal(before[it], after[it]) }
        val wrapperBefore = stripList(f.originalValue, kind)
        val wrapperAfter = stripList(f.value, kind)
        val reordered = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
        FileChange(f.name, !f.exists, added, changed, removed, other = reordered || !JsonTools.equal(wrapperBefore, wrapperAfter))
    }

    private fun entriesById(value: Any?, kind: EntryKind): Map<String, Any?> {
        val list = when (value) {
            is List<*> -> value
            is Map<*, *> -> value[kind.listKey] as? List<*> ?: emptyList<Any?>()
            else -> emptyList<Any?>()
        }
        return list.mapNotNull { e -> ((e as? Map<*, *>)?.get("id") as? String)?.let { it to e } }.toMap()
    }

    private fun stripList(value: Any?, kind: EntryKind): Any? =
        (value as? Map<*, *>)?.filterKeys { it != kind.listKey } ?: if (value is List<*>) "list" else value

    /**
     * Writes the changed files (only those), keeping the text of every unchanged part, then validates the folder
     * with Setup.load rules. A new setup also gets its Teams/ and Assets/ folders (with a README.txt). Saving an
     * incomplete or invalid setup is allowed: the returned validation says what is wrong.
     */
    fun save(): SaveResult {
        val written = mutableListOf<String>()
        dir.mkdirs()
        for (f in files.values) {
            if (!f.changed && f.name !in forceWrite) continue
            val text = JsonSplice.render(f.originalText?.takeIf { f.name !in forceWrite }, f.originalValue, f.value)
            val target = File(dir, f.name)
            target.absoluteFile.parentFile?.mkdirs()
            target.writeText(text, Charsets.UTF_8)
            f.originalText = text
            f.originalValue = JsonTools.copy(f.value)
            written += f.name
        }
        forceWrite.clear()
        if (isNew) {
            File(dir, "Teams").mkdirs()
            val assets = File(dir, "Assets")
            assets.mkdirs()
            val readme = File(assets, "README.txt")
            if (!readme.exists()) readme.writeText(ASSETS_README + "\n", Charsets.UTF_8)
            isNew = false
        }
        val (setup, report) = Setup.check(dir)
        return SaveResult(written, Validation(report.errors.toList(), report.warnings.toList(), setup))
    }

    /**
     * Validates the current state without saving: the files are written to a temporary copy of the folder, which is
     * loaded with Setup's checks and deleted.
     */
    fun validate(): Validation {
        val temp = Files.createTempDirectory("bakemon-creator").toFile()
        try {
            val copy = File(temp, folderName)
            copy.mkdirs()
            for (f in files.values) {
                if (f.name.startsWith("Teams/")) continue
                val text = if (f.error != null) f.originalText else if (f.value == null) f.originalText else JsonSplice.render(
                    f.originalText?.takeIf { f.name !in forceWrite }, f.originalValue, f.value,
                )
                if (text != null) File(copy, f.name).writeText(text, Charsets.UTF_8)
            }
            // Images: only whether they exist matters.
            for (species in entries(EntryKind.BAKEMON)) {
                val image = species["image"] as? String ?: continue
                if (image.isBlank() || !File(dir, image).isFile) continue
                val placeholder = File(copy, image)
                if (!placeholder.normalize().path.startsWith(copy.path)) continue
                placeholder.parentFile?.mkdirs()
                placeholder.createNewFile()
            }
            val (_, report) = Setup.check(copy)
            return Validation(report.errors.toList(), report.warnings.toList())
        } finally {
            temp.deleteRecursively()
        }
    }

    /** What is still missing before battles can work: types, moves, bakemons, a fallback move when PP is used... */
    fun checklist(): List<ChecklistItem> {
        val rules = rules()
        val items = mutableListOf<ChecklistItem>()
        items += ChecklistItem(ids(EntryKind.TYPE).isNotEmpty(), "At least one type", EntryKind.TYPE)
        val moves = ids(EntryKind.MOVE)
        items += ChecklistItem(moves.isNotEmpty(), "At least one move", EntryKind.MOVE)
        val species = entries(EntryKind.BAKEMON)
        items += ChecklistItem(species.isNotEmpty(), "At least one bakemon", EntryKind.BAKEMON)
        val noMoves = species.filter { (it.strings("moves") ?: emptyList()).none { m -> m in moves } }
        if (species.isNotEmpty()) {
            items += ChecklistItem(
                noMoves.isEmpty(),
                if (noMoves.isEmpty()) "Every bakemon can learn a move"
                else "Bakemons without a learnable move: ${noMoves.joinToString(", ") { it.str("id") ?: "?" }}",
                EntryKind.BAKEMON,
            )
        }
        val team = rules["team"] as? Map<*, *>
        val min = (team?.get("min") as? Number)?.toInt() ?: 1
        if ((team?.get("speciesClause") as? Boolean) == true && min > 1) {
            items += ChecklistItem(species.size >= min, "At least $min bakemons (the species clause needs $min different ones per team)", EntryKind.BAKEMON)
        }
        if ((rules["pp"] as? Boolean) != false) {
            val fallback = rules["fallbackMove"] as? String
            items += ChecklistItem(
                fallback != null && fallback in moves,
                if (fallback == null) "A fallback move for when every move is out of PP (optional)" else "The fallback move '$fallback' exists",
                EntryKind.MOVE,
                optional = fallback == null,
            )
        }
        return items
    }

    // ------------------------------------------------------------------------------------------------------------
    // Formulas

    /**
     * Checks a formula typed by the user ("advanced" choices) for the place it goes: syntax (Formula.parse) and
     * names (Setup.checkFormula in [scope], with this draft's stats, fields and types). [statusContext] adds `stacks`
     * and `turnsLeft` (statuses and fields); [event] (a trigger's "on") limits the event variables (damage, amount...).
     * [recipient] false: the formula goes where `recipient` has no value (trigger conditions and chances, durations, the
     * values of effects without a target), so using it is a problem (Setup.recipientProblems).
     * Returns the problems in plain words (empty = fine).
     */
    fun checkFormula(text: String, scope: FormulaScope, statusContext: Boolean = false, event: String? = null, recipient: Boolean = true): List<String> {
        val formula = try {
            Formula.parse(text)
        } catch (e: ExpressionException) {
            return listOf((e.message ?: "invalid formula").replace("Formula '$text': ", ""))
        }
        val report = LoadReport()
        val rules = try {
            Rules.parse(JsonObj.of(rules(), "Rules.json"), report)
        } catch (e: Exception) {
            return listOf("Rules.json cannot be read, so the formula cannot be checked: ${e.message}")
        }
        val problems = Setup.checkFormula(formula, scope, rules, "@", statusContext, fieldStubs(), typeStubs())
            .map { it.removePrefix("@: ").replace(" in formula '$text'", "") }
            .toMutableList()
        if (scope == FormulaScope.TRIGGER) {
            val provided = event?.let { EventType.fromId(it) }?.let { EventType.EVENT_VARIABLES[it] } ?: emptySet()
            for (name in formula.variables().sorted()) {
                if (name !in FormulaNames.EVENT_VARS || name in provided) continue
                val events = EventType.EVENT_VARIABLES.filterValues { name in it }.keys.joinToString(", ") { it.id }
                problems += "'$name' has no value here (it is only set when: $events)"
            }
            if (!recipient) {
                problems += Setup.recipientProblems(formula, "@", "this place")
                    .map { it.removePrefix("@: ").replace(" in formula '$formula'", "").replace(" in formula '$text'", "") }
            }
        }
        return problems
    }

    /** Stand-ins for the draft's fields: Setup.checkFormula only needs their ids. */
    private fun fieldStubs(): FieldManager = FieldManager(entries(EntryKind.FIELD).mapNotNull { e ->
        val id = e.str("id") ?: return@mapNotNull null
        val scope = if (e.str("scope").equals("side", ignoreCase = true)) FieldScope.SIDE else FieldScope.GLOBAL
        id to FieldDef(id, id, id, "", scope, e.str("group"), null, Stacking.IGNORE, 1, false, emptyMap(), emptyList(), FieldRules.NONE, null, null)
    }.toMap())

    /** Stand-ins for the draft's types: Setup.checkFormula only needs their ids. */
    private fun typeStubs(): TypeChart =
        TypeChart(ids(EntryKind.TYPE).associateWith { TypeDef(it, it, null) }, emptyMap(), "", "", "")

    /** The context recipes need (stats, types, statuses, fields... of this draft) for an owner and event. */
    fun recipeContext(owner: OwnerKind, event: String? = null): RecipeContext = RecipeContext.of(this, owner, event)

    companion object {
        const val RULES = "Rules.json"
        const val TYPES = "Types.json"

        /** Every setup file, in the order the creator shows them. */
        val SETUP_FILES = listOf(RULES, TYPES, "Statuses.json", "Moves.json", "Abilities.json", "Items.json", "Fields.json", "Bakemons.json")

        private const val ASSETS_README =
            "Put monster images here, referenced by \"image\" in Bakemons.json (e.g. Assets/<id>.png); missing images are fine."

        /**
         * Loads the setup in [dir] as a draft. Never fails because of the setup's content: missing files are empty,
         * files with JSON syntax errors are listed in [problems] and read-only.
         */
        fun load(dir: File): SetupDraft {
            val folder = dir.absoluteFile.normalize()
            val files = LinkedHashMap<String, DraftFile>()
            for (name in SETUP_FILES) files[name] = readFile(folder, name)
            val teams = File(folder, "Teams").listFiles()?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
                ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) ?: emptyList()
            for (team in teams) files["Teams/${team.name}"] = readFile(folder, "Teams/${team.name}")
            return SetupDraft(folder, false, files)
        }

        private fun readFile(folder: File, name: String): DraftFile {
            val file = File(folder, name)
            if (!file.isFile) return DraftFile(name, null, null, null, null)
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                return DraftFile(name, null, null, null, "$name: cannot read the file (${e.message})")
            }
            return try {
                val value = Json.parse(text, file.name)
                DraftFile(name, text, value, JsonTools.copy(value), null)
            } catch (e: Exception) {
                DraftFile(name, text, null, null, e.message ?: "$name: invalid JSON")
            }
        }

        /**
         * A new setup called [name] under [setupsRoot] (folder name from the name, unique), with the Rules.json the
         * rule template builds from [answers] and empty lists (plus the template's starter entries, such as a
         * Struggle-like fallback move when PP is used). Nothing is written until [save].
         */
        fun create(setupsRoot: File, name: String, answers: RuleAnswers): SetupDraft {
            val folder = File(setupsRoot.absoluteFile, folderNameFor(setupsRoot, name))
            val files = LinkedHashMap<String, DraftFile>()
            for (file in SETUP_FILES) files[file] = DraftFile(file, null, null, null, null)
            val draft = SetupDraft(folder, true, files)
            val named = answers.copy(name = answers.name.ifBlank { name })
            draft.setRules(RuleTemplate.build(named))
            draft.file(TYPES).value = linkedMapOf<String, Any?>("types" to ArrayList<Any?>(), "chart" to LinkedHashMap<String, Any?>())
            for (kind in EntryKind.entries) if (kind != EntryKind.TYPE) draft.list(kind, true)
            for ((kind, entries) in RuleTemplate.starterEntries(named)) entries.forEach { draft.add(kind, it) }
            draft.templateAnswers = named
            return draft
        }

        /** A folder name for a setup called [name]: a safe file name, unique (case-insensitive) under [setupsRoot]. */
        fun folderNameFor(setupsRoot: File, name: String): String {
            val base = if (name.isBlank()) "New setup" else Setup.teamFileStem(name)
            val taken = setupsRoot.listFiles()?.map { it.name.lowercase() }?.toSet() ?: emptySet()
            if (base.lowercase() !in taken) return base
            var n = 2
            while ("$base $n".lowercase() in taken) n++
            return "$base $n"
        }

        /** lower_snake_case ASCII version of [name] ("Fire Fang!" -> "fire_fang"); may be empty. */
        fun snakeCase(name: String): String {
            val ascii = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            val words = ascii.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            val id = words.joinToString("_")
            return if (id.isNotEmpty() && id[0].isDigit()) "n_$id" else id
        }

        /** Renames a setup folder (not the setup's "name"). Throws DraftException if the new folder exists or the move fails. */
        fun renameSetupFolder(dir: File, newName: String): File {
            val stem = Setup.teamFileStem(newName)
            val target = File(dir.absoluteFile.parentFile, stem)
            if (target.exists() && !target.absoluteFile.equals(dir.absoluteFile)) throw DraftException("A folder called '$stem' already exists.")
            if (!dir.renameTo(target)) throw DraftException("The folder could not be renamed (is a file in it open?).")
            return target
        }
    }
}

/** Lower-snake-case helper shared by builders. */
internal fun snake(name: String): String = SetupDraft.snakeCase(name)
